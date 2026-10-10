import AppIntents
import AlarmKit
import Foundation

// Buttons of the Live Activity for Kairo's timers and alarms (AlarmKit).
// They run in the app's process even when tapped in the Dynamic Island or on the lock screen.

struct StopAlarmIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop"
    static let isDiscoverable = false

    @Parameter(title: "Alarm") var alarmId: String

    init() {}
    init(alarmId: String) { self.alarmId = alarmId }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmId) { try? AlarmManager.shared.stop(id: id) }
        return .result()
    }
}

struct PauseAlarmIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Pause"
    static let isDiscoverable = false

    @Parameter(title: "Alarm") var alarmId: String

    init() {}
    init(alarmId: String) { self.alarmId = alarmId }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmId) { try? AlarmManager.shared.pause(id: id) }
        return .result()
    }
}

struct ResumeAlarmIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Resume"
    static let isDiscoverable = false

    @Parameter(title: "Alarm") var alarmId: String

    init() {}
    init(alarmId: String) { self.alarmId = alarmId }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmId) { try? AlarmManager.shared.resume(id: id) }
        return .result()
    }
}
