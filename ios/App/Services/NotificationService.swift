import Foundation
import UserNotifications
import KairoCore

/// Local notifications: reminders, event alerts and the morning briefing ring even without internet.
/// It also shows instantly what arrives over the live connection while the app is in the background.
enum NotificationService {
    static let categoryReminder = "kairo.reminder"
    static let categoryBriefing = "kairo.briefing"
    static let actionDone = "kairo.done"
    static let actionSnooze = "kairo.snooze"
    static let actionListen = "kairo.listen"
    private static let managedPrefixes = ["rem:", "ev:", "brief:"]

    static func registerCategories() {
        let done = UNNotificationAction(identifier: actionDone, title: String(localized: "Done"), options: [], icon: UNNotificationActionIcon(systemImageName: "checkmark"))
        let snooze = UNNotificationAction(identifier: actionSnooze, title: String(localized: "Snooze 10 min"), options: [], icon: UNNotificationActionIcon(systemImageName: "clock.arrow.circlepath"))
        let listen = UNNotificationAction(identifier: actionListen, title: String(localized: "Listen"), options: [.foreground], icon: UNNotificationActionIcon(systemImageName: "play.fill"))
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: categoryReminder, actions: [done, snooze], intentIdentifiers: []),
            UNNotificationCategory(identifier: categoryBriefing, actions: [listen], intentIdentifiers: []),
        ])
    }

    static func requestAuthorization() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }

    /// Replaces the scheduled notifications with the new plan (without touching the ones that do not change).
    static func apply(_ plan: [PlannedNotification]) async {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests()
        let wanted = Set(plan.map(\.id))
        let stale = pending.map(\.identifier).filter { id in managedPrefixes.contains { id.hasPrefix($0) } && !wanted.contains(id) }
        center.removePendingNotificationRequests(withIdentifiers: stale)
        let existing = Set(pending.map(\.identifier))
        for p in plan where !existing.contains(p.id) || p.kind == .briefing {
            let content = UNMutableNotificationContent()
            content.title = p.title
            content.body = p.body
            content.sound = .default
            content.threadIdentifier = p.kind.rawValue
            var info: [String: String] = [:]
            p.entityId.map { info["entityId"] = $0 }
            switch p.kind {
            case .reminder:
                content.categoryIdentifier = categoryReminder
                content.interruptionLevel = .timeSensitive
                info["destination"] = "reminders"
            case .event:
                content.interruptionLevel = .timeSensitive
                info["destination"] = "calendar"
            case .briefing:
                content.categoryIdentifier = categoryBriefing
                info["destination"] = "today"
            }
            content.userInfo = info
            let trigger: UNNotificationTrigger
            if let h = p.dailyHour, let m = p.dailyMinute {
                trigger = UNCalendarNotificationTrigger(dateMatching: DateComponents(hour: h, minute: m), repeats: true)
            } else {
                let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: p.date)
                trigger = UNCalendarNotificationTrigger(dateMatching: c, repeats: false)
            }
            try? await center.add(UNNotificationRequest(identifier: p.id, content: content, trigger: trigger))
        }
    }

    static func showNow(id: String, title: String, body: String, entityId: String?, category: String?, destination: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        if let category { content.categoryIdentifier = category }
        var info = ["destination": destination]
        entityId.map { info["entityId"] = $0 }
        content.userInfo = info
        // If the local alarm with the same id was already scheduled, it is replaced: it rings only once
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    static func clearAll() {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }
}
