import Foundation

/// API dates (ISO-8601 with or without fractions) and human-readable text in English or Spanish.
///
/// Every human-readable helper takes a `locale` (default: the app language, see `KairoLanguage`). Weekday and
/// month names come from the locale's calendar symbols; the few connecting words ("today", "at", "ago") and the
/// word order are defined here for the two supported languages.
public enum KairoDate {

    /// "2026-10-11T14:00:00Z", "2026-10-11T14:00:00.123Z", "2026-10-11T09:00:00-05:00" or "2026-10-11".
    public static func parse(_ text: String?) -> Date? {
        guard let text, !text.isEmpty else { return nil }
        if let d = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) { return d }
        if let d = try? Date(text, strategy: Date.ISO8601FormatStyle()) { return d }
        if text.count == 10 { return day(text) }
        return nil
    }

    /// "2026-10-11" in the local time zone, at midnight.
    public static func day(_ text: String, calendar: Calendar = .current) -> Date? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// ISO-8601 in UTC for the API.
    public static func iso(_ date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle())
    }

    /// "2026-10-11" in the local time zone.
    public static func dayString(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Spanish locale, for code that needs it explicitly (tests, speech).
    public static let spanish = Locale(identifier: "es_ES")
    /// English locale, for code that needs it explicitly (tests, speech).
    public static let english = Locale(identifier: "en_US")

    private static func isSpanish(_ locale: Locale) -> Bool { locale.language.languageCode?.identifier == "es" }

    /// Full weekday and month names of the locale, indexed from 0 (Sunday / January).
    private static func names(_ calendar: Calendar, _ locale: Locale) -> (weekdays: [String], months: [String]) {
        var cal = calendar
        cal.locale = locale
        return (cal.weekdaySymbols, cal.monthSymbols)
    }

    /// "Thursday, October 15" / "jueves 15 de octubre" (+ year when `year` is set).
    private static func dayPhrase(_ date: Date, year: Bool, calendar: Calendar, locale: Locale) -> String {
        let n = names(calendar, locale)
        let c = calendar.dateComponents([.weekday, .day, .month, .year], from: date)
        let weekday = n.weekdays[((c.weekday ?? 1) - 1) % 7]
        let month = n.months[((c.month ?? 1) - 1) % 12]
        let day = c.day ?? 0
        if isSpanish(locale) {
            let base = "\(weekday) \(day) de \(month)"
            return year ? base + " de \(c.year ?? 0)" : base
        }
        let base = "\(weekday), \(month) \(day)"
        return year ? base + ", \(c.year ?? 0)" : base
    }

    /// "today", "tomorrow", "yesterday", the weekday (within the next week) or "Thursday, October 15".
    /// Spanish: "hoy", "mañana", "ayer", "jueves" or "jueves 15 de octubre".
    public static func humanDay(_ date: Date, now: Date = .now, calendar: Calendar = .current, locale: Locale = KairoLanguage.locale) -> String {
        let d0 = calendar.startOfDay(for: now)
        let d1 = calendar.startOfDay(for: date)
        let diff = calendar.dateComponents([.day], from: d0, to: d1).day ?? 0
        let es = isSpanish(locale)
        switch diff {
        case 0: return es ? "hoy" : "today"
        case 1: return es ? "mañana" : "tomorrow"
        case -1: return es ? "ayer" : "yesterday"
        case 2...6: return names(calendar, locale).weekdays[(calendar.component(.weekday, from: date) - 1) % 7]
        default:
            let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
            return dayPhrase(date, year: !sameYear, calendar: calendar, locale: locale)
        }
    }

    /// Time of day in the locale's own style: "9:00 AM" in English, "9:00" in Spanish (Spain).
    public static func time(_ date: Date, calendar: Calendar = .current, locale: Locale = KairoLanguage.locale) -> String {
        date.formatted(Date.FormatStyle(date: .omitted, time: .shortened, locale: locale, calendar: calendar, timeZone: calendar.timeZone))
    }

    /// "today at 9:00 AM", "tomorrow at 3:30 PM", "Thursday, October 15 at 10:00 AM".
    /// Spanish: "hoy a las 9:00", "mañana a las 15:30", "jueves 15 de octubre a las 10:00".
    public static func humanDateTime(_ date: Date, now: Date = .now, calendar: Calendar = .current, locale: Locale = KairoLanguage.locale) -> String {
        let at = isSpanish(locale) ? "a las" : "at"
        return "\(humanDay(date, now: now, calendar: calendar, locale: locale)) \(at) \(time(date, calendar: calendar, locale: locale))"
    }

    /// "Saturday, October 10" / "Sábado 10 de octubre".
    public static func longDay(_ date: Date, calendar: Calendar = .current, locale: Locale = KairoLanguage.locale) -> String {
        let s = dayPhrase(date, year: false, calendar: calendar, locale: locale)
        return s.prefix(1).uppercased() + s.dropFirst()
    }

    /// "October 2026" / "Octubre 2026".
    public static func monthTitle(_ date: Date, calendar: Calendar = .current, locale: Locale = KairoLanguage.locale) -> String {
        let n = names(calendar, locale)
        let c = calendar.dateComponents([.month, .year], from: date)
        let s = "\(n.months[((c.month ?? 1) - 1) % 12]) \(c.year ?? 0)"
        return s.prefix(1).uppercased() + s.dropFirst()
    }

    /// One-letter weekday symbols in calendar order, starting at `calendar.firstWeekday`:
    /// ["M", "T", "W", "T", "F", "S", "S"] (English, Monday first) or ["L", "M", "X", "J", "V", "S", "D"] (Spanish).
    public static func weekdayInitials(calendar: Calendar = .current, locale: Locale = KairoLanguage.locale) -> [String] {
        var cal = calendar
        cal.locale = locale
        let symbols = cal.veryShortStandaloneWeekdaySymbols
        let first = max(0, min(symbols.count - 1, cal.firstWeekday - 1))
        return Array(symbols[first...] + symbols[..<first])
    }

    /// "5 min ago", "2 h ago", the time (today), "yesterday", "Thu, Oct 8".
    /// Spanish: "hace 5 min", "hace 2 h", the time, "ayer", "jue 8 oct".
    public static func relative(_ date: Date, now: Date = .now, calendar: Calendar = .current, locale: Locale = KairoLanguage.locale) -> String {
        let es = isSpanish(locale)
        let secs = now.timeIntervalSince(date)
        if secs < 60 { return es ? "ahora" : "now" }
        if secs < 3600 { let m = Int(secs / 60); return es ? "hace \(m) min" : "\(m) min ago" }
        if secs < 6 * 3600 { let h = Int(secs / 3600); return es ? "hace \(h) h" : "\(h) h ago" }
        if calendar.isDate(date, inSameDayAs: now) { return time(date, calendar: calendar, locale: locale) }
        if let y = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: y) { return es ? "ayer" : "yesterday" }
        let n = names(calendar, locale)
        let c = calendar.dateComponents([.weekday, .day, .month], from: date)
        let weekday = n.weekdays[((c.weekday ?? 1) - 1) % 7].prefix(3)
        let month = n.months[((c.month ?? 1) - 1) % 12].prefix(3)
        return es ? "\(weekday) \(c.day ?? 0) \(month)" : "\(weekday), \(month) \(c.day ?? 0)"
    }

    /// "12:34" or "1:02:03" for durations.
    public static func duration(_ seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
