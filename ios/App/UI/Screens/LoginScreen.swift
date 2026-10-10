import SwiftUI
import KairoCore
import KairoUI

/// Sign in to your server, or join the family with a code.
struct LoginScreen: View {
    @Environment(AppModel.self) private var model
    @State private var server = ""
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var showJoin = false
    @FocusState private var focus: Field?

    private enum Field { case server, email, password }

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                VStack(spacing: 14) {
                    KairoOrb(state: busy ? .thinking : .idle).frame(width: 84)
                    Text("Kairo").font(.kLargeTitle)
                    Text("Your assistant, on your own server.").font(.kCallout).foregroundStyle(KairoColor.muted)
                }
                .padding(.top, 40)

                VStack(spacing: 14) {
                    LabeledField("Server") {
                        TextField("https://kairo.example.com", text: $server)
                            .textContentType(.URL).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focus, equals: .server).submitLabel(.next).onSubmit { focus = .email }
                    }
                    LabeledField("Email") {
                        TextField("you@example.com", text: $email)
                            .textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focus, equals: .email).submitLabel(.next).onSubmit { focus = .password }
                    }
                    LabeledField("Password") {
                        SecureField("Your password", text: $password)
                            .textContentType(.password)
                            .focused($focus, equals: .password).submitLabel(.go).onSubmit { Task { await login() } }
                    }
                    if let error {
                        Label(error, systemImage: "exclamationmark.circle")
                            .font(.kFootnote).foregroundStyle(KairoColor.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .transition(.opacity)
                    }
                    Button {
                        Task { await login() }
                    } label: {
                        HStack { if busy { ProgressView().tint(KairoColor.onAccent) }; Text("Sign in") }
                    }
                    .buttonStyle(KairoButtonStyle(kind: .primary, fullWidth: true))
                    .disabled(busy || email.isEmpty || password.isEmpty)
                }

                Button("I have an invite code") { showJoin = true }
                    .font(.kairo(15, .medium))
                    .foregroundStyle(KairoColor.accentStrong)
            }
            .frame(maxWidth: 420)
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .kairoBackground()
        .onAppear {
            if server.isEmpty { server = model.pendingJoinServer ?? AppConfig.defaultServer }
            if model.pendingJoinCode != nil { showJoin = true }
        }
        .sheet(isPresented: $showJoin) {
            JoinScreen(server: server, initialCode: model.pendingJoinCode ?? "")
                .presentationDetents([.large])
        }
        .animation(KairoMotion.base, value: error)
    }

    private func login() async {
        busy = true
        error = nil
        defer { busy = false }
        do {
            let u = try await model.api.login(server: server, email: email, password: password)
            await model.didSignIn(u)
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Join the family (invitation) or set a new password (access code).
struct JoinScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var server: String
    @State private var code: String
    @State private var preview: InvitePreview?
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""
    @State private var confirm = ""
    @State private var busy = false
    @State private var error: String?

    init(server: String, initialCode: String) {
        _server = State(initialValue: server)
        _code = State(initialValue: initialCode)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let p = preview {
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(p.kind == "reset" ? "New password" : "You're invited to \(p.householdName)").font(.kTitle3)
                            Text(p.kind == "reset" ? "Set a new password for \(p.email ?? String(localized: "your account")). Your other sessions will be signed out."
                                 : "Create your account. You'll see what the family shares and have your own personal space.")
                                .font(.kSubheadline).foregroundStyle(KairoColor.muted)
                        }
                        .padding(.vertical, 4)
                    }
                    if p.kind != "reset" {
                        Section("Your details") {
                            TextField("Name", text: $name).textContentType(.name)
                            TextField("Email", text: $email).textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                    }
                    Section {
                        SecureField("Password (at least 10 characters)", text: $password).textContentType(.newPassword)
                        SecureField("Confirm password", text: $confirm).textContentType(.newPassword)
                    }
                } else {
                    Section {
                        TextField("Server", text: $server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        TextField("Code XXXX-XXXX", text: $code)
                            .font(.kairoMono(18, .medium))
                            .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    } footer: {
                        Text("Whoever manages your family creates it in Kairo, under Family > Invite someone.")
                    }
                }
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.circle").foregroundStyle(KairoColor.danger) }
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle(preview == nil ? "Join with a code" : "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(preview == nil ? "Continue" : (preview?.kind == "reset" ? "Save" : "Join")) {
                        Task { preview == nil ? await check() : await join() }
                    }
                    .disabled(busy || (preview == nil ? code.filter(\.isLetter).isEmpty : password.count < 10 || password != confirm))
                }
            }
            .task { if !code.isEmpty { await check() } }
        }
    }

    private func check() async {
        busy = true
        error = nil
        defer { busy = false }
        // Accept a pasted invite link (canonical /join?code= or legacy /unirse?codigo=) as well as a bare code
        if let invite = InviteLink.parse(code) {
            code = invite.code
            if let s = invite.server { server = s }
        }
        do {
            let p = try await model.api.invitePreview(server: server, code: code)
            withAnimation(KairoMotion.base) { preview = p }
            if name.isEmpty { name = p.name ?? "" }
        } catch {
            self.error = (error as? APIError)?.status == 404 ? String(localized: "That code isn't valid or has expired") : error.localizedDescription
        }
    }

    private func join() async {
        busy = true
        error = nil
        defer { busy = false }
        do {
            let isReset = preview?.kind == "reset"
            let u = try await model.api.join(server: server, code: code, email: isReset ? nil : email, password: password, displayName: isReset ? nil : name)
            model.pendingJoinCode = nil
            dismiss()
            await model.didSignIn(u)
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Labeled field, in Kairo's style.
struct LabeledField<Content: View>: View {
    let label: LocalizedStringKey
    @ViewBuilder var content: Content

    init(_ label: LocalizedStringKey, @ViewBuilder content: () -> Content) {
        self.label = label
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.kairo(13, .medium)).foregroundStyle(KairoColor.muted)
            content
                .font(.kBody)
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .background(KairoColor.surface, in: RoundedRectangle(cornerRadius: KairoRadius.control, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: KairoRadius.control, style: .continuous).strokeBorder(KairoColor.border))
        }
    }
}
