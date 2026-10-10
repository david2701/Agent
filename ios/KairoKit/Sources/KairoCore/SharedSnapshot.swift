import Foundation

/// Resumen que la app deja en el contenedor compartido (App Group) para los widgets, las Live Activities,
/// los controles y el reloj. Así muestran datos al instante sin pedir nada a la red.
public struct SharedSnapshot: Codable, Sendable, Hashable {
    public struct Item: Codable, Sendable, Hashable, Identifiable {
        public var id: String
        public var title: String
        public var subtitle: String
        public var date: Date?
        public var shared: Bool
        public var done: Bool

        public init(id: String, title: String, subtitle: String = "", date: Date? = nil, shared: Bool = false, done: Bool = false) {
            self.id = id; self.title = title; self.subtitle = subtitle; self.date = date; self.shared = shared; self.done = done
        }
    }

    public struct ListSnapshot: Codable, Sendable, Hashable, Identifiable {
        public var id: String
        public var name: String
        public var shared: Bool
        public var items: [Item]
    }

    public var updatedAt: Date
    public var userName: String
    public var greeting: String
    public var dateLabel: String
    public var events: [Item]
    public var tasks: [Item]
    public var reminders: [Item]
    public var lists: [ListSnapshot]
    public var weather: String?
    public var unreadNotices: Int

    public init(updatedAt: Date = .now, userName: String = "", greeting: String = "", dateLabel: String = "", events: [Item] = [], tasks: [Item] = [], reminders: [Item] = [], lists: [ListSnapshot] = [], weather: String? = nil, unreadNotices: Int = 0) {
        self.updatedAt = updatedAt; self.userName = userName; self.greeting = greeting; self.dateLabel = dateLabel
        self.events = events; self.tasks = tasks; self.reminders = reminders; self.lists = lists; self.weather = weather; self.unreadNotices = unreadNotices
    }

    /// Construye el resumen a partir de `/today`.
    public init(today t: TodayData, userName: String, unreadNotices: Int, family: FamilyInfo?) {
        func owner(_ id: String?) -> String { family?.ownerName(id).map { " · de \($0)" } ?? "" }
        let events = t.events.compactMap { e -> Item? in
            guard let start = e.start else { return nil }
            let when = e.allDay ? "Todo el día" : KairoDate.time(start)
            return Item(id: e.occurrenceId, title: e.title, subtitle: when + (e.location.isEmpty ? "" : " · \(e.location)"), date: start, shared: e.shared)
        }
        let tasks = (t.tasksOverdue + t.tasksDueToday + t.tasksInProgress).reduce(into: [KTask]()) { acc, task in
            if !acc.contains(where: { $0.id == task.id }) { acc.append(task) }
        }.map { task in
            let due = task.dueDate.flatMap { KairoDate.day($0) }.map { KairoDate.humanDay($0) } ?? (task.status == "doing" ? "En curso" : "")
            return Item(id: task.id, title: task.title, subtitle: due + owner(task.ownerId), date: task.dueDate.flatMap { KairoDate.day($0) }, shared: task.shared, done: task.isDone)
        }
        let reminders = t.reminders.compactMap { r -> Item? in
            guard let at = KairoDate.parse(r.nextFireAt) else { return nil }
            return Item(id: r.id, title: r.text, subtitle: KairoDate.time(at), date: at)
        }
        let lists = t.lists.map { l in
            ListSnapshot(id: l.id, name: l.name, shared: l.shared, items: l.items.map { Item(id: $0.id, title: $0.text, done: $0.checked) })
        }
        let weather = t.weather.map { "\(Int($0.temperature.rounded()))° · \($0.description)" }
        self.init(updatedAt: .now, userName: userName, greeting: t.greeting, dateLabel: t.dateLabel, events: events, tasks: tasks,
                  reminders: reminders, lists: lists, weather: weather, unreadNotices: unreadNotices)
    }

    /// Próximo evento que todavía no terminó.
    public func nextEvent(after now: Date = .now) -> Item? {
        events.first { ($0.date ?? .distantPast).addingTimeInterval(3600) > now }
    }
}

/// Lectura y escritura del resumen en un directorio compartido.
public struct SnapshotStore: Sendable {
    public let directory: URL

    public init(directory: URL) { self.directory = directory }

    private var file: URL { directory.appendingPathComponent("snapshot.json") }

    public func save(_ s: SharedSnapshot) {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        guard let data = try? enc.encode(s) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: file, options: .atomic)
    }

    public func load() -> SharedSnapshot? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        return try? dec.decode(SharedSnapshot.self, from: data)
    }
}
