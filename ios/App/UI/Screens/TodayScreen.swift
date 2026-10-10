import SwiftUI
import KairoCore
import KairoUI

/// Lo que carga "Hoy": el día y los avisos de la familia sin leer.
struct TodayBundle: Equatable {
    var today: TodayData
    var notices: [Notice]
}

/// Hoy: saludo, voz, lo que sonó, la familia, la agenda y los pendientes.
/// En iPad y en la pantalla interior del iPhone Duo se divide en dos columnas.
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
                                .accessibilityLabel("Escuchar resumen del día")
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
        .navigationTitle("Hoy")
        .toolbar(hSize == .regular ? .visible : .hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { model.perform("briefing") } label: { Label("Escuchar resumen", systemImage: "play.circle") }
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

    // MARK: Contenido del día

    @ViewBuilder private func dayContent(_ b: TodayBundle, includeFamily: Bool) -> some View {
        let d = b.today
        VStack(alignment: .leading, spacing: 18) {
            if !d.firedReminders.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    BlockHeader(title: "Sonaron", count: d.firedReminders.count)
                    KairoCard {
                        ForEach(d.firedReminders) { r in
                            ReminderRow(reminder: r, highlight: true,
                                        onDone: { act("Hecho") { _ = try await model.api.reminderDone(r.id) } },
                                        onSnooze: { act("Te aviso en 10 minutos") { _ = try await model.api.reminderSnooze(r.id, minutes: 10) } })
                            if r.id != d.firedReminders.last?.id { Divider().overlay(KairoColor.border) }
                        }
                    }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            if includeFamily { FamilyCard(notices: b.notices) }

            VStack(alignment: .leading, spacing: 4) {
                BlockHeader(title: "Agenda", count: d.events.count, action: "Calendario") { model.perform("section:calendar") }
                if d.events.isEmpty {
                    QuietLine("Sin eventos hoy")
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
                BlockHeader(title: "Pendientes", count: tasks.count, action: "Tareas") { model.perform("section:tasks") }
                if tasks.isEmpty {
                    QuietLine("Nada pendiente para hoy")
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
                    BlockHeader(title: "Recordatorios de hoy", count: d.reminders.count, action: "Ver todos") { model.perform("section:reminders") }
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
                    BlockHeader(title: "Listas", action: "Abrir") { model.perform("section:lists") }
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
                    BlockHeader(title: "Notas recientes", action: "Notas") { model.perform("section:notes") }
                    KairoCard {
                        ForEach(d.recentNotes.prefix(4)) { n in
                            Button { model.perform("section:notes") } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: n.pinned ? "pin.fill" : "note.text").foregroundStyle(KairoColor.muted).frame(width: 24)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(n.title.isEmpty ? "Sin título" : n.title).font(.kBody).foregroundStyle(KairoColor.text).lineLimit(1)
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

    private func act(_ success: String, _ f: @escaping () async throws -> Void) {
        Task {
            await attempt(model, success, f)
            await reload()
        }
    }
}

extension KTask {
    /// La misma tarea marcada como hecha (para la animación antes de recargar).
    var completed: KTask {
        var t = self
        t.status = "done"
        return t
    }
}

// MARK: - Piezas

private struct QuietLine: View {
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.kSubheadline).foregroundStyle(KairoColor.muted).padding(.vertical, 6)
    }
}

private struct Greeting: View {
    var today: TodayData
    var name: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(today.dateLabel.isEmpty ? KairoDate.longDay(.now) : today.dateLabel)
                .font(.kairo(14, .medium))
                .foregroundStyle(KairoColor.muted)
            Text(name.isEmpty ? (today.greeting.isEmpty ? "Hola" : today.greeting) : "\(today.greeting.isEmpty ? "Hola" : today.greeting), \(name)")
                .font(.kLargeTitle)
                .foregroundStyle(KairoColor.text)
            if let w = today.weather {
                HStack(spacing: 6) {
                    Image(systemName: WeatherSymbol.symbol(for: w.description)).symbolRenderingMode(.multicolor)
                    Text("\(w.place) · \(Int(w.temperature.rounded()))° \(w.description)")
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
        if d.contains("torment") { return "cloud.bolt.rain.fill" }
        if d.contains("lluv") || d.contains("llovizna") || d.contains("chubasc") { return "cloud.rain.fill" }
        if d.contains("niev") { return "cloud.snow.fill" }
        if d.contains("niebla") { return "cloud.fog.fill" }
        if d.contains("nubl") || d.contains("cubierto") { return "cloud.fill" }
        if d.contains("nub") || d.contains("parcial") { return "cloud.sun.fill" }
        return "sun.max.fill"
    }
}

/// El orbe en línea: toca para hablar; la respuesta aparece debajo.
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
            .accessibilityLabel("Hablar con Kairo")
            Text(voice.statusText)
                .font(.kairo(15, .medium))
                .foregroundStyle(KairoColor.muted)
                .contentTransition(.opacity)
            if voice.phase == .idle, !voice.turn.text.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(voice.turn.actions.suffix(3)) { a in ActionCard(summary: a.summary, entityType: a.entity?.type, ok: a.ok) }
                    MarkdownText(voice.turn.text, font: .kCallout).lineLimit(6)
                    HStack {
                        Spacer()
                        Button("Seguir hablando") { model.showTalk = true }
                            .font(.kairo(14, .medium)).foregroundStyle(KairoColor.accentStrong)
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: 8) {
                TextField("Escribe a Kairo", text: $text)
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
                .accessibilityLabel(text.isEmpty ? "Hablar" : "Enviar")
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

/// Atajos para empezar ("¿Qué tengo hoy?").
struct SuggestionChips: View {
    var onPick: (String) -> Void
    private let items = ["¿Qué tengo hoy?", "Recuérdame algo", "Añade a la lista", "Cita compartida"]

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(items, id: \.self) { s in
                    Button(s) { onPick(s == "Cita compartida" ? "Crea una cita familiar" : s) }
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

/// Familia en Hoy: quiénes son y los últimos avisos sin leer.
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
                        Text(f.members.count > 1 ? "Todo al día en la familia" : "Invita a tu familia para compartir citas, listas y tareas")
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
            .accessibilityHint("Abre Familia")
        }
    }
}

/// Tarjeta de lista en Hoy (los primeros pendientes).
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
                    Text("\(list.pending.count)").font(.kMonoSmall).foregroundStyle(KairoColor.faint)
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
