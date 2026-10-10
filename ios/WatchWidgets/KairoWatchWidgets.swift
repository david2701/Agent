import WidgetKit
import SwiftUI
import KairoCore
import KairoUI

nonisolated struct WatchEntry: TimelineEntry { var date: Date }

nonisolated struct WatchProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchEntry { WatchEntry(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (WatchEntry) -> Void) { completion(WatchEntry(date: .now)) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchEntry>) -> Void) {
        completion(Timeline(entries: [WatchEntry(date: .now)], policy: .never))
    }
}

@main
struct KairoWatchWidgets: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "KairoWatchTalk", provider: WatchProvider()) { _ in
            Image(systemName: "mic.fill").containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Hablar con Kairo")
        .supportedFamilies([.accessoryCircular])
    }
}
