import SwiftUI
import KairoCore
import KairoUI

struct TasksBundle: Equatable {
    var tasks: [KTask]
    var projects: [Project]
}

/// Tasks: list by status on iPhone; three-column board on iPad and on an unfolded iPhone Duo.
struct TasksScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var state: Loadable<TasksBundle> = .loading
    @State private var filter: Filter = .all
    @State private var showDone = false
    @State private var editing: TaskDraft?
    @State private var pendingDone: Set<String> = []

    enum Filter: Hashable { case all, mine, family }

    var body: some View {
        LoadableView(state: $state, version: model.version("task") + model.version("project") + model.version("family"), load: load) { b in
            let tasks = filtered(b.tasks)
            VStack(spacing: 0) {
                if model.hasFamily {
                    OwnershipFilter<Filter>(options: [
                        (.all, "All", nil),
                        (.mine, "Assigned to me", b.tasks.filter { isAssignedToMe($0) && !$0.isDone }.count),
                        (.family, "Shared", b.tasks.filter { $0.shared && !$0.isDone }.count),
                    ], selection: $filter)
                    .padding(.vertical, 8)
                }
                if tasks.isEmpty {
                    EmptyStateView(symbol: "checkmark.circle", title: "Nothing here",
                                   message: "Tell Kairo \"remind me to pay the electricity bill on Friday\" or create a task.",
                                   actionTitle: "New task") { editing = TaskDraft() }
                        .frame(maxHeight: .infinity)
                } else if hSize == .regular {
                    board(tasks, projects: b.projects)
                } else {
                    list(tasks, projects: b.projects)
                }
            }
        }
        .navigationTitle("Tasks")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { editing = TaskDraft() } label: { Label("New task", systemImage: "plus") }
                    .keyboardShortcut("n", modifiers: .command)
            }
        }
        .sheet(item: $editing) { d in
            TaskEditor(draft: d, projects: state.value?.projects ?? []) { await reload() }
        }
        .kairoBackground()
    }

    private func load() async throws -> TasksBundle {
        async let t = model.api.tasks(includeDone: true)
        async let p = try? model.api.projects()
        return TasksBundle(tasks: try await t, projects: await p ?? [])
    }

    private func reload() async {
        if let b = try? await load() { withAnimation(KairoMotion.base) { state = .loaded(b) } }
    }

    private func isAssignedToMe(_ t: KTask) -> Bool {
        t.assigneeId == model.user?.id || (t.assigneeId == nil && t.ownerId == model.user?.id)
    }

    private func filtered(_ all: [KTask]) -> [KTask] {
        switch filter {
        case .all: all
        case .mine: all.filter(isAssignedToMe)
        case .family: all.filter(\.shared)
        }
    }

    private func overdue(_ t: KTask) -> Bool {
        guard !t.isDone, let d = t.dueDate.flatMap({ KairoDate.day($0) }) else { return false }
        return d < Calendar.current.startOfDay(for: .now)
    }

    private func sorted(_ list: [KTask]) -> [KTask] {
        list.sorted { a, b in
            let da = a.dueDate ?? "9999", db = b.dueDate ?? "9999"
            if da != db { return da < db }
            return rank(a.priority) > rank(b.priority)
        }
    }

    private func rank(_ p: String) -> Int { ["low": 0, "medium": 1, "high": 2, "urgent": 3][p] ?? 1 }

    // MARK: List (compact)

    private func list(_ tasks: [KTask], projects: [Project]) -> some View {
        let todo = sorted(tasks.filter { $0.status == "todo" || pendingDone.contains($0.id) })
        let doing = sorted(tasks.filter { $0.status == "doing" && !pendingDone.contains($0.id) })
        let done = tasks.filter { $0.isDone && !pendingDone.contains($0.id) }.sorted { ($0.completedAt ?? "") > ($1.completedAt ?? "") }
        return List {
            if !doing.isEmpty {
                Section { ForEach(doing) { row($0) } } header: { SectionLabel("In progress", count: doing.count) }
            }
            if !todo.isEmpty {
                Section { ForEach(todo) { row($0) } } header: { SectionLabel("To do", count: todo.count) }
            }
            if !done.isEmpty {
                Section(isExpanded: $showDone) {
                    ForEach(done.prefix(50)) { row($0) }
                } header: {
                    SectionLabel("Done", count: done.count)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .refreshable { await reload() }
        .animation(KairoMotion.spring, value: tasks)
    }

    private func row(_ t: KTask) -> some View {
        let shown = pendingDone.contains(t.id) ? t.completed : t
        return TaskRow(task: shown, overdue: overdue(t)) { toggle(t) }
            .listRowInsets(EdgeInsets(top: 2, leading: 6, bottom: 2, trailing: 12))
            .listRowBackground(KairoColor.surface)
            .contentShape(Rectangle())
            .onTapGesture { editing = TaskDraft(t) }
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                Button { toggle(t) } label: { Label(t.isDone ? "Reopen" : "Done", systemImage: t.isDone ? "arrow.uturn.backward" : "checkmark") }
                    .tint(KairoColor.success)
            }
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) { delete(t) } label: { Label("Delete", systemImage: "trash") }
                if t.status == "todo" {
                    Button { setStatus(t, "doing") } label: { Label("In progress", systemImage: "play") }.tint(KairoColor.accent)
                }
            }
            .contextMenu { menu(t) }
    }

    @ViewBuilder private func menu(_ t: KTask) -> some View {
        Button { editing = TaskDraft(t) } label: { Label("Edit task", systemImage: "pencil") }
        if t.status != "doing" && !t.isDone { Button { setStatus(t, "doing") } label: { Label("Move to in progress", systemImage: "play") } }
        Button { toggle(t) } label: { Label(t.isDone ? "Mark as to do" : "Mark as done", systemImage: t.isDone ? "arrow.uturn.backward" : "checkmark") }
        Divider()
        Button(role: .destructive) { delete(t) } label: { Label("Delete", systemImage: "trash") }
    }

    // MARK: Board (regular)

    private func board(_ tasks: [KTask], projects: [Project]) -> some View {
        let columns: [(String, LocalizedStringKey, [KTask])] = [
            ("todo", "To do", sorted(tasks.filter { $0.status == "todo" })),
            ("doing", "In progress", sorted(tasks.filter { $0.status == "doing" })),
            ("done", "Done", Array(tasks.filter(\.isDone).sorted { ($0.completedAt ?? "") > ($1.completedAt ?? "") }.prefix(40))),
        ]
        return ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(columns, id: \.0) { col in
                    VStack(alignment: .leading, spacing: 10) {
                        SectionLabel(col.1, count: col.2.count).padding(.horizontal, 4)
                        ScrollView {
                            LazyVStack(spacing: 8) {
                                ForEach(col.2) { t in
                                    BoardCard(task: t, overdue: overdue(t)) { toggle(t) }
                                        .onTapGesture { editing = TaskDraft(t) }
                                        .contextMenu { menu(t) }
                                        .draggable(t.id)
                                }
                            }
                            .padding(.bottom, 24)
                        }
                        .scrollIndicators(.hidden)
                    }
                    .padding(12)
                    .frame(width: 320)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .background(KairoColor.surface2.opacity(0.6), in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
                    .dropDestination(for: String.self) { ids, _ in
                        guard let id = ids.first, let t = tasks.first(where: { $0.id == id }), t.status != col.0 else { return false }
                        setStatus(t, col.0)
                        return true
                    }
                }
            }
            .padding(20)
        }
        .animation(KairoMotion.spring, value: tasks)
    }

    // MARK: Actions

    private func toggle(_ t: KTask) {
        if t.isDone {
            setStatus(t, "todo")
            return
        }
        withAnimation(KairoMotion.spring) { _ = pendingDone.insert(t.id) }
        Task {
            await attempt(model, "Task completed") { _ = try await model.api.updateTask(t.id, ["status": "done"]) }
            try? await Task.sleep(for: .milliseconds(500))
            await reload()
            pendingDone.remove(t.id)
        }
    }

    private func setStatus(_ t: KTask, _ s: String) {
        Task {
            await attempt(model) { _ = try await model.api.updateTask(t.id, ["status": .string(s)]) }
            await reload()
        }
    }

    private func delete(_ t: KTask) {
        Task {
            await attempt(model, "Task deleted. You can undo it in Activity.") { try await model.api.deleteTask(t.id) }
            await reload()
        }
    }
}

/// Board card.
private struct BoardCard: View {
    @Environment(AppModel.self) private var model
    var task: KTask
    var overdue: Bool
    var onToggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 4) {
                CheckCircle(done: task.isDone, action: onToggle).padding(.leading, -10).padding(.top, -10)
                Text(task.title).font(.kairo(15, .medium)).foregroundStyle(task.isDone ? KairoColor.muted : KairoColor.text)
                    .strikethrough(task.isDone, color: KairoColor.faint)
                    .frame(maxWidth: .infinity, alignment: .leading)
                PriorityFlag(priority: task.isDone ? "" : task.priority)
            }
            if !task.notes.isEmpty {
                Text(task.notes).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(3)
            }
            HStack(spacing: 6) {
                if let d = task.dueDate.flatMap({ KairoDate.day($0) }) {
                    Label(KairoDate.humanDay(d).capitalizedFirst, systemImage: "calendar")
                        .font(.kCaption).foregroundStyle(overdue ? KairoColor.danger : KairoColor.muted)
                }
                if let p = task.projectName { Text(p).font(.kCaption).foregroundStyle(KairoColor.muted).lineLimit(1) }
                Spacer(minLength: 0)
                if let a = task.assigneeId, let m = model.family?.member(a) { MemberAvatar(initials: m.initials, size: 22, highlighted: a == model.user?.id) }
                if task.shared { SharedBadge(compact: true) }
            }
        }
        .padding(12)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(KairoColor.border))
        .contentShape(Rectangle())
    }
}

// MARK: - Editor

struct TaskDraft: Identifiable {
    var id = UUID()
    var original: KTask?
    var title = ""
    var notes = ""
    var priority = "medium"
    var status = "todo"
    var hasDue = false
    var due = Date.now
    var projectId: String?
    var shared = false
    var assigneeId: String?

    init() {}

    init(_ t: KTask) {
        original = t
        title = t.title
        notes = t.notes
        priority = t.priority
        status = t.status
        if let d = t.dueDate.flatMap({ KairoDate.day($0) }) { hasDue = true; due = d }
        projectId = t.projectId
        shared = t.shared
        assigneeId = t.assigneeId
    }
}

struct TaskEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var draft: TaskDraft
    var projects: [Project]
    var onSaved: () async -> Void
    @State private var busy = false
    @FocusState private var titleFocused: Bool

    private var assignedToOther: Bool {
        if let a = draft.assigneeId, a != model.user?.id { return true }
        return false
    }

    /// Why the "Family" switch is locked, when the task is assigned to someone else.
    private var lockedReason: String? {
        guard assignedToOther else { return nil }
        let who = model.family?.member(draft.assigneeId)?.firstName ?? String(localized: "another member")
        return String(localized: "Assigning it to \(who) makes it a family task.")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("What needs to be done", text: $draft.title, axis: .vertical)
                        .font(.kairo(18, .medium))
                        .focused($titleFocused)
                    TextField("Notes", text: $draft.notes, axis: .vertical).lineLimit(2...6)
                }
                Section {
                    Toggle(isOn: $draft.hasDue.animation(KairoMotion.base)) { Label("Due date", systemImage: "calendar") }
                    if draft.hasDue {
                        DatePicker("Day", selection: $draft.due, displayedComponents: .date)
                            .datePickerStyle(.graphical)
                            .tint(KairoColor.accent)
                    }
                    Picker(selection: $draft.priority) {
                        Text("Low").tag("low")
                        Text("Medium").tag("medium")
                        Text("High").tag("high")
                        Text("Urgent").tag("urgent")
                    } label: { Label("Priority", systemImage: "flag") }
                    if !projects.isEmpty {
                        Picker(selection: $draft.projectId) {
                            Text("None").tag(String?.none)
                            ForEach(projects) { p in Text(p.name).tag(Optional(p.id)) }
                        } label: { Label("Project", systemImage: "folder") }
                    }
                    if draft.original != nil {
                        Picker(selection: $draft.status) {
                            Text("To do").tag("todo")
                            Text("In progress").tag("doing")
                            Text("Done").tag("done")
                        } label: { Label("Status", systemImage: "circle.dashed") }
                    }
                }
                if model.hasFamily {
                    Section {
                        MemberPicker(title: "Assignee", memberId: $draft.assigneeId)
                        FamilyShareToggle(shared: $draft.shared, ownerId: draft.original?.ownerId, lockedReason: lockedReason)
                    }
                }
                if let t = draft.original {
                    Section {
                        Button("Delete task", role: .destructive) {
                            Task {
                                await attempt(model, "Task deleted") { try await model.api.deleteTask(t.id) }
                                await onSaved()
                                dismiss()
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle(draft.original == nil ? "New task" : "Edit task")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(busy || draft.title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { if draft.original == nil { titleFocused = true } }
        }
        .presentationDetents([.medium, .large])
    }

    private func save() async {
        busy = true
        defer { busy = false }
        var b: JSONObject = [
            "title": .string(draft.title.trimmingCharacters(in: .whitespacesAndNewlines)),
            "notes": .string(draft.notes),
            "priority": .string(draft.priority),
            "dueDate": draft.hasDue ? .string(KairoDate.dayString(draft.due)) : .null,
            "projectId": .stringOrNull(draft.projectId),
            "assigneeId": .stringOrNull(draft.assigneeId),
        ]
        if !assignedToOther { b["shared"] = .bool(draft.shared) }
        let isNew = draft.original == nil
        if !isNew { b["status"] = .string(draft.status) }
        do {
            if let t = draft.original {
                _ = try await model.api.updateTask(t.id, b)
            } else {
                b = b.filter { !$0.value.isNull }
                _ = try await model.api.createTask(b)
            }
            model.show(Toast(title: isNew ? String(localized: "Task created") : String(localized: "Task saved")))
            await onSaved()
            dismiss()
        } catch {
            model.show(Toast(title: String(localized: "Couldn't save"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}
