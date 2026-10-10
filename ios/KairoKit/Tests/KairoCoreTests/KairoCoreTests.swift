import Foundation
import Testing
@testable import KairoCore

/// Real backend responses (with anonymized emails) in Fixtures/.
private func fixture(_ name: String) throws -> Data {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/\(name).json")
    return try Data(contentsOf: url)
}

private func decode<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
    try JSONDecoder().decode(T.self, from: try fixture(name))
}

@Suite("API models")
struct ModelTests {
    @Test func allResponsesDecode() throws {
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
        _ = try decode([ActivityEntry].self, "activity")
        _ = try decode([Conversation].self, "conversations")
        _ = try decode([Place].self, "places")
        _ = try decode([Recording].self, "recordings")
        _ = try decode([Device].self, "devices")
        let auth = try decode(AuthResponse.self, "auth")
        #expect(auth.user.householdId != nil)
    }

    @Test func messagesWithActionCards() throws {
        let msgs = try decode([StoredMessage].self, "messages")
        let tool = msgs.first { $0.role == "tool" }
        #expect(tool?.meta?["summary"]?.string != nil)
    }

    @Test func familyNames() throws {
        let family = try decode(FamilyInfo.self, "family")
        #expect(family.ownerName(family.me) == nil)
        if let other = family.others.first {
            #expect(family.ownerName(other.id) == other.firstName)
            #expect(!other.initials.isEmpty)
        }
    }

    @Test func patchesWithExplicitNulls() throws {
        let patch: JSONObject = ["dueDate": .null, "title": "Bread", "shared": true]
        let json = String(decoding: try JSONEncoder().encode(patch), as: UTF8.self)
        #expect(json.contains("\"dueDate\":null"))
        #expect(json.contains("\"shared\":true"))
    }
}

@Suite("Agent (SSE)")
struct AgentTests {
    @Test func parserDeliversEachEvent() {
        var p = SSEParser()
        #expect(p.feed(": kairo") == nil)
        #expect(p.feed("") == nil)
        let payload = p.feed(#"data: {"type":"delta","text":"Hello","step":0}"#)
        #expect(payload == #"{"type":"delta","text":"Hello","step":0}"#)
        #expect(p.feed("") == nil)
        if let payload, let ev = SSEParser.decode(payload) {
            #expect(ev == .delta("Hello", step: 0))
        } else {
            Issue.record("did not decode")
        }
    }

    @Test func fullTurnWithTools() {
        let lines = [
            #"{"type":"start","conversationId":"c1"}"#,
            #"{"type":"delta","text":"Let me save that","step":0}"#,
            #"{"type":"tool_start","callId":"t1","name":"create_note","label":"Saving note"}"#,
            #"{"type":"tool_end","callId":"t1","name":"create_note","ok":true,"summary":"Note saved: Wifi","entity":{"type":"note","id":"n1","title":"Wifi"}}"#,
            #"{"type":"device_action","action":{"type":"timer","seconds":600,"label":"Pasta"}}"#,
            #"{"type":"done","conversationId":"c1","messageId":"m1","text":"**Done**","spoken":"Done"}"#,
        ]
        var turn = AgentTurn()
        for l in lines { if let ev = SSEParser.decode(l) { turn.apply(ev) } }
        #expect(turn.finished)
        #expect(turn.text == "**Done**")
        #expect(turn.spoken == "Done")
        #expect(turn.actions.count == 1)
        #expect(turn.actions[0].entity?.type == "note")
        #expect(turn.deviceActions == [.timer(seconds: 600, label: "Pasta")])
        #expect(turn.working == nil)
    }

    @Test func agentError() {
        var turn = AgentTurn()
        turn.apply(SSEParser.decode(#"{"type":"error","message":"Nan did not respond"}"#)!)
        #expect(turn.error == "Nan did not respond")
        #expect(turn.finished)
    }

    @Test func alarmWithDays() {
        let a = DeviceAction(json: ["type": "alarm", "hour": 7, "minute": 30, "label": "Gym", "days": ["mon", "wed"]])
        #expect(a == .alarm(hour: 7, minute: 30, label: "Gym", days: ["mon", "wed"]))
    }
}

@Suite("Local notifications")
struct NotificationTests {
    @Test func planFromSync() throws {
        let sync = try decode(DeviceSync.self, "sync")
        let now = KairoDate.parse(sync.serverTime) ?? .now
        let plan = NotificationPlan.make(sync, now: now)
        #expect(plan.count <= NotificationPlan.limit)
        #expect(plan.filter { $0.kind != .briefing }.allSatisfy { $0.date > now })
        if sync.briefingEnabled { #expect(plan.contains { $0.kind == .briefing && $0.dailyHour != nil }) }
        let dates = plan.filter { $0.kind != .briefing }.map(\.date)
        #expect(dates == dates.sorted())
    }

    @Test func sameKeyAsTheLiveNotice() throws {
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

    @Test func familyNotice() {
        let ev = RealtimeEvent(ServerEvent(type: "family.notice", data: ["id": "n1", "kind": "assigned", "title": "Alex assigned you a task", "body": "Take out the trash"]))
        guard case .familyNotice(let n) = ev else { Issue.record("not a notice"); return }
        #expect(n.kind == "assigned")
        #expect(ev.dedupeKey == "notice:n1")
    }

    @Test func seenOnlyOnce() {
        let d = UserDefaults(suiteName: "kairo-tests-\(UUID().uuidString)")!
        let log = SeenLog(defaults: d)
        #expect(log.markIfNew("a"))
        #expect(!log.markIfNew("a"))
        #expect(log.contains("a"))
    }
}

@Suite("Voice")
struct VoiceTests {
    @Test func endOfSpeechAfterSilence() {
        var v = EndOfSpeech()
        for _ in 0..<10 { #expect(v.push(100) == .continue) }
        for _ in 0..<20 { _ = v.push(3000) }
        #expect(v.heardSpeech)
        var verdict = EndOfSpeech.Verdict.continue
        for _ in 0..<40 { verdict = v.push(100); if verdict != .continue { break } }
        #expect(verdict == .done)
    }

    @Test func givesUpWithoutVoice() {
        var v = EndOfSpeech()
        var verdict = EndOfSpeech.Verdict.continue
        for _ in 0..<400 { verdict = v.push(80); if verdict != .continue { break } }
        #expect(verdict == .noSpeech)
    }

    @Test func wakePhraseAcceptsBothLanguages() {
        // Spanish and English phrases are accepted whatever the configured phrase is.
        #expect(WakePhrase.match("Oye Cairo, ¿qué tengo hoy?", phrase: "oye kairo").hit)
        #expect(WakePhrase.match("hey kairo agrega pan", phrase: "Oye Kairo").rest == "agrega pan")
        #expect(WakePhrase.match("Hey Cairo, what's on today?", phrase: "hey kairo").hit)
        #expect(WakePhrase.match("oye kairo add bread", phrase: "Hey Kairo").rest == "add bread")
        #expect(WakePhrase.match("Hey Kairo, add bread", phrase: "hey kairo").rest == "add bread")
        #expect(!WakePhrase.match("the cairo trip is on friday", phrase: "hey kairo").hit)
        #expect(!WakePhrase.match("el cairo es la capital", phrase: "oye kairo").hit)
    }

    @Test func wavIsValid() {
        let wav = AudioMath.wav([0, 1000, -1000, 0])
        #expect(wav.count == 44 + 8)
        #expect(String(decoding: wav.prefix(4), as: UTF8.self) == "RIFF")
        #expect(AudioMath.level(20) == 0)
        #expect(AudioMath.level(30000) > 0.9)
    }
}

@Suite("Utilities")
struct UtilTests {
    @Test func apiDates() {
        #expect(KairoDate.parse("2026-10-11T14:00:00Z") != nil)
        #expect(KairoDate.parse("2026-10-11T14:00:00.864Z") != nil)
        #expect(KairoDate.parse("2026-10-11T09:00:00-05:00") == KairoDate.parse("2026-10-11T14:00:00Z"))
        #expect(KairoDate.parse("2026-10-11") != nil)
        #expect(KairoDate.parse("") == nil)
    }

    // Saturday, October 10, 2026 at 12:00
    private static let cal = Calendar(identifier: .gregorian)
    private static let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 10, hour: 12))!

    private static func at(days: Int = 0, hours: Int = 0, minutes: Int = 0) -> Date {
        var d = cal.date(byAdding: .day, value: days, to: now)!
        d = cal.date(byAdding: .hour, value: hours, to: d)!
        return cal.date(byAdding: .minute, value: minutes, to: d)!
    }

    @Test func humanDaysInEnglish() {
        let cal = Self.cal, now = Self.now, en = KairoDate.english
        #expect(KairoDate.humanDay(now, now: now, calendar: cal, locale: en) == "today")
        #expect(KairoDate.humanDay(Self.at(days: 1), now: now, calendar: cal, locale: en) == "tomorrow")
        #expect(KairoDate.humanDay(Self.at(days: -1), now: now, calendar: cal, locale: en) == "yesterday")
        #expect(KairoDate.humanDay(Self.at(days: 3), now: now, calendar: cal, locale: en) == "Tuesday")
        #expect(KairoDate.humanDay(Self.at(days: 10), now: now, calendar: cal, locale: en) == "Tuesday, October 20")
        #expect(KairoDate.humanDay(Self.at(days: 87), now: now, calendar: cal, locale: en) == "Tuesday, January 5, 2027")
        #expect(KairoDate.longDay(now, calendar: cal, locale: en) == "Saturday, October 10")
    }

    @Test func humanDaysInSpanish() {
        let cal = Self.cal, now = Self.now, es = KairoDate.spanish
        #expect(KairoDate.humanDay(now, now: now, calendar: cal, locale: es) == "hoy")
        #expect(KairoDate.humanDay(Self.at(days: 1), now: now, calendar: cal, locale: es) == "mañana")
        #expect(KairoDate.humanDay(Self.at(days: -1), now: now, calendar: cal, locale: es) == "ayer")
        #expect(KairoDate.humanDay(Self.at(days: 3), now: now, calendar: cal, locale: es) == "martes")
        #expect(KairoDate.humanDay(Self.at(days: 10), now: now, calendar: cal, locale: es) == "martes 20 de octubre")
        #expect(KairoDate.humanDay(Self.at(days: 87), now: now, calendar: cal, locale: es) == "martes 5 de enero de 2027")
        #expect(KairoDate.longDay(now, calendar: cal, locale: es) == "Sábado 10 de octubre")
    }

    @Test func dateTimesInBothLanguages() {
        let cal = Self.cal, now = Self.now
        let nine = Self.at(hours: -3)
        let en = KairoDate.humanDateTime(nine, now: now, calendar: cal, locale: KairoDate.english)
        #expect(en.hasPrefix("today at 9:00"))
        let es = KairoDate.humanDateTime(nine, now: now, calendar: cal, locale: KairoDate.spanish)
        #expect(es == "hoy a las 9:00")
        #expect(KairoDate.time(nine, calendar: cal, locale: KairoDate.english).contains("9:00"))
    }

    @Test func relativeTimesInBothLanguages() {
        let cal = Self.cal, now = Self.now, en = KairoDate.english, es = KairoDate.spanish
        #expect(KairoDate.relative(Self.at(minutes: -1), now: now, calendar: cal, locale: en) == "1 min ago")
        #expect(KairoDate.relative(Self.at(minutes: -1), now: now, calendar: cal, locale: es) == "hace 1 min")
        #expect(KairoDate.relative(Self.at(minutes: -30), now: now, calendar: cal, locale: en) == "30 min ago")
        #expect(KairoDate.relative(Self.at(hours: -2), now: now, calendar: cal, locale: en) == "2 h ago")
        #expect(KairoDate.relative(Self.at(hours: -2), now: now, calendar: cal, locale: es) == "hace 2 h")
        #expect(KairoDate.relative(now, now: now, calendar: cal, locale: en) == "now")
        #expect(KairoDate.relative(now, now: now, calendar: cal, locale: es) == "ahora")
        #expect(KairoDate.relative(Self.at(hours: -30), now: now, calendar: cal, locale: en) == "yesterday")
        #expect(KairoDate.relative(Self.at(hours: -30), now: now, calendar: cal, locale: es) == "ayer")
        #expect(KairoDate.relative(Self.at(days: -4), now: now, calendar: cal, locale: en) == "Tue, Oct 6")
        #expect(KairoDate.relative(Self.at(days: -4), now: now, calendar: cal, locale: es) == "mar 6 oct")
    }

    @Test func recurrenceTextsInBothLanguages() {
        let en = KairoDate.english, es = KairoDate.spanish
        #expect(RecurrenceText.describe("FREQ=DAILY", locale: en) == "Every day")
        #expect(RecurrenceText.describe("RRULE:FREQ=DAILY;INTERVAL=3", locale: en) == "Every 3 days")
        #expect(RecurrenceText.describe("FREQ=WEEKLY;BYDAY=MO,TH", locale: en) == "Every Monday and Thursday")
        #expect(RecurrenceText.describe("FREQ=WEEKLY;BYDAY=MO,WE,FR", locale: en) == "Every Monday, Wednesday and Friday")
        #expect(RecurrenceText.describe("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", locale: en) == "Weekdays")
        #expect(RecurrenceText.describe("FREQ=WEEKLY", locale: en) == "Every week")
        #expect(RecurrenceText.describe("FREQ=MONTHLY", locale: en) == "Every month")
        #expect(RecurrenceText.describe("FREQ=YEARLY", locale: en) == "Every year")
        #expect(RecurrenceText.describe("FREQ=HOURLY", locale: en) == "Repeats")
        #expect(RecurrenceText.describe("FREQ=DAILY", locale: es) == "Cada día")
        #expect(RecurrenceText.describe("FREQ=WEEKLY;BYDAY=MO,TH", locale: es) == "Cada lunes y jueves")
        #expect(RecurrenceText.describe("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", locale: es) == "Entre semana")
        #expect(RecurrenceText.describe("FREQ=MONTHLY;INTERVAL=2", locale: es) == "Cada 2 meses")
    }

    @Test func monthTitlesAndWeekdayInitials() {
        var cal = Self.cal
        cal.firstWeekday = 2 // Monday
        #expect(KairoDate.monthTitle(Self.now, calendar: cal, locale: KairoDate.english) == "October 2026")
        #expect(KairoDate.monthTitle(Self.now, calendar: cal, locale: KairoDate.spanish) == "Octubre 2026")
        #expect(KairoDate.weekdayInitials(calendar: cal, locale: KairoDate.english) == ["M", "T", "W", "T", "F", "S", "S"])
        #expect(KairoDate.weekdayInitials(calendar: cal, locale: KairoDate.spanish) == ["L", "M", "X", "J", "V", "S", "D"])
        cal.firstWeekday = 1 // Sunday
        #expect(KairoDate.weekdayInitials(calendar: cal, locale: KairoDate.english) == ["S", "M", "T", "W", "T", "F", "S"])
    }

    @Test func durations() {
        #expect(KairoDate.duration(3725) == "1:02:05")
        #expect(KairoDate.duration(65) == "1:05")
    }

    @Test func languageFollowsTheFirstSupportedPreference() {
        #expect(KairoLanguage.resolve(["es-MX", "en-US"]) == "es")
        #expect(KairoLanguage.resolve(["en-GB", "es"]) == "en")
        #expect(KairoLanguage.resolve(["fr-FR", "es-ES"]) == "es")
        #expect(KairoLanguage.resolve(["de-DE"]) == "en")
        #expect(KairoLanguage.resolve([]) == "en")
        #expect(KairoLanguage.locale(forCode: "es").language.languageCode?.identifier == "es")
        #expect(KairoLanguage.locale(forCode: "en").language.languageCode?.identifier == "en")
    }

    @Test func inviteLinksAcceptCanonicalAndLegacyForms() throws {
        let canonical = try #require(InviteLink.parse(url: URL(string: "https://kairo.example.com/join?code=ABCD-EFGH")!))
        #expect(canonical == InviteLink.Parsed(code: "ABCD-EFGH", server: "https://kairo.example.com"))
        let legacy = try #require(InviteLink.parse(url: URL(string: "https://kairo.example.com:8443/unirse?codigo=abcd-efgh")!))
        #expect(legacy == InviteLink.Parsed(code: "ABCD-EFGH", server: "https://kairo.example.com:8443"))
        #expect(InviteLink.parse(url: URL(string: "kairo://join?code=ABCD-EFGH")!) == InviteLink.Parsed(code: "ABCD-EFGH"))
        #expect(InviteLink.parse(url: URL(string: "kairo://unirse?codigo=ABCD-EFGH")!) == InviteLink.Parsed(code: "ABCD-EFGH"))
        #expect(InviteLink.parse(url: URL(string: "kairo://talk")!) == nil)
        #expect(InviteLink.parse(url: URL(string: "https://kairo.example.com/today")!) == nil)
        // Pasted text: a link, a link without scheme or a bare code
        #expect(InviteLink.parse("  https://kairo.example.com/join?code=ABCD-EFGH \n")?.code == "ABCD-EFGH")
        #expect(InviteLink.parse("kairo.example.com/unirse?codigo=ABCD-EFGH")?.server == "https://kairo.example.com")
        #expect(InviteLink.parse("abcd efgh")?.code == "ABCD-EFGH")
        #expect(InviteLink.parse("ABCDEFGH")?.code == "ABCD-EFGH")
        #expect(InviteLink.parse("") == nil)
    }

    @Test func encodedQueries() {
        #expect(KairoAPI.query(["q": "café + pan", "tag": nil]) == "?q=caf%C3%A9%20%2B%20pan")
        #expect(KairoAPI.query(["a": nil]) == "")
    }

    @Test func normalizedServer() throws {
        #expect(try KairoAPI.normalizeServer("kairo.example.com/") == "https://kairo.example.com")
        #expect(try KairoAPI.normalizeServer("http://192.168.1.10:8080/api") == "http://192.168.1.10:8080")
        #expect(throws: APIError.self) { try KairoAPI.normalizeServer("   ") }
    }

    @Test func widgetSummary() throws {
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
