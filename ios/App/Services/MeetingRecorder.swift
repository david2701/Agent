import Foundation
import AVFoundation
import ActivityKit
import KairoCore

/// Grabación larga (reuniones, notas de voz): sube segmentos de 5 minutos mientras graba y al final
/// el servidor transcribe, resume y propone tareas. Sigue grabando con la pantalla bloqueada.
@MainActor @Observable
final class MeetingRecorder {
    private(set) var isRecording = false
    private(set) var startedAt: Date?
    private(set) var segments = 0
    private(set) var recordingId: String?
    var lastError: String?
    var level: Double = 0

    private let api: KairoAPI
    private var recorder: AVAudioRecorder?
    private var segmentStart = Date()
    private var rotateTask: Task<Void, Never>?
    private var meterTask: Task<Void, Never>?
    private var uploads: [Task<Void, Never>] = []
    private var activity: ActivityKit.Activity<RecordingActivityAttributes>?
    private let segmentSeconds: TimeInterval = 300

    init(api: KairoAPI) { self.api = api }

    func start(title: String = "") async {
        guard !isRecording else { return }
        guard await Microphone.requestPermission() else { lastError = "Kairo necesita el micrófono"; return }
        do {
            let rec = try await api.createRecording(title: title)
            recordingId = rec.id
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try AVAudioSession.sharedInstance().setActive(true)
            segments = 0
            startedAt = .now
            try beginSegment()
            isRecording = true
            lastError = nil
            startActivity(title: title.isEmpty ? "Grabación" : title)
            rotateTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(self?.segmentSeconds ?? 300))
                    guard !Task.isCancelled else { return }
                    self?.rotate()
                }
            }
            meterTask = Task { [weak self] in
                while !Task.isCancelled {
                    if let r = self?.recorder {
                        r.updateMeters()
                        self?.level = max(0, min(1, (Double(r.averagePower(forChannel: 0)) + 50) / 50))
                    }
                    try? await Task.sleep(for: .milliseconds(80))
                }
            }
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Termina, sube lo que falte y pide el resumen.
    func stop(title: String? = nil) async {
        guard isRecording, let id = recordingId else { return }
        rotateTask?.cancel()
        meterTask?.cancel()
        finishSegment()
        isRecording = false
        level = 0
        updateActivity(processing: true)
        for u in uploads { await u.value }
        uploads.removeAll()
        try? await api.finishRecording(id, title: title)
        endActivity()
        AppModel.shared.bump("recording")
    }

    private func fileURL(_ seq: Int) -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("kairo-\(recordingId ?? "x")-\(seq).m4a")
    }

    private func beginSegment() throws {
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32_000,
        ]
        let r = try AVAudioRecorder(url: fileURL(segments), settings: settings)
        r.isMeteringEnabled = true
        guard r.record() else { throw CocoaError(.fileWriteUnknown) }
        recorder = r
        segmentStart = .now
    }

    private func rotate() {
        finishSegment()
        try? beginSegment()
        updateActivity(processing: false)
    }

    private func finishSegment() {
        guard let r = recorder, let id = recordingId else { return }
        r.stop()
        recorder = nil
        let seq = segments
        let duration = Int(Date().timeIntervalSince(segmentStart))
        let url = fileURL(seq)
        segments += 1
        let api = api
        uploads.append(Task {
            guard let data = try? Data(contentsOf: url) else { return }
            _ = try? await api.uploadSegment(id, seq: seq, durationS: duration, audio: data)
            try? FileManager.default.removeItem(at: url)
        })
    }

    private func startActivity(title: String) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled, let startedAt else { return }
        let state = RecordingActivityAttributes.ContentState(startedAt: startedAt, segments: 0, paused: false, processing: false)
        activity = try? Activity.request(attributes: RecordingActivityAttributes(title: title), content: .init(state: state, staleDate: nil), pushType: nil)
    }

    private func updateActivity(processing: Bool) {
        guard let activity, let startedAt else { return }
        let state = RecordingActivityAttributes.ContentState(startedAt: startedAt, segments: segments, paused: false, processing: processing)
        Task { await activity.update(.init(state: state, staleDate: nil)) }
    }

    private func endActivity() {
        guard let a = activity, let startedAt else { return }
        activity = nil
        let state = RecordingActivityAttributes.ContentState(startedAt: startedAt, segments: segments, paused: false, processing: true)
        Task { await a.end(.init(state: state, staleDate: nil), dismissalPolicy: .after(.now.addingTimeInterval(5))) }
    }
}
