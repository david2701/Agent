import AppIntents
import AlarmKit
import Foundation

// Botones de la Live Activity de los temporizadores y alarmas de Kairo (AlarmKit).
// Corren en el proceso de la app aunque se toquen en la Isla Dinámica o la pantalla bloqueada.

struct StopAlarmIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Detener"
    static let isDiscoverable = false

    @Parameter(title: "Alarma") var alarmId: String

    init() {}
    init(alarmId: String) { self.alarmId = alarmId }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmId) { try? AlarmManager.shared.stop(id: id) }
        return .result()
    }
}

struct PauseAlarmIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Pausar"
    static let isDiscoverable = false

    @Parameter(title: "Alarma") var alarmId: String

    init() {}
    init(alarmId: String) { self.alarmId = alarmId }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmId) { try? AlarmManager.shared.pause(id: id) }
        return .result()
    }
}

struct ResumeAlarmIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Reanudar"
    static let isDiscoverable = false

    @Parameter(title: "Alarma") var alarmId: String

    init() {}
    init(alarmId: String) { self.alarmId = alarmId }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmId) { try? AlarmManager.shared.resume(id: id) }
        return .result()
    }
}
