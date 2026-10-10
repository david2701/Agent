import Foundation
import AVFoundation
import Speech
import KairoCore

/// "Hey Kairo" / "Oye Kairo": recognition on the iPhone itself (no audio is sent) until the phrase is heard.
/// Works with the app open and, thanks to the background audio mode, with the screen locked.
/// iOS shows the orange microphone indicator while it is listening.
@MainActor @Observable
final class WakeWordService {
    private(set) var isRunning = false
    var phrase = "hey kairo"
    var lastError: String?
    var onWake: ((String) -> Void)?

    var isEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: "kairo.wake.enabled") }
        set {
            UserDefaults.standard.set(newValue, forKey: "kairo.wake.enabled")
            if newValue { Task { await start() } } else { stop() }
        }
    }

    private let engine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var restartTask: Task<Void, Never>?
    private var paused = false

    func start() async {
        guard isEnabled, !isRunning else { return }
        let status = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) } }
        guard status == .authorized else { lastError = String(localized: "Turn on speech recognition in iPhone Settings"); return }
        guard await Microphone.requestPermission() else { lastError = String(localized: "Kairo needs the microphone"); return }
        // The recognizer follows the app language ("en-US" or "es-ES")
        let r = SFSpeechRecognizer(locale: Locale(identifier: KairoLanguage.speechLocaleIdentifier)) ?? SFSpeechRecognizer()
        guard let r, r.isAvailable else { lastError = String(localized: "Speech recognition isn't available right now"); return }
        recognizer = r
        paused = false
        do {
            try begin()
            isRunning = true
            lastError = nil
        } catch {
            lastError = String(localized: "Couldn't turn on the microphone")
        }
    }

    func stop() {
        restartTask?.cancel()
        end()
        isRunning = false
    }

    /// While Kairo listens to your sentence or speaks, the detector turns off and then comes back.
    func pause() {
        guard isRunning else { return }
        paused = true
        end()
    }

    func resume() {
        guard isEnabled, paused else { return }
        paused = false
        try? begin()
    }

    private func begin() throws {
        end()
        let s = AVAudioSession.sharedInstance()
        try s.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers, .defaultToSpeaker, .allowBluetoothHFP])
        try s.setActive(true)
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.taskHint = .search
        // Hints follow the UI language; both "Hey Kairo" and "Oye Kairo" are accepted when matching (see WakePhrase)
        req.contextualStrings = [KairoLanguage.wakePhraseDisplay, "Kairo", phrase]
        if recognizer?.supportsOnDeviceRecognition == true { req.requiresOnDeviceRecognition = true }
        request = req
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 2048, format: input.outputFormat(forBus: 0), block: Self.tap(req))
        engine.prepare()
        try engine.start()
        task = recognizer?.recognitionTask(with: req, resultHandler: Self.results(phrase: phrase))

        // The recognizer cuts long sessions: it restarts every 50 s
        restartTask?.cancel()
        restartTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(50))
            guard !Task.isCancelled else { return }
            self?.restart()
        }
    }

    // Audio and the recognizer call from their own queues: these closures are not isolated to the main thread.
    nonisolated private static func tap(_ req: SFSpeechAudioBufferRecognitionRequest) -> AVAudioNodeTapBlock {
        { buffer, _ in req.append(buffer) }
    }

    nonisolated private static func results(phrase: String) -> @Sendable (SFSpeechRecognitionResult?, Error?) -> Void {
        { result, error in
            let text = result?.bestTranscription.formattedString ?? ""
            let final = (result?.isFinal ?? false) || error != nil
            Task { @MainActor in AppModel.shared.wake.handle(text: text, final: final, phrase: phrase) }
        }
    }

    private func handle(text: String, final: Bool, phrase: String) {
        guard isRunning, !paused else { return }
        let m = WakePhrase.match(text, phrase: phrase)
        if m.hit {
            pause()
            onWake?(m.rest)
        } else if final {
            restart()
        }
    }

    private func restart() {
        guard isRunning, !paused else { return }
        try? begin()
    }

    private func end() {
        task?.cancel()
        task = nil
        request?.endAudio()
        request = nil
        if engine.isRunning {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
    }
}
