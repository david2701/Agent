import WidgetKit
import SwiftUI
import KairoCore
import KairoUI

nonisolated struct SnapshotEntry: TimelineEntry {
    var date: Date
    var snapshot: SharedSnapshot
}

nonisolated struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry { SnapshotEntry(date: .now, snapshot: .preview) }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(SnapshotEntry(date: .now, snapshot: context.isPreview ? .preview : (AppConfig.snapshots.load() ?? SharedSnapshot())))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let snap = AppConfig.snapshots.load() ?? SharedSnapshot()
        completion(Timeline(entries: [SnapshotEntry(date: .now, snapshot: snap)], policy: .after(.now.addingTimeInterval(30 * 60))))
    }
}

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.today, provider: SnapshotProvider()) { entry in
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.snapshot.greeting.isEmpty ? "Hoy" : entry.snapshot.greeting).font(.kHeadline)
                ForEach(entry.snapshot.events.prefix(3)) { e in Text(e.title).font(.kFootnote).lineLimit(1) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .containerBackground(for: .widget) { KairoColor.surface }
        }
        .configurationDisplayName("Hoy")
        .description("Tu agenda y tus pendientes.")
    }
}

extension SharedSnapshot {
    nonisolated static var preview: SharedSnapshot {
        SharedSnapshot(userName: "Ana", greeting: "Buenos días", dateLabel: "Sábado 10 de octubre",
                       events: [.init(id: "1", title: "Cita con el pediatra", subtitle: "10:30 · Clínica", date: .now.addingTimeInterval(3600), shared: true)],
                       tasks: [.init(id: "t1", title: "Pagar la luz", subtitle: "Hoy")])
    }
}
