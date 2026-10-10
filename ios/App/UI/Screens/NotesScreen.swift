import SwiftUI
import KairoCore
import KairoUI

/// Notes: search by meaning, family filters and a markdown editor.
/// On wide screens (iPad, unfolded iPhone Duo) the list and the note go side by side.
struct NotesScreen: View {
    @Environment(AppModel.self) private var model
    @State private var state: Loadable<[Note]> = .loading
    @State private var query = ""
    @State private var results: [Note]?
    @State private var filter: Filter = .all
    @State private var selection: String?
    @State private var creating = false

    enum Filter: Hashable { case all, mine, family }

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > 720
            LoadableView(state: $state, version: model.version("note") + model.version("family"), load: { try await model.api.notes() }) { all in
                let notes = filtered(results ?? all)
                if wide {
                    HStack(spacing: 0) {
                        listColumn(notes, all: all, wide: true).frame(width: min(380, geo.size.width * 0.4))
                        Divider().overlay(KairoColor.border)
                        Group {
                            if let id = selection, let n = all.first(where: { $0.id == id }) {
                                NoteDetail(note: n, pushed: false, onDeleted: { selection = nil }).id(n.id)
                            } else {
                                EmptyStateView(symbol: "note.text", title: "Pick a note", message: "Or create a new one.", actionTitle: "New note") { creating = true }
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                } else {
                    listColumn(notes, all: all, wide: false)
                        .navigationDestination(for: String.self) { id in
                            if let n = all.first(where: { $0.id == id }) { NoteDetail(note: n, pushed: true, onDeleted: {}) }
                        }
                }
            }
        }
        .navigationTitle("Notes")
        .searchable(text: $query, prompt: "Search notes")
        .task(id: query) { await search() }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { creating = true } label: { Label("New note", systemImage: "square.and.pencil") }
                    .keyboardShortcut("n", modifiers: .command)
            }
        }
        .sheet(isPresented: $creating) {
            NavigationStack { NoteEditor(note: nil, onCancel: nil) { n in selection = n.id; model.bump("note") } }
        }
        .kairoBackground()
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { withAnimation(KairoMotion.base) { results = nil }; return }
        try? await Task.sleep(for: .milliseconds(300))
        guard !Task.isCancelled else { return }
        if let r = try? await model.api.searchNotes(q) { withAnimation(KairoMotion.base) { results = r } }
    }

    private func filtered(_ notes: [Note]) -> [Note] {
        let f: [Note] = switch filter {
        case .all: notes
        case .mine: notes.filter { !$0.shared || $0.ownerId == model.user?.id }
        case .family: notes.filter(\.shared)
        }
        return results != nil ? f : f.sorted { ($0.pinned ? 1 : 0, $0.updatedAt) > ($1.pinned ? 1 : 0, $1.updatedAt) }
    }

    @ViewBuilder private func listColumn(_ notes: [Note], all: [Note], wide: Bool) -> some View {
        List(selection: wide ? $selection : .constant(nil)) {
            if model.hasFamily {
                OwnershipFilter<Filter>(options: [(.all, "All", nil), (.mine, "Mine", nil), (.family, "Shared", all.filter(\.shared).count)], selection: $filter)
                    .listRowInsets(EdgeInsets(top: 4, leading: -4, bottom: 4, trailing: 0))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            if notes.isEmpty {
                Group {
                    if results != nil {
                        EmptyStateView(symbol: "magnifyingglass", title: "No results", message: "Try different words; search understands meaning.")
                    } else if filter == .family {
                        EmptyStateView(symbol: "house", title: "Nothing shared yet", message: "Open a note and turn on Share with family so everyone can see it.")
                    } else {
                        EmptyStateView(symbol: "note.text", title: "No notes yet", message: "Tell Kairo \"note that the wifi password is…\" or create one.")
                    }
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            ForEach(notes) { n in
                Group {
                    if wide {
                        NoteRow(note: n).tag(n.id)
                    } else {
                        NavigationLink(value: n.id) { NoteRow(note: n) }
                    }
                }
                .listRowBackground(wide && selection == n.id ? KairoColor.accent.opacity(0.12) : KairoColor.surface)
                .swipeActions(edge: .leading) {
                    Button { Task { await togglePin(n) } } label: { Label(n.pinned ? "Unpin" : "Pin", systemImage: n.pinned ? "pin.slash" : "pin") }
                        .tint(KairoColor.accent)
                }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) { Task { await delete(n) } } label: { Label("Delete", systemImage: "trash") }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .refreshable { model.bump("note") }
        .animation(KairoMotion.spring, value: notes.map(\.id))
    }

    private func togglePin(_ n: Note) async {
        await attempt(model) { _ = try await model.api.updateNote(n.id, ["pinned": .bool(!n.pinned)]) }
        model.bump("note")
    }

    private func delete(_ n: Note) async {
        await attempt(model, "Note deleted. You can undo it in Activity.") { try await model.api.deleteNote(n.id) }
        if selection == n.id { selection = nil }
        model.bump("note")
    }
}

private struct NoteRow: View {
    @Environment(AppModel.self) private var model
    var note: Note

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                if note.pinned { Image(systemName: "pin.fill").font(.system(size: 11)).foregroundStyle(KairoColor.accent) }
                Text(note.title.isEmpty ? String(localized: "Untitled") : note.title).font(.kHeadline).foregroundStyle(KairoColor.text).lineLimit(1)
                Spacer(minLength: 0)
                if let d = KairoDate.parse(note.updatedAt) { Text(KairoDate.relative(d)).font(.kMonoSmall).foregroundStyle(KairoColor.faint) }
            }
            if !note.content.isEmpty {
                Text(note.content.plainPreview).font(.kSubheadline).foregroundStyle(KairoColor.muted).lineLimit(2)
            }
            if note.shared || !note.tags.isEmpty || !note.attachments.isEmpty {
                HStack(spacing: 6) {
                    if note.shared { SharedBadge(owner: model.ownerName(note.ownerId)) }
                    ForEach(note.tags.prefix(3), id: \.self) { t in
                        Text("#\(t)").font(.kCaption).foregroundStyle(KairoColor.muted)
                    }
                    if !note.attachments.isEmpty { Image(systemName: "paperclip").font(.system(size: 11)).foregroundStyle(KairoColor.muted) }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

/// A note: markdown reading and editing in the same place.
struct NoteDetail: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var note: Note
    var pushed: Bool
    var onDeleted: () -> Void
    @State private var editing = false

    var body: some View {
        Group {
            if editing {
                NoteEditor(note: note, onCancel: { editing = false }) { _ in editing = false; model.bump("note") }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text(note.title.isEmpty ? String(localized: "Untitled") : note.title).font(.kTitle).foregroundStyle(KairoColor.text).selectableText()
                        HStack(spacing: 8) {
                            if let d = KairoDate.parse(note.updatedAt) { Text("Edited \(KairoDate.relative(d))").font(.kFootnote).foregroundStyle(KairoColor.muted) }
                            if note.shared {
                                SharedBadge(owner: model.ownerName(note.ownerId))
                            }
                        }
                        if !note.tags.isEmpty {
                            HStack(spacing: 6) {
                                ForEach(note.tags, id: \.self) { t in
                                    Text("#\(t)").font(.kCaption).padding(.horizontal, 8).frame(height: 24).background(KairoColor.surface2, in: Capsule())
                                }
                            }
                        }
                        MarkdownText(note.content)
                        ForEach(note.attachments) { a in AttachmentView(file: a) }
                    }
                    .padding(24)
                    .frame(maxWidth: 760, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .toolbar {
                    ToolbarItemGroup(placement: .primaryAction) {
                        Button { editing = true } label: { Label("Edit", systemImage: "pencil") }
                        Menu {
                            Button { Task { await pin() } } label: { Label(note.pinned ? "Unpin" : "Pin", systemImage: note.pinned ? "pin.slash" : "pin") }
                            if model.hasFamily, note.ownerId == nil || note.ownerId == model.user?.id {
                                Button { Task { await share() } } label: {
                                    Label(note.shared ? "Stop sharing" : "Share with family", systemImage: "house")
                                }
                            }
                            ShareLink(item: "\(note.title)\n\n\(note.content)") { Label("Share…", systemImage: "square.and.arrow.up") }
                            Divider()
                            Button(role: .destructive) { Task { await delete() } } label: { Label("Delete", systemImage: "trash") }
                        } label: { Label("More", systemImage: "ellipsis") }
                    }
                }
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .kairoBackground()
    }

    private func pin() async {
        await attempt(model, note.pinned ? nil : "Pinned") { _ = try await model.api.updateNote(note.id, ["pinned": .bool(!note.pinned)]) }
        model.bump("note")
    }

    private func share() async {
        await attempt(model, note.shared ? "The note is yours alone again" : "Note shared with the family") {
            _ = try await model.api.updateNote(note.id, ["shared": .bool(!note.shared)])
        }
        model.bump("note")
    }

    private func delete() async {
        await attempt(model, "Note deleted. You can undo it in Activity.") { try await model.api.deleteNote(note.id) }
        model.bump("note")
        onDeleted()
        if pushed { dismiss() }
    }
}

/// Create or edit a note.
struct NoteEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var note: Note?
    var onCancel: (() -> Void)?
    var onSaved: (Note) -> Void
    @State private var title = ""
    @State private var content = ""
    @State private var shared = false
    @State private var busy = false
    @FocusState private var focus: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TextField("Title", text: $title)
                .font(.kTitle2)
                .padding(.horizontal, 20)
                .padding(.top, 16)
            TextEditor(text: $content)
                .font(.kBody)
                .scrollContentBackground(.hidden)
                .focused($focus)
                .padding(.horizontal, 15)
                .overlay(alignment: .topLeading) {
                    if content.isEmpty {
                        Text("Write here. Markdown works: # headings, - lists, **bold**")
                            .font(.kBody).foregroundStyle(KairoColor.faint).padding(.horizontal, 20).padding(.top, 8).allowsHitTesting(false)
                    }
                }
            if model.hasFamily, note == nil || note?.ownerId == model.user?.id || note?.ownerId == nil {
                Toggle(isOn: $shared) {
                    Label("Share with family", systemImage: "house")
                    Text(shared ? "They'll be able to see and edit it in \(model.family?.household.name ?? String(localized: "your family"))." : "Only you can see it.")
                }
                .tint(KairoColor.accent)
                .padding(16)
                .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
                .padding(16)
            }
        }
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity)
        .kairoBackground()
        .navigationTitle(note == nil ? "New note" : "Edit")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { if let onCancel { onCancel() } else { dismiss() } }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { Task { await save() } }
                    .disabled(busy || (title.trimmingCharacters(in: .whitespaces).isEmpty && content.trimmingCharacters(in: .whitespaces).isEmpty))
            }
        }
        .onAppear {
            if let note { title = note.title; content = note.content; shared = note.shared } else { focus = true }
        }
    }

    private func save() async {
        busy = true
        defer { busy = false }
        do {
            let saved: Note
            if let note {
                var patch: JSONObject = ["title": .string(title), "content": .string(content)]
                if note.ownerId == nil || note.ownerId == model.user?.id { patch["shared"] = .bool(shared) }
                saved = try await model.api.updateNote(note.id, patch)
            } else {
                saved = try await model.api.createNote(title: title, content: content, shared: shared)
                model.show(Toast(title: shared ? String(localized: "Note shared with the family") : String(localized: "Note saved"), symbol: "note.text", destination: .notes))
                dismiss()
            }
            onSaved(saved)
        } catch {
            model.show(Toast(title: String(localized: "Couldn't save the note"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}

/// Attachment of a note or of the chat (the URLs come signed from the server).
struct AttachmentView: View {
    @Environment(AppModel.self) private var model
    var file: FileRef

    private var url: URL? {
        if file.url.hasPrefix("http") { return URL(string: file.url) }
        return URL(string: model.serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + file.url)
    }

    var body: some View {
        if (file.contentType ?? "").hasPrefix("image/") {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let img): img.resizable().scaledToFit()
                case .failure: Label("Couldn't load the image", systemImage: "photo").foregroundStyle(KairoColor.muted)
                default: RoundedRectangle(cornerRadius: 12).fill(KairoColor.surface2).frame(height: 180)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .frame(maxWidth: 480)
        } else if let url {
            Link(destination: url) { Label("Attachment", systemImage: "paperclip") }.font(.kCallout)
        }
    }
}

extension String {
    /// Text of a note without markdown marks, on one line, for previews.
    var plainPreview: String {
        components(separatedBy: "\n").map { line -> String in
            var l = line.trimmingCharacters(in: .whitespaces)
            while l.hasPrefix("#") { l.removeFirst() }
            for p in ["- [ ] ", "- [x] ", "[ ] ", "[x] ", "- ", "* ", "> "] where l.hasPrefix(p) { l.removeFirst(p.count); break }
            return l.replacingOccurrences(of: "**", with: "").replacingOccurrences(of: "__", with: "").replacingOccurrences(of: "`", with: "")
                .trimmingCharacters(in: .whitespaces)
        }
        .filter { !$0.isEmpty }
        .joined(separator: " · ")
    }
}
