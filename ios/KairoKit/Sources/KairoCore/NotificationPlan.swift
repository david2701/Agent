import Foundation

/// Notificación local que el teléfono programa a partir de `/sync/device`, para que suene sin internet.
public struct PlannedNotification: Sendable, Hashable, Identifiable {
    public enum Kind: String, Sendable { case reminder, event, briefing }

    /// Identificador estable (el mismo que usa la deduplicación de `RealtimeEvent`).
    public var id: String
    public var kind: Kind
    public var date: Date
    public var title: String
    public var body: String
    /// Id del recordatorio o del evento (para las acciones "Hecho" y "Posponer").
    public var entityId: String?
    /// La hora del resumen se repite cada día.
    public var dailyHour: Int?
    public var dailyMinute: Int?
}

/// Arma la lista de notificaciones locales. iOS admite 64 pendientes por app: se priorizan las más próximas
/// y se reserva espacio para el resumen diario. El resto se programa en la siguiente sincronización.
public enum NotificationPlan {
    public static let limit = 60

    public static func make(_ sync: DeviceSync, now: Date = .now, horizon: TimeInterval = 7 * 86400) -> [PlannedNotification] {
        var out: [PlannedNotification] = []
        for r in sync.reminders where r.status == "scheduled" && r.placeId == nil {
            guard let at = KairoDate.parse(r.nextFireAt), at > now, at.timeIntervalSince(now) < horizon else { continue }
            out.append(PlannedNotification(
                id: "rem:\(r.id):\(Int(at.timeIntervalSince1970))", kind: .reminder, date: at, title: r.text,
                body: r.createdBy != nil ? "Recordatorio de la familia" : (r.rrule != nil ? "Recordatorio que se repite" : "Recordatorio"),
                entityId: r.id
            ))
        }
        for a in sync.eventAlerts {
            guard let at = KairoDate.parse(a.alertAt), let start = KairoDate.parse(a.startAt), at > now, at.timeIntervalSince(now) < horizon else { continue }
            let when = a.minutes == 0 ? "Empieza ahora" : "En \(a.minutes) min"
            let place = (a.location ?? "").isEmpty ? "" : " · \(a.location ?? "")"
            out.append(PlannedNotification(
                id: "ev:\(a.eventId):\(Int(start.timeIntervalSince1970)):\(a.minutes)", kind: .event, date: at,
                title: a.title, body: when + place + ((a.shared ?? false) ? " · Familiar" : ""), entityId: a.eventId
            ))
        }
        out.sort { $0.date < $1.date }
        var result = Array(out.prefix(sync.briefingEnabled ? limit - 1 : limit))
        if sync.briefingEnabled {
            let parts = sync.briefingTime.split(separator: ":").compactMap { Int($0) }
            if parts.count == 2 {
                result.append(PlannedNotification(
                    id: "brief:daily", kind: .briefing, date: now, title: "Tu resumen del día",
                    body: "Toca para escucharlo", entityId: nil, dailyHour: parts[0], dailyMinute: parts[1]
                ))
            }
        }
        return result
    }
}

/// Avisos que se ven una sola vez por dispositivo aunque lleguen por varias vías.
public final class SeenLog: @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard, key: String = "kairo.seen") {
        self.defaults = defaults
        self.key = key
    }

    /// true si es la primera vez (y lo marca como visto). Guarda las últimas 400 claves.
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
