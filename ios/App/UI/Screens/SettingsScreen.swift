import SwiftUI
import KairoCore
import KairoUI

/// Ajustes: voz, "Oye Kairo", resumen de la mañana, avisos, reloj, dispositivos y cuenta.
struct SettingsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var settings = UserSettings()
    @State private var loaded = false
    @State private var voices: [VoiceOption] = []
    @State private var devices: [Device] = []
    @State private var apns: ApnsStatus?
    @State private var briefingTime = Date.now
    @State private var city = ""
    @State private var pairCode: String?
    @State private var changingPassword = false
    @State private var editingName = false
    @State private var name = ""
    @AppStorage("kairo.wake", store: AppConfig.sharedDefaults) private var wakeEnabled = false
    @AppStorage("kairo.followUp", store: AppConfig.sharedDefaults) private var followUp = true

    var body: some View {
        Form {
            account
            voiceSection
            listening
            briefing
            notifications
            familySection
            watchSection
            devicesSection
            Section {
                Button("Cerrar sesión", role: .destructive) { Task { await model.signOut() } }
            } footer: {
                Text("Kairo \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · \(model.serverURL)")
                    .font(.kMonoSmall)
            }
        }
        .scrollContentBackground(.hidden)
        .kairoBackground()
        .navigationTitle("Ajustes")
        .task { await load() }
        .sheet(isPresented: $changingPassword) { PasswordSheet() }
        .alert("Tu nombre", isPresented: $editingName) {
            TextField("Nombre", text: $name)
            Button("Cancelar", role: .cancel) {}
            Button("Guardar") {
                Task {
                    await attempt(model, "Nombre guardado") { model.user = try await model.api.updateProfile(displayName: name) }
                }
            }
        }
    }

    private func load() async {
        if let s = try? await model.api.settings() {
            settings = s.settings
            city = s.settings.homeCity ?? ""
            let parts = s.settings.briefingTime.split(separator: ":").compactMap { Int($0) }
            if parts.count == 2 { briefingTime = Calendar.current.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: .now) ?? .now }
        }
        loaded = true
        async let v = try? model.api.voices()
        async let d = try? model.api.devices()
        async let a = try? model.api.apnsStatus()
        voices = await v ?? []
        devices = await d ?? []
        apns = await a
    }

    private func save(_ patch: JSONObject) {
        Task {
            do {
                let r = try await model.api.updateSettings(patch)
                settings = r.settings
                model.settings = r.settings
                model.wake.phrase = r.settings.wakeWord
            } catch {
                model.show(Toast(title: "No se pudo guardar", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
            }
        }
    }

    // MARK: Secciones

    private var account: some View {
        Section {
            if let u = model.user {
                Button { name = u.displayName; editingName = true } label: {
                    HStack(spacing: 12) {
                        MemberAvatar(initials: String(u.displayName.prefix(1)).uppercased(), size: 44, highlighted: true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(u.displayName).font(.kHeadline).foregroundStyle(KairoColor.text)
                            Text(u.email).font(.kFootnote).foregroundStyle(KairoColor.muted)
                        }
                        Spacer()
                        Image(systemName: "pencil").foregroundStyle(KairoColor.faint)
                    }
                }
                .buttonStyle(.plain)
            }
            Button { changingPassword = true } label: { Label("Cambiar contraseña", systemImage: "key") }
        } header: { Text("Cuenta") }
    }

    private var voiceSection: some View {
        Section {
            Picker(selection: Binding(get: { settings.voice }, set: { settings.voice = $0; save(["voice": .string($0)]) })) {
                ForEach(voices.isEmpty ? [VoiceOption(id: settings.voice, name: settings.voice, description: "")] : voices) { v in
                    Text(v.name).tag(v.id)
                }
            } label: { Label("Voz de Kairo", systemImage: "waveform") }
            VStack(alignment: .leading) {
                HStack {
                    Label("Velocidad", systemImage: "speedometer")
                    Spacer()
                    Text(String(format: "%.1f×", settings.voiceSpeed)).font(.kMono).foregroundStyle(KairoColor.muted)
                }
                Slider(value: $settings.voiceSpeed, in: 0.7...1.4, step: 0.1) { editing in
                    if !editing { save(["voiceSpeed": .number(settings.voiceSpeed)]) }
                }
                .tint(KairoColor.accent)
            }
            Button { Task { await model.voice.say("Hola, soy Kairo. Así sueno.") } } label: { Label("Probar", systemImage: "play.circle") }
            Toggle(isOn: Binding(get: { settings.fastMode }, set: { settings.fastMode = $0; save(["fastMode": .bool($0)]) })) {
                Label("Respuestas rápidas", systemImage: "hare")
                Text("Desactiva el razonamiento extendido del modelo para responder antes.")
            }
        } header: { Text("Voz") } footer: {
            Text("Modelo: \(settings.chatModel) (respaldo \(settings.fallbackModel))")
        }
        .tint(KairoColor.accent)
    }

    private var listening: some View {
        Section {
            Toggle(isOn: Binding(get: { wakeEnabled }, set: { on in
                wakeEnabled = on
                Task { if on { await model.wake.start() } else { model.wake.stop() } }
            })) {
                Label("Oye Kairo", systemImage: "ear")
                Text(model.wake.lastError ?? "Escucha \"\(settings.wakeWord)\" dentro del iPhone, sin enviar audio hasta que lo dices. Funciona con Kairo abierto o mientras habla.")
            }
            Toggle(isOn: Binding(get: { followUp }, set: { followUp = $0; model.voice.followUp = $0 })) {
                Label("Seguir la conversación", systemImage: "arrow.uturn.left.circle")
                Text("Si Kairo te pregunta algo, escucha la respuesta sin repetir \"Oye Kairo\".")
            }
            NavigationLink {
                AssistantHelp()
            } label: { Label("Siri, botón de Acción y Centro de control", systemImage: "sparkles") }
        } header: { Text("Escucha") }
        .tint(KairoColor.accent)
    }

    private var briefing: some View {
        Section {
            Toggle(isOn: Binding(get: { settings.briefingEnabled }, set: { settings.briefingEnabled = $0; save(["briefingEnabled": .bool($0)]) })) {
                Label("Resumen de la mañana", systemImage: "sun.horizon")
                Text("A las \(settings.briefingTime), Kairo te resume el día: agenda, pendientes y clima.")
            }
            if settings.briefingEnabled {
                DatePicker("Hora", selection: $briefingTime, displayedComponents: .hourAndMinute)
                    .onChange(of: briefingTime) { _, t in
                        let c = Calendar.current.dateComponents([.hour, .minute], from: t)
                        let s = String(format: "%02d:%02d", c.hour ?? 7, c.minute ?? 0)
                        if s != settings.briefingTime { save(["briefingTime": .string(s)]) }
                    }
            }
            HStack {
                Label("Ciudad para el clima", systemImage: "cloud.sun")
                TextField("Tu ciudad", text: $city)
                    .multilineTextAlignment(.trailing)
                    .submitLabel(.done)
                    .onSubmit { save(["homeCity": .string(city)]) }
            }
        } header: { Text("Tu día") }
        .tint(KairoColor.accent)
    }

    private var notifications: some View {
        Section {
            Button { Task { await testPush() } } label: { Label("Enviar prueba", systemImage: "bell.badge") }
            Button { Task { await NotificationService.requestAuthorization() } } label: { Label("Permitir notificaciones", systemImage: "bell") }
            Button {
                LocationService.shared.requestAlways()
            } label: { Label("Ubicación para recordatorios por lugar", systemImage: "location") }
            Button { if let u = URL(string: UIApplication.openSettingsURLString) { openURL(u) } } label: { Label("Abrir ajustes del iPhone", systemImage: "gear") }
        } header: { Text("Avisos") } footer: {
            if let a = apns, a.available {
                Text("Push de Apple activo en tu servidor (\(a.registeredDevices) dispositivos). Los avisos llegan aunque Kairo esté cerrado.")
            } else {
                Text("Los recordatorios y eventos se programan en el iPhone y suenan aunque no haya internet. Los avisos de la familia llegan con Kairo abierto o al sincronizar; con el push de Apple configurado en el servidor (docs/IOS.md) llegan al instante.")
            }
        }
    }

    private var familySection: some View {
        Section {
            Button { model.perform("section:family") } label: {
                LabeledContent {
                    Text(model.family?.household.name ?? "").foregroundStyle(KairoColor.muted)
                } label: {
                    Label("Familia", systemImage: "house")
                    Text(model.hasFamily ? "Miembros, invitaciones y avisos de tu hogar." : "Solo estás tú. Invita a tu familia para compartir listas, eventos y tareas.")
                }
            }
            .buttonStyle(.plain)
        }
    }

    private var watchSection: some View {
        Section {
            if let pairCode {
                VStack(alignment: .leading, spacing: 8) {
                    Text(pairCode).font(.kairoMono(30, .medium)).kerning(4).selectableText()
                    Text("Abre Kairo en tu reloj y escribe este código. Vence en 5 minutos.").font(.kFootnote).foregroundStyle(KairoColor.muted)
                }
                .padding(.vertical, 4)
            }
            Button {
                Task {
                    do { pairCode = try await model.api.pairCode() } catch {
                        model.show(Toast(title: "No se pudo generar el código", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
                    }
                }
            } label: { Label(pairCode == nil ? "Vincular reloj" : "Nuevo código", systemImage: "applewatch") }
        } header: { Text("Apple Watch") } footer: {
            Text("Con el iPhone cerca, el reloj se vincula solo al abrir Kairo. El código sirve para relojes con datos móviles o Wear OS.")
        }
    }

    private var devicesSection: some View {
        Section {
            ForEach(devices) { d in
                HStack {
                    Image(systemName: deviceSymbol(d.platform)).frame(width: 24).foregroundStyle(KairoColor.muted)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(d.name).font(.kBody)
                        if let seen = KairoDate.parse(d.lastSeenAt) {
                            Text(d.current == true ? "Este dispositivo" : "Activo \(KairoDate.relative(seen))").font(.kFootnote).foregroundStyle(KairoColor.muted)
                        }
                    }
                }
                .swipeActions {
                    if d.current != true {
                        Button(role: .destructive) {
                            Task {
                                await attempt(model, "Sesión cerrada en \(d.name)") { try await model.api.revokeDevice(d.id) }
                                devices = (try? await model.api.devices()) ?? devices
                            }
                        } label: { Label("Cerrar sesión", systemImage: "xmark.circle") }
                    }
                }
            }
        } header: { Text("Dispositivos con sesión") }
    }

    private func deviceSymbol(_ p: String) -> String {
        switch p {
        case "ios": "iphone"
        case "watch", "wear": "applewatch"
        case "android": "candybarphone"
        case "web": "macbook"
        default: "desktopcomputer"
        }
    }

    private func testPush() async {
        do {
            let n = try await model.api.testPush()
            model.show(Toast(title: n == 1 ? "Prueba enviada: la aceptó 1 dispositivo" : "Prueba enviada: la aceptaron \(n) dispositivos", symbol: "bell.badge"))
        } catch {
            model.show(Toast(title: "No se pudo enviar la prueba", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}

/// Cómo usar Kairo con Siri, el botón de Acción, el Centro de control y la pantalla bloqueada.
private struct AssistantHelp: View {
    var body: some View {
        List {
            Section {
                row("mic.circle", "Siri", "Di \"Oye Siri, habla con Kairo\" o \"Oye Siri, abre Kairo en Tareas\".")
                row("button.horizontal.top.press", "Botón de Acción", "Ajustes del iPhone > Botón de Acción > Atajo > Hablar con Kairo.")
                row("switch.2", "Centro de control", "Agrega el control \"Hablar con Kairo\" desde el Centro de control o la pantalla bloqueada.")
                row("rectangle.3.group", "Widgets", "Hoy, Siguiente evento, una lista para marcar y el botón para hablar, también en la pantalla bloqueada.")
                row("platter.2.filled.iphone", "Isla Dinámica", "Mientras Kairo te escucha, graba una reunión o corre un temporizador.")
            }
        }
        .scrollContentBackground(.hidden)
        .kairoBackground()
        .navigationTitle("Asistente")
    }

    private func row(_ symbol: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol).font(.system(size: 20)).foregroundStyle(KairoColor.accent).frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.kHeadline)
                Text(detail).font(.kSubheadline).foregroundStyle(KairoColor.muted)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct PasswordSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var new = ""
    @State private var confirm = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                SecureField("Contraseña actual", text: $current).textContentType(.password)
                SecureField("Nueva (mínimo 10 caracteres)", text: $new).textContentType(.newPassword)
                SecureField("Confirmar", text: $confirm).textContentType(.newPassword)
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle("Cambiar contraseña")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        Task {
                            busy = true
                            defer { busy = false }
                            do {
                                try await model.api.changePassword(current: current, new: new)
                                model.show(Toast(title: "Contraseña cambiada", symbol: "key"))
                                dismiss()
                            } catch {
                                model.show(Toast(title: "No se pudo cambiar", message: error.localizedDescription, symbol: "exclamationmark.triangle"))
                            }
                        }
                    }
                    .disabled(busy || current.isEmpty || new.count < 10 || new != confirm)
                }
            }
        }
        .presentationDetents([.medium])
    }
}
