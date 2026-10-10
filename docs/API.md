# Kairo API

Base URL: `https://<your-domain>/api`. UTF-8 JSON. Timestamps are ISO 8601 in UTC (`2026-10-11T14:00:00Z`) and
dates are `YYYY-MM-DD`. Null fields are sent as `null`. Errors are `{"error": "message"}` with an HTTP status code.
Error messages are human-readable text in the language of the signed-in user and may change. Clients should rely on
the status code, not on the message.

Other languages: [Spanish version](es/API.md).

- JSON bodies must be sent with `Content-Type: application/json` (415 otherwise). `/auth/refresh` always requires it,
  even when the body is `{}` and the refresh token travels in the cookie.
- Maximum size: 16 KB on `/auth/*` and `PATCH /me`, 1 MB for all other JSON bodies, and 30 MB per multipart upload
  (20 MB on `/capture`, at most 16 parts). Anything larger gets 413.
- Per-person limits (429 with `Retry-After`): routes that use the AI (`/chat`, `/voice/*`, `/capture`, `/briefing`,
  finishing a recording and extracting its tasks) allow 20 requests per minute and 300 per hour. Recording segments
  allow 30 per minute. The `/auth/*` limits are per IP.

## Authentication

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/auth/login` | `{email, password, deviceName, platform: "web"\|"android"\|"wear", timezone?}` | `AuthResponse` |
| POST | `/auth/refresh` | `{refreshToken}` (app) or cookie `kairo_rt` (web) | `AuthResponse` |
| POST | `/auth/logout` | | `{ok}` |
| POST | `/auth/password` | `{currentPassword, newPassword}` | `{ok}` |
| POST | `/auth/pair-code` | (authenticated) | `{code, expiresIn: 300}`, a 6-digit code to pair a watch |
| POST | `/auth/pair` | `{code, deviceName, platform: "wear"}` | `AuthResponse` (single use) |
| GET/PATCH | `/me` | `{displayName?, timezone?}` | `{user}` |
| GET | `/auth/invite/{code}` | (public) | `{kind: invite\|reset, householdName, name, email\|null}` or 404 |
| POST | `/auth/join` | `{code, email?, password, displayName?, deviceName, platform, timezone?}` | `AuthResponse` |

`User = {id, email, displayName, timezone, createdAt, householdId, role: admin|member, color}`.
`AuthResponse = {user, accessToken, refreshToken|null, expiresIn: 3600, deviceId}`.
- `/auth/join` with an invitation creates the new member's account (it requires `email`). With an access code
  (`kind: reset`), it sets a new password for an existing member and signs out their other sessions.
  Codes look like `XXXX-XXXX`. Lowercase, no dash and extra spaces are accepted. An invalid code returns 400.
- New accounts (`/auth/join` with an invitation) get their language from the `Accept-Language` header: `es*` gives
  Spanish, anything else gives English. The owner account uses `KAIRO_DEFAULT_LANGUAGE`.
- Web: the refresh token travels in the httpOnly cookie `kairo_rt` (path `/api/auth`), and `refreshToken` comes back
  as `null`.
- App: store `refreshToken` encrypted. It rotates on every `/auth/refresh`, so save the new one.
  - Sending the same refresh token again within 60 s returns the same new token (two tabs, retries). Reusing one that
    was already rotated, after those 60 s, signs out that device (a sign of theft).
  - A device that does not renew its session within 90 days loses it (401, and you have to sign in again).
- `/auth/password` signs out the user's other sessions (the current one stays open).
- `/auth/login`: after 10 failed attempts in 15 minutes for the same email, requests are rejected for a while with
  the same generic error (401). `/auth/pair`: 20 failed codes in 5 minutes cancel all active codes. Requesting a new
  code cancels the same user's previous one.
- Requests: `Authorization: Bearer <accessToken>`. Only the WebSocket (`/ws`) accepts `?access_token=<accessToken>`.
- On 401, refresh and retry once. If that fails, go back to sign-in.

Optional client headers:
- `X-Kairo-Client: android|wear|web` (the origin of changes shown in Activity)
- `X-Kairo-Capabilities: timer,alarm,location` (enables timers and alarms in the agent)
- `X-Kairo-Location: lat,lon` (for "save this place" and local weather)

## Agent (SSE)

`POST /chat` `{message, conversationId?, channel?: "chat"|"voice"|"wear"}` responds with `text/event-stream`.
Each event is a `data: {json}` line with a `type`:

| type | fields |
|---|---|
| `start` | `conversationId` |
| `transcript` | `text` (only on `/voice/turn`) |
| `delta` | `text`, `step` (streaming text; when `done` arrives, use `done.text` as the final text) |
| `tool_start` | `callId`, `name`, `label` (for example "Saving note") |
| `tool_end` | `callId`, `name`, `ok`, `summary`, `entity: {type, id, title}\|null` |
| `device_action` | `action: {type:"timer", seconds, label}` or `{type:"alarm", hour, minute, label, days?}` |
| `attachment` | `file: {id, url, contentType}` (a signed relative URL; prepend the origin) |
| `done` | `conversationId`, `messageId`, `text` (markdown), `spoken` (clean text for TTS) |
| `error` | `message` |

`entity.type`: `note|task|reminder|list|event|memory|place|project|recording`.

**Confirmations.** If the turn read content from other people or from outside, sensitive actions (sharing, notices,
writing to shared or another person's items, reminders or tasks for someone else, MCP, deleting) are not executed.
They arrive as a `tool_end` with `ok: false` and a `summary` saying the action needs your confirmation, and
`done.text` ends with a "waiting for your confirmation" message that asks you to reply "yes". If the next message in
that conversation (`/chat` or `/voice/turn`, not `/capture`) is affirmative, the server runs the pending actions and
emits the normal `tool_start`/`tool_end` (in the history, `meta.confirmed: true`). Any other message discards them.
Pending actions live in memory for 10 minutes. The reply must be a plain affirmative, in English or Spanish (for example "yes", "ok", "sí", "dale"). Details in
[FAMILY.md](FAMILY.md#protection-against-hidden-instructions).

### Voice
- `POST /voice/turn` multipart `file` (m4a/webm/wav/mp3), `conversationId?`, `channel?` -> SSE (`transcript` first).
- `POST /voice/transcribe` multipart `file` -> `{text, language, duration}`.
- `POST /voice/speak` `{text, voice?, speed?}` -> `audio/mpeg`.
- `GET /voice/voices` -> `{voices: [{id, name, description}]}`.

The speech-to-text language hint and the default voice follow the user's `language` setting (`en`, `es` or `auto`).

### Share with Kairo
`POST /capture` multipart (`file` image, `text?`, `url?`, `conversationId?`) or JSON `{text?, url?}` -> SSE.
The preview of `url` is only downloaded from public addresses (each redirect, at most 3, is validated again), with a
maximum of 512 KB and 12 s. If it cannot be fetched, the agent receives only the link.

### Conversations
- `GET /conversations` -> `[{id, title, createdAt, updatedAt, preview}]`
- `GET /conversations/{id}/messages` -> `[{id, role: user|assistant|tool, content, toolName, meta, createdAt}]`
  - `role=tool`: `meta = {summary, ok, entity?, attachment?, confirmed?}` (action cards). `content` is what the
    model saw, and it may arrive wrapped in delimiters that mark it as other people's data, not instructions. Build
    cards from `meta`.
  - `role=user`: `meta = {channel, attachments?}`.
- `DELETE /conversations/{id}`

## Today
- `GET /today?date=YYYY-MM-DD` -> `{date, dateLabel, greeting, events[], tasksDueToday[], tasksOverdue[], tasksInProgress[], reminders[], firedReminders[], recentNotes[], lists[], weather|null}`
  - `weather = {place, temperature, feelsLike, description, days: [{date, min, max, rainChance, description}]}`
  - `dateLabel`, `greeting` and the weather `description` are generated in the user's language.
- `POST /briefing` -> `{text}` (a spoken-style briefing generated by the model, in the user's language)

## Resources

Everything is personal unless it is marked `shared: true` (family): events, lists, tasks, notes and places.
Everyone in the household can see and edit shared items. Only the creator (`ownerId`) can stop sharing one (400 if
someone else tries). Personal items are not exposed to other accounts, including the administrator's. However, an
administrator can generate an access code that resets a member's password (and the whole family is notified; see
*Family*).

**Note** `{id, title, content, tags[], pinned, source, attachments: [{id,url,contentType}], createdAt, updatedAt, score|null, shared, ownerId}`
- `GET /notes?q=&tag=&limit=&offset=` · `GET /notes/search?q=` (semantic) · `GET /notes/tags` -> `{tags}`
- `POST /notes {title?, content, tags?, pinned?, shared?}` · `GET|PATCH|DELETE /notes/{id}` (`PATCH {shared}` shares or unshares)

**Task** `{id, title, notes, status: todo|doing|done, priority: low|medium|high|urgent, dueDate, dueAt, projectId, projectName, tags[], sort, source, completedAt, createdAt, updatedAt, shared, ownerId, assigneeId}`
- `GET /tasks?status=&projectId=&includeDone=true&assignee=me|<userId>`
- `POST /tasks {title, notes?, priority?, dueDate?, dueAt?, projectId?, project? (name), tags?, status?, shared?, assigneeId?}`
  (assigning to another member makes the task shared and sends them a notice)
- `PATCH /tasks/{id}` any field, including `shared` and `assigneeId`; `null` in `dueDate|dueAt|projectId|assigneeId` clears it · `DELETE /tasks/{id}`
- **Project** `{id, name, color, sort, createdAt}`: `GET|POST /projects`, `PATCH|DELETE /projects/{id}`

**Reminder** `{id, text, dueAt, rrule, timezone, placeId, placeName, placeTrigger: arrive|leave, status: scheduled|fired|done|cancelled, nextFireAt, lastFiredAt, taskId, source, createdAt, updatedAt, ownerId, createdBy}`
- `GET /reminders?status=scheduled,fired` (includes the ones you scheduled for others: `ownerId` is not you)
- `POST /reminders {text, dueAt?, rrule?, placeId?, placeTrigger?, forUserId?}` (`forUserId`: another member; it rings on their phone and they get a notice)
- `PATCH /reminders/{id}` · `POST /reminders/{id}/done` · `POST /reminders/{id}/snooze {minutes}` · `POST /reminders/{id}/cancel` · `DELETE /reminders/{id}`

**Place** `{id, name, latitude, longitude, radiusM, address, createdAt, shared, ownerId}`: `GET|POST /places {..., shared?}`, `PATCH /places/{id} {shared}`, `DELETE /places/{id}`

**List** `{id, name, icon, items: [{id, text, checked, sort, createdAt}], createdAt, updatedAt, shared, ownerId}`
- `GET|POST /lists {name, icon?, shared?}` · `GET|PATCH {name?, icon?, shared?}|DELETE /lists/{id}` · `POST /lists/{id}/items {items: ["bread"]}`
- `PATCH /lists/{id}/items/{itemId} {text?, checked?, sort?}` · `DELETE /lists/{id}/items/{itemId}` · `POST /lists/{id}/clear-checked`

**Event** `{id, title, description, location, startAt, endAt, allDay, rrule, timezone, reminderMinutes[], color, source: kairo|google, googleEventId, createdAt, updatedAt, shared, ownerId}`
- `GET /events?from=&to=` (recurring events arrive expanded: same `id`, different `startAt`; family events are included)
- `POST /events {title, startAt, endAt?, allDay?, location?, description?, rrule?, reminderMinutes?, color?, shared?}` · `GET|PATCH|DELETE /events/{id}`
  (creating, moving or cancelling a family event notifies the rest of the family)

**Memory** `{id, content, category, createdAt, updatedAt}`: `GET|POST /memories`, `PATCH|DELETE /memories/{id}`
- categories: `perfil, preferencias, personas, salud, trabajo, hogar, general`. These are fixed identifiers (the
  wire values are Spanish words: profile, preferences, people, health, work, home, general). The apps show localized
  labels.

**Recording** `{id, title, status: recording|processing|done|failed, durationS, transcript, summary, keyPoints[], actionItems[], noteId, error, segments, createdAt, updatedAt}`
- `POST /recordings {title?}` -> create · `POST /recordings/{id}/segments` multipart `file`, `seq` (0..n), `duration` (s)
- `POST /recordings/{id}/finish {title?}` (202, processed in the background) · `POST /recordings/{id}/tasks` -> `{created}`
- `GET /recordings` · `GET|DELETE /recordings/{id}`

**Activity** `{id, kind: created|updated|deleted|completed|fired|restored, entityType, entityId, summary, source, undoable, undoneAt, createdAt}`
- `GET /activity?limit=&before=` · `POST /activity/{id}/undo`

## Family

- `GET /family` -> `FamilyInfo = {household: {id, name, createdAt}, members: [Member], me, isAdmin}`
  - `Member = {id, displayName, email, role: admin|member, joinedAt}`
- `PATCH /family {name}` (admin)
- `GET /family/invites` (admin) -> `[Invitation]`, active ones and ones used in the last 7 days
  - `Invitation = {id, kind: invite|reset, name, role, targetUserId, createdAt, expiresAt, usedAt}`
- `POST /family/invites {name?, role?: member|admin}` (admin) -> `{invitation, code, link}` (7 days, single use).
  `link = <KAIRO_PUBLIC_URL>/join?code=XXXX-XXXX`. The legacy form `/unirse?codigo=XXXX-XXXX` is still accepted by the
  server and the apps.
- `DELETE /family/invites/{id}` (admin)
- `POST /family/members/{id}/access-code` (admin) -> `{invitation, code, link}`: a 24-hour code that lets that member
  set a new password (the whole family, including that member, receives a `family.notice` when it is generated and
  another when it is used)
- `PATCH /family/members/{id} {role}` (admin; there is always at least one admin)
- `DELETE /family/members/{id}` (admin): deletes the account and personal items; what they shared passes to whoever removed them
- `POST /family/message {text, to?: [userId]}` -> `{sent}`: a family notice (without `to`, it goes to everyone except you)

Permission errors: 403 `{"error": "..."}` (only a family administrator can do this).

**Notice** `{id, kind: shared|updated|assigned|reminder|message, title, body, actorId, entityType, entityId, createdAt, readAt}`
- `GET /notices?unread=true&limit=50` -> `{notices, unread}` · `POST /notices/read {ids?}` (without `ids`, all of them)
- Notices are created when something new is shared, a family event is moved or cancelled, a family task is assigned or
  completed, a reminder is scheduled for another member, a message is sent, and when someone joins.

## Self-hosted push (no Firebase)

The server sends encrypted Web Push (RFC 8291 `aes128gcm`, signed with VAPID RFC 8292) to the endpoints that each
phone registers with UnifiedPush (for example, a self-hosted ntfy). The content is the same `{type, data}` that
arrives over the WebSocket, and the distributor cannot read it.
- `GET /push` -> `{vapidPublicKey (base64url, 87 characters), registeredDevices}`
- `POST /push/register {endpoint, p256dh, auth}` (keys from the UnifiedPush connector; bound to the session's device)
- `DELETE /push/register` · `POST /push/test` -> `{delivered}` (sends a test `family.notice` through push only: Web
  Push and APNs; `delivered` adds up the devices that accepted it over both channels)

Endpoints must be `https` (`KAIRO_PUSH_ALLOW_HTTP=true` allows `http` for testing or a local network) and must point to
a public address, except for the host of `NTFY_PUBLIC_URL` / `NTFY_URL` (your ntfy) or when
`KAIRO_PUSH_ALLOW_PRIVATE=true` (private networks; never loopback or link-local). This is checked when registering and
before every delivery. An endpoint that another account has already registered is not reassigned: `400`.

### Apple push (APNs)

Optional (see [PUSH.md](PUSH.md#apple-push-apns-for-iphone)). The server sends alerts over HTTP/2 to APNs with an
ES256 provider token signed with the `.p8` key. Each iPhone token is bound to the session's device.
- `GET /push/apns` -> `{available, registeredDevices}` (`available`: the server has valid APNs credentials;
  `registeredDevices`: this user's APNs tokens)
- `POST /push/apns/register {token, environment: "sandbox"|"production", kind: "device", activityId?}` -> `{available, registeredDevices}`
  - `token`: the device token in hexadecimal (64 to 200 characters), otherwise `400`. It is updated if it already
    existed for the same account. If another account registered it, `400` (that account must sign out first). It
    replaces the previous token of the same device. An invalid `environment` or a `kind` other than `device` gives `400`.
  - It is stored even if the server has no APNs configured (`available: false`), and starts arriving as soon as APNs
    is configured.
- `DELETE /push/apns/register` -> `{ok}` (deletes this device's APNs tokens; they are also deleted when the session
  is signed out or revoked)

Content of each push (`apns-push-type: alert`, `apns-priority: 10`, `apns-expiration` = 1 hour,
`apns-collapse-id` = the same key the app uses to deduplicate, for example `rem:{id}:{fireAt}` or `notice:{id}`):
```json
{
  "aps": {
    "alert": {"title": "Kairo", "body": "Bring the health card"},
    "sound": "default", "thread-id": "reminder", "interruption-level": "time-sensitive",
    "category": "kairo.reminder", "content-available": 1
  },
  "kairo": {"type": "reminder.fire", "data": {"id": "…", "text": "Bring the health card", "fireAt": "…", "recurring": false}},
  "entityId": "…", "destination": "reminders"
}
```
- `kairo` is the same `{type, data}` as the WebSocket. `interruption-level` is `time-sensitive` for `reminder.fire` and
  `event.alert`, and `active` for everything else. `thread-id`: `reminder`, `event`, `briefing` or `family`.
- `category` (`kairo.reminder` with "Done"/"In 10 min" actions, `kairo.briefing` with "Listen"), `entityId` and
  `destination` are the same ones the app's local notifications use.
- The payload never exceeds 4 KB. If needed, the alert text and long `data` strings are trimmed (they end with `…`).
  Ids and dates are never touched.
- If Apple answers `410`, or `400` with `BadDeviceToken` / `DeviceTokenNotForTopic`, the token is deleted.

## Settings and system
- `GET|PATCH /settings` -> `{settings: UserSettings, googleAvailable, ntfyAvailable}`
  - `UserSettings = {chatModel, fallbackModel, visionModel, imageModel, fastMode, voice, voiceSpeed, sttModel, language, wakeWord, briefingEnabled, briefingTime "HH:MM", lastBriefingDate, homeCity, homeLatitude, homeLongitude, ntfyTopic, googleCalendarId, defaultEventReminderMinutes, keepRecordingAudio}`
  - `language` is `en` (default), `es` or `auto`. It drives the assistant's reply language, the system prompt, the
    morning briefing, server-generated notices, weather text, date formatting, the default TTS voice and the speech-to-text
    language hint. `auto` means Whisper auto-detects the language and Kairo mirrors the user's language.
  - Models (`chatModel`, `fallbackModel`, `visionModel`, `imageModel`, `sttModel`) must be in `GET /models` (if Nan does
    not respond, they only need a valid name: letters, numbers and `._:/@+-`, at most 100). The current value and the
    default are always accepted. `ntfyTopic`: letters, numbers, `-` and `_` (at most 64). Otherwise, `400`.
- `GET /geocode?q=Medellin` -> `[{name, admin, country, latitude, longitude, timezone}]`
- `GET /models` -> `{models: [...]}` (models available on Nan)
- `GET /devices` -> `[{id, name, platform, createdAt, lastSeenAt, current}]` · `DELETE /devices/{id}`
- `GET /sync/device` -> `{serverTime, reminders[] (scheduled), places[], eventAlerts[] (next 7 days: {eventId, title, startAt, alertAt, minutes, location, shared}), briefingEnabled, briefingTime, notices[] (unread, last 48 h), pushRegistered}` (for local alarms, event alerts, the morning briefing, geofences and family notices that did not arrive by push)
- `GET /export` -> JSON with all your data
- Google: `GET /integrations/google` -> `{available, connected, calendarId, familyCalendarId, lastSyncAt, lastError, redirectUri}`,
  `POST /integrations/google/auth-url` -> `{url}` (sets an HttpOnly cookie `kairo_google_oauth`: open the URL in the same browser),
  `POST /integrations/google/sync`, `GET /integrations/google/calendars`,
  `POST /integrations/google/calendar {calendarId}`, `POST /integrations/google/family-calendar {calendarId|null}`
  (the Google calendar shared with the family, where the family events you create go), `DELETE /integrations/google`
- MCP: `GET|POST /mcp-servers {name, url, headers?}`, `PATCH /mcp-servers/{id} {enabled}`, `DELETE /mcp-servers/{id}`, `POST /mcp-servers/{id}/test` -> `{tools}`
  (see [MCP servers](#mcp-servers))
- `GET /health` (public)

### MCP servers

So that nobody can use Kairo to reach internal services (SSRF), an MCP server URL is validated when you save it and
before every request:
- Only `http`/`https`, with no `user:password@` and no unusual IP forms (`2130706433`, `0x7f.1`...).
- Every address it resolves to must be public. With `KAIRO_MCP_ALLOW_PRIVATE=true`, private networks and CGNAT are also
  accepted (Home Assistant on the LAN or over Tailscale), but never loopback, link-local (`169.254.x.x`, cloud
  metadata) or the internal services `db`, `kairo`, `ntfy`, `backup` or `localhost`.
- `KAIRO_MCP_ALLOWED_HOSTS=host1,host2`: exact hosts the operator always allows (for example, another container).
- Redirects are not followed, and responses are cut off at 2 MB.
- `POST /mcp-servers/{id}/test` and the agent's tools only report a generic "could not connect to the MCP server"
  failure. The reason (connection refused, HTTP status...) goes to the server log.
- MCP sessions and the tool cache are separated by URL and headers: two people with the same URL and different
  credentials never share a session.

## Real time (WebSocket)
`GET /ws?access_token=...` sends `{type, data}`. The server closes the connection (code 1008) when the access token
expires or the device is revoked. Reconnect with a valid token (refreshing if needed).
- `entity.changed` `{entity, id?, op}`: reload that collection.
- `entity.changed` also reaches the whole family when something shared changes (`entity` can be `family` or `notice`).
- `reminder.fire` `{id, text, fireAt, recurring, createdBy?}`: show a notification (deduplicate by `id+fireAt` with the local alarm).
- `event.alert` `{id, title, startAt, alertAt, minutes, location, shared}`: an advance alert for a calendar event (deduplicate by `id+startAt+minutes` with the local alarm).
- `family.notice` `{id, kind, title, body, actorId?, entityType?, entityId?, createdAt}`: a family notice (deduplicate by `id`).

The same `reminder.fire`, `event.alert`, `briefing.ready` and `family.notice` events also arrive through push
(Web Push and, on iPhone, APNs inside the `kairo` key).
- `briefing.ready` `{text, date}`
- `recording.updated` `{id}`
- `ping` every 25 s (the client can send `"ping"` and receives `pong`).
