import Foundation
import Testing
@testable import KairoCore

/// Pruebas contra un backend real. Sólo corren si se definen KAIRO_TEST_SERVER, KAIRO_TEST_EMAIL y
/// KAIRO_TEST_PASSWORD (por ejemplo, el backend local de desarrollo). Inician sesión una sola vez.
private let env = ProcessInfo.processInfo.environment
private let liveEnabled = env["KAIRO_TEST_SERVER"] != nil && env["KAIRO_TEST_EMAIL"] != nil && env["KAIRO_TEST_PASSWORD"] != nil

@Suite("Servidor real", .enabled(if: liveEnabled), .serialized)
struct LiveServerTests {
    @Test func flujoCompleto() async throws {
        let api = KairoAPI(
            sessions: SessionStore(store: MemoryStore()),
            client: ClientInfo(kind: "ios", deviceName: "Pruebas de KairoKit", platform: "ios", capabilities: ["timer", "alarm"])
        )
        let user = try await api.login(server: env["KAIRO_TEST_SERVER"]!, email: env["KAIRO_TEST_EMAIL"]!, password: env["KAIRO_TEST_PASSWORD"]!)
        #expect(!user.displayName.isEmpty)

        // Cada recurso se decodifica con los modelos de la app
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

        // Un turno del agente en streaming (Nan)
        var turn = AgentTurn()
        for try await ev in api.chat("Responde sólo con la palabra listo.", channel: "chat") { turn.apply(ev) }
        #expect(turn.finished)
        #expect(turn.error == nil)
        #expect(!turn.text.isEmpty)
        if let cid = turn.conversationId { try await api.deleteConversation(cid) }

        // Crear y borrar una nota personal
        let note = try await api.createNote(title: "Prueba de iOS", content: "Creada por las pruebas de KairoKit")
        #expect(note.ownerId == user.id)
        #expect(!note.shared)
        try await api.deleteNote(note.id)

        await api.logout()
        #expect(await api.sessions.current() == nil)
    }
}
