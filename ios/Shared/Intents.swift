import AppIntents
import Foundation
import KairoCore
import WidgetKit

/// Lo que la app debe hacer al abrirse desde un widget, un control, Siri o el botón de Acción.
/// Se deja en el contenedor compartido porque el intent puede correr en el proceso de los widgets.
nonisolated enum PendingAction {
    private static let key = "kairo.pendingAction"

    static func set(_ action: String) {
        AppConfig.sharedDefaults.set(action, forKey: key)
        AppConfig.sharedDefaults.set(Date().timeIntervalSince1970, forKey: key + ".at")
    }

    /// La acción pendiente si es reciente (menos de un minuto), y la borra.
    static func take() -> String? {
        let d = AppConfig.sharedDefaults
        guard let a = d.string(forKey: key), Date().timeIntervalSince1970 - d.double(forKey: key + ".at") < 60 else { return nil }
        d.removeObject(forKey: key)
        return a
    }
}

/// Avisa a la app (si el intent corre en su proceso) de una acción recién pedida.
nonisolated extension Notification.Name {
    static let kairoPendingAction = Notification.Name("kairo.pendingAction")
}

// MARK: - Hablar

nonisolated struct TalkToKairoIntent: AppIntent {
    static let title: LocalizedStringResource = "Hablar con Kairo"
    static let description = IntentDescription("Abre Kairo y empieza a escucharte.")
    static let openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        PendingAction.set("talk")
        NotificationCenter.default.post(name: .kairoPendingAction, object: "talk")
        return .result()
    }
}

// MARK: - Abrir una sección

nonisolated enum KairoSection: String, AppEnum {
    case today, chat, tasks, calendar, notes, lists, reminders, family

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Sección de Kairo"
    static let caseDisplayRepresentations: [KairoSection: DisplayRepresentation] = [
        .today: "Hoy", .chat: "Conversación", .tasks: "Tareas", .calendar: "Calendario",
        .notes: "Notas", .lists: "Listas", .reminders: "Recordatorios", .family: "Familia",
    ]
}

nonisolated struct OpenKairoIntent: AppIntent {
    static let title: LocalizedStringResource = "Abrir en Kairo"
    static let openAppWhenRun = true

    @Parameter(title: "Sección", default: .today)
    var section: KairoSection

    init() {}
    init(section: KairoSection) { self.section = section }

    func perform() async throws -> some IntentResult {
        PendingAction.set("section:\(section.rawValue)")
        NotificationCenter.default.post(name: .kairoPendingAction, object: "section:\(section.rawValue)")
        return .result()
    }
}

// MARK: - Widgets interactivos

nonisolated struct ToggleListItemIntent: AppIntent {
    static let title: LocalizedStringResource = "Marcar en la lista"
    static let isDiscoverable = false

    @Parameter(title: "Lista") var listId: String
    @Parameter(title: "Elemento") var itemId: String
    @Parameter(title: "Marcado") var checked: Bool

    init() {}
    init(listId: String, itemId: String, checked: Bool) {
        self.listId = listId
        self.itemId = itemId
        self.checked = checked
    }

    func perform() async throws -> some IntentResult {
        // Respuesta inmediata en el widget; luego se confirma con el servidor
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

nonisolated struct CompleteTaskIntent: AppIntent {
    static let title: LocalizedStringResource = "Completar tarea"
    static let isDiscoverable = false

    @Parameter(title: "Tarea") var taskId: String

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

// MARK: - Configuración del widget de lista

nonisolated struct KairoListEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Lista"
    static let defaultQuery = KairoListQuery()

    var id: String
    var name: String
    var shared: Bool

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: shared ? "Familiar" : nil)
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

nonisolated struct SelectListIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Lista"
    static let description = IntentDescription("Elige qué lista mostrar.")

    @Parameter(title: "Lista")
    var list: KairoListEntity?

    init() {}
}

// MARK: - Datos para widgets

nonisolated enum WidgetKinds {
    static let today = "KairoToday"
    static let list = "KairoList"
    static let talk = "KairoTalk"
    static let next = "KairoNext"
}

nonisolated enum WidgetData {
    /// Modifica el resumen compartido (cambios optimistas desde un widget).
    static func update(_ change: (inout SharedSnapshot) -> Void) {
        let store = AppConfig.snapshots
        guard var s = store.load() else { return }
        change(&s)
        store.save(s)
    }
}
