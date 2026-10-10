import SwiftUI
import KairoCore
import KairoUI

/// What "Today" loads: the day and the unread family notices.
struct TodayBundle: Equatable {
    var today: TodayData
    var notices: [Notice]
}

/// Today: greeting, voice, what rang, the family, the schedule and the to-dos.
/// On iPad and on the inner screen of the iPhone Duo it splits into two columns.
struct TodayScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var state: Loadable<TodayBundle> = .loading
    @State private var completing: Set<String> = []

    private var version: Int {
        ["event", "task", "reminder", "note", "list", "notice", "family"].reduce(0) { $0 + model.version($1) }
    }

    var body: some View {
        LoadableView(state: $state, version: version, skeleton: 6, load: load) { b in
            GeometryReader { geo in
                if hSize == .regular && geo.size.width > 760 {
                    HStack(alignment: .top, spacing: 0) {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 20) {
                                Greeting(today: b.today, name: model.user?.firstName ?? "")
                                VoiceCard(orbSize: 200)
                                FamilyCard(notices: b.notices)
                            }
                            .padding(24)
                        }
                        .frame(width: min(geo.size.width * 0.42, 440))
                        Divider().overlay(KairoColor.border)
                        ScrollView { dayContent(b, includeFamily: false).padding(24) }
                    }
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 18) {
                            HStack(alignment: .top) {
                                Greeting(today: b.today, name: model.user?.firstName ?? "")
                                Button { model.perform("briefing") } label: {
                                    Image(systemName: "play.fill").font(.system(size: 14, weight: .semibold)).frame(width: 40, height: 40)
                                }
                                .buttonStyle(.plain)
                                .glassEffect(.regular.interactive(), in: .circle)
                                .padding(.top, 14)
                                .accessibilityLabel("Listen to today's briefing")
                            }
                            VoiceCard(orbSize: 132)
                            dayContent(b, includeFamily: true)
                        }
                        .padding(.horizontal, 20)
                        .padding(.bottom, 24)
                        .readableWidth()
                    }
                }
            }
            .refreshable { await reload() }
        }
        .navigationTitle("Today")
        .toolbar(hSize == .regular ? .visible : .hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { model.perform("briefing") } label: { Label("Listen to briefing", systemImage: "play.circle") }
            }
        }
        .kairoBackground()
    }

    private func load() async throws -> TodayBundle {
        async let t = model.api.today()
        async let n = try? model.api.notices(unread: true, limit: 3)
        return TodayBundle(today: try await t, notices: await n?.notices ?? [])
    }

    private func reload() async {
        if let b = try? await load() { withAnimation(KairoMotion.base) { state = .loaded(b) } }
    }

    // MARK: Day content

    @ViewBuilder private func dayContent(_ b: TodayBundle, includeFamily: Bool) -> some View {
        let d = b.today
        VStack(alignment: .leading, spacing: 18) {
            if !d.firedReminders.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    BlockHeader(title: "Went off", count: d.firedReminders.count)
                    KairoCard {
                        ForEach(d.firedReminders) { r in
                            ReminderRow(reminder: r, highlight: true,
                                        onDone: { act("Done") { _ = try await model.api.reminderDone(r.id) } },
                                        onSnooze: { act("I'll remind you in 10 minutes") { _ = try await model.api.reminderSnooze(r.id, minutes: 10) } })
                            if r.id != d.firedReminders.last?.id { Divider().overlay(KairoColor.border) }
                        }
                    }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            if includeFamily { FamilyCard(notices: b.notices) }

            VStack(alignment: .leading, spacing: 4) {
                BlockHeader(title: "Schedule", count: d.events.count, action: "Calendar") { model.perform("section:calendar") }
                if d.events.isEmpty {
                    QuietLine("No events today")
                } else {
                    KairoCard {
                        ForEach(d.events, id: \.occurrenceId) { e in
                            EventRow(event: e)
                            if e.occurrenceId != d.events.last?.occurrenceId { Divider().overlay(KairoColor.border) }
                        }
                    }
                }
            }

            let tasks = pendingTasks(d)
            VStack(alignment: .leading, spacing: 4) {
                BlockHeader(title: "Pending", count: tasks.count, action: "Tasks") { model.perform("section:tasks") }
                if tasks.isEmpty {
                    QuietLine("Nothing to do today")
                } else {
                    KairoCard {
                        ForEach(tasks) { t in
                            TaskRow(task: completing.contains(t.id) ? t.completed : t, overdue: d.tasksOverdue.contains { $0.id == t.id }) { complete(t) }
                                .padding(.leading, -10)
                                .transition(.opacity.combined(with: .move(edge: .leading)))
                            if t.id != tasks.last?.id { Divider().overlay(KairoColor.border) }
                        }
                    }
                    .animation(KairoMotion.spring, value: tasks.map(\.id))
                }
            }

            if !d.reminders.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    BlockHeader(title: "Today's reminders", count: d.reminders.count, action: "See all") { model.perform("section:reminders") }
                    KairoCard {
                        ForEach(d.reminders) { r in
                            ReminderRow(reminder: r)
                            if r.id != d.reminders.last?.id { Divider().overlay(KairoColor.border) }
                        }
                    }
                }
            }

            let lists = d.lists.filter { !$0.pending.isEmpty }
            if !lists.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    BlockHeader(title: "Lists", action: "Open") { model.perform("section:lists") }
                    ScrollView(.horizontal) {
                        HStack(spacing: 10) {
                            ForEach(lists) { l in ListChipCard(list: l) }
                        }
                        .scrollTargetLayout()
                    }
                    .scrollTargetBehavior(.viewAligned)
                    .scrollIndicators(.hidden)
                }
            }

            if !d.recentNotes.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    BlockHeader(title: "Recent notes", action: "Notes") { model.perform("section:notes") }
                    KairoCard {
                        ForEach(d.recentNotes.prefix(4)) { n in
                            Button { model.perform("section:notes") } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: n.pinned ? "pin.fill" : "note.text").foregroundStyle(KairoColor.muted).frame(width: 24)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(n.title.isEmpty ? String(localized: "Untitled") : n.title).font(.kBody).foregroundStyle(KairoColor.text).lineLimit(1)
                                        Text(n.content.plainPreview).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                    if n.shared { SharedBadge(owner: model.ownerName(n.ownerId), compact: true) }
                                }
                                .padding(.vertical, 4)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .animation(KairoMotion.base, value: d)
    }

    private func pendingTasks(_ d: TodayData) -> [KTask] {
        var seen = Set<String>()
        return (d.tasksOverdue + d.tasksDueToday + d.tasksInProgress).filter { seen.insert($0.id).inserted }
    }

    private func complete(_ t: KTask) {
        withAnimation(KairoMotion.spring) { _ = completing.insert(t.id) }
        Task {
            await attempt(model) { _ = try await model.api.updateTask(t.id, ["status": "done"]) }
            try? await Task.sleep(for: .milliseconds(450))
            await reload()
            completing.remove(t.id)
        }
    }

    private func act(_ success: LocalizedStringResource, _ f: @escaping () async throws -> Void) {
        Task {
            await attempt(model, success, f)
            await reload()
        }
    }
}

extension KTask {
    /// The same task marked as done (for the animation before reloading).
    var completed: KTask {
        var t = self
        t.status = "done"
        return t
    }
}

// MARK: - Pieces

private struct QuietLine: View {
    var text: LocalizedStringKey
    init(_ text: LocalizedStringKey) { self.text = text }
    var body: some View {
        Text(text).font(.kSubheadline).foregroundStyle(KairoColor.muted).padding(.vertical, 6)
    }
}

private struct Greeting: View {
    var today: TodayData
    var name: String

    private var greeting: String {
        let hello = today.greeting.isEmpty ? String(localized: "Hello") : today.greeting
        return name.isEmpty ? hello : "\(hello), \(name)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(today.dateLabel.isEmpty ? KairoDate.longDay(.now) : today.dateLabel)
                .font(.kairo(14, .medium))
                .foregroundStyle(KairoColor.muted)
            Text(verbatim: greeting)
                .font(.kLargeTitle)
                .foregroundStyle(KairoColor.text)
            if let w = today.weather {
                HStack(spacing: 6) {
                    Image(systemName: WeatherSymbol.symbol(for: w.description)).symbolRenderingMode(.multicolor)
                    Text(verbatim: "\(w.place) · \(Int(w.temperature.rounded()))° \(w.description)")
                }
                .font(.kairo(13, .medium))
                .foregroundStyle(KairoColor.muted)
                .padding(.horizontal, 10)
                .frame(height: 28)
                .background(KairoColor.surface, in: Capsule())
                .overlay(Capsule().strokeBorder(KairoColor.border))
                .padding(.top, 2)
            }
        }
        .padding(.top, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

enum WeatherSymbol {
    static func symbol(for description: String) -> String {
        let d = description.lowercased()
        // The server describes the weather in its configured language (English or Spanish): match both.
        if d.contains("thunder") || d.contains("storm") || d.contains("torment") { return "cloud.bolt.rain.fill" }
        if d.contains("rain") || d.contains("drizzle") || d.contains("shower") || d.contains("lluv") || d.contains("llovizna") || d.contains("chubasc") { return "cloud.rain.fill" }
        if d.contains("snow") || d.contains("niev") { return "cloud.snow.fill" }
        if d.contains("fog") || d.contains("mist") || d.contains("niebla") { return "cloud.fog.fill" }
        if d.contains("partly") || d.contains("mostly sunny") { return "cloud.sun.fill" }
        if d.contains("cloud") || d.contains("overcast") || d.contains("nubl") || d.contains("cubierto") { return "cloud.fill" }
        if d.contains("nub") || d.contains("parcial") { return "cloud.sun.fill" }
        return "sun.max.fill"
    }
}

/// The inline orb: tap to talk; the answer appears below.
private struct VoiceCard: View {
    @Environment(AppModel.self) private var model
    var orbSize: CGFloat
    @State private var text = ""
    @FocusState private var focused: Bool

    private var voice: VoiceEngine { model.voice }

    var body: some View {
        VStack(spacing: 12) {
            Button { model.startTalking() } label: {
                KairoOrb(state: voice.orbState, level: voice.level).frame(width: orbSize, height: orbSize)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Talk to Kairo")
            Text(verbatim: voice.statusText)
                .font(.kairo(15, .medium))
                .foregroundStyle(KairoColor.muted)
                .contentTransition(.opacity)
            if voice.phase == .idle, !voice.turn.text.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(voice.turn.actions.suffix(3)) { a in ActionCard(summary: a.summary, entityType: a.entity?.type, ok: a.ok) }
                    MarkdownText(voice.turn.text, font: .kCallout).lineLimit(6)
                    HStack {
                        Spacer()
                        Button("Keep talking") { model.showTalk = true }
                            .font(.kairo(14, .medium)).foregroundStyle(KairoColor.accentStrong)
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: 8) {
                TextField("Type to Kairo", text: $text)
                    .font(.kCallout)
                    .focused($focused)
                    .submitLabel(.send)
                    .onSubmit(send)
                    .padding(.horizontal, 14)
                    .frame(height: 44)
                    .background(KairoColor.surface2, in: Capsule())
                Button(action: { text.isEmpty ? model.startTalking() : send() }) {
                    Image(systemName: text.isEmpty ? "mic.fill" : "arrow.up")
                        .font(.system(size: 16, weight: .bold))
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .foregroundStyle(KairoColor.onAccent)
                .background(KairoColor.accent, in: Circle())
                .accessibilityLabel(text.isEmpty ? "Talk" : "Send")
            }
            SuggestionChips { model.startTalking(initialText: $0) }
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(KairoColor.border))
        .animation(KairoMotion.base, value: voice.turn.text.isEmpty)
    }

    private func send() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        text = ""
        focused = false
        model.startTalking(initialText: t)
    }
}

/// Shortcuts to get started ("What's on today?").
struct SuggestionChips: View {
    var onPick: (String) -> Void
    // Each suggestion is also sent to the assistant, so it follows the app language.
    private let items = [String(localized: "What's on today?"), String(localized: "Remind me about something"),
                         String(localized: "Add to the list"), String(localized: "Shared event")]

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(items, id: \.self) { s in
                    Button(s) { onPick(s == String(localized: "Shared event") ? String(localized: "Create a family event") : s) }
                        .font(.kairo(13, .medium))
                        .foregroundStyle(KairoColor.text)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(KairoColor.surface2, in: Capsule())
                        .buttonStyle(.plain)
                }
            }
        }
        .scrollIndicators(.hidden)
    }
}

/// Family on Today: who they are and the latest unread notices.
private struct FamilyCard: View {
    @Environment(AppModel.self) private var model
    var notices: [Notice]

    var body: some View {
        if let f = model.family {
            Button { model.perform("section:family") } label: {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        Image(systemName: "house").font(.system(size: 15, weight: .semibold)).foregroundStyle(KairoColor.accent)
                        Text(f.household.name).font(.kHeadline).foregroundStyle(KairoColor.text)
                        Spacer()
                        HStack(spacing: -8) {
                            ForEach(f.members.prefix(5)) { m in
                                MemberAvatar(initials: m.initials, size: 28, highlighted: m.id == f.me)
                                    .overlay(Circle().strokeBorder(KairoColor.surface, lineWidth: 2))
                            }
                        }
                    }
                    if notices.isEmpty {
                        Text(f.members.count > 1 ? "All caught up with the family" : "Invite your family to share events, lists and tasks")
                            .font(.kSubheadline).foregroundStyle(KairoColor.muted)
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(notices.prefix(3)) { n in
                                HStack(alignment: .top, spacing: 10) {
                                    Image(systemName: KairoSymbol.notice(n.kind)).font(.system(size: 13)).foregroundStyle(KairoColor.muted).frame(width: 20)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(n.title).font(.kairo(14, .medium)).foregroundStyle(KairoColor.text).lineLimit(1)
                                        if !n.body.isEmpty { Text(n.body).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1) }
                                    }
                                    Spacer(minLength: 0)
                                    if let d = KairoDate.parse(n.createdAt) {
                                        Text(KairoDate.relative(d)).font(.kMonoSmall).foregroundStyle(KairoColor.faint)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(notices.isEmpty ? KairoColor.border : KairoColor.accent.opacity(0.45)))
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens Family")
        }
    }
}

/// List card on Today (the first pending items).
private struct ListChipCard: View {
    @Environment(AppModel.self) private var model
    var list: ShoppingList

    var body: some View {
        Button { model.perform("section:lists") } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Text(list.name).font(.kHeadline).foregroundStyle(KairoColor.text).lineLimit(1)
                    if list.shared { SharedBadge(compact: true) }
                    Spacer(minLength: 0)
                    Text(verbatim: "\(list.pending.count)").font(.kMonoSmall).foregroundStyle(KairoColor.faint)
                }
                ForEach(list.pending.prefix(3)) { i in
                    HStack(spacing: 8) {
                        Circle().strokeBorder(KairoColor.faint, lineWidth: 1.5).frame(width: 12, height: 12)
                        Text(i.text).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .frame(width: 210, height: 128, alignment: .topLeading)
            .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
        }
        .buttonStyle(.plain)
    }
}
