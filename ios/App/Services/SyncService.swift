import Foundation
import BackgroundTasks
import CoreLocation
import UIKit
import UserNotifications
import KairoCore

/// Sincronización del teléfono: notificaciones locales, geovallas y avisos de la familia que no llegaron por push.
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

    /// iOS decide cuándo; pedimos cada 15 minutos como mínimo.
    nonisolated static func scheduleBackgroundRefresh() {
        Task {
            let req = BGAppRefreshTaskRequest(identifier: AppConfig.syncTaskId)
            req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
            try? await BGTaskScheduler.shared.submitTaskRequest(req)
        }
    }
}

/// Recordatorios por lugar ("cuando llegue a casa") con CLMonitor. iOS admite 20 condiciones por app.
@MainActor
final class GeofenceService {
    static let shared = GeofenceService()
    private var monitor: CLMonitor?
    private var listening: Task<Void, Never>?
    private var texts: [String: String] = [:]

    private func ensureMonitor() async -> CLMonitor {
        if let monitor { return monitor }
        let m = await CLMonitor("kairo.lugares")
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

    /// Se llama al iniciar la app (también cuando iOS la despierta por una geovalla).
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
            let id = "rem:\(r.id):\(trigger)"
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
        let parts = identifier.split(separator: ":")
        guard parts.count == 3 else { return }
        let arrive = parts[2] == "arrive"
        guard arrive == satisfied else { return }
        let text = texts[identifier] ?? "Recordatorio"
        NotificationService.showNow(
            id: "geo:\(parts[1]):\(Int(Date().timeIntervalSince1970 / 600))", title: text,
            body: arrive ? "Llegaste" : "Saliste", entityId: String(parts[1]),
            category: NotificationService.categoryReminder, destination: "reminders"
        )
    }
}

/// Ubicación aproximada para "guarda este lugar", el clima local y los recordatorios por lugar.
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

    /// Última ubicación conocida (sin encender el GPS).
    nonisolated func lastLocation() async -> (Double, Double)? {
        await MainActor.run {
            guard authorized, let l = manager.location, l.timestamp.timeIntervalSinceNow > -1800 else { return nil }
            return (l.coordinate.latitude, l.coordinate.longitude)
        }
    }

    /// Ubicación actual, pidiendo una lectura nueva.
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

/// Push de Apple (APNs): sólo si el servidor lo tiene configurado. Sin él, Kairo usa las otras vías.
enum PushRegistration {
    static func register() {
        UIApplication.shared.registerForRemoteNotifications()
    }
}

/// Sesión propia para los widgets interactivos, los controles y la extensión de compartir.
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
