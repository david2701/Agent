import SwiftUI
import KairoCore
import KairoUI

struct FamilyBundle: Equatable {
    var info: FamilyInfo
    var invites: [Invitation]
    var notices: [Notice]
}

/// Familia: miembros, invitaciones, avisos y novedades. Lo personal sigue siendo privado.
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
                    Text("Lo que no marcas como familiar es privado, también para quien administra la familia.")
                        .font(.kFootnote).foregroundStyle(KairoColor.muted).padding(.horizontal, 4)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .readableWidth(720)
            }
            .refreshable { await reload() }
            .onAppear { Task { await markSeen(b.notices) } }
        }
        .navigationTitle("Familia")
        .sheet(isPresented: $inviting) { InviteSheet { created = $0; Task { await reload() } } }
        .sheet(item: $created) { c in CodeSheet(code: c) }
        .alert("Nombre de la familia", isPresented: $renaming) {
            TextField("Nombre", text: $newName)
            Button("Cancelar", role: .cancel) {}
            Button("Guardar") {
                Task {
                    await attempt(model, "Nombre guardado") { _ = try await model.api.renameFamily(newName.trimmingCharacters(in: .whitespacesAndNewlines)) }
                    await model.refreshFamily()
                    await reload()
                }
            }
        }
        .confirmationDialog(removing.map { "¿Quitar a \($0.firstName) de la familia?" } ?? "", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            Button("Quitar de la familia", role: .destructive) {
                guard let m = removing else { return }
                Task {
                    await attempt(model, "\(m.firstName) ya no está en la familia") { try await model.api.removeMember(m.id) }
                    await model.refreshFamily()
                    await reload()
                }
            }
        } message: {
            Text("Lo que había compartido con la familia pasa a ser tuyo. No se puede deshacer.")
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

    // MARK: Miembros

    private func membersCard(_ b: FamilyBundle) -> some View {
        let f = b.info
        let pending = b.invites.filter { $0.usedAt == nil && (KairoDate.parse($0.expiresAt) ?? .distantPast) > .now }
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(f.household.name).font(.kTitle2)
                    Text("\(f.members.count == 1 ? "1 miembro" : "\(f.members.count) miembros")\(f.isAdmin ? " · administras la familia" : "")")
                        .font(.kFootnote).foregroundStyle(KairoColor.muted)
                }
                Spacer()
                if f.isAdmin {
                    Button { newName = f.household.name; renaming = true } label: { Image(systemName: "pencil").frame(width: 36, height: 36) }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Cambiar el nombre de la familia")
                }
            }
            .padding(.bottom, 12)
            ForEach(f.members) { m in
                Divider().overlay(KairoColor.border)
                memberRow(m, info: f)
            }
            if f.members.count == 1 {
                Divider().overlay(KairoColor.border)
                Text("Aún no hay nadie más en la familia.").font(.kSubheadline).foregroundStyle(KairoColor.muted).padding(.vertical, 12)
            }
            ForEach(pending) { inv in
                Divider().overlay(KairoColor.border)
                HStack(spacing: 12) {
                    Image(systemName: "clock").frame(width: 40, height: 40).overlay(Circle().strokeBorder(KairoColor.border)).foregroundStyle(KairoColor.muted)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(inv.kind == "reset" ? "Código de acceso de \(f.member(inv.targetUserId)?.firstName ?? "un miembro")" : (inv.name.isEmpty ? "Invitación sin nombre" : inv.name))
                            .font(.kBody)
                        if let d = KairoDate.parse(inv.expiresAt) {
                            Text("Pendiente · vence \(KairoDate.humanDay(d))").font(.kFootnote).foregroundStyle(KairoColor.muted)
                        }
                    }
                    Spacer()
                    Button("Revocar") {
                        Task {
                            await attempt(model, "Invitación revocada") { try await model.api.revokeInvite(inv.id) }
                            await reload()
                        }
                    }
                    .font(.kairo(14, .medium)).foregroundStyle(KairoColor.accentStrong).buttonStyle(.plain)
                }
                .padding(.vertical, 10)
            }
            if f.isAdmin {
                Button { inviting = true } label: { Label("Invitar a la familia", systemImage: "person.badge.plus") }
                    .buttonStyle(KairoButtonStyle(kind: .primary))
                    .padding(.top, 14)
            }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
    }

    private func memberRow(_ m: Member, info f: FamilyInfo) -> some View {
        HStack(spacing: 12) {
            MemberAvatar(initials: m.initials, size: 40, highlighted: m.id == f.me)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(m.displayName).font(.kBody)
                    if m.id == f.me { Tag("Tú") }
                    if m.isAdmin { Tag("Admin") }
                }
                Text(m.email).font(.kFootnote).foregroundStyle(KairoColor.muted).lineLimit(1)
            }
            Spacer()
            if f.isAdmin, m.id != f.me {
                Menu {
                    Button { Task { await accessCode(m) } } label: { Label("Código de acceso", systemImage: "key") }
                    Button { Task { await setRole(m, m.isAdmin ? "member" : "admin") } } label: {
                        Label(m.isAdmin ? "Quitar como administrador" : "Hacer administrador", systemImage: "person.badge.shield.checkmark")
                    }
                    Divider()
                    Button(role: .destructive) { removing = m } label: { Label("Quitar de la familia", systemImage: "person.badge.minus") }
                } label: {
                    Image(systemName: "ellipsis").frame(width: 36, height: 36).contentShape(Rectangle())
                }
                .accessibilityLabel("Opciones de \(m.displayName)")
            }
        }
        .padding(.vertical, 10)
    }

    private func accessCode(_ m: Member) async {
        do {
            let c = try await model.api.accessCode(memberId: m.id)
            created = CreatedCode(created: c, title: "Código de acceso para \(m.firstName)", household: model.family?.household.name ?? "Kairo")
            await reload()
        } catch {
            model.show(Toast(title: "No se pudo crear el código", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }

    private func setRole(_ m: Member, _ role: String) async {
        await attempt(model, role == "admin" ? "\(m.firstName) ahora administra la familia" : "\(m.firstName) ya no es administrador") {
            _ = try await model.api.setMemberRole(m.id, role: role)
        }
        await model.refreshFamily()
        await reload()
    }

    // MARK: Avisar

    private func messageCard(_ f: FamilyInfo) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Avisar a la familia").font(.kHeadline)
            if f.others.isEmpty {
                Text("Cuando invites a alguien, podrás avisarle al instante.").font(.kSubheadline).foregroundStyle(KairoColor.muted)
            } else {
                if f.others.count > 1 {
                    ScrollView(.horizontal) {
                        HStack(spacing: 8) {
                            FilterChip("Todos", selected: messageTo == nil) { messageTo = nil }
                            ForEach(f.others) { m in FilterChip(m.firstName, selected: messageTo == m.id) { messageTo = m.id } }
                        }
                    }
                    .scrollIndicators(.hidden)
                }
                HStack(spacing: 8) {
                    TextField("La cena está lista", text: $message, axis: .vertical)
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
                    .accessibilityLabel("Enviar aviso")
                }
                let who = messageTo.flatMap { f.member($0)?.firstName } ?? ListFormatter.localizedString(byJoining: f.others.map(\.firstName))
                Text("Le llega a \(who) al instante como notificación.").font(.kFootnote).foregroundStyle(KairoColor.muted)
            }
        }
        .padding(16)
        .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: KairoRadius.card, style: .continuous).strokeBorder(KairoColor.border))
    }

    private func sendMessage(_ f: FamilyInfo) async {
        let t = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        do {
            let n = try await model.api.familyMessage(t, to: messageTo.map { [$0] })
            message = ""
            let who = messageTo.flatMap { f.member($0)?.firstName }
            model.show(Toast(title: who.map { "Aviso enviado a \($0)" } ?? "Aviso enviado a \(n) personas", symbol: "paperplane"))
        } catch {
            model.show(Toast(title: "No se pudo enviar", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }

    // MARK: Novedades

    private func noticesCard(_ notices: [Notice]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Novedades").font(.kHeadline)
                Spacer()
                if notices.contains(where: { $0.readAt == nil }) {
                    Button("Marcar todo leído") {
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
                Text("Sin novedades").font(.kSubheadline).foregroundStyle(KairoColor.muted)
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
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.kCaption).foregroundStyle(KairoColor.muted)
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(KairoColor.surface2, in: Capsule())
    }
}

/// Código recién creado (invitación o acceso) para compartir.
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
                    TextField("Nombre (Sofía)", text: $name).textContentType(.givenName)
                    Toggle("Administrador", isOn: $admin).tint(KairoColor.accent)
                } footer: {
                    Text("Verá y editará lo familiar; lo suyo personal sigue siendo privado. Recibirás un código de un solo uso que vence en 7 días.")
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle("Invitar a la familia")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Crear invitación") {
                        Task {
                            busy = true
                            defer { busy = false }
                            do {
                                let c = try await model.api.createInvite(name: name.trimmingCharacters(in: .whitespacesAndNewlines), role: admin ? "admin" : "member")
                                dismiss()
                                onCreated(CreatedCode(created: c, title: "Invitación para \(name.isEmpty ? "tu familia" : name)", household: model.family?.household.name ?? "Kairo"))
                            } catch {
                                model.show(Toast(title: "No se pudo crear", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
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
            ? "Tu código de acceso a Kairo es \(code.created.code). Ábrelo en \(code.created.link) o, en la app, toca \"Tengo un código de invitación\"."
            : "Te invito a \(code.household) en Kairo. Únete en \(code.created.link) o, en la app, toca \"Tengo un código de invitación\" y escribe \(code.created.code)."
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
                    .accessibilityLabel("Código \(code.created.code.map(String.init).joined(separator: " "))")
                Text(code.created.invitation.kind == "reset" ? "Vence en 24 horas y sirve una sola vez." : "Vence en 7 días y sirve una sola vez.")
                    .font(.kFootnote).foregroundStyle(KairoColor.muted)
                HStack(spacing: 12) {
                    Button {
                        UIPasteboard.general.string = code.created.code
                        withAnimation(KairoMotion.base) { copied = true }
                    } label: { Label(copied ? "Copiado" : "Copiar código", systemImage: copied ? "checkmark" : "doc.on.doc") }
                        .buttonStyle(KairoButtonStyle(kind: .secondary))
                        .sensoryFeedback(.success, trigger: copied)
                    ShareLink(item: shareText) { Label(code.created.invitation.kind == "reset" ? "Compartir código" : "Compartir invitación", systemImage: "square.and.arrow.up") }
                        .buttonStyle(KairoButtonStyle(kind: .primary))
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .kairoBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Listo") { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}
