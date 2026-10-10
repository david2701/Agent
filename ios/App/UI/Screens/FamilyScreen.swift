import SwiftUI
import KairoCore
import KairoUI

struct FamilyBundle: Equatable {
    var info: FamilyInfo
    var invites: [Invitation]
    var notices: [Notice]
}

/// Family: members, invitations, notices and updates. Personal things stay private.
struct FamilyScreen: View {
    @Environment(AppModel.self) private var model
    @State private var state: Loadable<FamilyBundle> = .loading
    @State private var inviting = false
    @State private var created: CreatedCode?
    @State private var renaming = false
    @State private var newName = ""
    @State private var message = ""
    @State private var messageTo: String?
    @State private var removing: Member?

    var body: some View {
        LoadableView(state: $state, version: model.version("family") + model.version("notice"), load: load) { b in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    membersCard(b)
                    messageCard(b.info)
                    noticesCard(b.notices)
                    Text("What you don't mark as family is private, even from whoever manages the family.")
                        .font(.kFootnote).foregroundStyle(KairoColor.muted).padding(.horizontal, 4)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .readableWidth(720)
            }
            .refreshable { await reload() }
            .onAppear { Task { await markSeen(b.notices) } }
        }
        .navigationTitle("Family")
        .sheet(isPresented: $inviting) { InviteSheet { created = $0; Task { await reload() } } }
        .sheet(item: $created) { c in CodeSheet(code: c) }
        .alert("Family name", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                Task {
                    await attempt(model, "Name saved") { _ = try await model.api.renameFamily(newName.trimmingCharacters(in: .whitespacesAndNewlines)) }
                    await model.refreshFamily()
                    await reload()
                }
            }
        }
        .confirmationDialog(removing.map { String(localized: "Remove \($0.firstName) from the family?") } ?? "", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            Button("Remove from family", role: .destructive) {
                guard let m = removing else { return }
                Task {
                    await attempt(model, "\(m.firstName) is no longer in the family") { try await model.api.removeMember(m.id) }
                    await model.refreshFamily()
                    await reload()
                }
            }
        } message: {
            Text("What they shared with the family becomes yours. This can't be undone.")
        }
        .kairoBackground()
    }

    private func load() async throws -> FamilyBundle {
        let info = try await model.api.family()
        async let inv = info.isAdmin ? ((try? await model.api.invites()) ?? []) : []
        async let n = try? model.api.notices(limit: 30)
        return FamilyBundle(info: info, invites: await inv, notices: await n?.notices ?? [])
    }

    private func reload() async {
        if let b = try? await load() { withAnimation(KairoMotion.base) { state = .loaded(b) } }
    }

    private func markSeen(_ notices: [Notice]) async {
        guard notices.contains(where: { $0.readAt == nil }) else { return }
        try? await Task.sleep(for: .seconds(2))
        try? await model.api.markNoticesRead()
        model.unreadNotices = 0
    }

    // MARK: Members

    private func membersCard(_ b: FamilyBundle) -> some View {
        let f = b.info
        let pending = b.invites.filter { $0.usedAt == nil && (KairoDate.parse($0.expiresAt) ?? .distantPast) > .now }
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(f.household.name).font(.kTitle2)
                    Text(verbatim: membersSummary(f))
                        .font(.kFootnote).foregroundStyle(KairoColor.muted)
                }
                Spacer()
                if f.isAdmin {
                    Button { newName = f.household.name; renaming = true } label: { Image(systemName: "pencil").frame(width: 36, height: 36) }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Rename the family")
                }
            }
            .padding(.bottom, 12)
            ForEach(f.members) { m in
                Divider().overlay(KairoColor.border)
                memberRow(m, info: f)
            }
            if f.members.count == 1 {
                Divider().overlay(KairoColor.border)
                Text("No one else is in the family yet.").font(.kSubheadline).foregroundStyle(KairoColor.muted).padding(.vertical, 12)
            }
            ForEach(pending) { inv in
                Divider().overlay(KairoColor.border)
                HStack(spacing: 12) {
                    Image(systemName: "clock").frame(width: 40, height: 40).overlay(Circle().strokeBorder(KairoColor.border)).foregroundStyle(KairoColor.muted)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: inviteTitle(inv, f))
                            .font(.kBody)
                        if let d = KairoDate.parse(inv.expiresAt) {
                            Text("Pending · expires \(KairoDate.humanDay(d))").font(.kFootnote).foregroundStyle(KairoColor.muted)
                        }
                    }
                    Spacer()
                    Button("Revoke") {
                        Task {
                            await attempt(model, "Invitation revoked") { try await model.api.revokeInvite(inv.id) }
                            await reload()
                        }
                    }
                    .font(.kairo(14, .medium)).foregroundStyle(KairoColor.accentStrong).buttonStyle(.plain)
                }
                .padding(.vertical, 10)
            }
            if f.isAdmin {
                Button { inviting = true } label: { Label("Invite someone", systemImage: "person.badge.plus") }
                    .buttonStyle(KairoButtonStyle(kind: .primary))
                    .padding(.top, 14)
            }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
    }

    private func membersSummary(_ f: FamilyInfo) -> String {
        let count = f.members.count == 1 ? String(localized: "1 member") : String(localized: "\(f.members.count) members")
        return f.isAdmin ? count + " · " + String(localized: "you manage the family") : count
    }

    private func inviteTitle(_ inv: Invitation, _ f: FamilyInfo) -> String {
        if inv.kind == "reset" {
            return String(localized: "\(f.member(inv.targetUserId)?.firstName ?? String(localized: "a member"))'s access code")
        }
        return inv.name.isEmpty ? String(localized: "Unnamed invitation") : inv.name
    }

    private func memberRow(_ m: Member, info f: FamilyInfo) -> some View {
        HStack(spacing: 12) {
            MemberAvatar(initials: m.initials, size: 40, highlighted: m.id == f.me)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(m.displayName).font(.kBody)
                    if m.id == f.me { Tag("You") }
                    if m.isAdmin { Tag("Admin") }
                }
                Text(m.email).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1)
            }
            Spacer()
            if f.isAdmin, m.id != f.me {
                Menu {
                    Button { Task { await accessCode(m) } } label: { Label("Access code", systemImage: "key") }
                    Button { Task { await setRole(m, m.isAdmin ? "member" : "admin") } } label: {
                        Label(m.isAdmin ? "Remove as admin" : "Make admin", systemImage: "person.badge.shield.checkmark")
                    }
                    Divider()
                    Button(role: .destructive) { removing = m } label: { Label("Remove from family", systemImage: "person.badge.minus") }
                } label: {
                    Image(systemName: "ellipsis").frame(width: 36, height: 36).contentShape(Rectangle())
                }
                .accessibilityLabel("Options for \(m.displayName)")
            }
        }
        .padding(.vertical, 10)
    }

    private func accessCode(_ m: Member) async {
        do {
            let c = try await model.api.accessCode(memberId: m.id)
            created = CreatedCode(created: c, title: String(localized: "Access code for \(m.firstName)"), household: model.family?.household.name ?? "Kairo")
            await reload()
        } catch {
            model.show(Toast(title: String(localized: "Couldn't create the code"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }

    private func setRole(_ m: Member, _ role: String) async {
        await attempt(model, role == "admin" ? "\(m.firstName) now manages the family" : "\(m.firstName) is no longer an admin") {
            _ = try await model.api.setMemberRole(m.id, role: role)
        }
        await model.refreshFamily()
        await reload()
    }

    // MARK: Notify

    private func messageCard(_ f: FamilyInfo) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Notify the family").font(.kHeadline)
            if f.others.isEmpty {
                Text("When you invite someone, you'll be able to notify them instantly.").font(.kSubheadline).foregroundStyle(KairoColor.muted)
            } else {
                if f.others.count > 1 {
                    ScrollView(.horizontal) {
                        HStack(spacing: 8) {
                            FilterChip("Everyone", selected: messageTo == nil) { messageTo = nil }
                            ForEach(f.others) { m in FilterChip(m.firstName, selected: messageTo == m.id) { messageTo = m.id } }
                        }
                    }
                    .scrollIndicators(.hidden)
                }
                HStack(spacing: 8) {
                    TextField("Dinner is ready", text: $message, axis: .vertical)
                        .lineLimit(1...4)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(KairoColor.surface2, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    Button { Task { await sendMessage(f) } } label: {
                        Image(systemName: "paperplane.fill").font(.system(size: 16, weight: .semibold)).frame(width: 46, height: 46)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(message.trimmingCharacters(in: .whitespaces).isEmpty ? KairoColor.faint : KairoColor.onAccent)
                    .background(message.trimmingCharacters(in: .whitespaces).isEmpty ? KairoColor.surface2 : KairoColor.accent, in: Circle())
                    .disabled(message.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityLabel("Send notice")
                }
                let who = messageTo.flatMap { f.member($0)?.firstName } ?? joinedNames(f.others.map(\.firstName))
                Text("It reaches \(who) instantly as a notification.").font(.kFootnote).foregroundStyle(KairoColor.muted)
            }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
    }

    /// "Alex, Sam and Mia" in the app language.
    private func joinedNames(_ names: [String]) -> String {
        let f = ListFormatter()
        f.locale = KairoLanguage.locale
        return f.string(from: names) ?? names.joined(separator: ", ")
    }

    private func sendMessage(_ f: FamilyInfo) async {
        let t = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        do {
            let n = try await model.api.familyMessage(t, to: messageTo.map { [$0] })
            message = ""
            let who = messageTo.flatMap { f.member($0)?.firstName }
            model.show(Toast(title: who.map { String(localized: "Notice sent to \($0)") } ?? String(localized: "Notice sent to \(n) people"), symbol: "paperplane"))
        } catch {
            model.show(Toast(title: String(localized: "Couldn't send"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }

    // MARK: Updates

    private func noticesCard(_ notices: [Notice]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Updates").font(.kHeadline)
                Spacer()
                if notices.contains(where: { $0.readAt == nil }) {
                    Button("Mark all as read") {
                        Task {
                            try? await model.api.markNoticesRead()
                            model.unreadNotices = 0
                            await reload()
                        }
                    }
                    .font(.kairo(14, .medium)).foregroundStyle(KairoColor.accentStrong)
                }
            }
            if notices.isEmpty {
                Text("No updates").font(.kSubheadline).foregroundStyle(KairoColor.muted)
            }
            ForEach(notices) { n in
                Button {
                    if let s = AppSection.from(entity: n.entityType), s != .family { model.perform("section:\(s.rawValue)") }
                } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: KairoSymbol.notice(n.kind))
                            .font(.system(size: 14, weight: .medium))
                            .frame(width: 32, height: 32)
                            .background(KairoColor.surface2, in: Circle())
                            .foregroundStyle(n.readAt == nil ? KairoColor.accent : KairoColor.muted)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(n.title).font(.kairo(15, n.readAt == nil ? .semibold : .regular)).foregroundStyle(KairoColor.text)
                            if !n.body.isEmpty { Text(n.body).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(3) }
                        }
                        Spacer(minLength: 0)
                        if let d = KairoDate.parse(n.createdAt) { Text(KairoDate.relative(d)).font(.kMonoSmall).foregroundStyle(KairoColor.faint) }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
    }
}

private struct Tag: View {
    var text: LocalizedStringKey
    init(_ text: LocalizedStringKey) { self.text = text }
    var body: some View {
        Text(text).font(.kCaption).foregroundStyle(KairoColor.muted)
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(KairoColor.surface2, in: Capsule())
    }
}

/// Freshly created code (invitation or access) to share.
struct CreatedCode: Identifiable {
    var created: InviteCreated
    var title: String
    var household: String
    var id: String { created.invitation.id }
}

private struct InviteSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onCreated: (CreatedCode) -> Void
    @State private var name = ""
    @State private var admin = false
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name (Sam)", text: $name).textContentType(.givenName)
                    Toggle("Administrator", isOn: $admin).tint(KairoColor.accent)
                } footer: {
                    Text("They'll see and edit what's shared with the family; their personal things stay private. You'll get a one-time code that expires in 7 days.")
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle("Invite someone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create invitation") {
                        Task {
                            busy = true
                            defer { busy = false }
                            do {
                                let c = try await model.api.createInvite(name: name.trimmingCharacters(in: .whitespacesAndNewlines), role: admin ? "admin" : "member")
                                dismiss()
                                onCreated(CreatedCode(created: c, title: String(localized: "Invitation for \(name.isEmpty ? String(localized: "your family") : name)"), household: model.family?.household.name ?? "Kairo"))
                            } catch {
                                model.show(Toast(title: String(localized: "Couldn't create"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
                            }
                        }
                    }
                    .disabled(busy)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct CodeSheet: View {
    @Environment(\.dismiss) private var dismiss
    var code: CreatedCode
    @State private var copied = false

    private var shareText: String {
        code.created.invitation.kind == "reset"
            ? String(localized: "Your Kairo access code is \(code.created.code). Open \(code.created.link) or, in the app, tap \"I have an invite code\".")
            : String(localized: "I'm inviting you to \(code.household) on Kairo. Join at \(code.created.link) or, in the app, tap \"I have an invite code\" and enter \(code.created.code).")
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(code.title).font(.kTitle3).multilineTextAlignment(.center)
                Text(code.created.code)
                    .font(.kairoMono(34, .medium))
                    .kerning(3)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 16)
                    .background(KairoColor.surface2, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
                    .selectableText()
                    .accessibilityLabel("Code \(code.created.code.map(String.init).joined(separator: " "))")
                Text(code.created.invitation.kind == "reset" ? "Expires in 24 hours and works only once." : "Expires in 7 days and works only once.")
                    .font(.kFootnote).foregroundStyle(KairoColor.muted)
                HStack(spacing: 12) {
                    Button {
                        UIPasteboard.general.string = code.created.code
                        withAnimation(KairoMotion.base) { copied = true }
                    } label: { Label(copied ? "Copied" : "Copy code", systemImage: copied ? "checkmark" : "doc.on.doc") }
                        .buttonStyle(KairoButtonStyle(kind: .secondary))
                        .sensoryFeedback(.success, trigger: copied)
                    ShareLink(item: shareText) { Label(code.created.invitation.kind == "reset" ? "Share code" : "Share invitation", systemImage: "square.and.arrow.up") }
                        .buttonStyle(KairoButtonStyle(kind: .primary))
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .kairoBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}
