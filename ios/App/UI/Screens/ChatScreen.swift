import SwiftUI
import PhotosUI
import KairoCore
import KairoUI

/// What shows in the conversation: your messages, Kairo's actions and its answers.
struct ChatItem: Identifiable, Equatable {
    enum Kind: Equatable {
        case user(String)
        case assistant(String)
        case action(summary: String, entityType: String?, ok: Bool)
        case attachment(FileRef)
    }
    var id: String
    var kind: Kind

    static func from(_ messages: [StoredMessage]) -> [ChatItem] {
        messages.compactMap { m in
            switch m.role {
            case "user": return ChatItem(id: m.id, kind: .user(m.content))
            case "assistant": return m.content.isEmpty ? nil : ChatItem(id: m.id, kind: .assistant(m.content))
            case "tool":
                let meta = m.meta
                if let a = meta?["attachment"], case .object(let o) = a, let url = o["url"]?.string, let fid = o["id"]?.string {
                    return ChatItem(id: m.id, kind: .attachment(FileRef(id: fid, url: url, contentType: o["contentType"]?.string)))
                }
                let summary = meta?["summary"]?.string ?? m.toolName ?? String(localized: "Done")
                return ChatItem(id: m.id, kind: .action(summary: summary, entityType: meta?["entity"]?["type"]?.string, ok: meta?["ok"]?.bool ?? true))
            default: return nil
            }
        }
    }
}

/// Text conversation with Kairo (with history). On wide screens, the history goes on the side.
struct ChatScreen: View {
    @Environment(AppModel.self) private var model
    @State private var conversations: [Conversation] = []
    @State private var currentId: String?
    @State private var items: [ChatItem] = []
    @State private var live = AgentTurn()
    @State private var sending = false
    @State private var text = ""
    @State private var showHistory = false
    @State private var photo: PhotosPickerItem?
    @State private var loadingHistory = false
    @FocusState private var focused: Bool

    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                if geo.size.width > 820 {
                    history.frame(width: 300)
                    Divider().overlay(KairoColor.border)
                }
                conversation
            }
        }
        .navigationTitle(conversations.first(where: { $0.id == currentId })?.title ?? String(localized: "Conversation"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { showHistory = true } label: { Label("Conversations", systemImage: "clock.arrow.circlepath") }
                Button { newConversation() } label: { Label("New conversation", systemImage: "square.and.pencil") }
                    .keyboardShortcut("n", modifiers: .command)
            }
        }
        .sheet(isPresented: $showHistory) {
            NavigationStack {
                history
                    .navigationTitle("Conversations")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { showHistory = false } } }
            }
            .presentationDetents([.medium, .large])
        }
        .task(id: model.version("conversation")) { await loadConversations() }
        .onChange(of: photo) { _, item in if let item { Task { await sendPhoto(item) } } }
        .kairoBackground()
    }

    // MARK: History

    private var history: some View {
        List {
            if conversations.isEmpty {
                EmptyStateView(symbol: "bubble.left.and.text.bubble.right", title: "No conversations", message: "What you talk about with Kairo shows up here.")
                    .listRowBackground(Color.clear)
            }
            ForEach(conversations) { c in
                Button { Task { await open(c.id) } } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(c.title.isEmpty ? String(localized: "Conversation") : c.title).font(.kairo(15, .medium)).foregroundStyle(KairoColor.text).lineLimit(1)
                            Spacer()
                            if let d = KairoDate.parse(c.updatedAt) { Text(KairoDate.relative(d)).font(.kMonoSmall).foregroundStyle(KairoColor.faint) }
                        }
                        if let p = c.preview, !p.isEmpty { Text(p).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(2) }
                    }
                    .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
                .listRowBackground(c.id == currentId ? KairoColor.accent.opacity(0.12) : KairoColor.surface)
                .swipeActions {
                    Button(role: .destructive) { Task { await delete(c) } } label: { Label("Delete conversation", systemImage: "trash") }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    private func loadConversations() async {
        if let c = try? await model.api.conversations() { withAnimation(KairoMotion.base) { conversations = c } }
    }

    private func open(_ id: String) async {
        showHistory = false
        guard id != currentId else { return }
        loadingHistory = true
        defer { loadingHistory = false }
        currentId = id
        live = AgentTurn()
        if let m = try? await model.api.messages(id) { withAnimation(KairoMotion.base) { items = ChatItem.from(m) } }
    }

    private func delete(_ c: Conversation) async {
        await attempt(model) { try await model.api.deleteConversation(c.id) }
        if c.id == currentId { newConversation() }
        await loadConversations()
    }

    private func newConversation() {
        withAnimation(KairoMotion.base) {
            currentId = nil
            items = []
            live = AgentTurn()
        }
        focused = true
    }

    // MARK: Conversation

    private var conversation: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if items.isEmpty && !sending && !loadingHistory {
                        VStack(spacing: 14) {
                            KairoOrb(state: .idle).frame(width: 88, height: 88)
                            Text("How can I help?").font(.kTitle2)
                            Text("Notes, tasks, reminders, your calendar or a question.").font(.kSubheadline).foregroundStyle(KairoColor.muted)
                            SuggestionChips { t in text = t; send() }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                    }
                    ForEach(items) { item in
                        bubble(item).transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                    if sending || !live.actions.isEmpty || !live.text.isEmpty {
                        liveTurn.id("live")
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: 780)
                .frame(maxWidth: .infinity)
                .animation(KairoMotion.spring, value: items)
            }
            .scrollDismissesKeyboard(.interactively)
            .defaultScrollAnchor(.bottom)
            .onChange(of: items.count) { _, _ in withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            .onChange(of: live.text) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            .safeAreaInset(edge: .bottom) { composer }
        }
    }

    @ViewBuilder private func bubble(_ item: ChatItem) -> some View {
        switch item.kind {
        case .user(let t):
            HStack {
                Spacer(minLength: 48)
                Text(t)
                    .font(.kBody)
                    .foregroundStyle(KairoColor.onAccent)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(KairoColor.accent, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .selectableText()
            }
        case .assistant(let t):
            MarkdownText(t).padding(.trailing, 32)
        case .action(let summary, let type, let ok):
            ActionCard(summary: summary, entityType: type, ok: ok) {
                if let s = AppSection.from(entity: type) { model.perform("section:\(s.rawValue)") }
            }
            .padding(.trailing, 48)
        case .attachment(let f):
            AttachmentView(file: f)
        }
    }

    private var liveTurn: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(live.actions) { a in
                ActionCard(summary: a.running ? a.label : a.summary, entityType: a.entity?.type, ok: a.ok, running: a.running)
                    .padding(.trailing, 48)
                    .transition(.scale(scale: 0.96).combined(with: .opacity))
            }
            ForEach(live.attachments) { AttachmentView(file: $0) }
            if live.text.isEmpty {
                if sending && live.working == nil {
                    HStack(spacing: 8) {
                        KairoOrb(state: .thinking).frame(width: 22, height: 22)
                        Text("Thinking").font(.kSubheadline).foregroundStyle(KairoColor.muted)
                    }
                }
            } else {
                MarkdownText(live.text).padding(.trailing, 32)
            }
            if let e = live.error {
                Label(e, systemImage: "exclamationmark.triangle").font(.kFootnote).foregroundStyle(KairoColor.danger)
            }
        }
        .animation(KairoMotion.spring, value: live.actions.count)
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            PhotosPicker(selection: $photo, matching: .images) {
                Image(systemName: "photo").font(.system(size: 17, weight: .medium)).frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel("Image")
            .disabled(sending)
            TextField("Type to Kairo", text: $text, axis: .vertical)
                .font(.kBody)
                .lineLimit(1...6)
                .focused($focused)
                .submitLabel(.send)
                .onSubmit(send)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .glassEffect(.regular, in: .rect(cornerRadius: 22))
            Button {
                if text.trimmingCharacters(in: .whitespaces).isEmpty { model.startTalking() } else { send() }
            } label: {
                Image(systemName: text.trimmingCharacters(in: .whitespaces).isEmpty ? "mic.fill" : "arrow.up")
                    .font(.system(size: 17, weight: .bold))
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .foregroundStyle(KairoColor.onAccent)
            .background(KairoColor.accent, in: Circle())
            .disabled(sending)
            .accessibilityLabel(text.isEmpty ? "Talk" : "Send")
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: 780)
        .frame(maxWidth: .infinity)
    }

    // MARK: Send

    private func send() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !sending else { return }
        text = ""
        withAnimation(KairoMotion.spring) { items.append(ChatItem(id: UUID().uuidString, kind: .user(t))) }
        stream(model.api.chat(t, conversationId: currentId))
    }

    private func sendPhoto(_ item: PhotosPickerItem) async {
        photo = nil
        guard let data = try? await item.loadTransferable(type: Data.self) else { return }
        let caption = text.trimmingCharacters(in: .whitespacesAndNewlines)
        text = ""
        withAnimation(KairoMotion.spring) { items.append(ChatItem(id: UUID().uuidString, kind: .user(caption.isEmpty ? String(localized: "Image") : String(localized: "Image: \(caption)")))) }
        let image = Self.jpeg(data) ?? data
        stream(model.api.capture(text: caption.isEmpty ? nil : caption, url: nil, image: KairoAPI.FilePart(filename: "photo.jpg", mime: "image/jpeg", data: image)))
    }

    /// Shrinks the photo before uploading it (2048 px at most).
    private static func jpeg(_ data: Data) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        let maxSide: CGFloat = 2048
        let scale = min(1, maxSide / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let out = UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        return out.jpegData(compressionQuality: 0.82)
    }

    private func stream(_ events: AsyncThrowingStream<AgentEvent, Error>) {
        sending = true
        live = AgentTurn()
        Task {
            do {
                for try await ev in events {
                    withAnimation(KairoMotion.base) { live.apply(ev) }
                    switch ev {
                    case .toolEnd(_, _, _, _, let e): if let e { model.bump(e.type) }
                    case .deviceAction(let a):
                        if let s = await DeviceActionService.perform(a) { model.show(Toast(title: s, symbol: "timer")) }
                    default: break
                    }
                }
            } catch {
                live.error = error.localizedDescription
            }
            finish()
        }
    }

    private func finish() {
        sending = false
        if let id = live.conversationId { currentId = id }
        var add: [ChatItem] = live.actions.map { ChatItem(id: $0.id, kind: .action(summary: $0.summary, entityType: $0.entity?.type, ok: $0.ok)) }
        add += live.attachments.map { ChatItem(id: $0.id, kind: .attachment($0)) }
        if !live.text.isEmpty { add.append(ChatItem(id: UUID().uuidString, kind: .assistant(live.text))) }
        let err = live.error
        withAnimation(KairoMotion.base) {
            items += add
            live = AgentTurn()
            live.error = err
        }
        model.bump("conversation")
    }
}
