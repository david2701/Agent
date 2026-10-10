import WidgetKit
import SwiftUI
import KairoCore
import KairoUI

// Apple Watch complications: talk, the next event and the to-dos.

nonisolated struct WatchEntry: TimelineEntry {
    var date: Date
    var snapshot: SharedSnapshot
}

nonisolated struct WatchProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchEntry { WatchEntry(date: .now, snapshot: SharedSnapshot()) }

    func getSnapshot(in context: Context, completion: @escaping (WatchEntry) -> Void) {
        completion(WatchEntry(date: .now, snapshot: AppConfig.snapshots.load() ?? SharedSnapshot()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchEntry>) -> Void) {
        let snap = AppConfig.snapshots.load() ?? SharedSnapshot()
        var dates: [Date] = [.now]
        for e in snap.events { if let d = e.date, d > .now { dates.append(d); dates.append(d.addingTimeInterval(3600)) } }
        let entries = Array(Set(dates)).sorted().prefix(10).map { WatchEntry(date: $0, snapshot: snap) }
        completion(Timeline(entries: entries, policy: .after(.now.addingTimeInterval(30 * 60))))
    }
}

struct TalkComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "KairoWatchTalk", provider: WatchProvider()) { _ in
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "waveform.circle.fill").font(.system(size: 24, weight: .semibold)).widgetAccentable()
            }
            .widgetURL(URL(string: "kairo://talk"))
            .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Talk to Kairo")
        .description("One tap and Kairo listens.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner])
    }
}

struct NextComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "KairoWatchNext", provider: WatchProvider()) { entry in
            NextComplicationView(entry: entry).containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Next")
        .description("Your next event and your to-dos.")
        .supportedFamilies([.accessoryRectangular, .accessoryInline, .accessoryCircular])
    }
}

struct NextComplicationView: View {
    @Environment(\.widgetFamily) private var family
    var entry: WatchEntry

    var body: some View {
        let next = entry.snapshot.nextEvent(after: entry.date)
        let open = entry.snapshot.tasks.filter { !$0.done }.count
        switch family {
        case .accessoryInline:
            if let n = next, let d = n.date { Text(verbatim: "\(KairoDate.time(d)) \(n.title)") } else { Text("\(open) pending") }
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text(verbatim: "\(open)").font(.system(size: 20, weight: .semibold, design: .rounded))
                    Image(systemName: "checkmark").font(.system(size: 9, weight: .bold))
                }
            }
        default:
            VStack(alignment: .leading, spacing: 1) {
                if let n = next, let d = n.date {
                    HStack(spacing: 3) {
                        Image(systemName: n.shared ? "house.fill" : "calendar")
                        Text(d > entry.date ? KairoDate.time(d) : String(localized: "Now"))
                    }
                    .font(.system(size: 12, weight: .semibold)).widgetAccentable()
                    Text(n.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    if d > entry.date { Text(d, style: .relative).font(.system(size: 12)).foregroundStyle(.secondary) }
                } else {
                    Text("Kairo").font(.system(size: 12, weight: .semibold)).widgetAccentable()
                    Text(open == 0 ? "Free day" : "\(open) pending").font(.system(size: 15, weight: .semibold))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

@main
struct KairoWatchWidgets: WidgetBundle {
    var body: some Widget {
        TalkComplication()
        NextComplication()
    }
}
