import SwiftUI
import KairoCore
import KairoUI

struct RemindersBundle: Equatable {
    var reminders: [Reminder]
    var places: [Place]
}

/// Recordatorios: lo que sonó, lo próximo, por lugar y los que se repiten; y tus lugares.
struct RemindersScreen: View {
    @Environment(AppModel.self) private var model
    @State private var state: Loadable<RemindersBundle> = .loading
    @State private var editing: ReminderDraft?
    @State private var showDone = false
    @State private var savingPlace = false
    @State private var placeName = ""

    var body: some View {
        LoadableView(state: $state, version: model.version("reminder") + model.version("place") + model.version("family"), load: load) { b in
            let fired = b.reminders.filter { $0.status == "fired" }
            let scheduled = b.reminders.filter { $0.status == "scheduled" }
            let timed = scheduled.filter { $0.placeId == nil && ($0.rrule ?? "").isEmpty }
                .sorted { ($0.nextFireAt ?? "") < ($1.nextFireAt ?? "") }
            let byPlace = scheduled.filter { $0.placeId != nil }
            let repeating = scheduled.filter { $0.placeId == nil && !($0.rrule ?? "").isEmpty }
            let done = b.reminders.filter { $0.status == "done" || $0.status == "cancelled" }
            List {
                if b.reminders.isEmpty {
                    EmptyStateView(symbol: "bell", title: "Nada pendiente",
                                   message: "Dile a Kairo \"recuérdame llamar a mamá mañana a las 6\" o \"cuando llegue a casa, sacar la basura\".",
                                   actionTitle: "Nuevo recordatorio") { editing = ReminderDraft() }
                        .listRowBackground(Color.clear)
                }
                if !fired.isEmpty {
                    Section { ForEach(fired) { r in row(r, highlight: true) } } header: { SectionLabel("Sonaron", count: fired.count, tint: KairoColor.accentStrong) }
                }
                if !timed.isEmpty {
                    Section { ForEach(timed) { row($0) } } header: { SectionLabel("Próximos", count: timed.count) }
                }
                if !byPlace.isEmpty {
                    Section { ForEach(byPlace) { row($0) } } header: { SectionLabel("Por lugar", count: byPlace.count) }
                }
                if !repeating.isEmpty {
                    Section { ForEach(repeating) { row($0) } } header: { SectionLabel("Se repiten", count: repeating.count) }
                }
                if !done.isEmpty {
                    Section(isExpanded: $showDone) {
                        ForEach(done.prefix(30)) { r in
                            ReminderRow(reminder: r).opacity(0.6)
                                .listRowBackground(KairoColor.surface)
                                .swipeActions { Button(role: .destructive) { delete(r) } label: { Label("Eliminar", systemImage: "trash") } }
                        }
                    } header: { SectionLabel("Hechos", count: done.count) }
                }
                Section {
                    ForEach(b.places) { p in placeRow(p) }
                    if savingPlace {
                        HStack {
                            TextField("Nombre (Casa, Trabajo, Súper)", text: $placeName).submitLabel(.done).onSubmit { Task { await savePlace() } }
                            Button("Guardar") { Task { await savePlace() } }.disabled(placeName.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                        .listRowBackground(KairoColor.surface)
                        Text("Se usará tu ubicación actual.").font(.kFootnote).foregroundStyle(KairoColor.muted).listRowBackground(Color.clear)
                    } else {
                        Button { withAnimation(KairoMotion.base) { savingPlace = true } } label: {
                            Label("Guardar este lugar", systemImage: "location")
                        }
                        .listRowBackground(KairoColor.surface)
                    }
                } header: {
                    SectionLabel("Lugares", count: b.places.count)
                } footer: {
                    Text("Kairo te avisa al llegar o salir aunque la app esté cerrada. Para eso, permite la ubicación \"Siempre\".")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .refreshable { await reload() }
            .animation(KairoMotion.spring, value: b)
        }
        .navigationTitle("Recordatorios")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { editing = ReminderDraft() } label: { Label("Nuevo recordatorio", systemImage: "plus") }
                    .keyboardShortcut("n", modifiers: .command)
            }
        }
        .sheet(item: $editing) { d in ReminderEditor(draft: d, places: state.value?.places ?? []) { await reload() } }
        .kairoBackground()
    }

    private func load() async throws -> RemindersBundle {
        async let r = model.api.reminders(statuses: ["scheduled", "fired", "done", "cancelled"])
        async let p = try? model.api.places()
        return RemindersBundle(reminders: try await r, places: await p ?? [])
    }

    private func reload() async {
        if let b = try? await load() { withAnimation(KairoMotion.base) { state = .loaded(b) } }
    }

    private func row(_ r: Reminder, highlight: Bool = false) -> some View {
        ReminderRow(reminder: r, highlight: highlight,
                    onDone: highlight ? { act("Hecho") { _ = try await model.api.reminderDone(r.id) } } : nil,
                    onSnooze: highlight ? { act("Te aviso en 10 minutos") { _ = try await model.api.reminderSnooze(r.id, minutes: 10) } } : nil)
            .listRowBackground(KairoColor.surface)
            .contentShape(Rectangle())
            .onTapGesture { editing = ReminderDraft(r) }
            .swipeActions(edge: .leading) {
                Button { act("Hecho") { _ = try await model.api.reminderDone(r.id) } } label: { Label("Hecho", systemImage: "checkmark") }.tint(KairoColor.success)
            }
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) { delete(r) } label: { Label("Eliminar", systemImage: "trash") }
                Button { act("Te aviso en 10 minutos") { _ = try await model.api.reminderSnooze(r.id, minutes: 10) } } label: { Label("Posponer", systemImage: "zzz") }
                    .tint(KairoColor.accent)
            }
    }

    private func placeRow(_ p: Place) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "mappin.circle.fill").font(.system(size: 22)).foregroundStyle(p.shared ? KairoColor.accent : KairoColor.muted)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(p.name).font(.kBody)
                    if p.shared { SharedBadge(owner: model.ownerName(p.ownerId), compact: true) }
                }
                Text(p.address ?? "Radio \(p.radiusM) m").font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1)
            }
            Spacer()
        }
        .listRowBackground(KairoColor.surface)
        .contextMenu {
            if model.hasFamily, p.ownerId == nil || p.ownerId == model.user?.id {
                Button { act(p.shared ? "Ya no es familiar" : "Lugar familiar") { _ = try await model.api.sharePlace(p.id, shared: !p.shared) } } label: {
                    Label(p.shared ? "Dejar de compartir" : "Compartir con la familia", systemImage: "house")
                }
            }
            Button(role: .destructive) { act("Lugar eliminado") { try await model.api.deletePlace(p.id) } } label: { Label("Eliminar lugar", systemImage: "trash") }
        }
        .swipeActions { Button(role: .destructive) { act("Lugar eliminado") { try await model.api.deletePlace(p.id) } } label: { Label("Eliminar", systemImage: "trash") } }
    }

    private func savePlace() async {
        let name = placeName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        LocationService.shared.requestAlways()
        guard let loc = await LocationService.shared.current() else {
            model.show(Toast(title: "No se pudo obtener tu ubicación", message: "Revisa el permiso de ubicación en Ajustes.", symbol: "location.slash"))
            return
        }
        await attempt(model, "Lugar guardado: \(name)") {
            _ = try await model.api.savePlace(name: name, latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude)
        }
        placeName = ""
        withAnimation(KairoMotion.base) { savingPlace = false }
        await reload()
    }

    private func act(_ success: String, _ f: @escaping () async throws -> Void) {
        Task {
            await attempt(model, success, f)
            await reload()
        }
    }

    private func delete(_ r: Reminder) { act("Eliminado. Puedes deshacerlo en Actividad.") { try await model.api.deleteReminder(r.id) } }
}

// MARK: - Editor

struct ReminderDraft: Identifiable {
    enum Mode: Hashable { case time, place }
    enum Repeat: String, CaseIterable, Identifiable {
        case none, daily, weekdays, weekly, monthly
        var id: String { rawValue }
        var label: String {
            switch self {
            case .none: "No se repite"
            case .daily: "Cada día"
            case .weekdays: "Días hábiles"
            case .weekly: "Cada semana"
            case .monthly: "Cada mes"
            }
        }
    }

    var id = UUID()
    var original: Reminder?
    var text = ""
    var mode: Mode = .time
    var at = Calendar.current.date(byAdding: .hour, value: 1, to: .now)!
    var repeats: Repeat = .none
    var placeId: String?
    var trigger = "arrive"
    var forUserId: String?

    init() {}

    init(_ r: Reminder) {
        original = r
        text = r.text
        if let p = r.placeId { mode = .place; placeId = p; trigger = r.placeTrigger ?? "arrive" }
        if let d = KairoDate.parse(r.nextFireAt ?? r.dueAt) { at = d }
        let rule = r.rrule ?? ""
        repeats = rule.contains("DAILY") ? .daily : rule.contains("MO,TU,WE,TH,FR") ? .weekdays : rule.contains("WEEKLY") ? .weekly : rule.contains("MONTHLY") ? .monthly : .none
        forUserId = r.ownerId
    }

    func rrule() -> String? {
        let cal = Calendar.current
        let codes = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"]
        switch repeats {
        case .none: return nil
        case .daily: return "FREQ=DAILY"
        case .weekdays: return "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        case .weekly: return "FREQ=WEEKLY;BYDAY=\(codes[cal.component(.weekday, from: at) - 1])"
        case .monthly: return "FREQ=MONTHLY;BYMONTHDAY=\(cal.component(.day, from: at))"
        }
    }
}

struct ReminderEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var draft: ReminderDraft
    var places: [Place]
    var onSaved: () async -> Void
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Qué recordar", text: $draft.text, axis: .vertical).font(.kairo(18, .medium))
                    Picker("Cuándo", selection: $draft.mode.animation(KairoMotion.base)) {
                        Text("A una hora").tag(ReminderDraft.Mode.time)
                        Text("En un lugar").tag(ReminderDraft.Mode.place)
                    }
                    .pickerStyle(.segmented)
                }
                if draft.mode == .time {
                    Section {
                        DatePicker("Día y hora", selection: $draft.at, in: Date.now..., displayedComponents: [.date, .hourAndMinute])
                        HStack(spacing: 8) {
                            QuickTime("En 1 hora") { draft.at = .now.addingTimeInterval(3600) }
                            QuickTime("Esta noche") { draft.at = Calendar.current.date(bySettingHour: 20, minute: 0, second: 0, of: .now)! }
                            QuickTime("Mañana") { draft.at = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: .now.addingTimeInterval(86400))! }
                        }
                        Picker(selection: $draft.repeats) {
                            ForEach(ReminderDraft.Repeat.allCases) { Text($0.label).tag($0) }
                        } label: { Label("Repetir", systemImage: "repeat") }
                    }
                } else {
                    Section {
                        if places.isEmpty {
                            Text("Guarda un lugar primero en Recordatorios > Lugares.").font(.kFootnote).foregroundStyle(KairoColor.muted)
                        } else {
                            Picker(selection: $draft.placeId) {
                                Text("Elige un lugar").tag(String?.none)
                                ForEach(places) { p in Text(p.name).tag(Optional(p.id)) }
                            } label: { Label("Lugar", systemImage: "mappin.and.ellipse") }
                            Picker("Avisar", selection: $draft.trigger) {
                                Text("Al llegar").tag("arrive")
                                Text("Al salir").tag("leave")
                            }
                            .pickerStyle(.segmented)
                        }
                    }
                }
                if model.hasFamily, draft.original == nil {
                    Section {
                        MemberPicker(title: "Para", noneLabel: "Para mí", memberId: $draft.forUserId)
                    } footer: {
                        if let id = draft.forUserId, id != model.user?.id, let m = model.family?.member(id) {
                            Text("Suena en el teléfono de \(m.firstName) y le llega un aviso.")
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle(draft.original == nil ? "Nuevo recordatorio" : "Recordatorio")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(draft.original == nil ? "Crear" : "Guardar") { Task { await save() } }
                        .disabled(busy || draft.text.trimmingCharacters(in: .whitespaces).isEmpty || (draft.mode == .place && draft.placeId == nil))
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func save() async {
        busy = true
        defer { busy = false }
        var b: JSONObject = ["text": .string(draft.text.trimmingCharacters(in: .whitespacesAndNewlines))]
        if draft.mode == .time {
            b["dueAt"] = .string(KairoDate.iso(draft.at))
            b["rrule"] = .stringOrNull(draft.rrule())
            b["placeId"] = .null
        } else {
            b["placeId"] = .stringOrNull(draft.placeId)
            b["placeTrigger"] = .string(draft.trigger)
            b["dueAt"] = .null
        }
        let target = draft.forUserId.flatMap { $0 == model.user?.id ? nil : $0 }
        do {
            if let r = draft.original {
                _ = try await model.api.updateReminder(r.id, b)
                model.show(Toast(title: "Recordatorio guardado", symbol: "bell"))
            } else {
                if let target { b["forUserId"] = .string(target) }
                _ = try await model.api.createReminder(b.filter { !$0.value.isNull })
                let who = target.flatMap { model.family?.member($0)?.firstName }
                model.show(Toast(title: who.map { "Recordatorio programado para \($0)" } ?? "Recordatorio creado", symbol: "bell"))
            }
            await onSaved()
            dismiss()
        } catch {
            model.show(Toast(title: "No se pudo guardar", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}

private struct QuickTime: View {
    var title: String
    var action: () -> Void
    init(_ title: String, action: @escaping () -> Void) { self.title = title; self.action = action }

    var body: some View {
        Button(title, action: action)
            .font(.kairo(13, .medium))
            .foregroundStyle(KairoColor.text)
            .padding(.horizontal, 10)
            .frame(height: 30)
            .background(KairoColor.surface2, in: Capsule())
            .buttonStyle(.borderless)
    }
}
