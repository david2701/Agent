import Foundation

/// Invite links and codes.
///
/// The canonical link is `https://host/join?code=XXXX-XXXX` (or `kairo://join?code=XXXX-XXXX`). The legacy
/// Spanish form `https://host/unirse?codigo=XXXX-XXXX` (or `kairo://unirse?codigo=...`) keeps working, and a bare
/// code ("XXXX-XXXX") is accepted too, so people can paste whatever they were sent.
public enum InviteLink {
    public struct Parsed: Equatable, Sendable {
        /// Upper-case code with letters, digits and dashes.
        public var code: String
        /// `scheme://host[:port]` when the link is http(s); nil for `kairo://` links and bare codes.
        public var server: String?

        public init(code: String, server: String? = nil) {
            self.code = code
            self.server = server
        }
    }

    private static let joinNames: Set<String> = ["join", "unirse"]
    private static let codeNames: Set<String> = ["code", "codigo"]

    /// A `kairo://` or http(s) link. Returns nil if it is not an invite link.
    public static func parse(url: URL) -> Parsed? {
        let host = (url.host ?? "").lowercased()
        let segments = url.pathComponents.map { $0.lowercased() }
        guard joinNames.contains(host) || segments.contains(where: { joinNames.contains($0) }) else { return nil }
        guard let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let raw = items.first(where: { codeNames.contains($0.name.lowercased()) })?.value,
              let code = cleanCode(raw) else { return nil }
        var server: String?
        if let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https", let h = url.host, !h.isEmpty {
            server = "\(scheme)://\(h)" + (url.port.map { ":\($0)" } ?? "")
        }
        return Parsed(code: code, server: server)
    }

    /// Text a person typed or pasted: an invite link or a bare code.
    public static func parse(_ text: String) -> Parsed? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.contains("://") || t.contains("/") {
            let withScheme = t.contains("://") ? t : "https://" + t
            if let url = URL(string: withScheme), let parsed = parse(url: url) { return parsed }
            return nil
        }
        guard let code = cleanCode(t) else { return nil }
        return Parsed(code: code)
    }

    /// "abcd efgh" or "abcd-efgh" -> "ABCD-EFGH". Nil if it has no letters or digits.
    public static func cleanCode(_ raw: String) -> String? {
        let upper = raw.uppercased().filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == " " }
        let compact = upper.split(whereSeparator: { $0 == " " || $0 == "-" }).joined()
        guard compact.count >= 4 else { return nil }
        if compact.count == 8 { return String(compact.prefix(4)) + "-" + String(compact.suffix(4)) }
        return compact
    }
}
