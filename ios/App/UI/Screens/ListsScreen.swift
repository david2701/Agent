import SwiftUI
import KairoCore
import KairoUI

/// Listas (compras, pendientes de la casa…). Las familiares las editan todos a la vez.
struct ListsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var state: Loadable<[ShoppingList]> = .loading
    @State private var selection: String?
    @State private var creating = false

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > 720
            LoadableView(state: $state, version: model.version("list") + model.version("family"), load: { try await model.api.lists() }) { lists in
                if wide {
                    HStack(spacing: 0) {
                        overview(lists, wide: true).frame(width: min(360, geo.size.width * 0.38))
                        Divider().overlay(KairoColor.border)
                        Group {
                            if let id = selection ?? lists.first?.id, let l = lists.first(where: { $0.id == id }) {
                                ListDetail(list: l, onChange: replace, onDeleted: { selection = nil })
                            } else {
                                EmptyStateView(symbol: "checklist", title: "Elige una lista", message: "Verás sus elementos aquí.")
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                } else {
                    overview(lists, wide: false)
                        .navigationDestination(for: ShoppingList.ID.self) { id in
                            if let l = state.value?.first(where: { $0.id == id }) {
                                ListDetail(list: l, onChange: replace, onDeleted: {})
                            }
                        }
                }
            }
        }
        .navigationTitle("Listas")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { creating = true } label: { Label("Nueva lista", systemImage: "plus") }
            }
        }
        .sheet(isPresented: $creating) { NewListSheet { l in replace(l); selection = l.id } }
        .kairoBackground()
    }

    /// Aplica la lista que devolvió el servidor sin recargar todo (respuesta inmediata).
    private func replace(_ l: ShoppingList) {
        guard var all = state.value else { return }
        if let i = all.firstIndex(where: { $0.id == l.id }) { all[i] = l } else { all.insert(l, at: 0) }
        withAnimation(KairoMotion.spring) { state = .loaded(all) }
    }

    @ViewBuilder private func overview(_ lists: [ShoppingList], wide: Bool) -> some View {
        if lists.isEmpty {
            EmptyStateView(symbol: "checklist", title: "Sin listas", message: "Dile a Kairo \"añade leche y pan a la lista del súper\".", actionTitle: "Nueva lista") { creating = true }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: wide ? 280 : 160), spacing: 12)], spacing: 12) {
                    ForEach(lists) { l in
                        if wide {
                            Button { withAnimation(KairoMotion.base) { selection = l.id } } label: {
                                ListCard(list: l, selected: (selection ?? lists.first?.id) == l.id)
                            }
                            .buttonStyle(.plain)
                        } else {
                            NavigationLink(value: l.id) { ListCard(list: l, selected: false) }.buttonStyle(.plain)
                        }
                    }
                }
                .padding(16)
            }
            .refreshable { model.bump("list") }
        }
    }
}

private struct ListCard: View {
    @Environment(AppModel.self) private var model
    var list: ShoppingList
    var selected: Bool

    var body: some View {
        let total = list.items.count
        let done = total - list.pending.count
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Image(systemName: list.shared ? "house" : "checklist").font(.system(size: 18, weight: .medium)).foregroundStyle(list.shared ? KairoColor.accent : KairoColor.muted)
                Spacer()
                if list.shared { SharedBadge(owner: model.ownerName(list.ownerId), compact: model.ownerName(list.ownerId) == nil) }
            }
            Text(list.name).font(.kHeadline).foregroundStyle(KairoColor.text).lineLimit(1)
            Text(list.pending.isEmpty ? (total == 0 ? "Vacía" : "Todo listo") : list.pending.prefix(3).map(\.text).joined(separator: ", "))
                .font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(2, reservesSpace: true)
            ProgressView(value: total == 0 ? 0 : Double(done), total: Double(max(total, 1)))
                .tint(KairoColor.accent)
                .animation(KairoMotion.spring, value: done)
            Text("\(list.pending.count) pendientes").font(.kMonoSmall).foregroundStyle(KairoColor.faint).contentTransition(.numericText())
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(selected ? KairoColor.accent : KairoColor.border, lineWidth: selected ? 1.5 : 1))
    }
}

/// Elementos de una lista: marcar, agregar varios, quitar marcados.
struct ListDetail: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var list: ShoppingList
    var onChange: (ShoppingList) -> Void
    var onDeleted: () -> Void
    @State private var newItems = ""
    @State private var renaming = false
    @State private var name = ""
    @FocusState private var addFocused: Bool

    private var pending: [ListItem] { list.items.filter { !$0.checked } }
    private var checked: [ListItem] { list.items.filter(\.checked) }
    private var isMine: Bool { list.ownerId == nil || list.ownerId == model.user?.id }

    var body: some View {
        List {
            Section {
                HStack(spacing: 10) {
                    Image(systemName: "plus.circle.fill").font(.system(size: 22)).foregroundStyle(KairoColor.accent)
                    TextField("Agregar (separa con comas)", text: $newItems)
                        .focused($addFocused)
                        .submitLabel(.done)
                        .onSubmit { Task { await add() } }
                }
                .listRowBackground(KairoColor.surface)
            } footer: {
                if list.shared { Text(model.ownerName(list.ownerId).map { "Lista de \($0). Todos en la familia pueden agregar y marcar." } ?? "Todos en la familia pueden agregar y marcar.") }
            }
            if !pending.isEmpty {
                Section {
                    ForEach(pending) { i in item(i) }
                }
            }
            if !checked.isEmpty {
                Section {
                    ForEach(checked) { i in item(i) }
                } header: {
                    HStack {
                        SectionLabel("Marcados", count: checked.count)
                        Spacer()
                        Button("Quitar marcados") { Task { await clear() } }.font(.kairo(13, .medium)).foregroundStyle(KairoColor.accentStrong)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .animation(KairoMotion.spring, value: list.items)
        .navigationTitle(list.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { name = list.name; renaming = true } label: { Label("Cambiar nombre", systemImage: "pencil") }
                    if model.hasFamily, isMine {
                        Button { Task { await share() } } label: { Label(list.shared ? "Dejar de compartir" : "Compartir con la familia", systemImage: "house") }
                    }
                    ShareLink(item: "\(list.name)\n" + pending.map { "• \($0.text)" }.joined(separator: "\n")) { Label("Enviar…", systemImage: "square.and.arrow.up") }
                    Divider()
                    Button(role: .destructive) { Task { await delete() } } label: { Label("Eliminar lista", systemImage: "trash") }
                } label: { Label("Más", systemImage: "ellipsis") }
            }
        }
        .alert("Nombre de la lista", isPresented: $renaming) {
            TextField("Nombre", text: $name)
            Button("Cancelar", role: .cancel) {}
            Button("Guardar") { Task { await rename() } }
        }
        .kairoBackground()
    }

    private func item(_ i: ListItem) -> some View {
        Button { Task { await toggle(i) } } label: {
            HStack(spacing: 12) {
                Image(systemName: i.checked ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 21))
                    .foregroundStyle(i.checked ? KairoColor.accent : KairoColor.faint)
                    .contentTransition(.symbolEffect(.replace))
                Text(i.text)
                    .font(.kBody)
                    .foregroundStyle(i.checked ? KairoColor.muted : KairoColor.text)
                    .strikethrough(i.checked, color: KairoColor.faint)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(KairoColor.surface)
        .sensoryFeedback(.selection, trigger: i.checked)
        .swipeActions { Button(role: .destructive) { Task { await remove(i) } } label: { Label("Quitar", systemImage: "trash") } }
    }

    private func run(_ success: String? = nil, _ f: () async throws -> ShoppingList) async {
        do {
            let l = try await f()
            onChange(l)
            if let success { model.show(Toast(title: success, symbol: "checklist")) }
        } catch {
            model.show(Toast(title: "No se pudo completar", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }

    private func toggle(_ i: ListItem) async {
        // Respuesta inmediata; el servidor confirma
        var l = list
        if let idx = l.items.firstIndex(where: { $0.id == i.id }) { l.items[idx].checked.toggle(); onChange(l) }
        await run { try await model.api.setItem(list.id, i.id, checked: !i.checked) }
    }

    private func add() async {
        let items = newItems.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard !items.isEmpty else { return }
        newItems = ""
        addFocused = true
        await run { try await model.api.addItems(list.id, items) }
    }

    private func remove(_ i: ListItem) async { await run { try await model.api.deleteItem(list.id, i.id) } }
    private func clear() async { await run("Marcados eliminados") { try await model.api.clearChecked(list.id) } }
    private func rename() async {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        await run { try await model.api.updateList(list.id, ["name": .string(n)]) }
    }
    private func share() async {
        await run(list.shared ? "La lista vuelve a ser solo tuya" : "Lista compartida con la familia") {
            try await model.api.updateList(list.id, ["shared": .bool(!list.shared)])
        }
    }
    private func delete() async {
        await attempt(model, "Lista eliminada. Puedes deshacerlo en Actividad.") { try await model.api.deleteList(list.id) }
        model.bump("list")
        onDeleted()
        dismiss()
    }
}

private struct NewListSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onCreated: (ShoppingList) -> Void
    @State private var name = ""
    @State private var shared = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Nombre", text: $name).font(.kairo(18, .medium))
                if model.hasFamily {
                    Toggle(isOn: $shared) {
                        Label("Familiar", systemImage: "house")
                        Text(shared ? "Todos pueden agregar y marcar." : "Solo tú la ves.")
                    }
                    .tint(KairoColor.accent)
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle("Nueva lista")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Crear") {
                        Task {
                            do {
                                let l = try await model.api.createList(name.trimmingCharacters(in: .whitespacesAndNewlines), shared: shared)
                                model.show(Toast(title: shared ? "Lista familiar creada" : "Lista creada", symbol: "checklist"))
                                onCreated(l)
                                dismiss()
                            } catch {
                                model.show(Toast(title: "No se pudo crear", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
                            }
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.height(260)])
    }
}
