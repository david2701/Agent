import WidgetKit
import SwiftUI
import KairoCore

nonisolated struct SnapshotEntry: TimelineEntry {
    var date: Date
    var snapshot: SharedSnapshot
    var signedIn: Bool
}

/// El completion de WidgetKit no es Sendable; se llama una sola vez desde la tarea.
nonisolated struct Once<T>: @unchecked Sendable {
    let f: (T) -> Void
    func callAsFunction(_ v: T) { f(v) }
}

/// Lee el resumen que deja la app. Si es viejo, lo renueva con la sesión propia de las extensiones.
nonisolated struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry { SnapshotEntry(date: .now, snapshot: .preview, signedIn: true) }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        if context.isPreview {
            completion(SnapshotEntry(date: .now, snapshot: .preview, signedIn: true))
        } else {
            let s = AppConfig.snapshots.load()
            completion(SnapshotEntry(date: .now, snapshot: s ?? SharedSnapshot(), signedIn: s != nil))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let done = Once(f: completion)
        Task {
            let snap = await Self.fresh()
            done(Self.timeline(snap))
        }
    }

    static func fresh() async -> SharedSnapshot? {
        let cached = AppConfig.snapshots.load()
        if let cached, Date.now.timeIntervalSince(cached.updatedAt) < 20 * 60 { return cached }
        guard await ExtensionAPI.isReady(), let t = try? await ExtensionAPI.shared.today() else { return cached }
        let family = try? await ExtensionAPI.shared.family()
        let s = SharedSnapshot(today: t, userName: cached?.userName ?? "", unreadNotices: cached?.unreadNotices ?? 0, family: family)
        AppConfig.snapshots.save(s)
        return s
    }

    /// Una entrada ahora y otra al empezar o terminar cada evento, para que "Siguiente" no quede viejo.
    static func timeline(_ snap: SharedSnapshot?) -> Timeline<SnapshotEntry> {
        guard let snap else { return Timeline(entries: [SnapshotEntry(date: .now, snapshot: SharedSnapshot(), signedIn: false)], policy: .after(.now.addingTimeInterval(3600))) }
        var dates: [Date] = [.now]
        for e in snap.events { if let d = e.date, d > .now { dates.append(d); dates.append(d.addingTimeInterval(3600)) } }
        let entries = Array(Set(dates)).sorted().prefix(12).map { SnapshotEntry(date: $0, snapshot: snap, signedIn: true) }
        return Timeline(entries: entries, policy: .after(.now.addingTimeInterval(30 * 60)))
    }
}

/// Proveedor del widget de lista (configurable).
nonisolated struct ListProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ListEntry { ListEntry(date: .now, list: SharedSnapshot.preview.lists.first, signedIn: true) }

    func snapshot(for configuration: SelectListIntent, in context: Context) async -> ListEntry {
        if context.isPreview { return placeholder(in: context) }
        return entry(configuration, AppConfig.snapshots.load())
    }

    func timeline(for configuration: SelectListIntent, in context: Context) async -> Timeline<ListEntry> {
        let snap = await SnapshotProvider.fresh()
        return Timeline(entries: [entry(configuration, snap)], policy: .after(.now.addingTimeInterval(30 * 60)))
    }

    private func entry(_ c: SelectListIntent, _ snap: SharedSnapshot?) -> ListEntry {
        let lists = snap?.lists ?? []
        let list = c.list.flatMap { sel in lists.first { $0.id == sel.id } } ?? lists.first
        return ListEntry(date: .now, list: list, signedIn: snap != nil)
    }
}

nonisolated struct ListEntry: TimelineEntry {
    var date: Date
    var list: SharedSnapshot.ListSnapshot?
    var signedIn: Bool
}

extension SharedSnapshot {
    nonisolated static var preview: SharedSnapshot {
        let now = Date.now
        return SharedSnapshot(
            userName: "Ana", greeting: "Buenos días", dateLabel: "Sábado 10 de octubre",
            events: [
                .init(id: "e1", title: "Pediatra de Sofía", subtitle: "10:30 · Clínica del Country", date: now.addingTimeInterval(5400), shared: true),
                .init(id: "e2", title: "Almuerzo con Marta", subtitle: "13:00", date: now.addingTimeInterval(4 * 3600)),
            ],
            tasks: [
                .init(id: "t1", title: "Pagar la luz", subtitle: "Hoy"),
                .init(id: "t2", title: "Comprar regalo de Juan", subtitle: "Hoy · de Valentina", shared: true),
                .init(id: "t3", title: "Enviar el informe", subtitle: "En curso"),
            ],
            reminders: [.init(id: "r1", title: "Sacar la basura", subtitle: "20:00", date: now.addingTimeInterval(8 * 3600))],
            lists: [.init(id: "l1", name: "Súper", shared: true, items: [
                .init(id: "i1", title: "Leche"), .init(id: "i2", title: "Pan"), .init(id: "i3", title: "Huevos", done: true), .init(id: "i4", title: "Café"),
            ])],
            weather: "19° · Parcialmente nublado", unreadNotices: 2)
    }
}
