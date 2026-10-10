import Foundation
import AlarmKit
import SwiftUI
import KairoCore

/// Timers and alarms the agent asks for ("set a 10 minute timer").
/// With AlarmKit they ring like the iOS Clock ones, even when the phone is silent, and show
/// the countdown in the Dynamic Island.
enum DeviceActionService {
    /// Returns a text to show, or nil if it did nothing.
    static func perform(_ action: DeviceAction) async -> String? {
        guard await authorized() else { return String(localized: "Allow Kairo to use alarms in iPhone Settings") }
        switch action {
        case .timer(let seconds, let label):
            let alert = AlarmPresentation.Alert(
                title: LocalizedStringResource(stringLiteral: label),
                secondaryButton: nil,
                secondaryButtonBehavior: nil
            )
            // Countdown in the Dynamic Island and on the lock screen (drawn by AlarmLiveActivity)
            let presentation = AlarmPresentation(
                alert: alert,
                countdown: AlarmPresentation.Countdown(title: LocalizedStringResource(stringLiteral: label),
                                                       pauseButton: AlarmButton(text: "Pause", textColor: .white, systemImageName: "pause.fill")),
                paused: AlarmPresentation.Paused(title: "Paused",
                                                 resumeButton: AlarmButton(text: "Resume", textColor: .white, systemImageName: "play.fill"))
            )
            let attributes = AlarmAttributes<KairoAlarmMetadata>(
                presentation: presentation,
                metadata: KairoAlarmMetadata(label: label, kind: "timer"),
                tintColor: Color(red: 0.94, green: 0.64, blue: 0.23)
            )
            do {
                _ = try await AlarmManager.shared.schedule(id: UUID(), configuration: .timer(duration: TimeInterval(seconds), attributes: attributes))
                return String(localized: "Timer: \(KairoDate.duration(seconds))")
            } catch {
                return String(localized: "Couldn't set the timer")
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
                let at = Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: .now)
                let when = at.map { KairoDate.time($0) } ?? String(format: "%d:%02d", hour, minute)
                return String(localized: "Alarm: \(when)")
            } catch {
                return String(localized: "Couldn't set the alarm")
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
        // The agent sends English day names; Spanish abbreviations are accepted too.
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
