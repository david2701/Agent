import Foundation

/// The language Kairo shows in its own UI: English by default, Spanish when the device prefers it.
///
/// It follows the same rule the system uses to pick a localization for the app bundle: the first of the
/// device's preferred languages that is English or Spanish wins, and English is the fallback. Dates, speech
/// recognition and the wake phrase all derive from this so they never disagree with the visible text.
public enum KairoLanguage {
    /// "en" or "es".
    public static var code: String { resolve(Locale.preferredLanguages) }

    public static func resolve(_ preferred: [String]) -> String {
        for language in preferred {
            let l = language.lowercased()
            if l == "es" || l.hasPrefix("es-") || l.hasPrefix("es_") { return "es" }
            if l == "en" || l.hasPrefix("en-") || l.hasPrefix("en_") { return "en" }
        }
        return "en"
    }

    public static var isSpanish: Bool { code == "es" }

    /// Locale for dates and numbers. Keeps the device region (12/24 h clock, first weekday...) when the device
    /// language is the app language; otherwise uses a neutral English or Spanish locale.
    public static var locale: Locale { locale(forCode: code) }

    public static func locale(forCode code: String) -> Locale {
        let current = Locale.current
        if current.language.languageCode?.identifier == code { return current }
        return Locale(identifier: code == "es" ? "es_ES" : "en_US")
    }

    /// Locale identifier for `SFSpeechRecognizer` and AVSpeech voices: "en-US" or "es-ES".
    public static var speechLocaleIdentifier: String { code == "es" ? "es-ES" : "en-US" }

    /// Wake phrase as shown in the UI: "Hey Kairo" or "Oye Kairo".
    public static var wakePhraseDisplay: String { code == "es" ? "Oye Kairo" : "Hey Kairo" }

    /// Value for the server's `language` setting that matches the UI language.
    public static var serverLanguage: String { code }
}

/// Looks up user-visible strings of KairoCore in its own resource bundle. The English text is the key.
///
/// SwiftPM resources are only declared on Apple platforms (see `Package.swift`), so on Linux, where the tests
/// run, the English key itself is returned.
enum L10n {
    static func tr(_ key: String) -> String {
        #if os(Linux)
        return key
        #else
        return Bundle.module.localizedString(forKey: key, value: nil, table: nil)
        #endif
    }

    /// Key with numeric placeholders (`%lld`).
    static func tr(_ key: String, _ args: CVarArg...) -> String {
        String(format: tr(key), arguments: args)
    }

    /// Key with `%@` placeholders filled by strings, in order (does not rely on `String` being `CVarArg`).
    static func tr(_ key: String, strings: String...) -> String {
        var out = tr(key)
        for s in strings {
            guard let r = out.range(of: "%@") else { break }
            out.replaceSubrange(r, with: s)
        }
        return out
    }
}
