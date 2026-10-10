import Foundation
import ActivityKit
import AlarmKit
import SwiftUI

/// Live Activity while you talk to Kairo: Dynamic Island and lock screen.
nonisolated struct VoiceActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        /// listening | thinking | speaking | done | error
        var phase: String
        var transcript: String
        var reply: String
        var working: String?
    }

    var startedAt: Date
}

/// Live Activity for a meeting recording in progress.
nonisolated struct RecordingActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        var startedAt: Date
        var segments: Int
        var paused: Bool
        var processing: Bool
    }

    var title: String
}

/// Next event (countdown). Started by the app or by the server through push.
nonisolated struct EventActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        var startAt: Date
        var location: String
        var cancelled: Bool
    }

    var eventId: String
    var title: String
    var shared: Bool
}

/// Data of the timers and alarms Kairo schedules with AlarmKit (used by its Live Activity).
nonisolated struct KairoAlarmMetadata: AlarmMetadata {
    var label: String
    var kind: String

    init(label: String = "Kairo", kind: String = "timer") {
        self.label = label
        self.kind = kind
    }
}
