import SwiftUI
import KairoCore
import KairoUI

/// Calendar: month with dots per day and the agenda of the chosen day.
/// On iPad and on the inner screen of the iPhone Duo, the month and the agenda go side by side.
struct CalendarScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var month = Calendar.current.dateInterval(of: .month, for: .now)!.start
    @State private var selected = Calendar.current.startOfDay(for: .now)
    @State private var state: Loadable<[Event]> = .loading
    @State private var editing: EventDraft?
    @State private var direction: Edge = .trailing

    /// The system calendar (week start, time zone) with the app language for names.
    private let cal: Calendar = {
        var c = Calendar.current
        c.locale = KairoLanguage.locale
        return c
    }()

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
        .navigationTitle("Calendar")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { editing = EventDraft(day: selected) } label: { Label("New event", systemImage: "plus") }
                    .keyboardShortcut("n", modifiers: .command)
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("Today") { goTo(.now) }
            }
        }
        .sheet(item: $editing) { d in EventEditor(draft: d) { await reload() } }
        .kairoBackground()
    }

    /// Changes when the month moves, to reload that range.
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

    // MARK: Month

    private func monthCard(_ byDay: [Date: [Event]]) -> some View {
        VStack(spacing: 12) {
            HStack {
                Text(verbatim: KairoDate.monthTitle(month, calendar: cal)).font(.kTitle3).contentTransition(.numericText())
                Spacer()
                Button { shiftMonth(-1) } label: { Image(systemName: "chevron.left").frame(width: 36, height: 36) }
                    .accessibilityLabel("Previous month")
                Button { shiftMonth(1) } label: { Image(systemName: "chevron.right").frame(width: 36, height: 36) }
                    .accessibilityLabel("Next month")
            }
            .buttonStyle(.plain)
            .foregroundStyle(KairoColor.text)
            let symbols = KairoDate.weekdayInitials(calendar: cal)
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

    /// Days of the month with gaps at the start (the week starts on the calendar's first weekday).
    private func gridDays() -> [Date?] {
        let range = cal.range(of: .day, in: .month, for: month)!
        let weekday = cal.component(.weekday, from: month) // 1 = Sunday
        let lead = (weekday - cal.firstWeekday + 7) % 7
        var days: [Date?] = Array(repeating: nil, count: lead)
        for d in range { days.append(cal.date(byAdding: .day, value: d - 1, to: month)) }
        while days.count % 7 != 0 { days.append(nil) }
        return days
    }

    // MARK: Day

    private func dayHeader(_ count: Int) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(KairoDate.longDay(selected)).font(.kTitle3)
            if cal.isDateInToday(selected) { Text("Today").font(.kCaption).foregroundStyle(KairoColor.accentStrong) }
            Spacer()
            if count > 0 { Text(verbatim: eventCountText(count)).font(.kFootnote).foregroundStyle(KairoColor.muted) }
        }
    }

    private func dayList(_ events: [Event]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            dayHeader(events.count)
            if events.isEmpty {
                EmptyStateView(symbol: "sun.max", title: "Free day", message: "Tell Kairo \"schedule the dentist on Thursday at 10\".",
                               actionTitle: "New event") { editing = EventDraft(day: selected) }
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
                    EmptyStateView(symbol: "sun.max", title: "Free day", message: "Tell Kairo \"schedule the dentist on Thursday at 10\".",
                                   actionTitle: "New event") { editing = EventDraft(day: selected) }
                } else {
                    DayTimeline(events: events) { editing = EventDraft($0) }
                }
            }
            .padding(24)
        }
        .id(selected)
    }

    @ViewBuilder private func eventMenu(_ e: Event) -> some View {
        Button { editing = EventDraft(e) } label: { Label("Edit", systemImage: "pencil") }
        Button(role: .destructive) {
            Task {
                await attempt(model, "Event deleted. You can undo it in Activity.") { try await model.api.deleteEvent(e.id) }
                await reload()
            }
        } label: { Label("Delete", systemImage: "trash") }
    }

    private func eventCountText(_ n: Int) -> String {
        n == 1 ? String(localized: "1 event") : String(localized: "\(n) events")
    }
}

/// Month cell: number and up to three dots (amber if shared with the family).
private struct DayCell: View {
    var day: Date
    var selected: Bool
    var today: Bool
    var events: [Event]
    var action: () -> Void

    private var accessibilityText: String {
        let long = KairoDate.longDay(day)
        guard !events.isEmpty else { return long }
        let count = events.count == 1 ? String(localized: "1 event") : String(localized: "\(events.count) events")
        return "\(long), \(count)"
    }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Text(verbatim: "\(Calendar.current.component(.day, from: day))")
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
        .accessibilityLabel(accessibilityText)
    }
}

/// Hourly agenda (iPad): blocks proportional to the duration.
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
                            Text(verbatim: hourLabel(h)).font(.kMonoSmall).foregroundStyle(KairoColor.faint).lineLimit(1).minimumScaleFactor(0.6).frame(width: 44, alignment: .leading)
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

    /// "9 AM" or "09", following the locale's clock style.
    private func hourLabel(_ h: Int) -> String {
        let d = Calendar.current.date(bySettingHour: h, minute: 0, second: 0, of: .now) ?? .now
        return d.formatted(.dateTime.hour().locale(KairoLanguage.locale))
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
                    TextField("Title", text: $draft.title).font(.kairo(18, .medium))
                    TextField("Place", text: $draft.location)
                }
                if !draft.isRecurring {
                    Section {
                        Toggle("All day", isOn: $draft.allDay.animation(KairoMotion.base)).tint(KairoColor.accent)
                        DatePicker("Starts", selection: $draft.start, displayedComponents: draft.allDay ? .date : [.date, .hourAndMinute])
                            .onChange(of: draft.start) { old, new in
                                if draft.end <= new { draft.end = new.addingTimeInterval(max(1800, draft.end.timeIntervalSince(old))) }
                            }
                        if !draft.allDay {
                            DatePicker("Ends", selection: $draft.end, in: draft.start..., displayedComponents: [.date, .hourAndMinute])
                        }
                        Picker("Alert", selection: $draft.reminder) {
                            Text("No alert").tag(0)
                            Text("5 min before").tag(5)
                            Text("15 min before").tag(15)
                            Text("30 min before").tag(30)
                            Text("1 hour before").tag(60)
                            Text("1 day before").tag(1440)
                        }
                    }
                } else {
                    Section {
                        Label("This is a recurring event: here you can change the title, the place and whether it is shared with the family.", systemImage: "repeat")
                            .font(.kFootnote).foregroundStyle(KairoColor.muted)
                    }
                }
                Section {
                    TextField("Notes", text: $draft.description, axis: .vertical).lineLimit(2...6)
                }
                if model.hasFamily {
                    Section {
                        FamilyShareToggle(shared: $draft.shared, ownerId: draft.original?.ownerId)
                    } footer: {
                        if draft.shared { Text("It appears on everyone's calendar and notifies them if you move or cancel it.") }
                    }
                }
                if let e = draft.original {
                    if e.source == "google" {
                        Section { Label("Synced with Google Calendar", systemImage: "arrow.triangle.2.circlepath").font(.kFootnote).foregroundStyle(KairoColor.muted) }
                    }
                    Section {
                        Button("Delete event", role: .destructive) {
                            Task {
                                await attempt(model, "Event deleted. You can undo it in Activity.") { try await model.api.deleteEvent(e.id) }
                                await onSaved()
                                dismiss()
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle(draft.original == nil ? (draft.shared ? "Family event" : "New event") : "Edit event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(draft.original == nil ? "Create" : "Save") { Task { await save() } }
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
            model.show(Toast(title: isNew ? (draft.shared ? String(localized: "Family event created") : String(localized: "Event created")) : String(localized: "Event saved"), symbol: "calendar"))
            await onSaved()
            dismiss()
        } catch {
            model.show(Toast(title: String(localized: "Couldn't save"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}
