import Foundation

/// Agent SSE events (see docs/API.md, "Agent (SSE)").
public enum AgentEvent: Sendable, Hashable {
    case start(conversationId: String)
    case transcript(String)
    case delta(String, step: Int)
    case toolStart(callId: String, name: String, label: String)
    case toolEnd(callId: String, name: String, ok: Bool, summary: String, entity: EntityRef?)
    case deviceAction(DeviceAction)
    case attachment(FileRef)
    case done(conversationId: String, messageId: String, text: String, spoken: String)
    case error(String)
}

/// Action the agent asks the phone to perform: a timer or an alarm.
public enum DeviceAction: Sendable, Hashable {
    case timer(seconds: Int, label: String)
    case alarm(hour: Int, minute: Int, label: String, days: [String])
    case unknown(JSONValue)

    public init(json: JSONValue) {
        switch json["type"]?.string {
        case "timer":
            self = .timer(seconds: json["seconds"]?.int ?? 60, label: json["label"]?.string ?? "Kairo")
        case "alarm":
            let days: [String] = {
                if case .array(let a)? = json["days"] { return a.compactMap(\.string) }
                return []
            }()
            self = .alarm(hour: json["hour"]?.int ?? 7, minute: json["minute"]?.int ?? 0, label: json["label"]?.string ?? "Kairo", days: days)
        default:
            self = .unknown(json)
        }
    }
}

extension AgentEvent: Decodable {
    private enum K: String, CodingKey {
        case type, conversationId, text, step, callId, name, label, ok, summary, entity, action, file, messageId, spoken, message
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        let type = try c.decode(String.self, forKey: .type)
        func s(_ k: K) -> String { (try? c.decodeIfPresent(String.self, forKey: k)) ?? "" }
        switch type {
        case "start": self = .start(conversationId: s(.conversationId))
        case "transcript": self = .transcript(s(.text))
        case "delta": self = .delta(s(.text), step: (try? c.decodeIfPresent(Int.self, forKey: .step)) ?? 0)
        case "tool_start": self = .toolStart(callId: s(.callId), name: s(.name), label: s(.label))
        case "tool_end":
            self = .toolEnd(
                callId: s(.callId), name: s(.name),
                ok: (try? c.decodeIfPresent(Bool.self, forKey: .ok)) ?? true,
                summary: s(.summary),
                entity: try? c.decodeIfPresent(EntityRef.self, forKey: .entity)
            )
        case "device_action": self = .deviceAction(DeviceAction(json: (try? c.decode(JSONValue.self, forKey: .action)) ?? .null))
        case "attachment": self = .attachment(try c.decode(FileRef.self, forKey: .file))
        case "done": self = .done(conversationId: s(.conversationId), messageId: s(.messageId), text: s(.text), spoken: s(.spoken))
        case "error": self = .error(s(.message).isEmpty ? L10n.tr("Agent error") : s(.message))
        default: self = .error(L10n.tr("Unknown event: %@", strings: type))
        }
    }
}

/// Incremental Server-Sent Events reader: takes lines and returns the JSON of each `data:`.
/// The backend sends one event per `data: {...}` line followed by an empty line.
public struct SSEParser: Sendable {
    private var buffer: [String] = []

    public init() {}

    /// Processes one line (without the line break). Returns the content of a complete event, if any.
    public mutating func feed(_ line: String) -> String? {
        let l = line.hasSuffix("\r") ? String(line.dropLast()) : line
        if l.isEmpty {
            defer { buffer.removeAll() }
            return buffer.isEmpty ? nil : buffer.joined(separator: "\n")
        }
        if l.hasPrefix(":") { return nil } // comment (": kairo")
        if l.hasPrefix("data:") {
            var v = l.dropFirst(5)
            if v.first == " " { v = v.dropFirst() }
            buffer.append(String(v))
            // The backend never splits a JSON over several lines: deliver it right away without waiting for the empty one
            if buffer.count == 1, v.first == "{", v.last == "}" {
                defer { buffer.removeAll() }
                return buffer[0]
            }
        }
        return nil
    }

    /// Whatever is left when the stream closes.
    public mutating func finish() -> String? {
        defer { buffer.removeAll() }
        return buffer.isEmpty ? nil : buffer.joined(separator: "\n")
    }

    public static func decode(_ payload: String) -> AgentEvent? {
        guard let data = payload.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(AgentEvent.self, from: data)
    }
}

/// State of an agent turn built from the events (used by chat, voice and Siri).
public struct AgentTurn: Sendable, Hashable {
    public struct Action: Sendable, Hashable, Identifiable {
        public var id: String
        public var name: String
        public var label: String
        public var running: Bool
        public var ok: Bool
        public var summary: String
        public var entity: EntityRef?
    }

    public var conversationId: String?
    public var transcript: String?
    public var partial: String = ""
    public var final: String?
    public var spoken: String?
    public var actions: [Action] = []
    public var attachments: [FileRef] = []
    public var deviceActions: [DeviceAction] = []
    public var error: String?
    public var finished = false

    public init() {}

    /// Text shown while it arrives and when it finishes.
    public var text: String { final ?? partial }

    /// What it is doing right now ("Saving note"), if a tool is running.
    public var working: String? { actions.last(where: \.running)?.label }

    public mutating func apply(_ ev: AgentEvent) {
        switch ev {
        case .start(let id): conversationId = id
        case .transcript(let t): transcript = t
        case .delta(let t, _): partial += t
        case .toolStart(let id, let name, let label):
            actions.append(Action(id: id, name: name, label: label, running: true, ok: true, summary: label, entity: nil))
            partial = "" // text before tool calls is not kept (same as on the server)
        case .toolEnd(let id, let name, let ok, let summary, let entity):
            if let i = actions.firstIndex(where: { $0.id == id }) {
                actions[i].running = false; actions[i].ok = ok; actions[i].summary = summary; actions[i].entity = entity
            } else {
                actions.append(Action(id: id, name: name, label: summary, running: false, ok: ok, summary: summary, entity: entity))
            }
        case .deviceAction(let a): deviceActions.append(a)
        case .attachment(let f): attachments.append(f)
        case .done(let cid, _, let text, let spoken):
            conversationId = cid
            final = text
            self.spoken = spoken.isEmpty ? text : spoken
            finished = true
        case .error(let m): error = m; finished = true
        }
    }
}
