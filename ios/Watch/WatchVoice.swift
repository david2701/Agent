import AVFoundation
import SwiftUI
import WatchKit
import KairoCore
import KairoUI

/// Voice on the watch: records until you stop talking, sends to your server and reads the answer.
@MainActor @Observable
final class WatchVoice {
    enum Phase: Equatable { case idle, listening, thinking, speaking }

    var phase: Phase = .idle
    var level: Double = 0
    var turn = AgentTurn()
    var error: String?

    private let api: KairoAPI
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var task: Task<Void, Never>?
    private var conversationId: String?

    init(api: KairoAPI) { self.api = api }

    var orbState: OrbState {
        switch phase {
        case .listening: .listening
        case .thinking: .thinking
        case .speaking: .speaking
        case .idle: error == nil ? .idle : .error
        }
    }

    var status: String {
        switch phase {
        case .listening: String(localized: "I'm listening")
        case .thinking: turn.working ?? String(localized: "Thinking")
        case .speaking: "Kairo"
        case .idle: error ?? (turn.finished ? String(localized: "Tap to continue") : String(localized: "Tap to talk"))
        }
    }

    func toggle() {
        switch phase {
        case .idle: task = Task { await listen() }
        case .listening: stopRecording()
        default: cancel()
        }
    }

    func cancel() {
        task?.cancel()
        recorder?.stop()
        recorder = nil
        player?.stop()
        phase = .idle
        level = 0
    }

    private var fileURL: URL { FileManager.default.temporaryDirectory.appendingPathComponent("kairo-voice.m4a") }
    private var stopRequested = false

    private func listen() async {
        error = nil
        guard await AVAudioApplication.requestRecordPermission() else {
            error = String(localized: "Allow the microphone on the watch")
            return
        }
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.playAndRecord, mode: .default)
            try s.setActive(true)
            let r = try AVAudioRecorder(url: fileURL, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 16_000, AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
            ])
            r.isMeteringEnabled = true
            recorder = r
            stopRequested = false
            r.record()
            WKInterfaceDevice.current().play(.start)
            phase = .listening
            turn = AgentTurn()
            // End of the sentence: 1.2 s of silence after speaking, or 20 s at most
            var spoke = false
            var quiet = 0.0
            let started = Date.now
            while !stopRequested && !Task.isCancelled && Date.now.timeIntervalSince(started) < 20 {
                try? await Task.sleep(for: .milliseconds(100))
                r.updateMeters()
                let db = Double(r.averagePower(forChannel: 0))
                level = max(0, min(1, (db + 50) / 45))
                if db > -32 { spoke = true; quiet = 0 } else if spoke { quiet += 0.1 }
                if spoke && quiet >= 1.2 { break }
                if !spoke && Date.now.timeIntervalSince(started) > 7 { break }
            }
            r.stop()
            recorder = nil
            level = 0
            guard !Task.isCancelled else { return }
            guard spoke, let audio = try? Data(contentsOf: fileURL) else {
                phase = .idle
                error = String(localized: "I didn't hear you")
                return
            }
            WKInterfaceDevice.current().play(.stop)
            phase = .thinking
            for try await ev in api.voiceTurn(audio: audio, filename: "voice.m4a", mime: "audio/mp4", conversationId: conversationId, channel: "wear") {
                withAnimation(KairoMotion.base) { turn.apply(ev) }
            }
            conversationId = turn.conversationId
            if let e = turn.error { error = e; phase = .idle; return }
            if let spoken = turn.spoken, !spoken.isEmpty { await say(spoken) }
            phase = .idle
            WatchModel.shared.voiceFinished(turn)
        } catch is CancellationError {
            phase = .idle
        } catch {
            self.error = error.localizedDescription
            phase = .idle
        }
    }

    private func stopRecording() { stopRequested = true }

    private func say(_ text: String) async {
        guard let data = try? await api.speak(text) else { return }
        do {
            let p = try AVAudioPlayer(data: data)
            p.isMeteringEnabled = true
            player = p
            phase = .speaking
            p.play()
            while p.isPlaying && !Task.isCancelled {
                p.updateMeters()
                level = max(0, min(1, (Double(p.averagePower(forChannel: 0)) + 50) / 50))
                try? await Task.sleep(for: .milliseconds(60))
            }
            level = 0
        } catch {}
    }
}

extension WatchModel {
    /// After an answer that changed something, refreshes the day.
    func voiceFinished(_ turn: AgentTurn) {
        if turn.actions.contains(where: { $0.entity != nil }) { Task { await refresh() } }
    }
}
