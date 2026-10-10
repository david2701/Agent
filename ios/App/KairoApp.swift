import SwiftUI
import Combine
import BackgroundTasks
import KairoCore
import KairoUI

@main
struct KairoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    init() {
        KairoFonts.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(KairoColor.accent)
                .onOpenURL { model.open(url: $0) }
                .onContinueUserActivity(AppConfig.talkActivityType) { _ in model.perform("talk") }
                .onReceive(NotificationCenter.default.publisher(for: .kairoPendingAction).receive(on: DispatchQueue.main)) { note in
                    if let a = note.object as? String { _ = PendingAction.take(); model.perform(a) }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.becameActive()
            case .background: model.enteredBackground()
            default: break
            }
        }
        .backgroundTask(.appRefresh(AppConfig.syncTaskId)) {
            await AppModel.shared.backgroundRefresh()
        }
    }
}

/// Notifications (local and Apple push), "Done" and "Snooze" actions, and APNs registration.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        NotificationService.registerCategories()
        WatchBridge.shared.activate()
        GeofenceService.shared.start()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        Task { await AppModel.shared.registerApns(token: token) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}

    /// Silent or content push: it carries the same `{type, data}` as the WebSocket.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        guard let ev = Self.serverEvent(userInfo) else { return .noData }
        AppModel.shared.handle(RealtimeEvent(ev), fromPush: true)
        await AppModel.shared.backgroundRefresh()
        return .newData
    }

    static func serverEvent(_ userInfo: [AnyHashable: Any]) -> ServerEvent? {
        guard let k = userInfo["kairo"], let data = try? JSONSerialization.data(withJSONObject: k) else { return nil }
        return try? JSONDecoder().decode(ServerEvent.self, from: data)
    }

    // UserNotifications calls its delegate from its own queue: the methods are not isolated to the main thread.
    // They are also shown in the foreground (except those the app already showed through the WebSocket)
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let entityId = info["entityId"] as? String
        let destination = info["destination"] as? String
        await Self.respond(action: response.actionIdentifier, entityId: entityId, destination: destination)
    }

    @MainActor
    private static func respond(action: String, entityId: String?, destination: String?) async {
        switch action {
        case NotificationService.actionDone:
            if let id = entityId { _ = try? await AppModel.shared.api.reminderDone(id) }
        case NotificationService.actionSnooze:
            if let id = entityId { _ = try? await AppModel.shared.api.reminderSnooze(id, minutes: 10) }
        case NotificationService.actionListen:
            AppModel.shared.perform("briefing")
        default:
            if let destination { AppModel.shared.perform("section:\(destination)") }
        }
    }
}
