import Foundation
import BackgroundTasks
import CoreLocation
import UIKit
import UserNotifications
import KairoCore

/// Phone sync: local notifications, geofences and family notices that did not arrive by push.
@MainActor
final class SyncService {
    static let shared = SyncService()
    private var running = false

    func sync(api: KairoAPI, model: AppModel) async {
        guard !running else { return }
        running = true
        defer { running = false }
        guard let s = try? await api.deviceSync() else { return }
        await NotificationService.apply(NotificationPlan.make(s))
        await GeofenceService.shared.update(places: s.places, reminders: s.reminders)
        model.showMissed(s.notices)
    }

    /// iOS decides when; we ask for every 15 minutes at the earliest.
    nonisolated static func scheduleBackgroundRefresh() {
        Task {
            let req = BGAppRefreshTaskRequest(identifier: AppConfig.syncTaskId)
            req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
            try? await BGTaskScheduler.shared.submitTaskRequest(req)
        }
    }
}

/// Place reminders ("when I get home") with CLMonitor. iOS allows 20 conditions per app.
@MainActor
final class GeofenceService {
    static let shared = GeofenceService()
    private var monitor: CLMonitor?
    private var listening: Task<Void, Never>?
    private var texts: [String: String] = [:]

    private func ensureMonitor() async -> CLMonitor {
        if let monitor { return monitor }
        // The monitor name is kept as is so existing installs keep their registered conditions.
        let m = await CLMonitor("KairoLugares")
        monitor = m
        listening = Task { [weak self] in
            do {
                for try await event in await m.events {
                    self?.fired(event.identifier, satisfied: event.state == .satisfied)
                }
            } catch {}
        }
        return m
    }

    /// Called when the app starts (also when iOS wakes it for a geofence).
    func start() {
        Task { _ = await ensureMonitor() }
    }

    func update(places: [Place], reminders: [Reminder]) async {
        let placeReminders = reminders.filter { $0.status == "scheduled" && $0.placeId != nil }
        let m = await ensureMonitor()
        let current = Set(await m.identifiers)
        var wanted: Set<String> = []
        for r in placeReminders.prefix(20) {
            guard let p = places.first(where: { $0.id == r.placeId }) else { continue }
            let trigger = r.placeTrigger ?? "arrive"
            let id = Self.conditionId(reminder: r.id, arrive: trigger == "arrive")
            wanted.insert(id)
            texts[id] = r.text
            if !current.contains(id) {
                let cond = CLMonitor.CircularGeographicCondition(
                    center: CLLocationCoordinate2D(latitude: p.latitude, longitude: p.longitude),
                    radius: CLLocationDistance(max(100, p.radiusM))
                )
                await m.add(cond, identifier: id, assuming: trigger == "arrive" ? .unsatisfied : .satisfied)
            }
        }
        for id in current.subtracting(wanted) { await m.remove(id) }
        if !wanted.isEmpty { LocationService.shared.requestAlways() }
    }

    private func fired(_ identifier: String, satisfied: Bool) {
        guard let parsed = Self.parse(identifier), parsed.1 == satisfied else { return }
        let (reminderId, arrive) = parsed
        let text = texts[identifier] ?? String(localized: "Reminder")
        NotificationService.showNow(
            id: "geo:\(reminderId):\(Int(Date().timeIntervalSince1970 / 600))", title: text,
            body: arrive ? String(localized: "You arrived") : String(localized: "You left"), entityId: reminderId,
            category: NotificationService.categoryReminder, destination: "reminders"
        )
    }

    /// CoreLocation only allows letters and numbers in names: "R" + id without dashes + "A" (arrive) or "L" (leave).
    static func conditionId(reminder: String, arrive: Bool) -> String {
        "R" + reminder.filter { $0.isLetter || $0.isNumber } + (arrive ? "A" : "L")
    }

    static func parse(_ identifier: String) -> (String, Bool)? {
        guard identifier.count == 34, identifier.first == "R", let last = identifier.last, last == "A" || last == "L" else { return nil }
        let hex = Array(identifier.dropFirst().dropLast())
        let uuid = [hex[0..<8], hex[8..<12], hex[12..<16], hex[16..<20], hex[20..<32]].map { String($0) }.joined(separator: "-")
        return (uuid, last == "A")
    }
}

/// Approximate location for "save this place", the local weather and place reminders.
@MainActor
final class LocationService: NSObject, CLLocationManagerDelegate {
    static let shared = LocationService()
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    var authorized: Bool {
        [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus)
    }

    func requestWhenInUse() { manager.requestWhenInUseAuthorization() }

    func requestAlways() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        else if manager.authorizationStatus == .authorizedWhenInUse { manager.requestAlwaysAuthorization() }
    }

    /// Last known location (without turning the GPS on).
    nonisolated func lastLocation() async -> (Double, Double)? {
        await MainActor.run {
            guard authorized, let l = manager.location, l.timestamp.timeIntervalSinceNow > -1800 else { return nil }
            return (l.coordinate.latitude, l.coordinate.longitude)
        }
    }

    /// Current location, asking for a fresh reading.
    func current() async -> CLLocation? {
        if !authorized { requestWhenInUse() }
        do {
            for try await update in CLLocationUpdate.liveUpdates() {
                if let l = update.location { return l }
            }
        } catch {}
        return nil
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {}
}

/// Apple push (APNs): only if the server has it configured. Without it, Kairo uses the other channels.
enum PushRegistration {
    static func register() {
        UIApplication.shared.registerForRemoteNotifications()
    }
}

/// Own session for the interactive widgets, the controls and the share extension.
enum ExtensionSessionService {
    static func ensure(api: KairoAPI) async {
        guard await !ExtensionAPI.isReady(), let server = await api.serverURL() else { return }
        guard let code = try? await api.pairCode() else { return }
        _ = try? await ExtensionAPI.shared.pair(server: server, code: code)
    }

    static func clear() async {
        await ExtensionAPI.shared.logout()
    }
}
