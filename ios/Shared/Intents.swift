import AppIntents
import Foundation
import KairoCore
import WidgetKit

/// What the app must do when it is opened from a widget, a control, Siri or the Action button.
/// It is left in the shared container because the intent may run in the widgets' process.
nonisolated enum PendingAction {
    private static let key = "kairo.pendingAction"

    static func set(_ action: String) {
        AppConfig.sharedDefaults.set(action, forKey: key)
        AppConfig.sharedDefaults.set(Date().timeIntervalSince1970, forKey: key + ".at")
    }

    /// The pending action if it is recent (less than a minute old), and deletes it.
    static func take() -> String? {
        let d = AppConfig.sharedDefaults
        guard let a = d.string(forKey: key), Date().timeIntervalSince1970 - d.double(forKey: key + ".at") < 60 else { return nil }
        d.removeObject(forKey: key)
        return a
    }
}

/// Tells the app (if the intent runs in its process) about a just-requested action.
nonisolated extension Notification.Name {
    static let kairoPendingAction = Notification.Name("kairo.pendingAction")
}

// MARK: - Talk

struct TalkToKairoIntent: AppIntent {
    static let title: LocalizedStringResource = "Talk to Kairo"
    static let description = IntentDescription("Opens Kairo and starts listening.")
    static let openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        PendingAction.set("talk")
        NotificationCenter.default.post(name: .kairoPendingAction, object: "talk")
        return .result()
    }
}

// MARK: - Open a section

nonisolated enum KairoSection: String, AppEnum {
    case today, chat, tasks, calendar, notes, lists, reminders, family

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Kairo section"
    static let caseDisplayRepresentations: [KairoSection: DisplayRepresentation] = [
        .today: "Today", .chat: "Chat", .tasks: "Tasks", .calendar: "Calendar",
        .notes: "Notes", .lists: "Lists", .reminders: "Reminders", .family: "Family",
    ]
}

struct OpenKairoIntent: AppIntent {
    static let title: LocalizedStringResource = "Open in Kairo"
    static let openAppWhenRun = true

    @Parameter(title: "Section", default: .today)
    var section: KairoSection

    init() {}
    init(section: KairoSection) { self.section = section }

    func perform() async throws -> some IntentResult {
        PendingAction.set("section:\(section.rawValue)")
        NotificationCenter.default.post(name: .kairoPendingAction, object: "section:\(section.rawValue)")
        return .result()
    }
}

// MARK: - Interactive widgets

struct ToggleListItemIntent: AppIntent {
    static let title: LocalizedStringResource = "Check off in list"
    static let isDiscoverable = false

    @Parameter(title: "List") var listId: String
    @Parameter(title: "Item") var itemId: String
    @Parameter(title: "Checked") var checked: Bool

    init() {}
    init(listId: String, itemId: String, checked: Bool) {
        self.listId = listId
        self.itemId = itemId
        self.checked = checked
    }

    func perform() async throws -> some IntentResult {
        // Immediate response in the widget; then it is confirmed with the server
        WidgetData.update { snap in
            guard let li = snap.lists.firstIndex(where: { $0.id == listId }),
                  let ii = snap.lists[li].items.firstIndex(where: { $0.id == itemId }) else { return }
            snap.lists[li].items[ii].done = checked
        }
        if await ExtensionAPI.isReady() {
            _ = try? await ExtensionAPI.shared.setItem(listId, itemId, checked: checked)
        }
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKinds.list)
        return .result()
    }
}

struct CompleteTaskIntent: AppIntent {
    static let title: LocalizedStringResource = "Complete task"
    static let isDiscoverable = false

    @Parameter(title: "Task") var taskId: String

    init() {}
    init(taskId: String) { self.taskId = taskId }

    func perform() async throws -> some IntentResult {
        WidgetData.update { snap in
            if let i = snap.tasks.firstIndex(where: { $0.id == taskId }) { snap.tasks[i].done = true }
        }
        if await ExtensionAPI.isReady() {
            _ = try? await ExtensionAPI.shared.updateTask(taskId, ["status": "done"])
        }
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKinds.today)
        return .result()
    }
}

// MARK: - List widget configuration

nonisolated struct KairoListEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "List"
    static let defaultQuery = KairoListQuery()

    var id: String
    var name: String
    var shared: Bool

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: shared ? "Family" : nil)
    }
}

nonisolated struct KairoListQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [KairoListEntity] {
        try await suggestedEntities().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [KairoListEntity] {
        (AppConfig.snapshots.load()?.lists ?? []).map { KairoListEntity(id: $0.id, name: $0.name, shared: $0.shared) }
    }

    func defaultResult() async -> KairoListEntity? { try? await suggestedEntities().first }
}

struct SelectListIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "List"
    static let description = IntentDescription("Choose which list to show.")

    @Parameter(title: "List")
    var list: KairoListEntity?

    init() {}
}

// MARK: - Widget data

nonisolated enum WidgetKinds {
    static let today = "KairoToday"
    static let list = "KairoList"
    static let talk = "KairoTalk"
    static let next = "KairoNext"
}

nonisolated enum WidgetData {
    /// Modifies the shared summary (optimistic changes from a widget).
    static func update(_ change: (inout SharedSnapshot) -> Void) {
        let store = AppConfig.snapshots
        guard var s = store.load() else { return }
        change(&s)
        store.save(s)
    }
}
