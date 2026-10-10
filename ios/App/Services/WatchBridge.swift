import Foundation
import WatchConnectivity
import KairoCore

/// The iPhone side of the connection with the Apple Watch.
nonisolated final class WatchBridge: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchBridge()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Day summary for the watch (it arrives even if the watch app is closed).
    func send(snapshot: SharedSnapshot) {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated, WCSession.default.isWatchAppInstalled else { return }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        guard let data = try? enc.encode(snapshot) else { return }
        try? WCSession.default.updateApplicationContext([WatchLink.snapshot: data])
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}
    func sessionDidBecomeInactive(_ session: WCSession) {}
    func sessionDidDeactivate(_ session: WCSession) { WCSession.default.activate() }

    /// The watch asks to pair: the iPhone generates a one-time code and sends it back.
    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        guard message[WatchLink.kind] as? String == WatchLink.pairRequest else { replyHandler([:]); return }
        let reply = UncheckedReply(replyHandler)
        Task { @MainActor in
            let api = AppModel.shared.api
            guard let server = await api.serverURL() else {
                reply([WatchLink.error: String(localized: "Sign in to Kairo on your iPhone first")])
                return
            }
            do {
                let code = try await api.pairCode()
                reply([WatchLink.server: server, WatchLink.code: code])
            } catch {
                reply([WatchLink.error: error.localizedDescription])
            }
        }
    }
}

/// WatchConnectivity's replyHandler is not Sendable; it is called only once.
nonisolated private struct UncheckedReply: @unchecked Sendable {
    let f: ([String: Any]) -> Void
    init(_ f: @escaping ([String: Any]) -> Void) { self.f = f }
    func callAsFunction(_ v: [String: Any]) { f(v) }
}
