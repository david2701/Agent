import SwiftUI
import KairoCore
import KairoUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            KairoColor.bg.ignoresSafeArea()
            switch model.phase {
            case .loading:
                KairoOrb(state: .idle).frame(width: 96).transition(.opacity)
            case .signedOut:
                LoginScreen().transition(.opacity.combined(with: .scale(scale: 0.98)))
            case .ready:
                MainView().transition(.opacity)
            }
        }
        .animation(KairoMotion.base, value: model.phase)
    }
}

/// iPhone and the outer screen of the iPhone Duo: tabs. iPad and the inner screen of the Duo: sidebar.
/// Decided by size class (not by device model), as Apple recommends for the Duo.
struct MainView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        @Bindable var model = model
        Group {
            if hSize == .regular {
                SplitLayout()
            } else {
                TabLayout()
            }
        }
        .overlay(alignment: .top) { ToastOverlay() }
        .fullScreenCover(isPresented: $model.showTalk) {
            TalkView().environment(model)
        }
    }
}

// MARK: - Tabs (compact)

private enum CompactTab: Hashable { case today, chat, tasks, calendar, more }

private struct TabLayout: View {
    @Environment(AppModel.self) private var model
    @State private var tab: CompactTab = .today
    @State private var morePath: [AppSection] = []

    var body: some View {
        TabView(selection: $tab) {
            Tab("Today", systemImage: AppSection.today.symbol, value: CompactTab.today) {
                NavigationStack { SectionScreen(section: .today) }
            }
            Tab("Chat", systemImage: AppSection.chat.symbol, value: CompactTab.chat) {
                NavigationStack { SectionScreen(section: .chat) }
            }
            Tab("Tasks", systemImage: AppSection.tasks.symbol, value: CompactTab.tasks) {
                NavigationStack { SectionScreen(section: .tasks) }
            }
            Tab("Calendar", systemImage: AppSection.calendar.symbol, value: CompactTab.calendar) {
                NavigationStack { SectionScreen(section: .calendar) }
            }
            Tab("More", systemImage: "square.grid.2x2", value: CompactTab.more) {
                NavigationStack(path: $morePath) {
                    MoreScreen()
                        .navigationDestination(for: AppSection.self) { SectionScreen(section: $0) }
                }
            }
            .badge(model.unreadNotices > 0 ? Text("\(min(model.unreadNotices, 99))") : nil)
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        // Chat already has a field to type and talk: the bar is not repeated there
        .tabViewBottomAccessory(isEnabled: tab != .chat) {
            TalkAccessory()
        }
        .onChange(of: tab) { _, t in
            // Keeps the current section when a tab is tapped (so shortcuts work again)
            let s: AppSection? = switch t {
            case .today: .today
            case .chat: .chat
            case .tasks: .tasks
            case .calendar: .calendar
            case .more: morePath.last
            }
            if let s, s != model.selection { model.selection = s }
        }
        .onChange(of: model.selection, initial: true) { _, s in
            switch s {
            case .today: tab = .today
            case .chat: tab = .chat
            case .tasks: tab = .tasks
            case .calendar: tab = .calendar
            default:
                tab = .more
                morePath = [s]
            }
        }
    }
}

/// "Ask Kairo" bar above the tabs (Liquid Glass).
private struct TalkAccessory: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Button {
            model.startTalking()
        } label: {
            HStack(spacing: 10) {
                KairoOrb(state: model.voice.orbState, level: model.voice.level).frame(width: 26, height: 26)
                label
                    .font(.kairo(15, .medium))
                    .foregroundStyle(KairoColor.text)
                    .lineLimit(1)
                Spacer()
                Image(systemName: "mic.fill").font(.system(size: 15, weight: .semibold)).foregroundStyle(KairoColor.accent)
            }
            .padding(.horizontal, 14)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Talk to Kairo")
    }

    private var label: Text {
        model.voice.phase == .idle ? Text("Ask Kairo") : Text(verbatim: model.voice.statusText)
    }
}

// MARK: - Sidebar (regular)

private struct SplitLayout: View {
    @Environment(AppModel.self) private var model
    @State private var visibility: NavigationSplitViewVisibility = .all

    var body: some View {
        @Bindable var model = model
        NavigationSplitView(columnVisibility: $visibility) {
            List(selection: Binding<AppSection?>(get: { model.selection }, set: { if let s = $0 { model.selection = s } })) {
                Section {
                    ForEach(AppSection.primary) { s in row(s) }
                }
                Section {
                    ForEach(AppSection.secondary) { s in row(s) }
                }
            }
            .navigationTitle("Kairo")
            .navigationSplitViewColumnWidth(min: 220, ideal: 250, max: 300)
            .safeAreaInset(edge: .bottom) {
                Button { model.startTalking() } label: {
                    HStack(spacing: 10) {
                        KairoOrb(state: model.voice.orbState, level: model.voice.level).frame(width: 28, height: 28)
                        Text("Talk to Kairo").font(.kairo(15, .semibold))
                        Spacer()
                        Text(verbatim: "⌘K").font(.kMonoSmall).foregroundStyle(KairoColor.faint)
                    }
                    .padding(.horizontal, 14)
                    .frame(height: 50)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .capsule)
                .padding(12)
                .keyboardShortcut("k", modifiers: .command)
            }
        } detail: {
            NavigationStack {
                SectionScreen(section: model.selection)
                    .id(model.selection)
            }
        }
        .navigationSplitViewStyle(.balanced)
    }

    @ViewBuilder private func row(_ s: AppSection) -> some View {
        Label(s.title, systemImage: s.symbol)
            .font(.kairo(15, .medium))
            .badge(s == .family && model.unreadNotices > 0 ? model.unreadNotices : 0)
            .tag(s)
    }
}

// MARK: - "More" (compact)

private struct MoreScreen: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            Section {
                ForEach(AppSection.secondary) { s in
                    NavigationLink(value: s) {
                        Label(s.title, systemImage: s.symbol).font(.kairo(16, .medium))
                    }
                    .badge(s == .family && model.unreadNotices > 0 ? model.unreadNotices : 0)
                }
            }
            if let u = model.user {
                Section {
                    HStack(spacing: 12) {
                        MemberAvatar(initials: String(u.displayName.prefix(1)).uppercased(), size: 38, highlighted: true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(u.displayName).font(.kHeadline)
                            Text(model.family?.household.name ?? u.email).font(.kFootnote).foregroundStyle(KairoColor.muted)
                        }
                    }
                }
            }
        }
        .navigationTitle("More")
        .scrollContentBackground(.hidden)
        .kairoBackground()
    }
}

/// Screen for each section.
struct SectionScreen: View {
    let section: AppSection

    var body: some View {
        switch section {
        case .today: TodayScreen()
        case .chat: ChatScreen()
        case .tasks: TasksScreen()
        case .calendar: CalendarScreen()
        case .notes: NotesScreen()
        case .reminders: RemindersScreen()
        case .lists: ListsScreen()
        case .memory: MemoryScreen()
        case .recordings: RecordingsScreen()
        case .activity: ActivityScreen()
        case .family: FamilyScreen()
        case .settings: SettingsScreen()
        }
    }
}

// MARK: - Brief notices

private struct ToastOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let t = model.toast {
            Button {
                if let d = t.destination { model.perform("section:\(d.rawValue)") }
                model.toast = nil
            } label: {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: t.symbol).font(.system(size: 16, weight: .semibold)).foregroundStyle(KairoColor.accent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.title).font(.kairo(15, .semibold)).foregroundStyle(KairoColor.text)
                        if !t.message.isEmpty { Text(t.message).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(2) }
                    }
                    Spacer(minLength: 0)
                }
                .padding(14)
                .frame(maxWidth: 520)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular, in: .rect(cornerRadius: 20))
            .padding(.horizontal, 16)
            .padding(.top, 6)
            .transition(.move(edge: .top).combined(with: .opacity))
            .sensoryFeedback(.success, trigger: t.id)
            .accessibilityAddTraits(.isStaticText)
        }
    }
}
