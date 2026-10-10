import SwiftUI
import KairoCore
import KairoUI

struct WatchRoot: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        switch model.phase {
        case .loading: KairoOrb(state: .idle).frame(width: 60, height: 60)
        case .signedOut: PairingView()
        case .ready: WatchHome()
        }
    }
}

/// Páginas verticales: hablar, hoy y listas (como las apps del sistema en watchOS).
struct WatchHome: View {
    @Environment(WatchModel.self) private var model
    @State private var page = 0

    var body: some View {
        NavigationStack {
            TabView(selection: $page) {
                TalkPage().tag(0)
                TodayPage().tag(1)
                ListsPage().tag(2)
            }
            .tabViewStyle(.verticalPage)
            .onChange(of: model.wantsTalk) { _, want in
                if want { page = 0; model.voice.toggle(); model.wantsTalk = false }
            }
        }
    }
}

private struct TalkPage: View {
    @Environment(WatchModel.self) private var model
    private var voice: WatchVoice { model.voice }

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Button { voice.toggle() } label: {
                    KairoOrb(state: voice.orbState, level: voice.level).frame(width: 96, height: 96)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(voice.phase == .idle ? "Hablar con Kairo" : "Detener")
                .handGestureShortcut(.primaryAction)
                Text(voice.status).font(.kairo(14, .medium)).foregroundStyle(KairoColor.muted).contentTransition(.opacity)
                if let t = voice.turn.transcript, !t.isEmpty {
                    Text("“\(t)”").font(.kairo(13)).foregroundStyle(KairoColor.text).multilineTextAlignment(.center)
                }
                ForEach(voice.turn.actions) { a in
                    Label(a.summary, systemImage: KairoSymbol.entity(a.entity?.type ?? ""))
                        .font(.kairo(12))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                        .background(KairoColor.surface2, in: RoundedRectangle(cornerRadius: 10))
                }
                if !voice.turn.text.isEmpty {
                    Text(voice.turn.text).font(.kairo(14)).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .animation(KairoMotion.spring, value: voice.turn.actions.count)
        }
        .navigationTitle("Kairo")
    }
}

private struct TodayPage: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        let s = model.snapshot
        List {
            Section("Agenda") {
                if s.events.isEmpty { Text("Sin eventos").foregroundStyle(KairoColor.muted) }
                ForEach(s.events) { e in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 4) {
                            if e.shared { Image(systemName: "house.fill").font(.system(size: 10)).foregroundStyle(KairoColor.accent) }
                            Text(e.title).font(.kairo(15, .semibold)).lineLimit(2)
                        }
                        Text(e.subtitle).font(.kairo(12)).foregroundStyle(KairoColor.muted).lineLimit(1)
                    }
                }
            }
            Section("Pendientes") {
                let open = s.tasks.filter { !$0.done }
                if open.isEmpty { Text("Nada pendiente").foregroundStyle(KairoColor.muted) }
                ForEach(open) { t in
                    Button { Task { await model.complete(task: t.id) } } label: {
                        HStack {
                            Image(systemName: "circle").foregroundStyle(KairoColor.faint)
                            Text(t.title).font(.kairo(14)).lineLimit(2)
                        }
                    }
                }
            }
            if !s.reminders.isEmpty {
                Section("Recordatorios") {
                    ForEach(s.reminders) { r in
                        Label { Text(r.title).font(.kairo(14)) } icon: { Text(r.subtitle).font(.kairoMono(11)).foregroundStyle(KairoColor.accent) }
                    }
                }
            }
        }
        .navigationTitle(s.greeting.isEmpty ? "Hoy" : s.greeting)
        .refreshable { await model.refresh() }
    }
}

private struct ListsPage: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        List {
            if model.snapshot.lists.isEmpty { Text("Sin listas").foregroundStyle(KairoColor.muted) }
            ForEach(model.snapshot.lists) { l in
                NavigationLink {
                    ListItemsView(listId: l.id)
                } label: {
                    HStack {
                        Text(l.name).font(.kairo(15, .semibold))
                        if l.shared { Image(systemName: "house").font(.system(size: 10)).foregroundStyle(KairoColor.muted) }
                        Spacer()
                        Text("\(l.items.filter { !$0.done }.count)").font(.kairoMono(13)).foregroundStyle(KairoColor.accent)
                    }
                }
            }
            Section {
                Button("Cerrar sesión", role: .destructive) { Task { await model.signOut() } }.font(.kairo(13))
            }
        }
        .navigationTitle("Listas")
    }
}

private struct ListItemsView: View {
    @Environment(WatchModel.self) private var model
    var listId: String

    var body: some View {
        let list = model.snapshot.lists.first { $0.id == listId }
        List {
            ForEach((list?.items ?? []).sorted { !$0.done && $1.done }) { i in
                Button { Task { await model.toggleItem(list: listId, item: i.id, checked: !i.done) } } label: {
                    HStack {
                        Image(systemName: i.done ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(i.done ? KairoColor.accent : KairoColor.faint)
                            .contentTransition(.symbolEffect(.replace))
                        Text(i.title).strikethrough(i.done).foregroundStyle(i.done ? KairoColor.muted : KairoColor.text)
                    }
                }
                .sensoryFeedback(.success, trigger: i.done)
            }
        }
        .navigationTitle(list?.name ?? "Lista")
    }
}

/// Vinculación: automática con el iPhone cerca, o con el código de Ajustes > Apple Watch.
private struct PairingView: View {
    @Environment(WatchModel.self) private var model
    @State private var server = AppConfig.defaultServer
    @State private var code = ""
    @State private var busy = false

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                KairoOrb(state: model.pairing || busy ? .thinking : .idle).frame(width: 54, height: 54)
                Text("Kairo").font(.kairo(18, .semibold))
                Button {
                    Task { await model.pairWithPhone() }
                } label: { Label("Vincular con el iPhone", systemImage: "iphone") }
                .disabled(model.pairing)
                if let e = model.pairError { Text(e).font(.kairo(12)).foregroundStyle(KairoColor.muted).multilineTextAlignment(.center) }
                Divider().padding(.vertical, 4)
                TextField("Servidor", text: $server).textContentType(.URL)
                TextField("Código", text: $code)
                Button("Vincular con código") {
                    Task {
                        busy = true
                        defer { busy = false }
                        do { try await model.pair(server: server, code: code) } catch { model.pairError = error.localizedDescription }
                    }
                }
                .disabled(code.count < 4 || busy)
                Text("El código está en Kairo, en Ajustes > Apple Watch.").font(.kairo(11)).foregroundStyle(KairoColor.faint).multilineTextAlignment(.center)
            }
        }
    }
}
