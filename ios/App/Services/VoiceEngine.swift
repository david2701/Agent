import Foundation
import AVFoundation
import ActivityKit
import AudioToolbox
import KairoCore
import KairoUI

/// Microphone at 16 kHz mono (16-bit PCM) with end-of-speech detection. Runs off the main thread.
nonisolated final class Microphone: @unchecked Sendable {
    struct NoSpeech: Error {}

    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var samples: [Int16] = []
    private var pending: [Int16] = []
    private var vad = EndOfSpeech()
    private var continuation: CheckedContinuation<[Int16], Error>?
    private var running = false

    static func requestPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    /// Records until the person finishes speaking. [onLevel] receives the 0...1 level for the orb.
    func record(onLevel: @escaping @Sendable (Double) -> Void) async throws -> [Int16] {
        try Self.configureSession(forRecording: true)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<[Int16], Error>) in
                lock.withLock {
                    continuation = c
                    samples.removeAll(keepingCapacity: true)
                    pending.removeAll()
                    vad.reset()
                }
                do {
                    try start(onLevel: onLevel)
                } catch {
                    finish(.failure(error))
                }
            }
        } onCancel: {
            self.finish(.failure(CancellationError()))
        }
    }

    func stop() { finish(.failure(CancellationError())) }

    private func start(onLevel: @escaping @Sendable (Double) -> Void) throws {
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(AudioSpec.sampleRate), channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: inFormat, to: target) else { throw NoSpeech() }
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buffer, _ in
            guard let self else { return }
            let ratio = target.sampleRate / inFormat.sampleRate
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32) else { return }
            var fed = false
            var err: NSError?
            converter.convert(to: out, error: &err) { _, status in
                if fed { status.pointee = .noDataNow; return nil }
                fed = true
                status.pointee = .haveData
                return buffer
            }
            guard err == nil, let ch = out.int16ChannelData else { return }
            let chunk = Array(UnsafeBufferPointer(start: ch[0], count: Int(out.frameLength)))
            self.consume(chunk, onLevel: onLevel)
        }
        engine.prepare()
        try engine.start()
        lock.withLock { running = true }
    }

    private func consume(_ chunk: [Int16], onLevel: @Sendable (Double) -> Void) {
        var verdict = EndOfSpeech.Verdict.continue
        var lastLevel = 0.0
        lock.withLock {
            samples.append(contentsOf: chunk)
            pending.append(contentsOf: chunk)
            while pending.count >= AudioSpec.frame, verdict == .continue {
                let frame = Array(pending.prefix(AudioSpec.frame))
                pending.removeFirst(AudioSpec.frame)
                let r = AudioMath.rms(frame)
                lastLevel = AudioMath.level(r)
                verdict = vad.push(r)
            }
        }
        onLevel(lastLevel)
        switch verdict {
        case .done: finish(.success(lock.withLock { samples }))
        case .noSpeech: finish(.failure(NoSpeech()))
        case .continue: break
        }
    }

    private func finish(_ result: Result<[Int16], Error>) {
        let c: CheckedContinuation<[Int16], Error>? = lock.withLock {
            let c = continuation
            continuation = nil
            if running {
                running = false
                engine.inputNode.removeTap(onBus: 0)
                engine.stop()
            }
            return c
        }
        c?.resume(with: result)
    }

    static func configureSession(forRecording: Bool) throws {
        let s = AVAudioSession.sharedInstance()
        if forRecording {
            try s.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP, .duckOthers])
        } else {
            try s.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        }
        try s.setActive(true, options: [])
    }
}

/// Voice conversation: listens, sends to your server (Whisper + agent on Nan) and answers with Kokoro's voice.
@MainActor @Observable
final class VoiceEngine {
    enum Phase: Equatable { case idle, listening, thinking, speaking }

    var phase: Phase = .idle
    var level: Double = 0
    var turn = AgentTurn()
    var errorMessage: String?
    var conversationId: String?
    /// If Kairo ends with a question, it keeps listening without repeating the wake phrase.
    var followUp = true
    weak var model: AppModel?

    private let api: KairoAPI
    private let mic = Microphone()
    private var task: Task<Void, Never>?
    private var player: AVAudioPlayer?
    private let activity = VoiceActivityController()

    init(api: KairoAPI) { self.api = api }

    var orbState: OrbState {
        switch phase {
        case .listening: .listening
        case .thinking: .thinking
        case .speaking: .speaking
        case .idle: errorMessage != nil ? .error : (model?.wake.isRunning == true ? .armed : .idle)
        }
    }

    var statusText: String {
        switch phase {
        case .listening: String(localized: "I'm listening")
        case .thinking: turn.working ?? String(localized: "Thinking")
        case .speaking: "Kairo"
        case .idle: errorMessage ?? (turn.finished ? String(localized: "All set") : String(localized: "Tap to talk"))
        }
    }

    func talk(initialText: String? = nil) async {
        cancel()
        if let text = initialText?.trimmingCharacters(in: .whitespaces), !text.isEmpty {
            task = Task { await run(text: text) }
            return
        }
        guard await Microphone.requestPermission() else {
            errorMessage = String(localized: "Kairo needs the microphone. Turn it on in iPhone Settings.")
            return
        }
        task = Task { await listenAndSend() }
    }

    func cancel() {
        task?.cancel()
        task = nil
        mic.stop()
        player?.stop()
        player = nil
        if phase != .idle { phase = .idle }
        level = 0
        activity.end()
    }

    private func listenAndSend() async {
        model?.wake.pause()
        phase = .listening
        errorMessage = nil
        turn = AgentTurn()
        AudioServicesPlaySystemSound(1113)
        activity.start(phase: "listening")
        do {
            let pcm = try await mic.record { lvl in Task { @MainActor in self.level = lvl } }
            AudioServicesPlaySystemSound(1114)
            level = 0
            phase = .thinking
            activity.update(phase: "thinking", transcript: "", reply: "", working: nil)
            for try await ev in api.voiceTurn(audio: AudioMath.wav(pcm), conversationId: conversationId) { await apply(ev) }
            await finishTurn()
        } catch is Microphone.NoSpeech {
            phase = .idle
            errorMessage = String(localized: "I didn't hear you")
            activity.end()
            model?.wake.resume()
        } catch is CancellationError {
            model?.wake.resume()
        } catch {
            phase = .idle
            errorMessage = error.localizedDescription
            activity.end()
            model?.wake.resume()
        }
    }

    private func run(text: String) async {
        model?.wake.pause()
        errorMessage = nil
        turn = AgentTurn()
        turn.transcript = text
        phase = .thinking
        activity.start(phase: "thinking")
        do {
            for try await ev in api.chat(text, conversationId: conversationId, channel: "voice") { await apply(ev) }
            await finishTurn()
        } catch {
            phase = .idle
            errorMessage = error.localizedDescription
            activity.end()
            model?.wake.resume()
        }
    }

    private func apply(_ ev: AgentEvent) async {
        turn.apply(ev)
        switch ev {
        case .deviceAction(let a):
            if let summary = await DeviceActionService.perform(a) {
                model?.show(Toast(title: summary, symbol: "timer"))
            }
        case .toolEnd(_, _, _, _, let entity):
            if let e = entity { model?.bump(e.type) }
        default: break
        }
        activity.update(phase: phase == .speaking ? "speaking" : "thinking", transcript: turn.transcript ?? "", reply: turn.text, working: turn.working)
    }

    private func finishTurn() async {
        conversationId = turn.conversationId
        if let e = turn.error {
            phase = .idle
            errorMessage = e
            activity.end()
            model?.wake.resume()
            return
        }
        if let spoken = turn.spoken, !spoken.isEmpty { await say(spoken) }
        phase = .idle
        activity.end(reply: turn.text)
        let asks = turn.text.trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix("?")
        if asks && followUp && !Task.isCancelled {
            await listenAndSend()
        } else {
            model?.wake.resume()
        }
    }

    /// Reads a text with the configured voice (Kokoro on Nan).
    func say(_ text: String) async {
        let s = model?.settings
        guard let data = try? await api.speak(text, voice: s?.voice, speed: s?.voiceSpeed) else { return }
        do {
            try Microphone.configureSession(forRecording: false)
            let p = try AVAudioPlayer(data: data)
            p.isMeteringEnabled = true
            player = p
            phase = .speaking
            activity.update(phase: "speaking", transcript: turn.transcript ?? "", reply: turn.text, working: nil)
            p.play()
            while p.isPlaying && !Task.isCancelled {
                p.updateMeters()
                let db = Double(p.averagePower(forChannel: 0))
                level = max(0, min(1, (db + 50) / 50))
                try? await Task.sleep(for: .milliseconds(50))
            }
            level = 0
        } catch {}
    }

    func playBriefing() async {
        cancel()
        phase = .thinking
        turn = AgentTurn()
        guard let text = try? await api.briefing() else {
            phase = .idle
            errorMessage = String(localized: "Couldn't prepare the briefing")
            return
        }
        turn.final = text
        turn.finished = true
        await say(text)
        phase = .idle
    }

    /// New conversation (the next sentence does not carry the previous context).
    func reset() {
        cancel()
        conversationId = nil
        turn = AgentTurn()
        errorMessage = nil
    }
}

/// Live Activity of the voice conversation (Dynamic Island and lock screen).
@MainActor
final class VoiceActivityController {
    private var activity: ActivityKit.Activity<VoiceActivityAttributes>?

    func start(phase: String) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        end()
        let state = VoiceActivityAttributes.ContentState(phase: phase, transcript: "", reply: "", working: nil)
        activity = try? Activity.request(attributes: VoiceActivityAttributes(startedAt: .now), content: .init(state: state, staleDate: nil), pushType: nil)
    }

    func update(phase: String, transcript: String, reply: String, working: String?) {
        guard let activity else { return }
        let state = VoiceActivityAttributes.ContentState(phase: phase, transcript: String(transcript.prefix(160)), reply: String(reply.prefix(240)), working: working)
        // Activity is not Sendable in the SDK; its methods are safe from any thread.
        nonisolated(unsafe) let live = activity
        Task { await live.update(.init(state: state, staleDate: nil)) }
    }

    func end(reply: String? = nil) {
        guard let a = activity else { return }
        activity = nil
        let state = VoiceActivityAttributes.ContentState(phase: "done", transcript: "", reply: String((reply ?? "").prefix(240)), working: nil)
        nonisolated(unsafe) let live = a
        Task { await live.end(.init(state: state, staleDate: nil), dismissalPolicy: .after(.now.addingTimeInterval(reply == nil ? 0 : 6))) }
    }
}
