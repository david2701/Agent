import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum APIError: Error, LocalizedError, Sendable, Equatable {
    case http(Int, String)
    case noSession
    case invalidServer
    case decoding(String)

    public var errorDescription: String? {
        switch self {
        case .http(let code, let message):
            if code == 0 { return message }
            if code == 429 { return L10n.tr("Too many attempts. Wait a minute.") }
            return message.isEmpty ? L10n.tr("The server responded %lld", code) : message
        case .noSession: return L10n.tr("Your session ended. Sign in again.")
        case .invalidServer: return L10n.tr("The server address isn't valid")
        case .decoding(let m): return L10n.tr("Unexpected server response (%@)", strings: m)
        }
    }

    public var status: Int { if case .http(let c, _) = self { c } else { 0 } }
}

/// Who is calling: the app, the watch, the widgets or the share extension.
public struct ClientInfo: Sendable {
    /// Cabecera `X-Kairo-Client`: ios | watch
    public var kind: String
    /// Device name shown in *Settings > Devices*.
    public var deviceName: String
    /// `platform` sent at sign-in.
    public var platform: String
    /// `X-Kairo-Capabilities`: timer, alarm, location
    public var capabilities: [String]

    public init(kind: String, deviceName: String, platform: String, capabilities: [String] = []) {
        self.kind = kind; self.deviceName = deviceName; self.platform = platform; self.capabilities = capabilities
    }
}

/// Kairo API client. Refreshes the access token only once even if several requests ask for it at the same time.
public actor KairoAPI {
    public let sessions: SessionStore
    public let client: ClientInfo
    private let urlSession: URLSession
    private var refreshTask: Task<Bool, Never>?
    private var onSessionExpired: (@Sendable () -> Void)?
    private var locationProvider: (@Sendable () async -> (Double, Double)?)?

    public init(sessions: SessionStore, client: ClientInfo, urlSession: URLSession = .shared) {
        self.sessions = sessions
        self.client = client
        self.urlSession = urlSession
    }

    public func setOnSessionExpired(_ f: @escaping @Sendable () -> Void) { onSessionExpired = f }
    public func setLocationProvider(_ f: @escaping @Sendable () async -> (Double, Double)?) { locationProvider = f }

    // MARK: - Server

    /// Normalizes what the person types ("kairo.example.com" -> "https://kairo.example.com").
    public static func normalizeServer(_ raw: String) throws -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix("/api") { s.removeLast(4) }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "https://" + s }
        guard let u = URL(string: s), let host = u.host, !host.isEmpty else { throw APIError.invalidServer }
        return s
    }

    public func serverURL() async -> String? { await sessions.current()?.serverURL }

    /// Absolute URL for the signed relative paths the server returns (attachments).
    public func absolute(_ url: String) async -> URL? {
        if url.hasPrefix("http") { return URL(string: url) }
        guard let base = await sessions.current()?.serverURL else { return nil }
        return URL(string: base + url)
    }

    // MARK: - Authentication

    @discardableResult
    public func login(server: String, email: String, password: String) async throws -> User {
        let base = try Self.normalizeServer(server)
        let body: JSONObject = [
            "email": .string(email.trimmingCharacters(in: .whitespaces)), "password": .string(password),
            "deviceName": .string(client.deviceName), "platform": .string(client.platform),
            "timezone": .string(TimeZone.current.identifier),
        ]
        let auth: AuthResponse = try await unauthenticated("POST", base, "/api/auth/login", body)
        return try await adopt(auth, server: base)
    }

    public func invitePreview(server: String, code: String) async throws -> InvitePreview {
        let base = try Self.normalizeServer(server)
        let clean = code.uppercased().filter { $0.isLetter || $0.isNumber }
        return try await unauthenticated("GET", base, "/api/auth/invite/\(clean)", nil)
    }

    @discardableResult
    public func join(server: String, code: String, email: String?, password: String, displayName: String?) async throws -> User {
        let base = try Self.normalizeServer(server)
        var body: JSONObject = [
            "code": .string(code), "password": .string(password), "deviceName": .string(client.deviceName),
            "platform": .string(client.platform), "timezone": .string(TimeZone.current.identifier),
        ]
        if let email { body["email"] = .string(email) }
        if let displayName { body["displayName"] = .string(displayName) }
        let auth: AuthResponse = try await unauthenticated("POST", base, "/api/auth/join", body)
        return try await adopt(auth, server: base)
    }

    /// Links this device with a 6-digit code generated by another one (the watch, the widgets).
    @discardableResult
    public func pair(server: String, code: String) async throws -> User {
        let base = try Self.normalizeServer(server)
        let body: JSONObject = ["code": .string(code), "deviceName": .string(client.deviceName), "platform": .string(client.platform)]
        let auth: AuthResponse = try await unauthenticated("POST", base, "/api/auth/pair", body)
        return try await adopt(auth, server: base)
    }

    /// Code to link another device of this same account.
    public func pairCode() async throws -> String {
        let r: PairCode = try await send("POST", "/api/auth/pair-code")
        return r.code
    }

    public func logout() async {
        _ = try? await sendRaw("POST", "/api/auth/logout", body: Data("{}".utf8), contentType: "application/json", retry: false)
        await sessions.clear()
    }

    private func adopt(_ auth: AuthResponse, server: String) async throws -> User {
        guard let refresh = auth.refreshToken else { throw APIError.decoding("missing refresh token") }
        await sessions.save(StoredSession(serverURL: server, user: auth.user, refreshToken: refresh, deviceId: auth.deviceId))
        await sessions.setAccess(auth.accessToken, expiresIn: auth.expiresIn)
        return auth.user
    }

    private func unauthenticated<T: Decodable>(_ method: String, _ base: String, _ path: String, _ body: JSONObject?) async throws -> T {
        guard let url = URL(string: base + path) else { throw APIError.invalidServer }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = method
        common(&req)
        if let body {
            req.httpBody = try JSONEncoder().encode(body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await perform(req)
        guard (200..<300).contains(resp.statusCode) else { throw Self.error(resp.statusCode, data) }
        return try Self.decode(data)
    }

    /// Refreshes the access token. Several simultaneous calls wait for the same refresh.
    private func refresh() async -> Bool {
        if let t = refreshTask { return await t.value }
        let t = Task { await self.doRefresh() }
        refreshTask = t
        let ok = await t.value
        refreshTask = nil
        return ok
    }

    private func doRefresh() async -> Bool {
        guard let s = await sessions.current(), let url = URL(string: s.serverURL + "/api/auth/refresh") else { return false }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = "POST"
        common(&req)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONEncoder().encode(["refreshToken": s.refreshToken])
        guard let (data, resp) = try? await perform(req) else { return false } // no network: do not close the session
        if resp.statusCode == 401 {
            await sessions.clear()
            onSessionExpired?()
            return false
        }
        guard resp.statusCode == 200, let auth = try? JSONDecoder().decode(AuthResponse.self, from: data), let rt = auth.refreshToken else { return false }
        await sessions.save(StoredSession(serverURL: s.serverURL, user: auth.user, refreshToken: rt, deviceId: auth.deviceId))
        await sessions.setAccess(auth.accessToken, expiresIn: auth.expiresIn)
        return true
    }

    /// Current access token (refreshed if needed).
    func currentAccessToken() async throws -> String { try await accessToken() }

    private func accessToken() async throws -> String {
        if let t = await sessions.validAccessToken() { return t }
        guard await sessions.current() != nil else { throw APIError.noSession }
        guard await refresh(), let t = await sessions.validAccessToken() else {
            if await sessions.current() == nil { throw APIError.noSession }
            throw APIError.http(0, L10n.tr("Couldn't reach your server"))
        }
        return t
    }

    // MARK: - Requests

    private nonisolated func common(_ req: inout URLRequest) {
        req.setValue(client.kind, forHTTPHeaderField: "X-Kairo-Client")
        req.setValue("Kairo-\(client.kind)/1.0", forHTTPHeaderField: "User-Agent")
        // The server picks the language of new accounts from this header ("es" -> Spanish, anything else -> English)
        req.setValue(KairoLanguage.code, forHTTPHeaderField: "Accept-Language")
        if !client.capabilities.isEmpty {
            req.setValue(client.capabilities.joined(separator: ","), forHTTPHeaderField: "X-Kairo-Capabilities")
        }
    }

    private func authorized(_ method: String, _ path: String, timeout: TimeInterval = 30) async throws -> URLRequest {
        guard let base = await sessions.current()?.serverURL, let url = URL(string: base + path) else { throw APIError.noSession }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method
        common(&req)
        req.setValue("Bearer \(try await accessToken())", forHTTPHeaderField: "Authorization")
        if let loc = await locationProvider?() {
            req.setValue(String(format: "%.5f,%.5f", loc.0, loc.1), forHTTPHeaderField: "X-Kairo-Location")
        }
        return req
    }

    private func perform(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, resp) = try await urlSession.data(for: req)
            guard let http = resp as? HTTPURLResponse else { throw APIError.http(0, L10n.tr("Invalid response")) }
            return (data, http)
        } catch let e as APIError {
            throw e
        } catch {
            throw APIError.http(0, L10n.tr("Couldn't reach your server"))
        }
    }

    /// Authenticated request; on a 401 it refreshes the token and retries once.
    public func sendRaw(_ method: String, _ path: String, body: Data? = nil, contentType: String? = nil, retry: Bool = true, timeout: TimeInterval = 30) async throws -> Data {
        var req = try await authorized(method, path, timeout: timeout)
        req.httpBody = body
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        let (data, resp) = try await perform(req)
        if resp.statusCode == 401 && retry {
            await sessions.invalidateAccess()
            guard await refresh() else { throw APIError.noSession }
            return try await sendRaw(method, path, body: body, contentType: contentType, retry: false, timeout: timeout)
        }
        guard (200..<300).contains(resp.statusCode) else { throw Self.error(resp.statusCode, data) }
        return data
    }

    public func send<T: Decodable & Sendable>(_ method: String, _ path: String, _ json: JSONObject? = nil) async throws -> T {
        let body = try json.map { try JSONEncoder().encode($0) } ?? (method == "GET" || method == "DELETE" ? nil : Data("{}".utf8))
        let data = try await sendRaw(method, path, body: body, contentType: body == nil ? nil : "application/json")
        return try Self.decode(data)
    }

    public func call(_ method: String, _ path: String, _ json: JSONObject? = nil) async throws {
        let body = try json.map { try JSONEncoder().encode($0) } ?? (method == "GET" || method == "DELETE" ? nil : Data("{}".utf8))
        _ = try await sendRaw(method, path, body: body, contentType: body == nil ? nil : "application/json")
    }

    static func decode<T: Decodable>(_ data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch let DecodingError.keyNotFound(key, ctx) {
            throw APIError.decoding("missing \(key.stringValue) in \(ctx.codingPath.map(\.stringValue).joined(separator: "."))")
        } catch let DecodingError.typeMismatch(_, ctx) {
            throw APIError.decoding("unexpected type at \(ctx.codingPath.map(\.stringValue).joined(separator: "."))")
        } catch {
            throw APIError.decoding(String(describing: error).prefix(120).description)
        }
    }

    static func error(_ status: Int, _ data: Data) -> APIError {
        let msg = (try? JSONDecoder().decode(ApiErrorBody.self, from: data))?.error ?? ""
        return .http(status, msg)
    }

    // MARK: - Multipart

    public struct FilePart: Sendable {
        public var field: String
        public var filename: String
        public var mime: String
        public var data: Data
        public init(field: String = "file", filename: String, mime: String, data: Data) {
            self.field = field; self.filename = filename; self.mime = mime; self.data = data
        }
    }

    static func multipart(fields: [(String, String)], file: FilePart?) -> (Data, String) {
        let boundary = "kairo-\(UUID().uuidString)"
        var body = Data()
        func append(_ s: String) { body.append(Data(s.utf8)) }
        for (k, v) in fields {
            append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(k)\"\r\n\r\n\(v)\r\n")
        }
        if let file {
            append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(file.field)\"; filename=\"\(file.filename)\"\r\nContent-Type: \(file.mime)\r\n\r\n")
            body.append(file.data)
            append("\r\n")
        }
        append("--\(boundary)--\r\n")
        return (body, "multipart/form-data; boundary=\(boundary)")
    }

    public func upload<T: Decodable & Sendable>(_ path: String, fields: [(String, String)] = [], file: FilePart?) async throws -> T {
        let (body, type) = Self.multipart(fields: fields, file: file)
        return try Self.decode(try await sendRaw("POST", path, body: body, contentType: type, timeout: 120))
    }

    // MARK: - Streaming (SSE)

    /// Stream of agent events. Refreshes the token if the server answers 401 before starting.
    nonisolated func stream(_ path: String, body: Data, contentType: String) -> AsyncThrowingStream<AgentEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await self.runStream(path, body: body, contentType: contentType, continuation: continuation, retry: true)
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runStream(_ path: String, body: Data, contentType: String, continuation: AsyncThrowingStream<AgentEvent, Error>.Continuation, retry: Bool) async throws {
        var req = try await authorized("POST", path, timeout: 300)
        req.httpBody = body
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        var parser = SSEParser()
        do {
            for try await line in LineStream.lines(for: req) {
                if let payload = parser.feed(line), let ev = SSEParser.decode(payload) { continuation.yield(ev) }
            }
            if let payload = parser.finish(), let ev = SSEParser.decode(payload) { continuation.yield(ev) }
        } catch let e as APIError where e.status == 401 && retry {
            await sessions.invalidateAccess()
            guard await refresh() else { throw APIError.noSession }
            try await runStream(path, body: body, contentType: contentType, continuation: continuation, retry: false)
        } catch let e as APIError {
            throw e
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw APIError.http(0, L10n.tr("Connection to your server was interrupted"))
        }
    }

    /// Text conversation with the agent.
    public nonisolated func chat(_ message: String, conversationId: String? = nil, channel: String = "chat") -> AsyncThrowingStream<AgentEvent, Error> {
        var body: JSONObject = ["message": .string(message), "channel": .string(channel)]
        if let conversationId { body["conversationId"] = .string(conversationId) }
        return stream("/api/chat", body: (try? JSONEncoder().encode(body)) ?? Data(), contentType: "application/json")
    }

    /// Voice turn: the server transcribes (Whisper), answers and returns the text to read aloud.
    public nonisolated func voiceTurn(audio: Data, filename: String = "voice.wav", mime: String = "audio/wav", conversationId: String? = nil, channel: String = "voice") -> AsyncThrowingStream<AgentEvent, Error> {
        var fields = [("channel", channel)]
        if let conversationId { fields.append(("conversationId", conversationId)) }
        let (body, type) = Self.multipart(fields: fields, file: FilePart(filename: filename, mime: mime, data: audio))
        return stream("/api/voice/turn", body: body, contentType: type)
    }

    /// Share to Kairo: text, link or image.
    public nonisolated func capture(text: String?, url: String?, image: FilePart?) -> AsyncThrowingStream<AgentEvent, Error> {
        var fields: [(String, String)] = []
        if let text, !text.isEmpty { fields.append(("text", text)) }
        if let url, !url.isEmpty { fields.append(("url", url)) }
        let (body, type) = Self.multipart(fields: fields, file: image)
        return stream("/api/capture", body: body, contentType: type)
    }

    /// Runs a complete turn and returns the result (Siri, shortcuts, extensions).
    public nonisolated func ask(_ message: String, channel: String = "voice") async throws -> AgentTurn {
        var turn = AgentTurn()
        for try await ev in chat(message, channel: channel) { turn.apply(ev) }
        if let e = turn.error { throw APIError.http(0, e) }
        return turn
    }
}

/// Lines of an HTTP response as they arrive. Uses a URLSession delegate, which works the same on
/// iOS, watchOS and Linux (the client tests run against the real backend).
enum LineStream {
    static func lines(for request: URLRequest) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let delegate = LineDelegate(continuation: continuation)
            let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
            let task = session.dataTask(with: request)
            continuation.onTermination = { _ in
                task.cancel()
                session.invalidateAndCancel()
            }
            task.resume()
        }
    }
}

private final class LineDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let continuation: AsyncThrowingStream<String, Error>.Continuation
    private var buffer = Data()
    private var status = 0
    private var errorBody = Data()
    private let lock = NSLock()

    init(continuation: AsyncThrowingStream<String, Error>.Continuation) { self.continuation = continuation }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
        lock.withLock { status = (response as? HTTPURLResponse)?.statusCode ?? 0 }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let lines: [String] = lock.withLock {
            if status >= 400 { errorBody.append(data); return [] }
            buffer.append(data)
            var out: [String] = []
            while let nl = buffer.firstIndex(of: 0x0A) {
                out.append(String(decoding: buffer[buffer.startIndex..<nl], as: UTF8.self))
                buffer.removeSubrange(buffer.startIndex...nl)
            }
            return out
        }
        for l in lines { continuation.yield(l) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let (code, body, rest): (Int, Data, Data) = lock.withLock { (status, errorBody, buffer) }
        if let error {
            continuation.finish(throwing: (error as NSError).code == NSURLErrorCancelled ? CancellationError() : APIError.http(0, L10n.tr("Connection to your server was interrupted")))
        } else if code >= 400 {
            continuation.finish(throwing: KairoAPI.error(code, body))
        } else {
            if !rest.isEmpty { continuation.yield(String(decoding: rest, as: UTF8.self)) }
            continuation.finish()
        }
        session.finishTasksAndInvalidate()
    }
}
