import WidgetKit
import SwiftUI
import KairoCore

nonisolated struct SnapshotEntry: TimelineEntry {
    var date: Date
    var snapshot: SharedSnapshot
    var signedIn: Bool
}

/// WidgetKit's completion is not Sendable; it is called only once from the task.
nonisolated struct Once<T>: @unchecked Sendable {
    let f: (T) -> Void
    func callAsFunction(_ v: T) { f(v) }
}

/// Reads the summary the app leaves. If it is old, it refreshes it with the extensions' own session.
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

    /// One entry now and another when each event starts or ends, so "Next" does not go stale.
    static func timeline(_ snap: SharedSnapshot?) -> Timeline<SnapshotEntry> {
        guard let snap else { return Timeline(entries: [SnapshotEntry(date: .now, snapshot: SharedSnapshot(), signedIn: false)], policy: .after(.now.addingTimeInterval(3600))) }
        var dates: [Date] = [.now]
        for e in snap.events { if let d = e.date, d > .now { dates.append(d); dates.append(d.addingTimeInterval(3600)) } }
        let entries = Array(Set(dates)).sorted().prefix(12).map { SnapshotEntry(date: $0, snapshot: snap, signedIn: true) }
        return Timeline(entries: entries, policy: .after(.now.addingTimeInterval(30 * 60)))
    }
}

/// Provider of the list widget (configurable).
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
            userName: "Alex", greeting: String(localized: "Good morning"), dateLabel: KairoDate.longDay(now),
            events: [
                .init(id: "e1", title: String(localized: "Mia's pediatrician"), subtitle: String(localized: "10:30 AM · Riverside Clinic"), date: now.addingTimeInterval(5400), shared: true),
                .init(id: "e2", title: String(localized: "Lunch with Sam"), subtitle: String(localized: "1:00 PM"), date: now.addingTimeInterval(4 * 3600)),
            ],
            tasks: [
                .init(id: "t1", title: String(localized: "Pay the electricity bill"), subtitle: String(localized: "Today")),
                .init(id: "t2", title: String(localized: "Buy a gift for Sam"), subtitle: String(localized: "Today · Alex's"), shared: true),
                .init(id: "t3", title: String(localized: "Send the report"), subtitle: String(localized: "In progress")),
            ],
            reminders: [.init(id: "r1", title: String(localized: "Take out the trash"), subtitle: String(localized: "8:00 PM"), date: now.addingTimeInterval(8 * 3600))],
            lists: [.init(id: "l1", name: String(localized: "Groceries"), shared: true, items: [
                .init(id: "i1", title: String(localized: "Milk")), .init(id: "i2", title: String(localized: "Bread")),
                .init(id: "i3", title: String(localized: "Eggs"), done: true), .init(id: "i4", title: String(localized: "Coffee")),
            ])],
            weather: String(localized: "19° · Partly cloudy"), unreadNotices: 2)
    }
}
