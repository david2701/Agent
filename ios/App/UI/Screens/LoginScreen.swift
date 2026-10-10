import SwiftUI
import KairoCore
import KairoUI

/// Inicio de sesión con tu servidor, o unirse a la familia con un código.
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
                    Text("Tu asistente, en tu propio servidor.").font(.kCallout).foregroundStyle(KairoColor.muted)
                }
                .padding(.top, 40)

                VStack(spacing: 14) {
                    LabeledField("Servidor") {
                        TextField("https://kairo.tudominio.com", text: $server)
                            .textContentType(.URL).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focus, equals: .server).submitLabel(.next).onSubmit { focus = .email }
                    }
                    LabeledField("Correo") {
                        TextField("tu@correo.com", text: $email)
                            .textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .focused($focus, equals: .email).submitLabel(.next).onSubmit { focus = .password }
                    }
                    LabeledField("Contraseña") {
                        SecureField("Tu contraseña", text: $password)
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
                        HStack { if busy { ProgressView().tint(KairoColor.onAccent) }; Text("Entrar") }
                    }
                    .buttonStyle(KairoButtonStyle(kind: .primary, fullWidth: true))
                    .disabled(busy || email.isEmpty || password.isEmpty)
                }

                Button("Tengo un código de invitación") { showJoin = true }
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

/// Unirse a la familia (invitación) o definir una contraseña nueva (código de acceso).
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
                            Text(p.kind == "reset" ? "Nueva contraseña" : "Te invitaron a \(p.householdName)").font(.kTitle3)
                            Text(p.kind == "reset" ? "Define una contraseña nueva para \(p.email ?? "tu cuenta"). Se cerrarán tus otras sesiones."
                                 : "Crea tu cuenta. Verás lo que la familia comparte y tendrás tu propio espacio personal.")
                                .font(.kSubheadline).foregroundStyle(KairoColor.muted)
                        }
                        .padding(.vertical, 4)
                    }
                    if p.kind != "reset" {
                        Section("Tus datos") {
                            TextField("Nombre", text: $name).textContentType(.name)
                            TextField("Correo", text: $email).textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                    }
                    Section {
                        SecureField("Contraseña (mínimo 10 caracteres)", text: $password).textContentType(.newPassword)
                        SecureField("Confirmar contraseña", text: $confirm).textContentType(.newPassword)
                    }
                } else {
                    Section {
                        TextField("Servidor", text: $server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        TextField("Código XXXX-XXXX", text: $code)
                            .font(.kairoMono(18, .medium))
                            .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    } footer: {
                        Text("Quien administra tu familia lo genera en Kairo, en Familia > Invitar a alguien.")
                    }
                }
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.circle").foregroundStyle(KairoColor.danger) }
                }
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle(preview == nil ? "Unirse con un código" : "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(preview == nil ? "Continuar" : (preview?.kind == "reset" ? "Guardar" : "Unirme")) {
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
        do {
            let p = try await model.api.invitePreview(server: server, code: code)
            withAnimation(KairoMotion.base) { preview = p }
            if name.isEmpty { name = p.name ?? "" }
        } catch {
            self.error = (error as? APIError)?.status == 404 ? "El código no es válido o ya venció" : error.localizedDescription
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

/// Campo con etiqueta, al estilo de Kairo.
struct LabeledField<Content: View>: View {
    let label: String
    @ViewBuilder var content: Content

    init(_ label: String, @ViewBuilder content: () -> Content) {
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
