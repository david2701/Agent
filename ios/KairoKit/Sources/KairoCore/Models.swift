import Foundation

// Models that mirror docs/API.md. Dates travel as ISO-8601 text and are converted with
// `KairoDate` when the UI needs them (same as in the Android app).

public struct User: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var email: String
    public var displayName: String
    public var timezone: String
    public var createdAt: String
    public var householdId: String?
    /// admin | member (of the family)
    public var role: String
    public var color: String?

    public init(id: String, email: String, displayName: String, timezone: String, createdAt: String, householdId: String? = nil, role: String = "member", color: String? = nil) {
        self.id = id; self.email = email; self.displayName = displayName; self.timezone = timezone
        self.createdAt = createdAt; self.householdId = householdId; self.role = role; self.color = color
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        email = try c.decode(String.self, forKey: .email)
        displayName = try c.decode(String.self, forKey: .displayName)
        timezone = try c.decodeIfPresent(String.self, forKey: .timezone) ?? "UTC"
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt) ?? ""
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId)
        role = try c.decodeIfPresent(String.self, forKey: .role) ?? "member"
        color = try c.decodeIfPresent(String.self, forKey: .color)
    }

    /// "Valentina" instead of "Valentina Gómez".
    public var firstName: String { displayName.split(separator: " ").first.map(String.init) ?? displayName }
}

public struct AuthResponse: Codable, Sendable {
    public var user: User
    public var accessToken: String
    public var refreshToken: String?
    public var expiresIn: Int
    public var deviceId: String
}

public struct FileRef: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var url: String
    public var contentType: String?

    public init(id: String, url: String, contentType: String? = nil) {
        self.id = id; self.url = url; self.contentType = contentType
    }
}

public struct Note: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var title: String
    public var content: String
    public var tags: [String]
    public var pinned: Bool
    public var source: String
    public var attachments: [FileRef]
    public var createdAt: String
    public var updatedAt: String
    public var score: Double?
    /// Family: every household member can see and edit it.
    public var shared: Bool
    public var ownerId: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        content = try c.decodeIfPresent(String.self, forKey: .content) ?? ""
        tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
        attachments = try c.decodeIfPresent([FileRef].self, forKey: .attachments) ?? []
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        score = try c.decodeIfPresent(Double.self, forKey: .score)
        shared = try c.decodeIfPresent(Bool.self, forKey: .shared) ?? false
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId)
    }
}

public struct Project: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var color: String?
    public var sort: Double?
    public var createdAt: String
}

public struct KTask: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var title: String
    public var notes: String
    /// todo | doing | done
    public var status: String
    /// low | medium | high | urgent
    public var priority: String
    public var dueDate: String?
    public var dueAt: String?
    public var projectId: String?
    public var projectName: String?
    public var tags: [String]
    public var sort: Double
    public var source: String
    public var completedAt: String?
    public var createdAt: String
    public var updatedAt: String
    public var shared: Bool
    public var ownerId: String?
    /// Assignee (a family member); assigning it to someone else makes it a family item.
    public var assigneeId: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        notes = try c.decodeIfPresent(String.self, forKey: .notes) ?? ""
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "todo"
        priority = try c.decodeIfPresent(String.self, forKey: .priority) ?? "medium"
        dueDate = try c.decodeIfPresent(String.self, forKey: .dueDate)
        dueAt = try c.decodeIfPresent(String.self, forKey: .dueAt)
        projectId = try c.decodeIfPresent(String.self, forKey: .projectId)
        projectName = try c.decodeIfPresent(String.self, forKey: .projectName)
        tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        sort = try c.decodeIfPresent(Double.self, forKey: .sort) ?? 0
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
        completedAt = try c.decodeIfPresent(String.self, forKey: .completedAt)
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        shared = try c.decodeIfPresent(Bool.self, forKey: .shared) ?? false
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId)
        assigneeId = try c.decodeIfPresent(String.self, forKey: .assigneeId)
    }

    public var isDone: Bool { status == "done" }
}

public struct Place: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var latitude: Double
    public var longitude: Double
    public var radiusM: Int
    public var address: String?
    public var createdAt: String
    public var shared: Bool
    public var ownerId: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        latitude = try c.decode(Double.self, forKey: .latitude)
        longitude = try c.decode(Double.self, forKey: .longitude)
        radiusM = try c.decodeIfPresent(Int.self, forKey: .radiusM) ?? 150
        address = try c.decodeIfPresent(String.self, forKey: .address)
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt) ?? ""
        shared = try c.decodeIfPresent(Bool.self, forKey: .shared) ?? false
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId)
    }
}

public struct Reminder: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var text: String
    public var dueAt: String?
    public var rrule: String?
    public var timezone: String
    public var placeId: String?
    public var placeName: String?
    /// arrive | leave
    public var placeTrigger: String?
    /// scheduled | fired | done | cancelled
    public var status: String
    public var nextFireAt: String?
    public var lastFiredAt: String?
    public var taskId: String?
    public var source: String
    public var createdAt: String
    public var updatedAt: String
    /// Who it rings for (if it is not you, you scheduled it for another member).
    public var ownerId: String?
    /// Who scheduled it, when it was another member.
    public var createdBy: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        text = try c.decode(String.self, forKey: .text)
        dueAt = try c.decodeIfPresent(String.self, forKey: .dueAt)
        rrule = try c.decodeIfPresent(String.self, forKey: .rrule)
        timezone = try c.decodeIfPresent(String.self, forKey: .timezone) ?? "UTC"
        placeId = try c.decodeIfPresent(String.self, forKey: .placeId)
        placeName = try c.decodeIfPresent(String.self, forKey: .placeName)
        placeTrigger = try c.decodeIfPresent(String.self, forKey: .placeTrigger)
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "scheduled"
        nextFireAt = try c.decodeIfPresent(String.self, forKey: .nextFireAt)
        lastFiredAt = try c.decodeIfPresent(String.self, forKey: .lastFiredAt)
        taskId = try c.decodeIfPresent(String.self, forKey: .taskId)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId)
        createdBy = try c.decodeIfPresent(String.self, forKey: .createdBy)
    }
}

public struct ListItem: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var text: String
    public var checked: Bool
    public var sort: Double?
    public var createdAt: String?
}

public struct ShoppingList: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var icon: String?
    public var items: [ListItem]
    public var createdAt: String
    public var updatedAt: String
    public var shared: Bool
    public var ownerId: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        icon = try c.decodeIfPresent(String.self, forKey: .icon)
        items = try c.decodeIfPresent([ListItem].self, forKey: .items) ?? []
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        shared = try c.decodeIfPresent(Bool.self, forKey: .shared) ?? false
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId)
    }

    public var pending: [ListItem] { items.filter { !$0.checked } }
}

public struct Event: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var title: String
    public var description: String
    public var location: String
    public var startAt: String
    public var endAt: String
    public var allDay: Bool
    public var rrule: String?
    public var timezone: String
    public var reminderMinutes: [Int]
    public var color: String?
    /// kairo | google
    public var source: String
    public var googleEventId: String?
    public var createdAt: String
    public var updatedAt: String
    public var shared: Bool
    public var ownerId: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
        location = try c.decodeIfPresent(String.self, forKey: .location) ?? ""
        startAt = try c.decode(String.self, forKey: .startAt)
        endAt = try c.decode(String.self, forKey: .endAt)
        allDay = try c.decodeIfPresent(Bool.self, forKey: .allDay) ?? false
        rrule = try c.decodeIfPresent(String.self, forKey: .rrule)
        timezone = try c.decodeIfPresent(String.self, forKey: .timezone) ?? "UTC"
        reminderMinutes = try c.decodeIfPresent([Int].self, forKey: .reminderMinutes) ?? []
        color = try c.decodeIfPresent(String.self, forKey: .color)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? "kairo"
        googleEventId = try c.decodeIfPresent(String.self, forKey: .googleEventId)
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        shared = try c.decodeIfPresent(Bool.self, forKey: .shared) ?? false
        ownerId = try c.decodeIfPresent(String.self, forKey: .ownerId)
    }

    public var start: Date? { KairoDate.parse(startAt) }
    public var end: Date? { KairoDate.parse(endAt) }
    /// Recurring events arrive expanded with the same id: the unique key is id + start.
    public var occurrenceId: String { "\(id)@\(startAt)" }
}

public struct Memory: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var content: String
    public var category: String
    public var createdAt: String
    public var updatedAt: String
}

public struct Conversation: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var title: String
    public var createdAt: String
    public var updatedAt: String
    public var preview: String?
}

public struct StoredMessage: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    /// user | assistant | tool
    public var role: String
    public var content: String
    public var toolName: String?
    public var meta: JSONValue?
    public var createdAt: String
}

public struct Recording: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var title: String
    /// recording | processing | done | failed
    public var status: String
    public var durationS: Int
    public var transcript: String
    public var summary: String
    public var keyPoints: [String]
    public var actionItems: [String]
    public var noteId: String?
    public var error: String?
    public var segments: Int
    public var createdAt: String
    public var updatedAt: String

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "recording"
        durationS = try c.decodeIfPresent(Int.self, forKey: .durationS) ?? 0
        transcript = try c.decodeIfPresent(String.self, forKey: .transcript) ?? ""
        summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
        keyPoints = try c.decodeIfPresent([String].self, forKey: .keyPoints) ?? []
        actionItems = try c.decodeIfPresent([String].self, forKey: .actionItems) ?? []
        noteId = try c.decodeIfPresent(String.self, forKey: .noteId)
        error = try c.decodeIfPresent(String.self, forKey: .error)
        segments = try c.decodeIfPresent(Int.self, forKey: .segments) ?? 0
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
    }
}

/// Entry in the activity history (not to be confused with `ActivityKit.Activity`).
public struct ActivityEntry: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var kind: String
    public var entityType: String
    public var entityId: String?
    public var summary: String
    public var source: String
    public var undoable: Bool
    public var undoneAt: String?
    public var createdAt: String
}

public struct Device: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var platform: String
    public var createdAt: String
    public var lastSeenAt: String
    public var current: Bool?
}

public struct EntityRef: Codable, Sendable, Hashable {
    /// note | task | reminder | list | event | memory | place | project | recording
    public var type: String
    public var id: String
    public var title: String
    public init(type: String, id: String, title: String) { self.type = type; self.id = id; self.title = title }
}

public struct WeatherDay: Codable, Sendable, Hashable {
    public var date: String
    public var min: Double
    public var max: Double
    public var rainChance: Int?
    public var description: String
}

public struct WeatherReport: Codable, Sendable, Hashable {
    public var place: String
    public var temperature: Double
    public var feelsLike: Double?
    public var description: String
    public var days: [WeatherDay]
}

public struct TodayData: Codable, Sendable, Hashable {
    public var date: String
    public var dateLabel: String
    public var greeting: String
    public var events: [Event]
    public var tasksDueToday: [KTask]
    public var tasksOverdue: [KTask]
    public var tasksInProgress: [KTask]
    public var reminders: [Reminder]
    public var firedReminders: [Reminder]
    public var recentNotes: [Note]
    public var lists: [ShoppingList]
    public var weather: WeatherReport?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        date = try c.decode(String.self, forKey: .date)
        dateLabel = try c.decodeIfPresent(String.self, forKey: .dateLabel) ?? ""
        greeting = try c.decodeIfPresent(String.self, forKey: .greeting) ?? ""
        events = try c.decodeIfPresent([Event].self, forKey: .events) ?? []
        tasksDueToday = try c.decodeIfPresent([KTask].self, forKey: .tasksDueToday) ?? []
        tasksOverdue = try c.decodeIfPresent([KTask].self, forKey: .tasksOverdue) ?? []
        tasksInProgress = try c.decodeIfPresent([KTask].self, forKey: .tasksInProgress) ?? []
        reminders = try c.decodeIfPresent([Reminder].self, forKey: .reminders) ?? []
        firedReminders = try c.decodeIfPresent([Reminder].self, forKey: .firedReminders) ?? []
        recentNotes = try c.decodeIfPresent([Note].self, forKey: .recentNotes) ?? []
        lists = try c.decodeIfPresent([ShoppingList].self, forKey: .lists) ?? []
        weather = try c.decodeIfPresent(WeatherReport.self, forKey: .weather)
    }
}

public struct UserSettings: Codable, Sendable, Hashable {
    public var chatModel: String = "deepseek-v4-flash"
    public var fallbackModel: String = "qwen3.8-flash"
    public var visionModel: String = "gemma4"
    public var imageModel: String = "flux-2-klein"
    public var fastMode: Bool = true
    public var voice: String = "ef_dora"
    public var voiceSpeed: Double = 1.0
    public var sttModel: String = "whisper"
    public var language: String = "en"
    public var wakeWord: String = "hey kairo"
    public var briefingEnabled: Bool = true
    public var briefingTime: String = "07:00"
    public var lastBriefingDate: String?
    public var homeCity: String?
    public var homeLatitude: Double?
    public var homeLongitude: Double?
    public var ntfyTopic: String?
    public var googleCalendarId: String = "primary"
    public var defaultEventReminderMinutes: Int = 15
    public var keepRecordingAudio: Bool = false

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = UserSettings()
        chatModel = try c.decodeIfPresent(String.self, forKey: .chatModel) ?? d.chatModel
        fallbackModel = try c.decodeIfPresent(String.self, forKey: .fallbackModel) ?? d.fallbackModel
        visionModel = try c.decodeIfPresent(String.self, forKey: .visionModel) ?? d.visionModel
        imageModel = try c.decodeIfPresent(String.self, forKey: .imageModel) ?? d.imageModel
        fastMode = try c.decodeIfPresent(Bool.self, forKey: .fastMode) ?? d.fastMode
        voice = try c.decodeIfPresent(String.self, forKey: .voice) ?? d.voice
        voiceSpeed = try c.decodeIfPresent(Double.self, forKey: .voiceSpeed) ?? d.voiceSpeed
        sttModel = try c.decodeIfPresent(String.self, forKey: .sttModel) ?? d.sttModel
        language = try c.decodeIfPresent(String.self, forKey: .language) ?? d.language
        wakeWord = try c.decodeIfPresent(String.self, forKey: .wakeWord) ?? d.wakeWord
        briefingEnabled = try c.decodeIfPresent(Bool.self, forKey: .briefingEnabled) ?? d.briefingEnabled
        briefingTime = try c.decodeIfPresent(String.self, forKey: .briefingTime) ?? d.briefingTime
        lastBriefingDate = try c.decodeIfPresent(String.self, forKey: .lastBriefingDate)
        homeCity = try c.decodeIfPresent(String.self, forKey: .homeCity)
        homeLatitude = try c.decodeIfPresent(Double.self, forKey: .homeLatitude)
        homeLongitude = try c.decodeIfPresent(Double.self, forKey: .homeLongitude)
        ntfyTopic = try c.decodeIfPresent(String.self, forKey: .ntfyTopic)
        googleCalendarId = try c.decodeIfPresent(String.self, forKey: .googleCalendarId) ?? d.googleCalendarId
        defaultEventReminderMinutes = try c.decodeIfPresent(Int.self, forKey: .defaultEventReminderMinutes) ?? d.defaultEventReminderMinutes
        keepRecordingAudio = try c.decodeIfPresent(Bool.self, forKey: .keepRecordingAudio) ?? d.keepRecordingAudio
    }
}

public struct SettingsResponse: Codable, Sendable {
    public var settings: UserSettings
    public var googleAvailable: Bool?
    public var ntfyAvailable: Bool?
}

public struct VoiceOption: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var description: String
}

public struct VoicesResponse: Codable, Sendable { public var voices: [VoiceOption] }

public struct EventAlert: Codable, Sendable, Hashable {
    public var eventId: String
    public var title: String
    public var startAt: String
    public var alertAt: String
    public var minutes: Int
    public var location: String?
    public var shared: Bool?
}

public struct DeviceSync: Codable, Sendable {
    public var serverTime: String
    public var reminders: [Reminder]
    public var places: [Place]
    public var eventAlerts: [EventAlert]
    public var briefingEnabled: Bool
    public var briefingTime: String
    /// Unread family notices (last 48 h), in case they did not arrive by push.
    public var notices: [Notice]
    public var pushRegistered: Bool

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try c.decode(String.self, forKey: .serverTime)
        reminders = try c.decodeIfPresent([Reminder].self, forKey: .reminders) ?? []
        places = try c.decodeIfPresent([Place].self, forKey: .places) ?? []
        eventAlerts = try c.decodeIfPresent([EventAlert].self, forKey: .eventAlerts) ?? []
        briefingEnabled = try c.decodeIfPresent(Bool.self, forKey: .briefingEnabled) ?? true
        briefingTime = try c.decodeIfPresent(String.self, forKey: .briefingTime) ?? "07:00"
        notices = try c.decodeIfPresent([Notice].self, forKey: .notices) ?? []
        pushRegistered = try c.decodeIfPresent(Bool.self, forKey: .pushRegistered) ?? false
    }
}

// MARK: - Family

public struct Household: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var createdAt: String
}

public struct Member: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var displayName: String
    public var email: String
    /// admin | member
    public var role: String
    public var joinedAt: String

    public var firstName: String { displayName.split(separator: " ").first.map(String.init) ?? displayName }
    public var initials: String {
        let parts = displayName.split(separator: " ").prefix(2)
        return parts.compactMap { $0.first.map(String.init) }.joined().uppercased()
    }
    public var isAdmin: Bool { role == "admin" }
}

public struct FamilyInfo: Codable, Sendable, Hashable {
    public var household: Household
    public var members: [Member]
    public var me: String
    public var isAdmin: Bool

    public func member(_ id: String?) -> Member? { id.flatMap { m in members.first { $0.id == m } } }
    /// The other members (not you).
    public var others: [Member] { members.filter { $0.id != me } }
    /// The owner's first name for things that are not yours; nil if it is yours.
    public func ownerName(_ ownerId: String?) -> String? {
        guard let ownerId, ownerId != me else { return nil }
        return member(ownerId)?.firstName ?? L10n.tr("another member")
    }
}

public struct Invitation: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    /// invite (new member) | reset (access code for an existing member)
    public var kind: String
    public var name: String
    public var role: String
    public var targetUserId: String?
    public var createdAt: String
    public var expiresAt: String
    public var usedAt: String?
}

public struct InviteCreated: Codable, Sendable, Hashable {
    public var invitation: Invitation
    public var code: String
    public var link: String
}

/// What a code opens before joining (`GET /auth/invite/{code}`).
public struct InvitePreview: Codable, Sendable, Hashable {
    public var kind: String
    public var householdName: String
    public var name: String?
    public var email: String?
}

/// Family notice. Arrives via `GET /notices`, `family.notice` (WebSocket or push) and `/sync/device`.
public struct Notice: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    /// shared | updated | assigned | reminder | message (and "test" in the push test)
    public var kind: String
    public var title: String
    public var body: String
    public var actorId: String?
    public var entityType: String?
    public var entityId: String?
    public var createdAt: String
    public var readAt: String?

    public init(id: String, kind: String, title: String, body: String, actorId: String? = nil, entityType: String? = nil, entityId: String? = nil, createdAt: String = "", readAt: String? = nil) {
        self.id = id; self.kind = kind; self.title = title; self.body = body; self.actorId = actorId
        self.entityType = entityType; self.entityId = entityId; self.createdAt = createdAt; self.readAt = readAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? "message"
        title = try c.decode(String.self, forKey: .title)
        body = try c.decodeIfPresent(String.self, forKey: .body) ?? ""
        actorId = try c.decodeIfPresent(String.self, forKey: .actorId)
        entityType = try c.decodeIfPresent(String.self, forKey: .entityType)
        entityId = try c.decodeIfPresent(String.self, forKey: .entityId)
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt) ?? ""
        readAt = try c.decodeIfPresent(String.self, forKey: .readAt)
    }
}

public struct NoticesResponse: Codable, Sendable {
    public var notices: [Notice]
    public var unread: Int
}

public struct BriefingResponse: Codable, Sendable { public var text: String }
public struct PairCode: Codable, Sendable { public var code: String; public var expiresIn: Int? }
public struct ApiErrorBody: Codable, Sendable { public var error: String }
public struct ModelsResponse: Codable, Sendable { public var models: [String] }
public struct CreatedCount: Codable, Sendable { public var created: Int }
public struct Delivered: Codable, Sendable { public var delivered: Int }
public struct Sent: Codable, Sendable { public var sent: Int }
public struct UserEnvelope: Codable, Sendable { public var user: User }
public struct TagsResponse: Codable, Sendable { public var tags: [String] }
public struct OkResponse: Codable, Sendable { public var ok: Bool? }

/// Native Apple push (APNs) registration for this device.
public struct ApnsStatus: Codable, Sendable { public var available: Bool; public var registeredDevices: Int }

/// WebSocket and push event: `{type, data}`.
public struct ServerEvent: Codable, Sendable, Hashable {
    public var type: String
    public var data: JSONValue

    public init(type: String, data: JSONValue = .object([:])) { self.type = type; self.data = data }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = try c.decode(String.self, forKey: .type)
        data = try c.decodeIfPresent(JSONValue.self, forKey: .data) ?? .object([:])
    }
}
