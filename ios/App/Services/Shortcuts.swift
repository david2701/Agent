import AppIntents
import ActivityKit
import Foundation
import KairoCore

/// Ask Kairo something from Siri or Shortcuts and hear the answer without opening the app.
struct AskKairoIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask Kairo"
    static let description = IntentDescription("Kairo answers and does what you ask: notes, tasks, reminders, your calendar…")

    @Parameter(title: "Question", requestValueDialog: "What should I tell Kairo?")
    var question: String

    init() {}

    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        guard await AppModel.shared.api.sessions.current() != nil else {
            return .result(value: "", dialog: "Open Kairo and sign in first.")
        }
        let turn = try await AppModel.shared.api.ask(question, channel: "voice")
        await MainActor.run { for a in turn.actions { if let e = a.entity { AppModel.shared.bump(e.type) } } }
        let reply = (turn.spoken?.isEmpty == false ? turn.spoken : nil) ?? turn.text
        if reply.isEmpty { return .result(value: turn.text, dialog: "Done.") }
        return .result(value: turn.text, dialog: IntentDialog(stringLiteral: reply))
    }
}

/// Siri phrases and actions for the Action button and Shortcuts. Spanish phrases live in AppShortcuts.xcstrings.
struct KairoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: TalkToKairoIntent(),
                    phrases: ["Talk to \(.applicationName)", "Chat with \(.applicationName)", "Open \(.applicationName) and listen to me"],
                    shortTitle: "Talk to Kairo", systemImageName: "waveform.circle")
        AppShortcut(intent: AskKairoIntent(),
                    phrases: ["Ask \(.applicationName)", "Tell \(.applicationName)"],
                    shortTitle: "Ask", systemImageName: "text.bubble")
        AppShortcut(intent: OpenKairoIntent(),
                    phrases: ["Open \(.applicationName) on \(\.$section)", "My day in \(.applicationName)"],
                    shortTitle: "Open section", systemImageName: "square.grid.2x2")
    }

    static let shortcutTileColor: ShortcutTileColor = .orange
}

/// Live Activity with the countdown to the next event (when less than 45 minutes remain).
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
