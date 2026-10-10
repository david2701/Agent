import SwiftUI
import WatchConnectivity
import WidgetKit
import KairoCore
import KairoUI

@main
struct KairoWatchApp: App {
    @State private var model = WatchModel.shared
    @Environment(\.scenePhase) private var scenePhase

    init() { KairoFonts.register() }

    var body: some Scene {
        WindowGroup {
            WatchRoot()
                .environment(model)
                .tint(KairoColor.accent)
                .onOpenURL { url in if url.host == "talk" { model.wantsTalk = true } }
        }
        .onChange(of: scenePhase) { _, p in if p == .active { Task { await model.refresh() } } }
    }
}

/// Watch state: own session with your server, day summary and link with the iPhone.
@MainActor @Observable
final class WatchModel {
    static let shared = WatchModel()

    enum Phase { case loading, signedOut, ready }

    let api = KairoAPI(
        sessions: SessionStore(store: AppConfig.appKeychain, key: "session.watch"),
        client: ClientInfo(kind: "watch", deviceName: "Apple Watch", platform: "watch")
    )
    var phase: Phase = .loading
    var snapshot = SharedSnapshot()
    var pairing = false
    var pairError: String?
    var wantsTalk = false
    let voice: WatchVoice

    private init() {
        voice = WatchVoice(api: api)
        if let s = AppConfig.snapshots.load() { snapshot = s }
        WatchSessionDelegate.shared.activate()
        Task { await bootstrap() }
    }

    func bootstrap() async {
        await api.setOnSessionExpired { Task { @MainActor in WatchModel.shared.phase = .signedOut } }
        phase = await api.sessions.current() == nil ? .signedOut : .ready
        if phase == .ready { await refresh() } else { await pairWithPhone() }
    }

    func refresh() async {
        guard phase == .ready, let t = try? await api.today() else { return }
        let family = try? await api.family()
        let me = await api.sessions.current()?.user.firstName ?? ""
        update(SharedSnapshot(today: t, userName: me, unreadNotices: snapshot.unreadNotices, family: family))
    }

    func update(_ s: SharedSnapshot) {
        withAnimation(KairoMotion.base) { snapshot = s }
        AppConfig.snapshots.save(s)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Asks the iPhone for a one-time code and opens the watch session with it.
    func pairWithPhone() async {
        guard WCSession.isSupported(), WCSession.default.activationState == .activated, WCSession.default.isReachable else {
            pairError = String(localized: "Open Kairo on the iPhone or enter a code")
            return
        }
        pairing = true
        defer { pairing = false }
        do {
            let reply = try await WatchSessionDelegate.shared.requestPairing()
            if let e = reply.error { pairError = e; return }
            guard let server = reply.server, let code = reply.code else { pairError = String(localized: "The iPhone didn't respond"); return }
            try await pair(server: server, code: code)
        } catch {
            pairError = String(localized: "Open Kairo on the iPhone or enter a code")
        }
    }

    func pair(server: String, code: String) async throws {
        _ = try await api.pair(server: server, code: code)
        pairError = nil
        withAnimation(KairoMotion.base) { phase = .ready }
        await refresh()
    }

    func toggleItem(list: String, item: String, checked: Bool) async {
        var s = snapshot
        if let li = s.lists.firstIndex(where: { $0.id == list }), let ii = s.lists[li].items.firstIndex(where: { $0.id == item }) {
            s.lists[li].items[ii].done = checked
            update(s)
        }
        _ = try? await api.setItem(list, item, checked: checked)
    }

    func complete(task: String) async {
        var s = snapshot
        if let i = s.tasks.firstIndex(where: { $0.id == task }) { s.tasks[i].done = true; update(s) }
        _ = try? await api.updateTask(task, ["status": "done"])
    }

    func signOut() async {
        await api.logout()
        phase = .signedOut
    }
}

/// The iPhone's answer to a pairing request.
nonisolated struct PairReply: Sendable {
    var server: String?
    var code: String?
    var error: String?
}

/// WatchConnectivity on the watch side: receives the day summary and asks for pairing codes.
nonisolated final class WatchSessionDelegate: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchSessionDelegate()

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func requestPairing() async throws -> PairReply {
        try await withCheckedThrowingContinuation { c in
            WCSession.default.sendMessage([WatchLink.kind: WatchLink.pairRequest], replyHandler: { r in
                c.resume(returning: PairReply(server: r[WatchLink.server] as? String, code: r[WatchLink.code] as? String, error: r[WatchLink.error] as? String))
            }, errorHandler: { e in c.resume(throwing: e) })
        }
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if let data = session.receivedApplicationContext[WatchLink.snapshot] as? Data { apply(data) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        if let data = applicationContext[WatchLink.snapshot] as? Data { apply(data) }
    }

    private func apply(_ data: Data) {
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        guard let s = try? dec.decode(SharedSnapshot.self, from: data) else { return }
        Task { @MainActor in
            // The iPhone's summary is used as long as the watch has no more recent one of its own
            if s.updatedAt > WatchModel.shared.snapshot.updatedAt { WatchModel.shared.update(s) }
        }
    }
}
