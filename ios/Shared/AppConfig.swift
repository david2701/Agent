import Foundation
import KairoCore

/// Configuración común a la app, los widgets, la extensión de compartir y el reloj.
/// Los valores vienen del Info.plist de cada target (ver Config/Signing.xcconfig).
nonisolated enum AppConfig {
    static func info(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    static var appGroup: String? { info("KairoAppGroup") }
    static var keychainGroup: String? { info("KairoKeychainGroup") }
    static var defaultServer: String { info("KairoDefaultServer") ?? "https://" }

    /// Carpeta compartida (App Group). Sin App Group configurado, la de la propia app.
    static var sharedDirectory: URL {
        if let g = appGroup, let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: g) {
            return url.appendingPathComponent("Kairo", isDirectory: true)
        }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Kairo", isDirectory: true)
    }

    static var sharedDefaults: UserDefaults { appGroup.flatMap { UserDefaults(suiteName: $0) } ?? .standard }

    static var snapshots: SnapshotStore { SnapshotStore(directory: sharedDirectory) }

    /// Llavero de la app (sólo la app lo lee).
    static var appKeychain: SecretStore { KeychainStore(service: "app.kairo", accessGroup: nil) }

    /// Llavero compartido con los widgets y la extensión de compartir. Tienen su propia sesión
    /// (con su propio refresh token), así nunca se pisan las renovaciones con la app.
    static var sharedKeychain: SecretStore { KeychainStore(service: "app.kairo.extensions", accessGroup: keychainGroup) }

    static let syncTaskId = (Bundle.main.bundleIdentifier ?? "app.kairo") + ".sync"
    static let talkActivityType = (Bundle.main.bundleIdentifier ?? "app.kairo") + ".talk"
}

/// Acceso al API desde las extensiones (widgets interactivos, controles, compartir).
nonisolated enum ExtensionAPI {
    static let shared: KairoAPI = KairoAPI(
        sessions: SessionStore(store: AppConfig.sharedKeychain, key: "session.extensions"),
        client: ClientInfo(kind: "ios", deviceName: "Widgets y compartir", platform: "ios")
    )

    static func isReady() async -> Bool { await shared.sessions.current() != nil }
}
