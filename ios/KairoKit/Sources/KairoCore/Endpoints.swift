import Foundation

/// All the API resources (docs/API.md), same as the Android client.
public extension KairoAPI {

    // MARK: Profile, today and voice

    func me() async throws -> User { try await (send("GET", "/api/me") as UserEnvelope).user }

    func updateProfile(displayName: String? = nil, timezone: String? = nil) async throws -> User {
        var b: JSONObject = [:]
        if let displayName { b["displayName"] = .string(displayName) }
        if let timezone { b["timezone"] = .string(timezone) }
        let u = try await (send("PATCH", "/api/me", b) as UserEnvelope).user
        await sessions.updateUser(u)
        return u
    }

    func changePassword(current: String, new: String) async throws {
        try await call("POST", "/api/auth/password", ["currentPassword": .string(current), "newPassword": .string(new)])
    }

    func today(date: String? = nil) async throws -> TodayData {
        try await send("GET", "/api/today" + (date.map { "?date=\($0)" } ?? ""))
    }

    func briefing() async throws -> String { try await (send("POST", "/api/briefing") as BriefingResponse).text }

    /// MP3 audio with Kairo's voice (Kokoro on Nan).
    func speak(_ text: String, voice: String? = nil, speed: Double? = nil) async throws -> Data {
        var b: JSONObject = ["text": .string(text)]
        if let voice { b["voice"] = .string(voice) }
        if let speed { b["speed"] = .number(speed) }
        return try await sendRaw("POST", "/api/voice/speak", body: try JSONEncoder().encode(b), contentType: "application/json", timeout: 60)
    }

    func voices() async throws -> [VoiceOption] { try await (send("GET", "/api/voice/voices") as VoicesResponse).voices }

    // MARK: Conversations

    func conversations() async throws -> [Conversation] { try await send("GET", "/api/conversations") }
    func messages(_ conversationId: String) async throws -> [StoredMessage] { try await send("GET", "/api/conversations/\(conversationId)/messages") }
    func deleteConversation(_ id: String) async throws { try await call("DELETE", "/api/conversations/\(id)") }

    // MARK: Notes

    func notes(query: String? = nil, tag: String? = nil) async throws -> [Note] {
        try await send("GET", "/api/notes" + Self.query(["q": query, "tag": tag]))
    }
    func searchNotes(_ q: String) async throws -> [Note] { try await send("GET", "/api/notes/search" + Self.query(["q": q])) }
    func note(_ id: String) async throws -> Note { try await send("GET", "/api/notes/\(id)") }
    func createNote(title: String, content: String, tags: [String] = [], pinned: Bool = false, shared: Bool = false) async throws -> Note {
        try await send("POST", "/api/notes", ["title": .string(title), "content": .string(content), "tags": .strings(tags), "pinned": .bool(pinned), "shared": .bool(shared)])
    }
    func updateNote(_ id: String, _ patch: JSONObject) async throws -> Note { try await send("PATCH", "/api/notes/\(id)", patch) }
    func deleteNote(_ id: String) async throws { try await call("DELETE", "/api/notes/\(id)") }
    func noteTags() async throws -> [String] { try await (send("GET", "/api/notes/tags") as TagsResponse).tags }

    // MARK: Tasks and projects

    func tasks(includeDone: Bool = true, assignee: String? = nil) async throws -> [KTask] {
        try await send("GET", "/api/tasks" + Self.query(["includeDone": String(includeDone), "assignee": assignee]))
    }
    func createTask(_ body: JSONObject) async throws -> KTask { try await send("POST", "/api/tasks", body) }
    func updateTask(_ id: String, _ patch: JSONObject) async throws -> KTask { try await send("PATCH", "/api/tasks/\(id)", patch) }
    func deleteTask(_ id: String) async throws { try await call("DELETE", "/api/tasks/\(id)") }
    func projects() async throws -> [Project] { try await send("GET", "/api/projects") }
    func createProject(_ name: String) async throws -> Project { try await send("POST", "/api/projects", ["name": .string(name)]) }

    // MARK: Reminders and places

    func reminders(statuses: [String]? = nil) async throws -> [Reminder] {
        try await send("GET", "/api/reminders" + Self.query(["status": statuses?.joined(separator: ",")]))
    }
    func createReminder(_ body: JSONObject) async throws -> Reminder { try await send("POST", "/api/reminders", body) }
    func updateReminder(_ id: String, _ patch: JSONObject) async throws -> Reminder { try await send("PATCH", "/api/reminders/\(id)", patch) }
    func reminderDone(_ id: String) async throws -> Reminder { try await send("POST", "/api/reminders/\(id)/done") }
    func reminderSnooze(_ id: String, minutes: Int) async throws -> Reminder { try await send("POST", "/api/reminders/\(id)/snooze", ["minutes": .number(Double(minutes))]) }
    func reminderCancel(_ id: String) async throws -> Reminder { try await send("POST", "/api/reminders/\(id)/cancel") }
    func deleteReminder(_ id: String) async throws { try await call("DELETE", "/api/reminders/\(id)") }

    func places() async throws -> [Place] { try await send("GET", "/api/places") }
    func savePlace(name: String, latitude: Double, longitude: Double, radiusM: Int = 150, shared: Bool? = nil) async throws -> Place {
        var b: JSONObject = ["name": .string(name), "latitude": .number(latitude), "longitude": .number(longitude), "radiusM": .number(Double(radiusM))]
        if let shared { b["shared"] = .bool(shared) }
        return try await send("POST", "/api/places", b)
    }
    func sharePlace(_ id: String, shared: Bool) async throws -> Place { try await send("PATCH", "/api/places/\(id)", ["shared": .bool(shared)]) }
    func deletePlace(_ id: String) async throws { try await call("DELETE", "/api/places/\(id)") }

    // MARK: Lists

    func lists() async throws -> [ShoppingList] { try await send("GET", "/api/lists") }
    func createList(_ name: String, shared: Bool = false) async throws -> ShoppingList { try await send("POST", "/api/lists", ["name": .string(name), "shared": .bool(shared)]) }
    func updateList(_ id: String, _ patch: JSONObject) async throws -> ShoppingList { try await send("PATCH", "/api/lists/\(id)", patch) }
    func deleteList(_ id: String) async throws { try await call("DELETE", "/api/lists/\(id)") }
    func addItems(_ listId: String, _ items: [String]) async throws -> ShoppingList { try await send("POST", "/api/lists/\(listId)/items", ["items": .strings(items)]) }
    func setItem(_ listId: String, _ itemId: String, checked: Bool) async throws -> ShoppingList {
        try await send("PATCH", "/api/lists/\(listId)/items/\(itemId)", ["checked": .bool(checked)])
    }
    func renameItem(_ listId: String, _ itemId: String, text: String) async throws -> ShoppingList {
        try await send("PATCH", "/api/lists/\(listId)/items/\(itemId)", ["text": .string(text)])
    }
    func deleteItem(_ listId: String, _ itemId: String) async throws -> ShoppingList { try await send("DELETE", "/api/lists/\(listId)/items/\(itemId)") }
    func clearChecked(_ listId: String) async throws -> ShoppingList { try await send("POST", "/api/lists/\(listId)/clear-checked") }

    // MARK: Calendar

    func events(from: Date, to: Date) async throws -> [Event] {
        try await send("GET", "/api/events" + Self.query(["from": KairoDate.iso(from), "to": KairoDate.iso(to)]))
    }
    func createEvent(_ body: JSONObject) async throws -> Event { try await send("POST", "/api/events", body) }
    func updateEvent(_ id: String, _ patch: JSONObject) async throws -> Event { try await send("PATCH", "/api/events/\(id)", patch) }
    func deleteEvent(_ id: String) async throws { try await call("DELETE", "/api/events/\(id)") }

    // MARK: Memory

    func memories() async throws -> [Memory] { try await send("GET", "/api/memories") }
    func createMemory(_ content: String, category: String) async throws -> Memory { try await send("POST", "/api/memories", ["content": .string(content), "category": .string(category)]) }
    func updateMemory(_ id: String, content: String, category: String) async throws -> Memory {
        try await send("PATCH", "/api/memories/\(id)", ["content": .string(content), "category": .string(category)])
    }
    func deleteMemory(_ id: String) async throws { try await call("DELETE", "/api/memories/\(id)") }

    // MARK: Recordings

    func recordings() async throws -> [Recording] { try await send("GET", "/api/recordings") }
    func recording(_ id: String) async throws -> Recording { try await send("GET", "/api/recordings/\(id)") }
    func createRecording(title: String = "") async throws -> Recording { try await send("POST", "/api/recordings", ["title": .string(title)]) }
    func uploadSegment(_ id: String, seq: Int, durationS: Int, audio: Data, mime: String = "audio/mp4") async throws -> Recording {
        try await upload("/api/recordings/\(id)/segments", fields: [("seq", String(seq)), ("duration", String(durationS))],
                         file: FilePart(filename: "segment-\(seq).m4a", mime: mime, data: audio))
    }
    func finishRecording(_ id: String, title: String? = nil) async throws {
        try await call("POST", "/api/recordings/\(id)/finish", title.map { ["title": .string($0)] } ?? [:])
    }
    func recordingTasks(_ id: String) async throws -> Int { try await (send("POST", "/api/recordings/\(id)/tasks") as CreatedCount).created }
    func deleteRecording(_ id: String) async throws { try await call("DELETE", "/api/recordings/\(id)") }

    // MARK: Activity, settings and devices

    func activity(limit: Int = 60) async throws -> [ActivityEntry] { try await send("GET", "/api/activity?limit=\(limit)") }
    func undo(_ activityId: String) async throws { try await call("POST", "/api/activity/\(activityId)/undo") }
    func settings() async throws -> SettingsResponse { try await send("GET", "/api/settings") }
    func updateSettings(_ patch: JSONObject) async throws -> SettingsResponse { try await send("PATCH", "/api/settings", patch) }
    func models() async throws -> [String] { try await (send("GET", "/api/models") as ModelsResponse).models }
    func devices() async throws -> [Device] { try await send("GET", "/api/devices") }
    func revokeDevice(_ id: String) async throws { try await call("DELETE", "/api/devices/\(id)") }
    func deviceSync() async throws -> DeviceSync { try await send("GET", "/api/sync/device") }

    // MARK: Family and notices

    func family() async throws -> FamilyInfo { try await send("GET", "/api/family") }
    func renameFamily(_ name: String) async throws -> Household { try await send("PATCH", "/api/family", ["name": .string(name)]) }
    func invites() async throws -> [Invitation] { try await send("GET", "/api/family/invites") }
    func createInvite(name: String, role: String = "member") async throws -> InviteCreated {
        try await send("POST", "/api/family/invites", ["name": .string(name), "role": .string(role)])
    }
    func revokeInvite(_ id: String) async throws { try await call("DELETE", "/api/family/invites/\(id)") }
    func accessCode(memberId: String) async throws -> InviteCreated { try await send("POST", "/api/family/members/\(memberId)/access-code") }
    func setMemberRole(_ memberId: String, role: String) async throws -> Member { try await send("PATCH", "/api/family/members/\(memberId)", ["role": .string(role)]) }
    func removeMember(_ memberId: String) async throws { try await call("DELETE", "/api/family/members/\(memberId)") }
    func familyMessage(_ text: String, to: [String]? = nil) async throws -> Int {
        var b: JSONObject = ["text": .string(text)]
        if let to { b["to"] = .strings(to) }
        return try await (send("POST", "/api/family/message", b) as Sent).sent
    }
    func notices(unread: Bool = false, limit: Int = 50) async throws -> NoticesResponse {
        try await send("GET", "/api/notices?unread=\(unread)&limit=\(limit)")
    }
    func markNoticesRead(_ ids: [String]? = nil) async throws {
        try await call("POST", "/api/notices/read", ids.map { ["ids": .strings($0)] } ?? [:])
    }

    // MARK: Native Apple push (APNs)

    func apnsStatus() async throws -> ApnsStatus { try await send("GET", "/api/push/apns") }
    func registerApns(token: String, environment: String, kind: String = "device", activityId: String? = nil) async throws {
        var b: JSONObject = ["token": .string(token), "environment": .string(environment), "kind": .string(kind)]
        if let activityId { b["activityId"] = .string(activityId) }
        try await call("POST", "/api/push/apns/register", b)
    }
    func unregisterApns() async throws { try await call("DELETE", "/api/push/apns/register") }
    func testPush() async throws -> Int { try await (send("POST", "/api/push/test") as Delivered).delivered }

    // MARK: Utilities

    /// "?q=hello&tag=x" omitting nulls, with correct encoding.
    nonisolated static func query(_ params: [String: String?]) -> String {
        var c = URLComponents()
        c.queryItems = params.sorted { $0.key < $1.key }.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }
        guard let items = c.queryItems, !items.isEmpty else { return "" }
        // "+" in a query is read as a space by some servers
        return "?" + (c.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B")
    }
}
