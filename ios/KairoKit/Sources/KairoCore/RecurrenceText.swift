import Foundation

/// "Every day", "Every Monday and Thursday", "Every month"... from a simple RRULE.
/// Spanish: "Cada día", "Cada lunes y jueves", "Cada mes"...
public enum RecurrenceText {
    /// RRULE day codes indexed like `Calendar.weekdaySymbols` (0 = Sunday).
    private static let dayCodes = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"]

    public static func describe(_ rrule: String, locale: Locale = KairoLanguage.locale) -> String {
        var parts: [String: String] = [:]
        for p in rrule.replacingOccurrences(of: "RRULE:", with: "").split(separator: ";") {
            let kv = p.split(separator: "=", maxSplits: 1)
            if kv.count == 2 { parts[String(kv[0])] = String(kv[1]) }
        }
        let es = locale.language.languageCode?.identifier == "es"
        let interval = Int(parts["INTERVAL"] ?? "1") ?? 1
        switch parts["FREQ"] {
        case "DAILY":
            if interval == 1 { return es ? "Cada día" : "Every day" }
            return es ? "Cada \(interval) días" : "Every \(interval) days"
        case "WEEKLY":
            let codes = (parts["BYDAY"] ?? "").split(separator: ",").map { String($0).uppercased() }.filter { dayCodes.contains($0) }
            if Set(codes) == ["MO", "TU", "WE", "TH", "FR"] { return es ? "Entre semana" : "Weekdays" }
            if codes.isEmpty {
                if interval == 1 { return es ? "Cada semana" : "Every week" }
                return es ? "Cada \(interval) semanas" : "Every \(interval) weeks"
            }
            var calendar = Calendar(identifier: .gregorian)
            calendar.locale = locale
            let symbols = calendar.weekdaySymbols
            let names = codes.compactMap { code in dayCodes.firstIndex(of: code).map { symbols[$0] } }
            return (es ? "Cada " : "Every ") + join(names, and: es ? "y" : "and")
        case "MONTHLY":
            if interval == 1 { return es ? "Cada mes" : "Every month" }
            return es ? "Cada \(interval) meses" : "Every \(interval) months"
        case "YEARLY":
            return es ? "Cada año" : "Every year"
        default:
            return es ? "Se repite" : "Repeats"
        }
    }

    /// "Monday", "Monday and Thursday", "Monday, Tuesday and Friday".
    private static func join(_ names: [String], and word: String) -> String {
        guard let last = names.last else { return "" }
        if names.count == 1 { return last }
        return names.dropLast().joined(separator: ", ") + " \(word) " + last
    }
}
