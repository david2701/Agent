import Foundation
import KairoCore

/// Configuration shared by the app, the widgets, the share extension and the watch.
/// The values come from each target's Info.plist (see Config/Signing.xcconfig).
nonisolated enum AppConfig {
    static func info(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    static var appGroup: String? { info("KairoAppGroup") }
    static var keychainGroup: String? { info("KairoKeychainGroup") }
    static var defaultServer: String { info("KairoDefaultServer") ?? "https://" }

    /// Shared folder (App Group). Without an App Group configured, the app's own folder.
    static var sharedDirectory: URL {
        if let g = appGroup, let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: g) {
            return url.appendingPathComponent("Kairo", isDirectory: true)
        }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Kairo", isDirectory: true)
    }

    static var sharedDefaults: UserDefaults { appGroup.flatMap { UserDefaults(suiteName: $0) } ?? .standard }

    static var snapshots: SnapshotStore { SnapshotStore(directory: sharedDirectory) }

    /// The app's Keychain (only the app reads it).
    static var appKeychain: SecretStore { KeychainStore(service: "app.kairo", accessGroup: nil) }

    /// Keychain shared with the widgets and the share extension. They have their own session
    /// (with their own refresh token), so refreshes never collide with the app's.
    static var sharedKeychain: SecretStore { KeychainStore(service: "app.kairo.extensions", accessGroup: keychainGroup) }

    static let syncTaskId = (Bundle.main.bundleIdentifier ?? "app.kairo") + ".sync"
    static let talkActivityType = (Bundle.main.bundleIdentifier ?? "app.kairo") + ".talk"
}

/// API access from the extensions (interactive widgets, controls, share).
nonisolated enum ExtensionAPI {
    static let shared: KairoAPI = KairoAPI(
        sessions: SessionStore(store: AppConfig.sharedKeychain, key: "session.extensions"),
        client: ClientInfo(kind: "ios", deviceName: String(localized: "Widgets & Share"), platform: "ios")
    )

    static func isReady() async -> Bool { await shared.sessions.current() != nil }
}
