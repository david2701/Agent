import AppIntents
import ActivityKit
import Foundation
import KairoCore

/// Pregúntale algo a Kairo desde Siri o Atajos y escucha la respuesta sin abrir la app.
struct AskKairoIntent: AppIntent {
    static let title: LocalizedStringResource = "Preguntarle a Kairo"
    static let description = IntentDescription("Kairo responde y hace lo que le pidas: notas, tareas, recordatorios, tu agenda…")

    @Parameter(title: "Pregunta", requestValueDialog: "¿Qué le digo a Kairo?")
    var question: String

    init() {}

    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        guard await AppModel.shared.api.sessions.current() != nil else {
            return .result(value: "", dialog: "Abre Kairo e inicia sesión primero.")
        }
        let turn = try await AppModel.shared.api.ask(question, channel: "voice")
        await MainActor.run { for a in turn.actions { if let e = a.entity { AppModel.shared.bump(e.type) } } }
        let reply = (turn.spoken?.isEmpty == false ? turn.spoken : nil) ?? turn.text
        return .result(value: turn.text, dialog: IntentDialog(stringLiteral: reply.isEmpty ? "Listo." : reply))
    }
}

/// Frases de Siri y acciones del botón de Acción y de Atajos.
struct KairoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: TalkToKairoIntent(),
                    phrases: ["Habla con \(.applicationName)", "Hablar con \(.applicationName)", "Abre \(.applicationName) y escúchame"],
                    shortTitle: "Hablar con Kairo", systemImageName: "waveform.circle")
        AppShortcut(intent: AskKairoIntent(),
                    phrases: ["Pregúntale a \(.applicationName)", "Dile a \(.applicationName)"],
                    shortTitle: "Preguntar", systemImageName: "text.bubble")
        AppShortcut(intent: OpenKairoIntent(),
                    phrases: ["Abre \(.applicationName) en \(\.$section)", "Mi día en \(.applicationName)"],
                    shortTitle: "Abrir sección", systemImageName: "square.grid.2x2")
    }

    static let shortcutTileColor: ShortcutTileColor = .orange
}

/// Live Activity con la cuenta regresiva del próximo evento (cuando falta menos de 45 minutos).
@MainActor
enum EventActivityController {
    static func refresh(_ snap: SharedSnapshot) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let now = Date.now
        let next = snap.events.first { e in
            guard let d = e.date else { return false }
            return d > now && d.timeIntervalSince(now) <= 45 * 60
        }
        let running = ActivityKit.Activity<EventActivityAttributes>.activities
        for a in running where a.attributes.eventId != next?.id {
            nonisolated(unsafe) let live = a
            Task { await live.end(nil, dismissalPolicy: .immediate) }
        }
        guard let n = next, let start = n.date, !running.contains(where: { $0.attributes.eventId == n.id }) else { return }
        let location = n.subtitle.components(separatedBy: " · ").dropFirst().joined(separator: " · ")
        let state = EventActivityAttributes.ContentState(startAt: start, location: location, cancelled: false)
        _ = try? ActivityKit.Activity.request(
            attributes: EventActivityAttributes(eventId: n.id, title: n.title, shared: n.shared),
            content: .init(state: state, staleDate: start.addingTimeInterval(15 * 60)),
            pushType: nil
        )
    }
}
