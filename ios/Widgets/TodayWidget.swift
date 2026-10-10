import WidgetKit
import SwiftUI
import AppIntents
import KairoCore
import KairoUI

// MARK: - Today

/// Schedule and to-dos. Tasks are completed from the widget.
struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.today, provider: SnapshotProvider()) { entry in
            TodayWidgetView(entry: entry)
                .containerBackground(for: .widget) { WidgetBackdrop() }
        }
        .configurationDisplayName("Today")
        .description("Your schedule and your to-dos; complete tasks with a tap.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge, .accessoryRectangular, .accessoryCircular, .accessoryInline])
    }
}

/// "9:30 AM", or "Now" once the event has started.
private func startLabel(_ d: Date, now: Date) -> Text {
    d > now ? Text(verbatim: KairoDate.time(d)) : Text("Now")
}

struct WidgetBackdrop: View {
    @Environment(\.widgetRenderingMode) private var mode
    var body: some View {
        if mode == .fullColor { KairoColor.surface } else { Color.clear }
    }
}

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) private var family
    var entry: SnapshotEntry

    private var s: SharedSnapshot { entry.snapshot }
    private var upcoming: [SharedSnapshot.Item] { s.events.filter { ($0.date ?? .distantPast).addingTimeInterval(3600) > entry.date } }
    private var openTasks: [SharedSnapshot.Item] { s.tasks.filter { !$0.done } }

    var body: some View {
        if !entry.signedIn {
            SignInPrompt()
        } else {
            switch family {
            case .accessoryInline: inline
            case .accessoryCircular: circular
            case .accessoryRectangular: rectangular
            case .systemSmall: small
            case .systemMedium: medium
            default: large
            }
        }
    }

    // Lock screen

    @ViewBuilder private var inline: some View {
        if let e = upcoming.first, let d = e.date {
            Text(verbatim: "\(KairoDate.time(d)) \(e.title)")
        } else {
            Text(openTasks.isEmpty ? "Free day" : "\(openTasks.count) pending")
        }
    }

    private var circular: some View {
        ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 0) {
                Text(verbatim: "\(openTasks.count)").font(.system(size: 22, weight: .semibold, design: .rounded))
                Image(systemName: "checkmark.circle").font(.system(size: 10, weight: .semibold))
            }
        }
        .widgetURL(URL(string: "kairo://section/tasks"))
        .accessibilityLabel("\(openTasks.count) pending tasks")
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 1) {
            if let e = upcoming.first, let d = e.date {
                startLabel(d, now: entry.date).font(.system(size: 12, weight: .semibold)).widgetAccentable()
                Text(e.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                Text(e.subtitle).font(.system(size: 12)).lineLimit(1).foregroundStyle(.secondary)
            } else {
                Text("Today").font(.system(size: 12, weight: .semibold)).widgetAccentable()
                Text(openTasks.first?.title ?? String(localized: "Free day")).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                Text(openTasks.count > 1 ? "and \(openTasks.count - 1) more" : " ").font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetURL(URL(string: "kairo://section/today"))
    }

    // Home screen

    private var small: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(verbatim: entry.date.formatted(.dateTime.weekday(.wide).locale(KairoLanguage.locale))).font(.kairo(13, .semibold)).foregroundStyle(KairoColor.accentStrong)
                Spacer()
                if s.unreadNotices > 0 { Image(systemName: "house.fill").font(.system(size: 11)).foregroundStyle(KairoColor.accent) }
            }
            Text(verbatim: "\(Calendar.current.component(.day, from: entry.date))").font(.kairo(30, .semibold)).foregroundStyle(KairoColor.text)
            Spacer(minLength: 0)
            if let e = upcoming.first, let d = e.date {
                Text(e.title).font(.kairo(14, .semibold)).foregroundStyle(KairoColor.text).lineLimit(2)
                startLabel(d, now: entry.date).font(.kairoMono(12)).foregroundStyle(KairoColor.muted)
            } else {
                Text(openTasks.isEmpty ? "Free day" : "\(openTasks.count) pending").font(.kairo(14, .semibold)).foregroundStyle(KairoColor.text)
                if let t = openTasks.first { Text(t.title).font(.kairo(12)).foregroundStyle(KairoColor.muted).lineLimit(1) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(URL(string: "kairo://section/today"))
    }

    private var medium: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                header
                if upcoming.isEmpty {
                    Text("No events").font(.kairo(13)).foregroundStyle(KairoColor.muted)
                }
                ForEach(upcoming.prefix(2)) { EventLine(item: $0, now: entry.date) }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .leading, spacing: 4) {
                Text("Pending").font(.kairo(12, .semibold)).foregroundStyle(KairoColor.muted)
                if openTasks.isEmpty { Text("Nothing pending").font(.kairo(13)).foregroundStyle(KairoColor.muted) }
                ForEach(openTasks.prefix(3)) { TaskLine(item: $0) }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var large: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            if let w = s.weather { Text(w).font(.kairo(12)).foregroundStyle(KairoColor.muted) }
            Divider().overlay(KairoColor.border)
            Text("Schedule").font(.kairo(12, .semibold)).foregroundStyle(KairoColor.muted)
            if upcoming.isEmpty { Text("No events").font(.kairo(13)).foregroundStyle(KairoColor.muted) }
            ForEach(upcoming.prefix(family == .systemExtraLarge ? 6 : 3)) { EventLine(item: $0, now: entry.date) }
            Text("Pending").font(.kairo(12, .semibold)).foregroundStyle(KairoColor.muted).padding(.top, 4)
            if openTasks.isEmpty { Text("Nothing pending").font(.kairo(13)).foregroundStyle(KairoColor.muted) }
            ForEach(openTasks.prefix(family == .systemExtraLarge ? 7 : 4)) { TaskLine(item: $0) }
            if let r = s.reminders.first {
                Label(reminderLine(r), systemImage: "bell").font(.kairo(12)).foregroundStyle(KairoColor.muted).lineLimit(1).padding(.top, 2)
            }
            Spacer(minLength: 0)
        }
    }

    private func reminderLine(_ r: SharedSnapshot.Item) -> String { "\(r.subtitle) · \(r.title)" }

    private var header: some View {
        HStack {
            Text(s.dateLabel.isEmpty ? KairoDate.longDay(entry.date) : s.dateLabel).font(.kairo(13, .semibold)).foregroundStyle(KairoColor.accentStrong).lineLimit(1)
            Spacer()
            Link(destination: URL(string: "kairo://talk")!) {
                Image(systemName: "mic.fill").font(.system(size: 12, weight: .bold)).foregroundStyle(KairoColor.onAccent)
                    .frame(width: 26, height: 26).background(KairoColor.accent, in: Circle())
            }
            .accessibilityLabel("Talk to Kairo")
        }
    }
}

struct EventLine: View {
    var item: SharedSnapshot.Item
    var now: Date

    var body: some View {
        Link(destination: URL(string: "kairo://section/calendar")!) {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 1.5).fill(item.shared ? KairoColor.accent : KairoColor.faint).frame(width: 3, height: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(item.title).font(.kairo(13, .semibold)).foregroundStyle(KairoColor.text).lineLimit(1)
                    if let d = item.date, d > now, d.timeIntervalSince(now) < 3 * 3600 {
                        Text(d, style: .relative).font(.kairoMono(11)).foregroundStyle(KairoColor.muted)
                    } else {
                        Text(item.subtitle).font(.kairo(11)).foregroundStyle(KairoColor.muted).lineLimit(1)
                    }
                }
            }
        }
    }
}

/// Task with its complete button (interactive, without opening the app).
struct TaskLine: View {
    var item: SharedSnapshot.Item

    var body: some View {
        HStack(spacing: 6) {
            Button(intent: CompleteTaskIntent(taskId: item.id)) {
                Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 15))
                    .foregroundStyle(item.done ? KairoColor.accent : KairoColor.faint)
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.plain)
            Text(item.title).font(.kairo(13)).foregroundStyle(item.done ? KairoColor.muted : KairoColor.text)
                .strikethrough(item.done).lineLimit(1)
            if item.shared { Image(systemName: "house").font(.system(size: 9)).foregroundStyle(KairoColor.muted) }
        }
        .invalidatableContent()
    }
}

struct SignInPrompt: View {
    var body: some View {
        VStack(spacing: 6) {
            KairoOrb(state: .idle).frame(width: 36, height: 36)
            Text("Open Kairo to get started").font(.kairo(12, .medium)).multilineTextAlignment(.center).foregroundStyle(KairoColor.muted)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Next event

struct NextEventWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.next, provider: SnapshotProvider()) { entry in
            NextEventView(entry: entry).containerBackground(for: .widget) { WidgetBackdrop() }
        }
        .configurationDisplayName("Next")
        .description("Your next event with a countdown.")
        .supportedFamilies([.systemSmall, .accessoryRectangular])
    }
}

struct NextEventView: View {
    @Environment(\.widgetFamily) private var family
    var entry: SnapshotEntry

    var body: some View {
        let next = entry.snapshot.nextEvent(after: entry.date)
        Group {
            if let e = next, let d = e.date {
                VStack(alignment: .leading, spacing: 4) {
                    Label(e.shared ? "Family event" : "Next", systemImage: e.shared ? "house" : "calendar")
                        .font(family == .systemSmall ? .kairo(12, .semibold) : .system(size: 12, weight: .semibold))
                        .foregroundStyle(family == .systemSmall ? KairoColor.accentStrong : .primary)
                        .widgetAccentable()
                    Text(e.title).font(family == .systemSmall ? .kairo(16, .semibold) : .system(size: 14, weight: .semibold)).lineLimit(2)
                    if d > entry.date {
                        Text(d, style: .relative).font(family == .systemSmall ? .kairoMono(14, .medium) : .system(size: 12)).foregroundStyle(.secondary)
                    } else {
                        Text("Now").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    if family == .systemSmall { Spacer(minLength: 0); Text(e.subtitle).font(.kairo(11)).foregroundStyle(KairoColor.muted).lineLimit(2) }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Image(systemName: "sun.max").font(.system(size: 18)).widgetAccentable()
                    Text("Free day").font(.system(size: 14, weight: .semibold))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
        .widgetURL(URL(string: "kairo://section/calendar"))
    }
}

// MARK: - List

struct ListWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetKinds.list, intent: SelectListIntent.self, provider: ListProvider()) { entry in
            ListWidgetView(entry: entry).containerBackground(for: .widget) { WidgetBackdrop() }
        }
        .configurationDisplayName("List")
        .description("Check off what you've already bought without opening the app.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct ListWidgetView: View {
    @Environment(\.widgetFamily) private var family
    var entry: ListEntry

    var body: some View {
        if !entry.signedIn {
            SignInPrompt()
        } else if let l = entry.list {
            let limit = family == .systemLarge ? 10 : (family == .systemMedium ? 4 : 3)
            let items = (l.items.filter { !$0.done } + l.items.filter(\.done)).prefix(limit)
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text(l.name).font(.kairo(14, .semibold)).foregroundStyle(KairoColor.text).lineLimit(1)
                    if l.shared { Image(systemName: "house").font(.system(size: 10)).foregroundStyle(KairoColor.muted) }
                    Spacer()
                    Text(verbatim: "\(l.items.filter { !$0.done }.count)").font(.kairoMono(12)).foregroundStyle(KairoColor.faint)
                }
                if l.items.isEmpty { Text("Empty").font(.kairo(12)).foregroundStyle(KairoColor.muted) }
                ForEach(Array(items)) { i in
                    Button(intent: ToggleListItemIntent(listId: l.id, itemId: i.id, checked: !i.done)) {
                        HStack(spacing: 7) {
                            Image(systemName: i.done ? "checkmark.circle.fill" : "circle")
                                .font(.system(size: 15))
                                .foregroundStyle(i.done ? KairoColor.accent : KairoColor.faint)
                                .contentTransition(.symbolEffect(.replace))
                            Text(i.title).font(.kairo(13)).foregroundStyle(i.done ? KairoColor.muted : KairoColor.text).strikethrough(i.done).lineLimit(1)
                            Spacer(minLength: 0)
                        }
                    }
                    .buttonStyle(.plain)
                }
                Spacer(minLength: 0)
            }
            .widgetURL(URL(string: "kairo://section/lists"))
        } else {
            VStack(spacing: 6) {
                Image(systemName: "checklist").font(.system(size: 22)).foregroundStyle(KairoColor.faint)
                Text("No lists").font(.kairo(13, .medium)).foregroundStyle(KairoColor.muted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - Talk

struct TalkWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetKinds.talk, provider: SnapshotProvider()) { entry in
            TalkWidgetView(entry: entry).containerBackground(for: .widget) { WidgetBackdrop() }
        }
        .configurationDisplayName("Talk to Kairo")
        .description("One tap and Kairo listens.")
        .supportedFamilies([.systemSmall, .accessoryCircular])
    }
}

struct TalkWidgetView: View {
    @Environment(\.widgetFamily) private var family
    var entry: SnapshotEntry

    var body: some View {
        if family == .accessoryCircular {
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "mic.fill").font(.system(size: 20, weight: .semibold))
            }
            .widgetURL(URL(string: "kairo://talk"))
            .accessibilityLabel("Talk to Kairo")
        } else {
            VStack(alignment: .leading) {
                KairoOrb(state: .idle).frame(width: 56, height: 56)
                Spacer()
                Text("Ask Kairo").font(.kairo(15, .semibold)).foregroundStyle(KairoColor.text)
                Text(entry.snapshot.userName.isEmpty ? "Tap to talk" : "Hello, \(entry.snapshot.userName)").font(.kairo(12)).foregroundStyle(KairoColor.muted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .widgetURL(URL(string: "kairo://talk"))
        }
    }
}

// MARK: - Control Center and lock screen

struct TalkControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "KairoTalkControl") {
            ControlWidgetButton(action: TalkToKairoIntent()) {
                Label("Talk to Kairo", systemImage: "waveform.circle.fill")
            }
        }
        .displayName("Talk to Kairo")
        .description("Opens Kairo listening.")
    }
}

struct TodayControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "KairoTodayControl") {
            ControlWidgetButton(action: OpenKairoIntent(section: .today)) {
                Label("My day", systemImage: "sun.horizon")
            }
        }
        .displayName("My day in Kairo")
    }
}
