import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Evento del servidor ya interpretado. Llega igual por WebSocket y por push (APNs).
public enum RealtimeEvent: Sendable, Hashable {
    /// Cambió una colección ("note", "task", "list", "family"...): hay que recargarla.
    case changed(entity: String, id: String?, op: String)
    case reminderFire(id: String, text: String, fireAt: Date, createdBy: String?)
    case eventAlert(id: String, title: String, startAt: Date, alertAt: Date, minutes: Int, location: String, shared: Bool)
    case briefing(text: String, date: String)
    case recordingUpdated(id: String)
    case familyNotice(Notice)
    case other(String)

    public init(_ ev: ServerEvent) {
        let d = ev.data
        func s(_ k: String) -> String? { d[k]?.string }
        switch ev.type {
        case "entity.changed":
            self = .changed(entity: s("entity") ?? "", id: s("id"), op: s("op") ?? "updated")
        case "reminder.fire":
            self = .reminderFire(id: s("id") ?? "", text: s("text") ?? "Recordatorio", fireAt: KairoDate.parse(s("fireAt")) ?? .now, createdBy: s("createdBy"))
        case "event.alert":
            self = .eventAlert(
                id: s("id") ?? "", title: s("title") ?? "Evento", startAt: KairoDate.parse(s("startAt")) ?? .now,
                alertAt: KairoDate.parse(s("alertAt")) ?? .now, minutes: d["minutes"]?.int ?? 0,
                location: s("location") ?? "", shared: d["shared"]?.bool ?? false
            )
        case "briefing.ready":
            self = .briefing(text: s("text") ?? "", date: s("date") ?? "")
        case "recording.updated":
            self = .recordingUpdated(id: s("id") ?? "")
        case "family.notice":
            self = .familyNotice(Notice(
                id: s("id") ?? UUID().uuidString, kind: s("kind") ?? "message", title: s("title") ?? "Familia", body: s("body") ?? "",
                actorId: s("actorId"), entityType: s("entityType"), entityId: s("entityId"), createdAt: s("createdAt") ?? ""
            ))
        default:
            self = .other(ev.type)
        }
    }

    /// Clave para no mostrar dos veces el mismo aviso si llega por WebSocket, por push y por la alarma local.
    /// Coincide con la de `NotificationPlan` y con la app de Android.
    public var dedupeKey: String? {
        switch self {
        case .reminderFire(let id, _, let at, _): "rem:\(id):\(Int(at.timeIntervalSince1970))"
        case .eventAlert(let id, _, let start, _, let minutes, _, _): "ev:\(id):\(Int(start.timeIntervalSince1970)):\(minutes)"
        case .briefing(_, let date): "brief:\(date)"
        case .familyNotice(let n): "notice:\(n.id)"
        default: nil
        }
    }
}

#if canImport(Darwin)
public extension KairoAPI {
    /// Conexión en vivo con el servidor: reconecta con espera creciente y mantiene vivo el socket.
    nonisolated func realtime() -> AsyncStream<ServerEvent> {
        AsyncStream { continuation in
            let task = Task {
                var delay = 1.0
                while !Task.isCancelled {
                    do {
                        let url = try await self.webSocketURL()
                        let ws = URLSession.shared.webSocketTask(with: url)
                        ws.resume()
                        let pinger = Task {
                            while !Task.isCancelled {
                                try? await Task.sleep(for: .seconds(25))
                                try? await ws.send(.string("ping"))
                            }
                        }
                        defer {
                            pinger.cancel()
                            ws.cancel(with: .goingAway, reason: nil)
                        }
                        while !Task.isCancelled {
                            let message = try await ws.receive()
                            delay = 1
                            guard case .string(let text) = message,
                                  let ev = try? JSONDecoder().decode(ServerEvent.self, from: Data(text.utf8)),
                                  ev.type != "ping", ev.type != "pong" else { continue }
                            continuation.yield(ev)
                        }
                    } catch {
                        if Task.isCancelled { break }
                    }
                    try? await Task.sleep(for: .seconds(delay))
                    delay = min(delay * 2, 30)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func webSocketURL() async throws -> URL {
        guard let url = try await socketURL() else { throw APIError.noSession }
        return url
    }
}
#endif

extension KairoAPI {
    /// `wss://servidor/api/ws?access_token=...` (el WebSocket no admite cabeceras en todas las plataformas).
    func socketURL() async throws -> URL? {
        guard let base = await sessions.current()?.serverURL else { return nil }
        let token = try await currentAccessToken()
        let ws = base.replacingOccurrences(of: "https://", with: "wss://").replacingOccurrences(of: "http://", with: "ws://")
        return URL(string: ws + "/api/ws?access_token=\(token)")
    }
}
