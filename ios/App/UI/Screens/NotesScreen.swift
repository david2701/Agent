import SwiftUI
import KairoCore
import KairoUI

/// Notas: búsqueda por significado, filtros de familia y editor en markdown.
/// En pantallas anchas (iPad, iPhone Duo abierto) la lista y la nota van lado a lado.
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
                                EmptyStateView(symbol: "note.text", title: "Elige una nota", message: "O crea una nueva.", actionTitle: "Nueva nota") { creating = true }
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
        .navigationTitle("Notas")
        .searchable(text: $query, prompt: "Buscar notas")
        .task(id: query) { await search() }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { creating = true } label: { Label("Nueva nota", systemImage: "square.and.pencil") }
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
                OwnershipFilter<Filter>(options: [(.all, "Todas", nil), (.mine, "Mías", nil), (.family, "Familiares", all.filter(\.shared).count)], selection: $filter)
                    .listRowInsets(EdgeInsets(top: 4, leading: -4, bottom: 4, trailing: 0))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            if notes.isEmpty {
                Group {
                    if results != nil {
                        EmptyStateView(symbol: "magnifyingglass", title: "Sin resultados", message: "Prueba con otras palabras; la búsqueda entiende el significado.")
                    } else if filter == .family {
                        EmptyStateView(symbol: "house", title: "Nada compartido aún", message: "Abre una nota y activa Compartir con la familia para que la vean todos.")
                    } else {
                        EmptyStateView(symbol: "note.text", title: "Aún no hay notas", message: "Dile a Kairo \"anota que la clave del wifi es…\" o crea una.")
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
                    Button { Task { await togglePin(n) } } label: { Label(n.pinned ? "Desfijar" : "Fijar", systemImage: n.pinned ? "pin.slash" : "pin") }
                        .tint(KairoColor.accent)
                }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) { Task { await delete(n) } } label: { Label("Eliminar", systemImage: "trash") }
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
        await attempt(model, "Nota eliminada. Puedes deshacerlo en Actividad.") { try await model.api.deleteNote(n.id) }
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
                Text(note.title.isEmpty ? "Sin título" : note.title).font(.kHeadline).foregroundStyle(KairoColor.text).lineLimit(1)
                Spacer(minLength: 0)
                if let d = KairoDate.parse(note.updatedAt) { Text(KairoDate.relative(d)).font(.kMonoSmall).foregroundStyle(KairoColor.faint) }
            }
            if !note.content.isEmpty {
                Text(note.content.replacingOccurrences(of: "\n", with: " ")).font(.kSubheadline).foregroundStyle(KairoColor.muted).lineLimit(2)
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

/// Una nota: lectura en markdown y edición en el mismo lugar.
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
                        Text(note.title.isEmpty ? "Sin título" : note.title).font(.kTitle).foregroundStyle(KairoColor.text).selectableText()
                        HStack(spacing: 8) {
                            if let d = KairoDate.parse(note.updatedAt) { Text("Editada \(KairoDate.relative(d))").font(.kFootnote).foregroundStyle(KairoColor.muted) }
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
                        Button { editing = true } label: { Label("Editar", systemImage: "pencil") }
                        Menu {
                            Button { Task { await pin() } } label: { Label(note.pinned ? "Desfijar" : "Fijar", systemImage: note.pinned ? "pin.slash" : "pin") }
                            if model.hasFamily, note.ownerId == nil || note.ownerId == model.user?.id {
                                Button { Task { await share() } } label: {
                                    Label(note.shared ? "Dejar de compartir" : "Compartir con la familia", systemImage: "house")
                                }
                            }
                            ShareLink(item: "\(note.title)\n\n\(note.content)") { Label("Enviar…", systemImage: "square.and.arrow.up") }
                            Divider()
                            Button(role: .destructive) { Task { await delete() } } label: { Label("Eliminar", systemImage: "trash") }
                        } label: { Label("Más", systemImage: "ellipsis") }
                    }
                }
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .kairoBackground()
    }

    private func pin() async {
        await attempt(model, note.pinned ? nil : "Fijada") { _ = try await model.api.updateNote(note.id, ["pinned": .bool(!note.pinned)]) }
        model.bump("note")
    }

    private func share() async {
        await attempt(model, note.shared ? "La nota vuelve a ser solo tuya" : "Nota compartida con la familia") {
            _ = try await model.api.updateNote(note.id, ["shared": .bool(!note.shared)])
        }
        model.bump("note")
    }

    private func delete() async {
        await attempt(model, "Nota eliminada. Puedes deshacerlo en Actividad.") { try await model.api.deleteNote(note.id) }
        model.bump("note")
        onDeleted()
        if pushed { dismiss() }
    }
}

/// Crear o editar una nota.
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
            TextField("Título", text: $title)
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
                        Text("Escribe aquí. Admite markdown: # títulos, - listas, **negritas**")
                            .font(.kBody).foregroundStyle(KairoColor.faint).padding(.horizontal, 20).padding(.top, 8).allowsHitTesting(false)
                    }
                }
            if model.hasFamily, note == nil || note?.ownerId == model.user?.id || note?.ownerId == nil {
                Toggle(isOn: $shared) {
                    Label("Compartir con la familia", systemImage: "house")
                    Text(shared ? "La verán y podrán editarla en \(model.family?.household.name ?? "tu familia")." : "Solo tú la ves.")
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
        .navigationTitle(note == nil ? "Nueva nota" : "Editar")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancelar") { if let onCancel { onCancel() } else { dismiss() } }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Guardar") { Task { await save() } }
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
                model.show(Toast(title: shared ? "Nota compartida con la familia" : "Nota guardada", symbol: "note.text", destination: .notes))
                dismiss()
            }
            onSaved(saved)
        } catch {
            model.show(Toast(title: "No se pudo guardar la nota", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}

/// Adjunto de una nota o del chat (las URLs vienen firmadas desde el servidor).
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
                case .failure: Label("No se pudo cargar la imagen", systemImage: "photo").foregroundStyle(KairoColor.muted)
                default: RoundedRectangle(cornerRadius: 12).fill(KairoColor.surface2).frame(height: 180)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .frame(maxWidth: 480)
        } else if let url {
            Link(destination: url) { Label("Adjunto", systemImage: "paperclip") }.font(.kCallout)
        }
    }
}
