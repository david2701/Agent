import SwiftUI
import KairoCore
import KairoUI

/// Settings: voice, "Hey Kairo", morning briefing, notifications, watch, devices and account.
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
    @State private var wakeEnabled = false
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
                Button("Sign out", role: .destructive) { Task { await model.signOut() } }
            } footer: {
                Text(verbatim: "Kairo \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · \(model.serverURL)")
                    .font(.kMonoSmall)
            }
        }
        .scrollContentBackground(.hidden)
        .kairoBackground()
        .navigationTitle("Settings")
        .task { await load() }
        .onAppear { wakeEnabled = model.wake.isEnabled }
        .sheet(isPresented: $changingPassword) { PasswordSheet() }
        .alert("Your name", isPresented: $editingName) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                Task {
                    await attempt(model, "Name saved") { model.user = try await model.api.updateProfile(displayName: name) }
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
                model.show(Toast(title: String(localized: "Couldn't save"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
            }
        }
    }

    // MARK: Sections

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
            Button { changingPassword = true } label: { Label("Change password", systemImage: "key") }
        } header: { Text("Account") }
    }

    private var voiceSection: some View {
        Section {
            Picker(selection: Binding(get: { settings.voice }, set: { settings.voice = $0; save(["voice": .string($0)]) })) {
                if voices.isEmpty {
                    Text(settings.voice).tag(settings.voice)
                } else {
                    ForEach(voices) { v in Text(v.name).tag(v.id) }
                }
            } label: { Label("Kairo's voice", systemImage: "waveform") }
            VStack(alignment: .leading) {
                HStack {
                    Label("Speed", systemImage: "speedometer")
                    Spacer()
                    Text(String(format: "%.1f×", settings.voiceSpeed)).font(.kMono).foregroundStyle(KairoColor.muted)
                }
                Slider(value: $settings.voiceSpeed, in: 0.7...1.4, step: 0.1) { editing in
                    if !editing { save(["voiceSpeed": .number(settings.voiceSpeed)]) }
                }
                .tint(KairoColor.accent)
            }
            Button { Task { await model.voice.say(String(localized: "Hi, I'm Kairo. This is how I sound.")) } } label: { Label("Try it", systemImage: "play.circle") }
            Toggle(isOn: Binding(get: { settings.fastMode }, set: { settings.fastMode = $0; save(["fastMode": .bool($0)]) })) {
                Label("Fast replies", systemImage: "hare")
                Text("Turns off the model's extended reasoning to answer sooner.")
            }
        } header: { Text("Voice") } footer: {
            Text("Model: \(settings.chatModel) (fallback \(settings.fallbackModel))")
        }
        .tint(KairoColor.accent)
    }

    private var listening: some View {
        Section {
            Toggle(isOn: Binding(get: { wakeEnabled }, set: { on in
                wakeEnabled = on
                model.wake.isEnabled = on
            })) {
                Label("Hey Kairo", systemImage: "ear")
                Text(verbatim: model.wake.lastError ?? String(localized: "Listens for \"\(KairoLanguage.wakePhraseDisplay)\" on the iPhone, without sending audio until you say it. Works with Kairo open or while it is speaking."))
            }
            Toggle(isOn: Binding(get: { followUp }, set: { followUp = $0; model.voice.followUp = $0 })) {
                Label("Keep the conversation going", systemImage: "arrow.uturn.left.circle")
                Text("If Kairo asks you something, it listens for the answer without you repeating \"\(KairoLanguage.wakePhraseDisplay)\".")
            }
            NavigationLink {
                AssistantHelp()
            } label: { Label("Siri, Action button and Control Center", systemImage: "sparkles") }
        } header: { Text("Listening") }
        .tint(KairoColor.accent)
    }

    private var briefing: some View {
        Section {
            Toggle(isOn: Binding(get: { settings.briefingEnabled }, set: { settings.briefingEnabled = $0; save(["briefingEnabled": .bool($0)]) })) {
                Label("Morning briefing", systemImage: "sun.horizon")
                Text("At \(KairoDate.time(briefingTime)), Kairo summarizes your day: schedule, to-dos and weather.")
            }
            if settings.briefingEnabled {
                DatePicker("Time", selection: $briefingTime, displayedComponents: .hourAndMinute)
                    .onChange(of: briefingTime) { _, t in
                        let c = Calendar.current.dateComponents([.hour, .minute], from: t)
                        let s = String(format: "%02d:%02d", c.hour ?? 7, c.minute ?? 0)
                        if s != settings.briefingTime { save(["briefingTime": .string(s)]) }
                    }
            }
            HStack {
                Label("City for the weather", systemImage: "cloud.sun")
                TextField("Your city", text: $city)
                    .multilineTextAlignment(.trailing)
                    .submitLabel(.done)
                    .onSubmit { save(["homeCity": .string(city)]) }
            }
        } header: { Text("Your day") }
        .tint(KairoColor.accent)
    }

    private var notifications: some View {
        Section {
            Button { Task { await testPush() } } label: { Label("Send test", systemImage: "bell.badge") }
            Button { Task { await NotificationService.requestAuthorization() } } label: { Label("Allow notifications", systemImage: "bell") }
            Button {
                LocationService.shared.requestAlways()
            } label: { Label("Location for place reminders", systemImage: "location") }
            Button { if let u = URL(string: UIApplication.openSettingsURLString) { openURL(u) } } label: { Label("Open iPhone settings", systemImage: "gear") }
        } header: { Text("Notifications") } footer: {
            if let a = apns, a.available {
                Text("Apple push is active on your server (\(a.registeredDevices) devices). Notices arrive even when Kairo is closed.")
            } else {
                Text("Reminders and events are scheduled on the iPhone and ring even without internet. Family notices arrive with Kairo open or when syncing; with Apple push set up on the server (docs/IOS.md) they arrive instantly.")
            }
        }
    }

    private var familySection: some View {
        Section {
            Button { model.perform("section:family") } label: {
                LabeledContent {
                    Text(model.family?.household.name ?? "").foregroundStyle(KairoColor.muted)
                } label: {
                    Label("Family", systemImage: "house")
                    Text(model.hasFamily ? "Members, invitations and notices of your household." : "It's just you. Invite your family to share lists, events and tasks.")
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
                    Text("Open Kairo on your watch and enter this code. It expires in 5 minutes.").font(.kFootnote).foregroundStyle(KairoColor.muted)
                }
                .padding(.vertical, 4)
            }
            Button {
                Task {
                    do { pairCode = try await model.api.pairCode() } catch {
                        model.show(Toast(title: String(localized: "Couldn't generate the code"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
                    }
                }
            } label: { Label(pairCode == nil ? "Pair watch" : "New code", systemImage: "applewatch") }
        } header: { Text("Apple Watch") } footer: {
            Text("With the iPhone nearby, the watch pairs itself when you open Kairo. The code is for watches with cellular or Wear OS.")
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
                            Text(d.current == true ? "This device" : "Active \(KairoDate.relative(seen))").font(.kFootnote).foregroundStyle(KairoColor.muted)
                        }
                    }
                }
                .swipeActions {
                    if d.current != true {
                        Button(role: .destructive) {
                            Task {
                                await attempt(model, "Signed out of \(d.name)") { try await model.api.revokeDevice(d.id) }
                                devices = (try? await model.api.devices()) ?? devices
                            }
                        } label: { Label("Sign out", systemImage: "xmark.circle") }
                    }
                }
            }
        } header: { Text("Signed-in devices") }
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
            model.show(Toast(title: n == 1 ? String(localized: "Test sent: 1 device accepted it") : String(localized: "Test sent: \(n) devices accepted it"), symbol: "bell.badge"))
        } catch {
            model.show(Toast(title: String(localized: "Couldn't send the test"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
        }
    }
}

/// How to use Kairo with Siri, the Action button, Control Center and the lock screen.
private struct AssistantHelp: View {
    var body: some View {
        List {
            Section {
                row("mic.circle", "Siri", "Say \"Hey Siri, talk to Kairo\" or \"Hey Siri, open Kairo on Tasks\".")
                row("button.horizontal.top.press", "Action button", "iPhone Settings > Action Button > Shortcut > Talk to Kairo.")
                row("switch.2", "Control Center", "Add the \"Talk to Kairo\" control from Control Center or the lock screen.")
                row("rectangle.3.group", "Widgets", "Today, Next event, a list to check off and the talk button, also on the lock screen.")
                row("platter.2.filled.iphone", "Dynamic Island", "While Kairo listens, records a meeting or runs a timer.")
            }
        }
        .scrollContentBackground(.hidden)
        .kairoBackground()
        .navigationTitle("Assistant")
    }

    private func row(_ symbol: String, _ title: LocalizedStringKey, _ detail: LocalizedStringKey) -> some View {
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
                SecureField("Current password", text: $current).textContentType(.password)
                SecureField("New (at least 10 characters)", text: $new).textContentType(.newPassword)
                SecureField("Confirm", text: $confirm).textContentType(.newPassword)
            }
            .scrollContentBackground(.hidden)
            .kairoBackground()
            .navigationTitle("Change password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            busy = true
                            defer { busy = false }
                            do {
                                try await model.api.changePassword(current: current, new: new)
                                model.show(Toast(title: String(localized: "Password changed"), symbol: "key"))
                                dismiss()
                            } catch {
                                model.show(Toast(title: String(localized: "Couldn't change it"), message: error.localizedDescription, symbol: "exclamationmark.triangle"))
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
