import Foundation

/// Local notification the phone schedules from `/sync/device`, so it rings without internet.
public struct PlannedNotification: Sendable, Hashable, Identifiable {
    public enum Kind: String, Sendable { case reminder, event, briefing }

    /// Stable identifier (the same one `RealtimeEvent` uses for de-duplication).
    public var id: String
    public var kind: Kind
    public var date: Date
    public var title: String
    public var body: String
    /// Reminder or event id (for the "Done" and "Snooze" actions).
    public var entityId: String?
    /// The briefing time repeats every day.
    public var dailyHour: Int?
    public var dailyMinute: Int?
}

/// Builds the list of local notifications. iOS allows 64 pending per app: the nearest ones are prioritized
/// and room is kept for the daily briefing. The rest is scheduled on the next sync.
public enum NotificationPlan {
    public static let limit = 60

    public static func make(_ sync: DeviceSync, now: Date = .now, horizon: TimeInterval = 7 * 86400) -> [PlannedNotification] {
        var out: [PlannedNotification] = []
        for r in sync.reminders where r.status == "scheduled" && r.placeId == nil {
            guard let at = KairoDate.parse(r.nextFireAt), at > now, at.timeIntervalSince(now) < horizon else { continue }
            out.append(PlannedNotification(
                id: "rem:\(r.id):\(Int(at.timeIntervalSince1970))", kind: .reminder, date: at, title: r.text,
                body: r.createdBy != nil ? L10n.tr("Family reminder") : (r.rrule != nil ? L10n.tr("Repeating reminder") : L10n.tr("Reminder")),
                entityId: r.id
            ))
        }
        for a in sync.eventAlerts {
            guard let at = KairoDate.parse(a.alertAt), let start = KairoDate.parse(a.startAt), at > now, at.timeIntervalSince(now) < horizon else { continue }
            let when = a.minutes == 0 ? L10n.tr("Starts now") : L10n.tr("In %lld min", a.minutes)
            let place = (a.location ?? "").isEmpty ? "" : " · \(a.location ?? "")"
            out.append(PlannedNotification(
                id: "ev:\(a.eventId):\(Int(start.timeIntervalSince1970)):\(a.minutes)", kind: .event, date: at,
                title: a.title, body: when + place + ((a.shared ?? false) ? " · " + L10n.tr("Family") : ""), entityId: a.eventId
            ))
        }
        out.sort { $0.date < $1.date }
        var result = Array(out.prefix(sync.briefingEnabled ? limit - 1 : limit))
        if sync.briefingEnabled {
            let parts = sync.briefingTime.split(separator: ":").compactMap { Int($0) }
            if parts.count == 2 {
                result.append(PlannedNotification(
                    id: "brief:daily", kind: .briefing, date: now, title: L10n.tr("Your morning briefing"),
                    body: L10n.tr("Tap to listen"), entityId: nil, dailyHour: parts[0], dailyMinute: parts[1]
                ))
            }
        }
        return result
    }
}

/// Notices that are shown only once per device even if they arrive through several channels.
public final class SeenLog: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard, key: String = "kairo.seen") {
        self.defaults = defaults
        self.key = key
    }

    /// true the first time (and marks it as seen). Keeps the last 400 keys.
    public func markIfNew(_ id: String) -> Bool {
        lock.withLock {
            var list = defaults.stringArray(forKey: key) ?? []
            if list.contains(id) { return false }
            list.append(id)
            if list.count > 400 { list.removeFirst(list.count - 400) }
            defaults.set(list, forKey: key)
            return true
        }
    }

    public func contains(_ id: String) -> Bool { lock.withLock { (defaults.stringArray(forKey: key) ?? []).contains(id) } }
}
