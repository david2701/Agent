import SwiftUI
import BackgroundTasks
import KairoCore
import KairoUI
import WidgetKit

/// Secciones de la app (pestañas en iPhone, barra lateral en iPad y en la pantalla interior del iPhone Duo).
enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case today, chat, tasks, calendar, notes, reminders, lists, memory, recordings, activity, family, settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .today: "Hoy"
        case .chat: "Conversación"
        case .tasks: "Tareas"
        case .calendar: "Calendario"
        case .notes: "Notas"
        case .reminders: "Recordatorios"
        case .lists: "Listas"
        case .memory: "Memoria"
        case .recordings: "Grabaciones"
        case .activity: "Actividad"
        case .family: "Familia"
        case .settings: "Ajustes"
        }
    }

    var symbol: String {
        switch self {
        case .today: "sun.horizon"
        case .chat: "bubble.left.and.text.bubble.right"
        case .tasks: "checkmark.circle"
        case .calendar: "calendar"
        case .notes: "note.text"
        case .reminders: "bell"
        case .lists: "checklist"
        case .memory: "brain"
        case .recordings: "waveform"
        case .activity: "clock.arrow.circlepath"
        case .family: "house"
        case .settings: "gearshape"
        }
    }

    /// Las cuatro principales van en la barra de pestañas del iPhone; el resto en "Más".
    static let primary: [AppSection] = [.today, .chat, .tasks, .calendar]
    static let secondary: [AppSection] = [.notes, .reminders, .lists, .memory, .recordings, .activity, .family, .settings]
}

struct Toast: Identifiable, Equatable {
    let id = UUID()
    var title: String
    var message: String = ""
    var symbol: String = "checkmark.circle"
    var destination: AppSection?
}

/// Estado global de la app: sesión, familia, avisos, conexión en vivo y navegación.
@MainActor @Observable
final class AppModel {
    static let shared = AppModel()

    enum Phase { case loading, signedOut, ready }

    let api: KairoAPI
    let voice: VoiceEngine
    let wake: WakeWordService
    let recorder: MeetingRecorder

    var phase: Phase = .loading
    var user: User?
    var serverURL: String = AppConfig.defaultServer
    var family: FamilyInfo?
    var unreadNotices = 0
    var settings: UserSettings?
    var selection: AppSection = .today
    var showTalk = false
    var toast: Toast?
    var apnsAvailable = false
    /// Contador por colección: las pantallas recargan cuando cambia (`.task(id:)`).
    private(set) var versions: [String: Int] = [:]

    private var realtimeTask: Task<Void, Never>?
    private let seen = SeenLog(defaults: AppConfig.sharedDefaults, key: "kairo.seen.app")

    private init() {
        let device = UIDevice.current
        api = KairoAPI(
            sessions: SessionStore(store: AppConfig.appKeychain, key: "session.app"),
            client: ClientInfo(kind: "ios", deviceName: device.userInterfaceIdiom == .pad ? "iPad" : "iPhone", platform: "ios", capabilities: ["timer", "alarm", "location"])
        )
        voice = VoiceEngine(api: api)
        wake = WakeWordService()
        recorder = MeetingRecorder(api: api)
        voice.model = self
        wake.onWake = { [weak self] rest in self?.startTalking(initialText: rest) }
        Task { await bootstrap() }
    }

    // MARK: Sesión

    func bootstrap() async {
        await api.setOnSessionExpired { Task { @MainActor in AppModel.shared.signedOut() } }
        await api.setLocationProvider { await LocationService.shared.lastLocation() }
        #if DEBUG
        await debugAutologin()
        #endif
        if let s = await api.sessions.current() {
            user = s.user
            serverURL = s.serverURL
            phase = .ready
            await afterSignIn(fresh: false)
        } else {
            phase = .signedOut
        }
    }

    /// Capturas automáticas en el simulador (CI): sin diálogos del sistema.
    static let screenshotMode = ProcessInfo.processInfo.environment["KAIRO_SCREENSHOT"] != nil

    #if DEBUG
    /// Sólo en compilaciones de depuración: inicia sesión y abre una sección desde variables de entorno
    /// (KAIRO_AUTOLOGIN="servidor|correo|contraseña", KAIRO_SECTION=tasks). Lo usa el flujo de capturas de CI.
    private func debugAutologin() async {
        let env = ProcessInfo.processInfo.environment
        if let s = env["KAIRO_SECTION"], let sec = AppSection(rawValue: s) { selection = sec }
        guard let auto = env["KAIRO_AUTOLOGIN"] else { return }
        let parts = auto.split(separator: "|", maxSplits: 2).map(String.init)
        guard parts.count == 3 else { return }
        _ = try? await api.login(server: parts[0], email: parts[1], password: parts[2])
    }
    #endif

    func didSignIn(_ u: User) async {
        user = u
        serverURL = await api.serverURL() ?? serverURL
        withAnimation(KairoMotion.base) { phase = .ready }
        await afterSignIn(fresh: true)
    }

    private func afterSignIn(fresh: Bool) async {
        startRealtime()
        async let f: Void = refreshFamily()
        async let n: Void = refreshNotices()
        async let s: Void = refreshSettings()
        _ = await (f, n, s)
        await SyncService.shared.sync(api: api, model: self)
        await refreshSnapshot()
        await ExtensionSessionService.ensure(api: api)
        if !Self.screenshotMode { await NotificationService.requestAuthorization() }
        if let me = try? await api.me() { user = me }
        PushRegistration.register()
        voice.followUp = AppConfig.sharedDefaults.object(forKey: "kairo.followUp") as? Bool ?? true
        if wake.isEnabled && !Self.screenshotMode { await wake.start() }
        if let a = PendingAction.take() { perform(a) }
    }

    func signOut() async {
        stopRealtime()
        wake.stop()
        await api.logout()
        await ExtensionSessionService.clear()
        NotificationService.clearAll()
        AppConfig.snapshots.save(SharedSnapshot())
        WidgetCenter.shared.reloadAllTimelines()
        signedOut()
    }

    func signedOut() {
        user = nil
        family = nil
        unreadNotices = 0
        withAnimation(KairoMotion.base) { phase = .signedOut }
    }

    // MARK: Ciclo de vida

    func becameActive() {
        guard phase == .ready else { return }
        startRealtime()
        Task {
            await SyncService.shared.sync(api: api, model: self)
            await refreshNotices()
            await refreshSnapshot()
        }
        if let a = PendingAction.take() { perform(a) }
    }

    func enteredBackground() {
        // Con "Oye Kairo" activo o una grabación, la conexión sigue viva en segundo plano
        if !wake.isRunning && !recorder.isRecording && voice.phase == .idle { stopRealtime() }
        SyncService.scheduleBackgroundRefresh()
    }

    func backgroundRefresh() async {
        guard await api.sessions.current() != nil else { return }
        await SyncService.shared.sync(api: api, model: self)
        await refreshSnapshot()
        SyncService.scheduleBackgroundRefresh()
    }

    // MARK: Tiempo real

    func startRealtime() {
        guard realtimeTask == nil else { return }
        realtimeTask = Task { [api] in
            for await ev in api.realtime() {
                self.handle(RealtimeEvent(ev), fromPush: false)
            }
        }
    }

    func stopRealtime() {
        realtimeTask?.cancel()
        realtimeTask = nil
    }

    func handle(_ ev: RealtimeEvent, fromPush: Bool) {
        switch ev {
        case .changed(let entity, _, _):
            bump(entity)
            if entity == "family" { Task { await refreshFamily() } }
            if entity == "notice" { Task { await refreshNotices() } }
            if ["reminder", "place", "event"].contains(entity) { Task { await SyncService.shared.sync(api: api, model: self) } }
            if ["task", "event", "list", "reminder"].contains(entity) { Task { await refreshSnapshot() } }
        case .reminderFire(let id, let text, _, _):
            bump("reminder")
            if let key = ev.dedupeKey, seen.markIfNew(key), !fromPush {
                NotificationService.showNow(id: key, title: text, body: "Recordatorio", entityId: id, category: NotificationService.categoryReminder, destination: "reminders")
            }
        case .eventAlert(let id, let title, _, _, let minutes, let location, _):
            if let key = ev.dedupeKey, seen.markIfNew(key), !fromPush {
                let when = minutes == 0 ? "Empieza ahora" : "En \(minutes) min"
                NotificationService.showNow(id: key, title: title, body: when + (location.isEmpty ? "" : " · \(location)"), entityId: id, category: nil, destination: "calendar")
            }
        case .briefing(let text, _):
            if let key = ev.dedupeKey, seen.markIfNew(key), !fromPush {
                NotificationService.showNow(id: key, title: "Tu día", body: text, entityId: nil, category: NotificationService.categoryBriefing, destination: "today")
            }
        case .recordingUpdated:
            bump("recording")
        case .familyNotice(let n):
            unreadNotices += 1
            bump("notice")
            if let key = ev.dedupeKey, seen.markIfNew(key) {
                if UIApplication.shared.applicationState == .active {
                    show(Toast(title: n.title, message: n.body, symbol: KairoSymbol.notice(n.kind), destination: AppSection.from(entity: n.entityType) ?? .family))
                } else if !fromPush {
                    NotificationService.showNow(id: key, title: n.title, body: n.body, entityId: n.entityId, category: nil, destination: AppSection.from(entity: n.entityType)?.rawValue ?? "family")
                }
            }
        case .other:
            break
        }
    }

    func bump(_ entity: String) { versions[entity, default: 0] += 1 }
    func version(_ entity: String) -> Int { versions[entity, default: 0] }

    /// Avisos de la familia que llegaron mientras no había conexión (los muestra una sola vez).
    func showMissed(_ notices: [Notice]) {
        for n in notices where seen.markIfNew("notice:\(n.id)") {
            NotificationService.showNow(id: "notice:\(n.id)", title: n.title, body: n.body, entityId: n.entityId, category: nil, destination: AppSection.from(entity: n.entityType)?.rawValue ?? "family")
        }
    }

    // MARK: Datos compartidos

    func refreshFamily() async {
        if let f = try? await api.family() { family = f }
    }

    func refreshNotices() async {
        if let r = try? await api.notices(unread: true, limit: 1) { unreadNotices = r.unread }
    }

    func refreshSettings() async {
        if let s = try? await api.settings() {
            settings = s.settings
            wake.phrase = s.settings.wakeWord
        }
    }

    /// Resumen para los widgets, las Live Activities y el reloj.
    func refreshSnapshot() async {
        guard let t = try? await api.today() else { return }
        let snap = SharedSnapshot(today: t, userName: user?.firstName ?? "", unreadNotices: unreadNotices, family: family)
        AppConfig.snapshots.save(snap)
        WidgetCenter.shared.reloadAllTimelines()
        WatchBridge.shared.send(snapshot: snap)
        EventActivityController.refresh(snap)
    }

    func ownerName(_ id: String?) -> String? { family?.ownerName(id) }
    var hasFamily: Bool { (family?.members.count ?? 0) > 1 }

    // MARK: Acciones

    func show(_ t: Toast) {
        withAnimation(KairoMotion.spring) { toast = t }
        let id = t.id
        Task {
            try? await Task.sleep(for: .seconds(4))
            if toast?.id == id { withAnimation(KairoMotion.base) { toast = nil } }
        }
    }

    func startTalking(initialText: String? = nil) {
        guard phase == .ready else { return }
        showTalk = true
        Task { await voice.talk(initialText: initialText) }
    }

    /// Acciones que llegan de Siri, widgets, controles, notificaciones o enlaces.
    func perform(_ action: String) {
        if action == "talk" { startTalking(); return }
        if action == "briefing" {
            showTalk = true
            Task { await voice.playBriefing() }
            return
        }
        if action.hasPrefix("section:"), let s = AppSection(rawValue: String(action.dropFirst(8))) {
            withAnimation(KairoMotion.base) { selection = s }
        }
    }

    /// kairo://talk, kairo://section/notes, kairo://unirse?codigo=XXXX-XXXX y enlaces https .../unirse?codigo=
    func open(url: URL) {
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        if url.path.contains("unirse") || url.host == "unirse", let code = comps?.queryItems?.first(where: { $0.name == "codigo" })?.value {
            pendingJoinCode = code
            if let scheme = url.scheme, scheme.hasPrefix("http"), let host = url.host {
                pendingJoinServer = "\(scheme)://\(host)" + (url.port.map { ":\($0)" } ?? "")
            }
            return
        }
        switch url.host {
        case "talk": perform("talk")
        case "section": perform("section:" + url.lastPathComponent)
        default: break
        }
    }

    var pendingJoinCode: String?
    var pendingJoinServer: String?

    func registerApns(token: String) async {
        #if DEBUG
        let env = "sandbox"
        #else
        let env = "production"
        #endif
        try? await api.registerApns(token: token, environment: env)
    }
}

extension AppSection {
    static func from(entity: String?) -> AppSection? {
        switch entity {
        case "note": .notes
        case "task": .tasks
        case "event": .calendar
        case "list": .lists
        case "reminder": .reminders
        case "family": .family
        default: nil
        }
    }
}
