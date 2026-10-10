import Foundation
import ActivityKit
import AlarmKit
import SwiftUI

/// Live Activity mientras hablas con Kairo: Isla Dinámica y pantalla bloqueada.
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

/// Live Activity de una grabación de reunión en curso.
nonisolated struct RecordingActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        var startedAt: Date
        var segments: Int
        var paused: Bool
        var processing: Bool
    }

    var title: String
}

/// Próximo evento (cuenta regresiva). La inicia la app o el servidor por push.
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

/// Datos de los temporizadores y alarmas que Kairo programa con AlarmKit (los usa su Live Activity).
nonisolated struct KairoAlarmMetadata: AlarmMetadata {
    var label: String
    var kind: String

    init(label: String = "Kairo", kind: String = "timer") {
        self.label = label
        self.kind = kind
    }
}
