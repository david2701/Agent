import SwiftUI
import KairoCore
import KairoUI

/// Calendario: mes con puntos por día y la agenda del día elegido.
/// En iPad y en la pantalla interior del iPhone Duo, el mes y la agenda van lado a lado.
struct CalendarScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var month = Calendar.current.dateInterval(of: .month, for: .now)!.start
    @State private var selected = Calendar.current.startOfDay(for: .now)
    @State private var state: Loadable<[Event]> = .loading
    @State private var editing: EventDraft?
    @State private var direction: Edge = .trailing

    private let cal = Calendar.current

    var body: some View {
        LoadableView(state: $state, version: model.version("event") + model.version("family") + monthKey, load: load) { events in
            let byDay = Dictionary(grouping: events) { cal.startOfDay(for: $0.start ?? .distantPast) }
            if hSize == .regular {
                HStack(alignment: .top, spacing: 0) {
                    ScrollView {
                        monthCard(byDay).padding(20)
                    }
                    .frame(width: 420)
                    Divider().overlay(KairoColor.border)
                    dayAgenda(byDay[selected] ?? [])
                }
            } else {
                ScrollView {
                    VStack(spacing: 16) {
                        monthCard(byDay)
                        dayList(byDay[selected] ?? [])
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                    .readableWidth()
                }
                .refreshable { await reload() }
            }
        }
        .navigationTitle("Calendario")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { editing = EventDraft(day: selected) } label: { Label("Nuevo evento", systemImage: "plus") }
                    .keyboardShortcut("n", modifiers: .command)
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("Hoy") { goTo(.now) }
            }
        }
        .sheet(item: $editing) { d in EventEditor(draft: d) { await reload() } }
        .kairoBackground()
    }

    /// Cambia al mover de mes, para recargar ese rango.
    private var monthKey: Int { Int(month.timeIntervalSince1970 / 86400) }

    private func load() async throws -> [Event] {
        let start = cal.date(byAdding: .day, value: -7, to: month)!
        let end = cal.date(byAdding: .day, value: 45, to: month)!
        return try await model.api.events(from: start, to: end).sorted { $0.startAt < $1.startAt }
    }

    private func reload() async {
        if let e = try? await load() { withAnimation(KairoMotion.base) { state = .loaded(e) } }
    }

    private func goTo(_ date: Date) {
        let m = cal.dateInterval(of: .month, for: date)!.start
        direction = m > month ? .trailing : .leading
        withAnimation(KairoMotion.spring) {
            month = m
            selected = cal.startOfDay(for: date)
        }
    }

    private func shiftMonth(_ by: Int) {
        let m = cal.date(byAdding: .month, value: by, to: month)!
        direction = by > 0 ? .trailing : .leading
        withAnimation(KairoMotion.spring) {
            month = m
            selected = by == 0 ? selected : (cal.isDate(m, equalTo: .now, toGranularity: .month) ? cal.startOfDay(for: .now) : m)
        }
    }

    // MARK: Mes

    private func monthCard(_ byDay: [Date: [Event]]) -> some View {
        VStack(spacing: 12) {
            HStack {
                Text(monthTitle(month)).font(.kTitle3).contentTransition(.numericText())
                Spacer()
                Button { shiftMonth(-1) } label: { Image(systemName: "chevron.left").frame(width: 36, height: 36) }
                    .accessibilityLabel("Mes anterior")
                Button { shiftMonth(1) } label: { Image(systemName: "chevron.right").frame(width: 36, height: 36) }
                    .accessibilityLabel("Mes siguiente")
            }
            .buttonStyle(.plain)
            .foregroundStyle(KairoColor.text)
            let symbols = ["L", "M", "X", "J", "V", "S", "D"]
            HStack(spacing: 0) {
                ForEach(symbols.indices, id: \.self) { i in
                    Text(symbols[i]).font(.kCaption).foregroundStyle(KairoColor.faint).frame(maxWidth: .infinity)
                }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 7), spacing: 6) {
                ForEach(Array(gridDays().enumerated()), id: \.offset) { _, day in
                    if let day {
                        DayCell(day: day, selected: cal.isDate(day, inSameDayAs: selected), today: cal.isDateInToday(day),
                                events: byDay[day] ?? []) {
                            withAnimation(KairoMotion.snappy) { selected = day }
                        }
                    } else {
                        Color.clear.frame(height: 44)
                    }
                }
            }
            .id(month)
            .transition(.push(from: direction))
            .gesture(DragGesture(minimumDistance: 30).onEnded { v in
                if v.translation.width < -60 { shiftMonth(1) } else if v.translation.width > 60 { shiftMonth(-1) }
            })
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
        .clipped()
    }

    private func monthTitle(_ d: Date) -> String {
        let months = ["Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"]
        let c = cal.dateComponents([.month, .year], from: d)
        return "\(months[(c.month ?? 1) - 1]) \(c.year ?? 0)"
    }

    /// Días del mes con huecos al inicio (la semana empieza el lunes).
    private func gridDays() -> [Date?] {
        let range = cal.range(of: .day, in: .month, for: month)!
        let weekday = cal.component(.weekday, from: month) // 1 = domingo
        let lead = (weekday + 5) % 7
        var days: [Date?] = Array(repeating: nil, count: lead)
        for d in range { days.append(cal.date(byAdding: .day, value: d - 1, to: month)) }
        while days.count % 7 != 0 { days.append(nil) }
        return days
    }

    // MARK: Día

    private func dayHeader(_ count: Int) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(KairoDate.longDay(selected)).font(.kTitle3)
            if cal.isDateInToday(selected) { Text("Hoy").font(.kCaption).foregroundStyle(KairoColor.accentStrong) }
            Spacer()
            if count > 0 { Text(count == 1 ? "1 evento" : "\(count) eventos").font(.kFootnote).foregroundStyle(KairoColor.muted) }
        }
    }

    private func dayList(_ events: [Event]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            dayHeader(events.count)
            if events.isEmpty {
                EmptyStateView(symbol: "sun.max", title: "Día libre", message: "Dile a Kairo \"agenda el dentista el jueves a las 10\".",
                               actionTitle: "Nuevo evento") { editing = EventDraft(day: selected) }
            } else {
                KairoCard {
                    ForEach(events, id: \.occurrenceId) { e in
                        Button { editing = EventDraft(e) } label: { EventRow(event: e) }
                            .buttonStyle(.plain)
                            .contextMenu { eventMenu(e) }
                        if e.occurrenceId != events.last?.occurrenceId { Divider().overlay(KairoColor.border) }
                    }
                }
            }
        }
        .id(selected)
        .transition(.opacity)
    }

    private func dayAgenda(_ events: [Event]) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                dayHeader(events.count)
                if events.isEmpty {
                    EmptyStateView(symbol: "sun.max", title: "Día libre", message: "Dile a Kairo \"agenda el dentista el jueves a las 10\".",
                                   actionTitle: "Nuevo evento") { editing = EventDraft(day: selected) }
                } else {
                    DayTimeline(events: events) { editing = EventDraft($0) }
                }
            }
            .padding(24)
        }
        .id(selected)
    }

    @ViewBuilder private func eventMenu(_ e: Event) -> some View {
        Button { editing = EventDraft(e) } label: { Label("Editar", systemImage: "pencil") }
        Button(role: .destructive) {
            Task {
                await attempt(model, "Evento eliminado. Puedes deshacerlo en Actividad.") { try await model.api.deleteEvent(e.id) }
                await reload()
            }
        } label: { Label("Eliminar", systemImage: "trash") }
    }
}

/// Celda del mes: número y hasta tres puntos (ámbar si es familiar).
private struct DayCell: View {
    var day: Date
    var selected: Bool
    var today: Bool
    var events: [Event]
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Text("\(Calendar.current.component(.day, from: day))")
                    .font(.kairoMono(15, selected || today ? .medium : .regular))
                    .foregroundStyle(selected ? KairoColor.onAccent : (today ? KairoColor.accentStrong : KairoColor.text))
                    .frame(width: 34, height: 34)
                    .background {
                        if selected { Circle().fill(KairoColor.accent) }
                        else if today { Circle().strokeBorder(KairoColor.accent, lineWidth: 1.5) }
                    }
                HStack(spacing: 3) {
                    ForEach(Array(events.prefix(3).enumerated()), id: \.offset) { _, e in
                        Circle().fill(e.shared ? KairoColor.accent : KairoColor.muted).frame(width: 4, height: 4)
                    }
                }
                .frame(height: 4)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: selected) { _, new in new }
        .accessibilityLabel("\(KairoDate.longDay(day))\(events.isEmpty ? "" : ", \(events.count) eventos")")
    }
}

/// Agenda por horas (iPad): bloques proporcionales a la duración.
private struct DayTimeline: View {
    @Environment(AppModel.self) private var model
    var events: [Event]
    var onOpen: (Event) -> Void
    private let hourHeight: CGFloat = 56

    var body: some View {
        let allDay = events.filter(\.allDay)
        let timed = events.filter { !$0.allDay && $0.start != nil }
        let firstHour = max(0, min(8, timed.compactMap { $0.start.map { Calendar.current.component(.hour, from: $0) } }.min() ?? 8))
        VStack(alignment: .leading, spacing: 10) {
            ForEach(allDay, id: \.occurrenceId) { e in
                Button { onOpen(e) } label: { EventRow(event: e) }.buttonStyle(.plain)
            }
            ZStack(alignment: .topLeading) {
                VStack(spacing: 0) {
                    ForEach(firstHour..<24, id: \.self) { h in
                        HStack(alignment: .top, spacing: 8) {
                            Text(String(format: "%02d:00", h)).font(.kMonoSmall).foregroundStyle(KairoColor.faint).frame(width: 44, alignment: .leading)
                            Rectangle().fill(KairoColor.border).frame(height: 1).padding(.top, 7)
                        }
                        .frame(height: hourHeight, alignment: .top)
                    }
                }
                ForEach(timed, id: \.occurrenceId) { e in
                    let box = frame(e, from: firstHour)
                    Button { onOpen(e) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.title).font(.kairo(14, .semibold)).foregroundStyle(KairoColor.text).lineLimit(2)
                            Text([e.start.map { KairoDate.time($0) }, e.location.isEmpty ? nil : e.location].compactMap { $0 }.joined(separator: " · "))
                                .font(.kCaption).foregroundStyle(KairoColor.muted).lineLimit(1)
                        }
                        .padding(8)
                        .frame(maxWidth: .infinity, minHeight: box.1, maxHeight: box.1, alignment: .topLeading)
                        .background((e.shared ? KairoColor.accent : KairoColor.muted).opacity(0.16), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .overlay(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 2).fill(e.shared ? KairoColor.accent : KairoColor.muted).frame(width: 3)
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.leading, 52)
                    .offset(y: box.0 + 8)
                }
                if Calendar.current.isDateInToday(events.first?.start ?? .distantPast) {
                    TimelineView(.everyMinute) { ctx in
                        let mins = CGFloat(Calendar.current.component(.hour, from: ctx.date) - firstHour) * 60 + CGFloat(Calendar.current.component(.minute, from: ctx.date))
                        HStack(spacing: 0) {
                            Circle().fill(KairoColor.danger).frame(width: 8, height: 8)
                            Rectangle().fill(KairoColor.danger).frame(height: 1.5)
                        }
                        .padding(.leading, 48)
                        .offset(y: mins / 60 * hourHeight + 4)
                    }
                }
            }
        }
    }

    private func frame(_ e: Event, from firstHour: Int) -> (CGFloat, CGFloat) {
        let cal = Calendar.current
        guard let s = e.start else { return (0, hourHeight) }
        let startMin = CGFloat((cal.component(.hour, from: s) - firstHour) * 60 + cal.component(.minute, from: s))
        let dur = CGFloat(max(30, Int((e.end ?? s.addingTimeInterval(3600)).timeIntervalSince(s) / 60)))
        return (startMin / 60 * hourHeight, dur / 60 * hourHeight - 4)
    }
}

// MARK: - Editor

struct EventDraft: Identifiable {
    var id = UUID()
    var original: Event?
    var title = ""
    var location = ""
    var description = ""
    var allDay = false
    var start: Date
    var end: Date
    var shared = false
    var reminder = 15

    init(day: Date) {
        let cal = Calendar.current
        let base = cal.isDateInToday(day)
            ? cal.date(bySettingHour: min(cal.component(.hour, from: .now) + 1, 22), minute: 0, second: 0, of: day)!
            : cal.date(bySettingHour: 9, minute: 0, second: 0, of: day)!
        start = base
        end = base.addingTimeInterval(3600)
    }

    init(_ e: Event) {
        original = e
        title = e.title
        location = e.location
        description = e.description
        allDay = e.allDay
        start = e.start ?? .now
        end = e.end ?? (e.start ?? .now).addingTimeInterval(3600)
        shared = e.shared
        reminder = e.reminderMinutes.first ?? 15
    }

    var isRecurring: Bool { (original?.rrule ?? "").isEmpty == false }
}

struct EventEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var draft: EventDraft
    var onSaved: () async -> Void
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Título", text: $draft.title).font(.kairo(18, .medium))
                    TextField("Lugar", text: $draft.location)
                }
                if !draft.isRecurring {
                    Section {
                        Toggle("Todo el día", isOn: $draft.allDay.animation(KairoMotion.base)).tint(KairoColor.accent)
                        DatePicker("Empieza", selection: $draft.start, displayedComponents: draft.allDay ? .date : [.date, .hourAndMinute])
                            .onChange(of: draft.start) { old, new in
                                if draft.end <= new { draft.end = new.addingTimeInterval(max(1800, draft.end.timeIntervalSince(old))) }
                            }
                        if !draft.allDay {
                            DatePicker("Termina", selection: $draft.end, in: draft.start..., displayedComponents: [.date, .hourAndMinute])
                        }
                        Picker("Aviso", selection: $draft.reminder) {
                            Text("Sin aviso").tag(0)
                            Text("5 min antes").tag(5)
                            Text("15 min antes").tag(15)
                            Text("30 min antes").tag(30)
                            Text("1 hora antes").tag(60)
                            Text("1 día antes").tag(1440)
                        }
                    }
                } else {
                    Section {
                        Label("Es un evento que se repite: aquí puedes cambiar el título, el lugar y si es familiar.", systemImage: "repeat")
                            .font(.kFootnote).foregroundStyle(KairoColor.muted)
                    }
                }
                Section {
                    TextField("Notas", text: $draft.description, axis: .vertical).lineLimit(2...6)
                }
                if model.hasFamily {
                    Section {
                        FamilyShareToggle(shared: $draft.shared, ownerId: draft.original?.ownerId)
                    } footer: {
                        if draft.shared { Text("Aparece en el calendario de todos y les avisa si lo mueves o lo cancelas.") }
                    }
                }
                if let e = draft.original {
                    if e.source == "google" {
                        Section { Label("Sincronizado con Google Calendar", systemImage: "arrow.triangle.2.circlepath").font(.kFootnote).foregroundStyle(KairoColor.muted) }
                    }
                    Section {
                        Button("Eliminar evento", role: .destructive) {
                            Task {
                                await attempt(model, "Evento eliminado. Puedes deshacerlo en Actividad.") { try await model.api.deleteEvent(e.id) }
                                await onSaved()
                                dismiss()
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle(draft.original == nil ? (draft.shared ? "Evento familiar" : "Nuevo evento") : "Editar evento")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(draft.original == nil ? "Crear" : "Guardar") { Task { await save() } }
                        .disabled(busy || draft.title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.large])
    }

    private func save() async {
        busy = true
        defer { busy = false }
        var b: JSONObject = [
            "title": .string(draft.title.trimmingCharacters(in: .whitespacesAndNewlines)),
            "location": .string(draft.location),
            "description": .string(draft.description),
            "shared": .bool(draft.shared),
        ]
        if !draft.isRecurring {
            let cal = Calendar.current
            let start = draft.allDay ? cal.startOfDay(for: draft.start) : draft.start
            let end = draft.allDay ? cal.date(byAdding: .day, value: 1, to: start)! : draft.end
            b["startAt"] = .string(KairoDate.iso(start))
            b["endAt"] = .string(KairoDate.iso(end))
            b["allDay"] = .bool(draft.allDay)
            b["reminderMinutes"] = .array(draft.reminder > 0 ? [.number(Double(draft.reminder))] : [])
        }
        let isNew = draft.original == nil
        do {
            if let e = draft.original { _ = try await model.api.updateEvent(e.id, b) } else { _ = try await model.api.createEvent(b) }
            model.show(Toast(title: isNew ? (draft.shared ? "Evento familiar creado" : "Evento creado") : "Evento guardado", symbol: "calendar"))
            await onSaved()
            dismiss()
        } catch {
            model.show(Toast(title: "No se pudo guardar", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}
