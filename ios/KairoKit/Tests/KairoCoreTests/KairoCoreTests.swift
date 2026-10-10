import Foundation
import Testing
@testable import KairoCore

/// Respuestas reales del backend (con correos anonimizados) en Fixtures/.
private func fixture(_ name: String) throws -> Data {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/\(name).json")
    return try Data(contentsOf: url)
}

private func decode<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
    try JSONDecoder().decode(T.self, from: try fixture(name))
}

@Suite("Modelos del API")
struct ModelTests {
    @Test func todasLasRespuestasSeDecodifican() throws {
        let today = try decode(TodayData.self, "today")
        #expect(!today.greeting.isEmpty)
        #expect(try !decode([Note].self, "notes").isEmpty)
        let tasks = try decode([KTask].self, "tasks")
        #expect(tasks.contains { $0.assigneeId != nil })
        _ = try decode([Reminder].self, "reminders")
        let lists = try decode([ShoppingList].self, "lists")
        #expect(lists.contains { $0.shared })
        let events = try decode([Event].self, "events")
        #expect(events.allSatisfy { $0.start != nil })
        _ = try decode([Memory].self, "memories")
        let family = try decode(FamilyInfo.self, "family")
        #expect(family.members.count >= 2)
        #expect(family.others.allSatisfy { $0.id != family.me })
        let notices = try decode(NoticesResponse.self, "notices")
        #expect(!notices.notices.isEmpty)
        let sync = try decode(DeviceSync.self, "sync")
        #expect(!sync.eventAlerts.isEmpty)
        _ = try decode(SettingsResponse.self, "settings")
        _ = try decode([Activity].self, "activity")
        _ = try decode([Conversation].self, "conversations")
        _ = try decode([Place].self, "places")
        _ = try decode([Recording].self, "recordings")
        _ = try decode([Device].self, "devices")
        let auth = try decode(AuthResponse.self, "auth")
        #expect(auth.user.householdId != nil)
    }

    @Test func mensajesConTarjetasDeAccion() throws {
        let msgs = try decode([StoredMessage].self, "messages")
        let tool = msgs.first { $0.role == "tool" }
        #expect(tool?.meta?["summary"]?.string != nil)
    }

    @Test func nombresDeLaFamilia() throws {
        let family = try decode(FamilyInfo.self, "family")
        #expect(family.ownerName(family.me) == nil)
        if let other = family.others.first {
            #expect(family.ownerName(other.id) == other.firstName)
            #expect(!other.initials.isEmpty)
        }
    }

    @Test func parchesConNulosExplicitos() throws {
        let patch: JSONObject = ["dueDate": .null, "title": "Pan", "shared": true]
        let json = String(decoding: try JSONEncoder().encode(patch), as: UTF8.self)
        #expect(json.contains("\"dueDate\":null"))
        #expect(json.contains("\"shared\":true"))
    }
}

@Suite("Agente (SSE)")
struct AgentTests {
    @Test func parserEntregaCadaEvento() {
        var p = SSEParser()
        #expect(p.feed(": kairo") == nil)
        #expect(p.feed("") == nil)
        let payload = p.feed(#"data: {"type":"delta","text":"Hola","step":0}"#)
        #expect(payload == #"{"type":"delta","text":"Hola","step":0}"#)
        #expect(p.feed("") == nil)
        if let payload, let ev = SSEParser.decode(payload) {
            #expect(ev == .delta("Hola", step: 0))
        } else {
            Issue.record("no decodificó")
        }
    }

    @Test func turnoCompletoConHerramientas() {
        let lines = [
            #"{"type":"start","conversationId":"c1"}"#,
            #"{"type":"delta","text":"Voy a guardar","step":0}"#,
            #"{"type":"tool_start","callId":"t1","name":"create_note","label":"Guardando nota"}"#,
            #"{"type":"tool_end","callId":"t1","name":"create_note","ok":true,"summary":"Nota guardada: Wifi","entity":{"type":"note","id":"n1","title":"Wifi"}}"#,
            #"{"type":"device_action","action":{"type":"timer","seconds":600,"label":"Pasta"}}"#,
            #"{"type":"done","conversationId":"c1","messageId":"m1","text":"**Listo**","spoken":"Listo"}"#,
        ]
        var turn = AgentTurn()
        for l in lines { if let ev = SSEParser.decode(l) { turn.apply(ev) } }
        #expect(turn.finished)
        #expect(turn.text == "**Listo**")
        #expect(turn.spoken == "Listo")
        #expect(turn.actions.count == 1)
        #expect(turn.actions[0].entity?.type == "note")
        #expect(turn.deviceActions == [.timer(seconds: 600, label: "Pasta")])
        #expect(turn.working == nil)
    }

    @Test func errorDelAgente() {
        var turn = AgentTurn()
        turn.apply(SSEParser.decode(#"{"type":"error","message":"Nan no respondió"}"#)!)
        #expect(turn.error == "Nan no respondió")
        #expect(turn.finished)
    }

    @Test func alarmaConDias() {
        let a = DeviceAction(json: ["type": "alarm", "hour": 7, "minute": 30, "label": "Gimnasio", "days": ["mon", "wed"]])
        #expect(a == .alarm(hour: 7, minute: 30, label: "Gimnasio", days: ["mon", "wed"]))
    }
}

@Suite("Avisos locales")
struct NotificationTests {
    @Test func planDesdeLaSincronizacion() throws {
        let sync = try decode(DeviceSync.self, "sync")
        let now = KairoDate.parse(sync.serverTime) ?? .now
        let plan = NotificationPlan.make(sync, now: now)
        #expect(plan.count <= NotificationPlan.limit)
        #expect(plan.filter { $0.kind != .briefing }.allSatisfy { $0.date > now })
        if sync.briefingEnabled { #expect(plan.contains { $0.kind == .briefing && $0.dailyHour != nil }) }
        let dates = plan.filter { $0.kind != .briefing }.map(\.date)
        #expect(dates == dates.sorted())
    }

    @Test func mismaClaveQueElAvisoEnVivo() throws {
        let sync = try decode(DeviceSync.self, "sync")
        let now = KairoDate.parse(sync.serverTime) ?? .now
        guard let alert = sync.eventAlerts.first(where: { (KairoDate.parse($0.alertAt) ?? now) > now }) else { return }
        let planned = NotificationPlan.make(sync, now: now).first { $0.entityId == alert.eventId && $0.kind == .event }
        let live = RealtimeEvent(ServerEvent(type: "event.alert", data: [
            "id": .string(alert.eventId), "title": .string(alert.title), "startAt": .string(alert.startAt),
            "alertAt": .string(alert.alertAt), "minutes": .number(Double(alert.minutes)),
        ]))
        #expect(planned?.id == live.dedupeKey)
    }

    @Test func avisoFamiliar() {
        let ev = RealtimeEvent(ServerEvent(type: "family.notice", data: ["id": "n1", "kind": "assigned", "title": "David te asignó una tarea", "body": "Sacar la basura"]))
        guard case .familyNotice(let n) = ev else { Issue.record("no es aviso"); return }
        #expect(n.kind == "assigned")
        #expect(ev.dedupeKey == "notice:n1")
    }

    @Test func vistoUnaSolaVez() {
        let d = UserDefaults(suiteName: "kairo-tests-\(UUID().uuidString)")!
        let log = SeenLog(defaults: d)
        #expect(log.markIfNew("a"))
        #expect(!log.markIfNew("a"))
        #expect(log.contains("a"))
    }
}

@Suite("Voz")
struct VoiceTests {
    @Test func finDeFraseTrasSilencio() {
        var v = EndOfSpeech()
        for _ in 0..<10 { #expect(v.push(100) == .continue) }
        for _ in 0..<20 { _ = v.push(3000) }
        #expect(v.heardSpeech)
        var verdict = EndOfSpeech.Verdict.continue
        for _ in 0..<40 { verdict = v.push(100); if verdict != .continue { break } }
        #expect(verdict == .done)
    }

    @Test func sinVozSeRinde() {
        var v = EndOfSpeech()
        var verdict = EndOfSpeech.Verdict.continue
        for _ in 0..<400 { verdict = v.push(80); if verdict != .continue { break } }
        #expect(verdict == .noSpeech)
    }

    @Test func fraseDeActivacion() {
        #expect(WakePhrase.match("Oye Cairo, ¿qué tengo hoy?", phrase: "oye kairo").hit)
        #expect(WakePhrase.match("hey kairo agrega pan", phrase: "Oye Kairo").rest == "agrega pan")
        #expect(!WakePhrase.match("el cairo es la capital", phrase: "oye kairo").hit)
    }

    @Test func wavValido() {
        let wav = AudioMath.wav([0, 1000, -1000, 0])
        #expect(wav.count == 44 + 8)
        #expect(String(decoding: wav.prefix(4), as: UTF8.self) == "RIFF")
        #expect(AudioMath.level(20) == 0)
        #expect(AudioMath.level(30000) > 0.9)
    }
}

@Suite("Utilidades")
struct UtilTests {
    @Test func fechasDelApi() {
        #expect(KairoDate.parse("2026-10-11T14:00:00Z") != nil)
        #expect(KairoDate.parse("2026-10-11T14:00:00.864Z") != nil)
        #expect(KairoDate.parse("2026-10-11T09:00:00-05:00") == KairoDate.parse("2026-10-11T14:00:00Z"))
        #expect(KairoDate.parse("2026-10-11") != nil)
        #expect(KairoDate.parse("") == nil)
    }

    @Test func diasEnEspanol() {
        let cal = Calendar(identifier: .gregorian)
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 10, hour: 12))!
        #expect(KairoDate.humanDay(now, now: now, calendar: cal) == "hoy")
        #expect(KairoDate.humanDay(cal.date(byAdding: .day, value: 1, to: now)!, now: now, calendar: cal) == "mañana")
        #expect(KairoDate.longDay(now, calendar: cal) == "Sábado 10 de octubre")
        #expect(KairoDate.duration(3725) == "1:02:05")
    }

    @Test func consultasCodificadas() {
        #expect(KairoAPI.query(["q": "café + pan", "tag": nil]) == "?q=caf%C3%A9%20%2B%20pan")
        #expect(KairoAPI.query(["a": nil]) == "")
    }

    @Test func servidorNormalizado() throws {
        #expect(try KairoAPI.normalizeServer("kairo.midominio.com/") == "https://kairo.midominio.com")
        #expect(try KairoAPI.normalizeServer("http://192.168.1.10:8080/api") == "http://192.168.1.10:8080")
        #expect(throws: APIError.self) { try KairoAPI.normalizeServer("   ") }
    }

    @Test func resumenParaWidgets() throws {
        let today = try decode(TodayData.self, "today")
        let family = try decode(FamilyInfo.self, "family")
        let snap = SharedSnapshot(today: today, userName: "Valentina", unreadNotices: 3, family: family)
        #expect(snap.events.count == today.events.count)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("kairo-snap-\(UUID().uuidString)")
        let store = SnapshotStore(directory: dir)
        store.save(snap)
        #expect(store.load()?.events.count == snap.events.count)
        #expect(store.load()?.unreadNotices == 3)
    }
}
