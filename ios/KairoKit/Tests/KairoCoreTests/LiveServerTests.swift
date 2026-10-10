import Foundation
import Testing
@testable import KairoCore

/// Tests against a real backend. They only run if KAIRO_TEST_SERVER, KAIRO_TEST_EMAIL and
/// KAIRO_TEST_PASSWORD are set (for example, the local development backend). They sign in only once.
private let env = ProcessInfo.processInfo.environment
private let liveEnabled = env["KAIRO_TEST_SERVER"] != nil && env["KAIRO_TEST_EMAIL"] != nil && env["KAIRO_TEST_PASSWORD"] != nil

@Suite("Real server", .enabled(if: liveEnabled), .serialized)
struct LiveServerTests {
    @Test func fullFlow() async throws {
        let api = KairoAPI(
            sessions: SessionStore(store: MemoryStore()),
            client: ClientInfo(kind: "ios", deviceName: "KairoKit tests", platform: "ios", capabilities: ["timer", "alarm"])
        )
        let user = try await api.login(server: env["KAIRO_TEST_SERVER"]!, email: env["KAIRO_TEST_EMAIL"]!, password: env["KAIRO_TEST_PASSWORD"]!)
        #expect(!user.displayName.isEmpty)

        // Every resource decodes with the app's models
        let today = try await api.today()
        #expect(!today.dateLabel.isEmpty)
        let family = try await api.family()
        #expect(family.members.contains { $0.id == user.id })
        _ = try await api.notes()
        _ = try await api.tasks(assignee: "me")
        _ = try await api.reminders(statuses: ["scheduled", "fired"])
        _ = try await api.lists()
        _ = try await api.events(from: .now, to: .now.addingTimeInterval(14 * 86400))
        _ = try await api.notices()
        let sync = try await api.deviceSync()
        _ = NotificationPlan.make(sync)
        _ = try await api.settings()
        _ = try await api.activity(limit: 10)

        // One streaming agent turn (Nan)
        var turn = AgentTurn()
        for try await ev in api.chat("Reply with just the word ready.", channel: "chat") { turn.apply(ev) }
        #expect(turn.finished)
        #expect(turn.error == nil)
        #expect(!turn.text.isEmpty)
        if let cid = turn.conversationId { try await api.deleteConversation(cid) }

        // Create and delete a personal note
        let note = try await api.createNote(title: "iOS test", content: "Created by the KairoKit tests")
        #expect(note.ownerId == user.id)
        #expect(!note.shared)
        try await api.deleteNote(note.id)

        await api.logout()
        #expect(await api.sessions.current() == nil)
    }
}
