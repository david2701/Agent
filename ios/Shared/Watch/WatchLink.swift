import Foundation

/// Messages between the iPhone and the Apple Watch (WatchConnectivity).
/// The watch asks the iPhone for a pairing code and opens its own session with the server;
/// the iPhone sends it the day's summary to show instantly.
nonisolated enum WatchLink {
    static let kind = "kind"
    static let pairRequest = "pairRequest"
    static let server = "server"
    static let code = "code"
    static let error = "error"
    static let snapshot = "snapshot"
}
