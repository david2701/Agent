import Foundation
#if canImport(Security)
import Security
#endif

/// Session stored on a device: server, user and refresh token (rotates on every refresh).
public struct StoredSession: Codable, Sendable, Hashable {
    public var serverURL: String
    public var user: User
    public var refreshToken: String
    public var deviceId: String

    public init(serverURL: String, user: User, refreshToken: String, deviceId: String) {
        self.serverURL = serverURL; self.user = user; self.refreshToken = refreshToken; self.deviceId = deviceId
    }
}

/// Secret store. On iPhone, iPad and Apple Watch it is the Keychain; in tests, memory.
public protocol SecretStore: Sendable {
    func read(_ key: String) -> Data?
    func write(_ key: String, _ value: Data?)
}

public final class MemoryStore: SecretStore, @unchecked Sendable {
    private var values: [String: Data] = [:]
    private let lock = NSLock()
    public init() {}
    public func read(_ key: String) -> Data? { lock.withLock { values[key] } }
    public func write(_ key: String, _ value: Data?) { lock.withLock { values[key] = value } }
}

#if canImport(Security)
/// Apple Keychain. With [accessGroup] the app and its extensions share the entries.
public struct KeychainStore: SecretStore {
    public let service: String
    public let accessGroup: String?

    public init(service: String = "app.kairo", accessGroup: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query(_ key: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        if let accessGroup { q[kSecAttrAccessGroup as String] = accessGroup }
        return q
    }

    public func read(_ key: String) -> Data? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    public func write(_ key: String, _ value: Data?) {
        let q = query(key)
        SecItemDelete(q as CFDictionary)
        guard let value else { return }
        var add = q
        add[kSecValueData as String] = value
        // Available after first unlock: alarms, widgets and the watch work with the screen locked
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }
}
#endif

/// Device session and in-memory access token.
public actor SessionStore {
    private let store: SecretStore
    private let key: String
    private var cached: StoredSession?
    private var loaded = false
    private var accessToken: String?
    private var accessExpiry: Date = .distantPast

    public init(store: SecretStore, key: String = "session") {
        self.store = store
        self.key = key
    }

    public func current() -> StoredSession? {
        if !loaded {
            loaded = true
            cached = store.read(key).flatMap { try? JSONDecoder().decode(StoredSession.self, from: $0) }
        }
        return cached
    }

    public func save(_ s: StoredSession) {
        cached = s
        loaded = true
        store.write(key, try? JSONEncoder().encode(s))
    }

    public func updateUser(_ u: User) {
        guard var s = current() else { return }
        s.user = u
        save(s)
    }

    public func clear() {
        cached = nil
        loaded = true
        accessToken = nil
        accessExpiry = .distantPast
        store.write(key, nil)
    }

    public func setAccess(_ token: String, expiresIn: Int) {
        accessToken = token
        // One-minute margin so a token about to expire is not used
        accessExpiry = Date().addingTimeInterval(TimeInterval(max(60, expiresIn - 60)))
    }

    public func validAccessToken() -> String? {
        guard let accessToken, accessExpiry > Date() else { return nil }
        return accessToken
    }

    public func invalidateAccess() {
        accessToken = nil
        accessExpiry = .distantPast
    }
}
