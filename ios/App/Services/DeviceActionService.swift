import Foundation
import AlarmKit
import SwiftUI
import KairoCore

/// Temporizadores y alarmas que pide el agente ("pon un temporizador de 10 minutos").
/// Con AlarmKit suenan como las del Reloj de iOS, aunque el teléfono esté en silencio, y muestran
/// la cuenta regresiva en la Isla Dinámica.
enum DeviceActionService {
    /// Devuelve un texto para mostrar, o nil si no hizo nada.
    static func perform(_ action: DeviceAction) async -> String? {
        guard await authorized() else { return "Permite a Kairo usar alarmas en Ajustes del iPhone" }
        switch action {
        case .timer(let seconds, let label):
            let alert = AlarmPresentation.Alert(
                title: LocalizedStringResource(stringLiteral: label),
                secondaryButton: nil,
                secondaryButtonBehavior: nil
            )
            // Cuenta regresiva en la Isla Dinámica y la pantalla bloqueada (la dibuja AlarmLiveActivity)
            let presentation = AlarmPresentation(
                alert: alert,
                countdown: AlarmPresentation.Countdown(title: LocalizedStringResource(stringLiteral: label),
                                                       pauseButton: AlarmButton(text: "Pausar", textColor: .white, systemImageName: "pause.fill")),
                paused: AlarmPresentation.Paused(title: "En pausa",
                                                 resumeButton: AlarmButton(text: "Reanudar", textColor: .white, systemImageName: "play.fill"))
            )
            let attributes = AlarmAttributes<KairoAlarmMetadata>(
                presentation: presentation,
                metadata: KairoAlarmMetadata(label: label, kind: "timer"),
                tintColor: Color(red: 0.94, green: 0.64, blue: 0.23)
            )
            do {
                _ = try await AlarmManager.shared.schedule(id: UUID(), configuration: .timer(duration: TimeInterval(seconds), attributes: attributes))
                return "Temporizador: \(KairoDate.duration(seconds))"
            } catch {
                return "No se pudo poner el temporizador"
            }
        case .alarm(let hour, let minute, let label, let days):
            let alert = AlarmPresentation.Alert(
                title: LocalizedStringResource(stringLiteral: label),
                secondaryButton: nil,
                secondaryButtonBehavior: nil
            )
            let attributes = AlarmAttributes<KairoAlarmMetadata>(
                presentation: AlarmPresentation(alert: alert),
                metadata: KairoAlarmMetadata(label: label, kind: "alarm"),
                tintColor: Color(red: 0.94, green: 0.64, blue: 0.23)
            )
            let weekdays = days.compactMap(weekday)
            let schedule = Alarm.Schedule.relative(.init(
                time: .init(hour: hour, minute: minute),
                repeats: weekdays.isEmpty ? .never : .weekly(weekdays)
            ))
            do {
                _ = try await AlarmManager.shared.schedule(id: UUID(), configuration: .alarm(schedule: schedule, attributes: attributes))
                return String(format: "Alarma: %d:%02d", hour, minute)
            } catch {
                return "No se pudo poner la alarma"
            }
        case .unknown:
            return nil
        }
    }

    private static func authorized() async -> Bool {
        switch AlarmManager.shared.authorizationState {
        case .authorized: return true
        case .notDetermined: return (try? await AlarmManager.shared.requestAuthorization()) == .authorized
        default: return false
        }
    }

    private static func weekday(_ s: String) -> Locale.Weekday? {
        switch s.prefix(3).lowercased() {
        case "mon", "lun": .monday
        case "tue", "mar": .tuesday
        case "wed", "mie", "mié": .wednesday
        case "thu", "jue": .thursday
        case "fri", "vie": .friday
        case "sat", "sab", "sáb": .saturday
        case "sun", "dom": .sunday
        default: nil
        }
    }
}
