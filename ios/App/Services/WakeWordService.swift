import Foundation
import AVFoundation
import Speech
import KairoCore

/// "Oye Kairo": reconocimiento en el propio iPhone (sin enviar audio) hasta escuchar la frase.
/// Funciona con la app abierta y, gracias al modo de audio en segundo plano, con la pantalla bloqueada.
/// iOS muestra el indicador naranja del micrófono mientras está atento.
@MainActor @Observable
final class WakeWordService {
    private(set) var isRunning = false
    var phrase = "oye kairo"
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
        guard status == .authorized else { lastError = "Activa el reconocimiento de voz en Ajustes del iPhone"; return }
        guard await Microphone.requestPermission() else { lastError = "Kairo necesita el micrófono"; return }
        let r = SFSpeechRecognizer(locale: Locale(identifier: "es-ES")) ?? SFSpeechRecognizer()
        guard let r, r.isAvailable else { lastError = "El reconocimiento de voz no está disponible ahora"; return }
        recognizer = r
        paused = false
        do {
            try begin()
            isRunning = true
            lastError = nil
        } catch {
            lastError = "No se pudo activar el micrófono"
        }
    }

    func stop() {
        restartTask?.cancel()
        end()
        isRunning = false
    }

    /// Mientras Kairo escucha tu frase o habla, el detector se apaga y luego vuelve.
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
        try s.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers, .defaultToSpeaker, .allowBluetooth])
        try s.setActive(true)
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.taskHint = .search
        req.contextualStrings = ["Oye Kairo", "Kairo", phrase]
        if recognizer?.supportsOnDeviceRecognition == true { req.requiresOnDeviceRecognition = true }
        request = req
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 2048, format: input.outputFormat(forBus: 0)) { buffer, _ in
            req.append(buffer)
        }
        engine.prepare()
        try engine.start()
        let phrase = self.phrase
        task = recognizer?.recognitionTask(with: req) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString ?? ""
            let final = result?.isFinal ?? false
            let failed = error != nil
            Task { @MainActor in self?.handle(text: text, final: final || failed, phrase: phrase) }
        }
        // El reconocedor corta sesiones largas: se reinicia cada 50 s
        restartTask?.cancel()
        restartTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(50))
            guard !Task.isCancelled else { return }
            self?.restart()
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
