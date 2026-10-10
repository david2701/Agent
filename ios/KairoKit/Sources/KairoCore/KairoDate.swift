import Foundation

/// Fechas del API (ISO-8601 con o sin fracciones) y textos humanos en español.
public enum KairoDate {

    /// "2026-10-11T14:00:00Z", "2026-10-11T14:00:00.123Z", "2026-10-11T09:00:00-05:00" o "2026-10-11".
    public static func parse(_ text: String?) -> Date? {
        guard let text, !text.isEmpty else { return nil }
        if let d = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) { return d }
        if let d = try? Date(text, strategy: Date.ISO8601FormatStyle()) { return d }
        if text.count == 10 { return day(text) }
        return nil
    }

    /// "2026-10-11" en la zona local, a medianoche.
    public static func day(_ text: String, calendar: Calendar = .current) -> Date? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// ISO-8601 en UTC para el API.
    public static func iso(_ date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle())
    }

    /// "2026-10-11" en la zona local.
    public static func dayString(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    public static let spanish = Locale(identifier: "es_CO")

    static let weekdays = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"]
    static let months = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"]

    /// "hoy", "mañana", "ayer", "el jueves" (esta semana) o "jueves 15 de octubre".
    public static func humanDay(_ date: Date, now: Date = .now, calendar: Calendar = .current) -> String {
        let d0 = calendar.startOfDay(for: now)
        let d1 = calendar.startOfDay(for: date)
        let diff = calendar.dateComponents([.day], from: d0, to: d1).day ?? 0
        switch diff {
        case 0: return "hoy"
        case 1: return "mañana"
        case -1: return "ayer"
        case 2...6: return weekdays[calendar.component(.weekday, from: date) - 1]
        default:
            let c = calendar.dateComponents([.weekday, .day, .month, .year], from: date)
            let base = "\(weekdays[(c.weekday ?? 1) - 1]) \(c.day ?? 0) de \(months[(c.month ?? 1) - 1])"
            return c.year == calendar.component(.year, from: now) ? base : base + " de \(c.year ?? 0)"
        }
    }

    /// "9:00" (24 h, como en el resto de Kairo).
    public static func time(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    /// "hoy a las 9:00", "mañana a las 15:30", "jueves 15 de octubre a las 10:00".
    public static func humanDateTime(_ date: Date, now: Date = .now, calendar: Calendar = .current) -> String {
        "\(humanDay(date, now: now, calendar: calendar)) a las \(time(date, calendar: calendar))"
    }

    /// "Sábado 10 de octubre".
    public static func longDay(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.weekday, .day, .month], from: date)
        let s = "\(weekdays[(c.weekday ?? 1) - 1]) \(c.day ?? 0) de \(months[(c.month ?? 1) - 1])"
        return s.prefix(1).uppercased() + s.dropFirst()
    }

    /// "hace 5 min", "hace 2 h", "ayer", "jue 8 oct".
    public static func relative(_ date: Date, now: Date = .now, calendar: Calendar = .current) -> String {
        let secs = now.timeIntervalSince(date)
        if secs < 60 { return "ahora" }
        if secs < 3600 { return "hace \(Int(secs / 60)) min" }
        if secs < 6 * 3600 { return "hace \(Int(secs / 3600)) h" }
        if calendar.isDate(date, inSameDayAs: now) { return time(date, calendar: calendar) }
        if let y = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: y) { return "ayer" }
        let c = calendar.dateComponents([.weekday, .day, .month], from: date)
        return "\(weekdays[(c.weekday ?? 1) - 1].prefix(3)) \(c.day ?? 0) \(months[(c.month ?? 1) - 1].prefix(3))"
    }

    /// "12:34" o "1:02:03" para duraciones.
    public static func duration(_ seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
