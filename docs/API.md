# API de Kairo

Base: `https://<tu-dominio>/api`. JSON UTF-8. Fechas-hora en ISO 8601 UTC (`2026-10-11T14:00:00Z`),
fechas en `YYYY-MM-DD`. Los campos nulos se envían como `null`. Errores: `{"error": "mensaje"}` con código HTTP.

## Autenticación

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| POST | `/auth/login` | `{email, password, deviceName, platform: "web"\|"android"\|"wear", timezone?}` | `AuthResponse` |
| POST | `/auth/refresh` | `{refreshToken}` (app) o cookie `kairo_rt` (web) | `AuthResponse` |
| POST | `/auth/logout` | | `{ok}` |
| POST | `/auth/password` | `{currentPassword, newPassword}` | `{ok}` |
| POST | `/auth/pair-code` | (autenticado) | `{code, expiresIn: 300}` código de 6 dígitos para vincular un reloj |
| POST | `/auth/pair` | `{code, deviceName, platform: "wear"}` | `AuthResponse` (un solo uso) |
| GET/PATCH | `/me` | `{displayName?, timezone?}` | `{user}` |

`AuthResponse = {user, accessToken, refreshToken|null, expiresIn: 3600, deviceId}`.
- Web: el refresh token va en cookie httpOnly `kairo_rt` (path `/api/auth`), `refreshToken` llega `null`.
- App: guarda `refreshToken` cifrado; se rota en cada `/auth/refresh` (guarda el nuevo).
- Peticiones: `Authorization: Bearer <accessToken>`. WebSocket: `?access_token=<accessToken>`.
- 401 => refrescar y reintentar una vez; si falla, volver a login.

Cabeceras opcionales de cliente:
- `X-Kairo-Client: android|wear|web` (origen de los cambios en Actividad)
- `X-Kairo-Capabilities: timer,alarm,location` (habilita temporizadores/alarmas en el agente)
- `X-Kairo-Location: lat,lon` (para "guarda este lugar" y clima local)

## Agente (SSE)

`POST /chat` `{message, conversationId?, channel?: "chat"|"voice"|"wear"}` responde `text/event-stream`.
Cada evento es una línea `data: {json}` con `type`:

| type | campos |
|---|---|
| `start` | `conversationId` |
| `transcript` | `text` (solo en `/voice/turn`) |
| `delta` | `text`, `step` (texto en streaming; al llegar `done`, usar `done.text` como texto final) |
| `tool_start` | `callId`, `name`, `label` ("Guardando nota") |
| `tool_end` | `callId`, `name`, `ok`, `summary`, `entity: {type, id, title}\|null` |
| `device_action` | `action: {type:"timer", seconds, label}` o `{type:"alarm", hour, minute, label, days?}` |
| `attachment` | `file: {id, url, contentType}` (url relativa firmada, anteponer el origen) |
| `done` | `conversationId`, `messageId`, `text` (markdown), `spoken` (limpio para TTS) |
| `error` | `message` |

`entity.type`: `note|task|reminder|list|event|memory|place|project|recording`.

### Voz
- `POST /voice/turn` multipart `file` (m4a/webm/wav/mp3), `conversationId?`, `channel?` -> SSE (primero `transcript`).
- `POST /voice/transcribe` multipart `file` -> `{text, language, duration}`.
- `POST /voice/speak` `{text, voice?, speed?}` -> `audio/mpeg`.
- `GET /voice/voices` -> `{voices: [{id, name, description}]}`.

### Compartir con Kairo
`POST /capture` multipart (`file` imagen, `text?`, `url?`, `conversationId?`) o JSON `{text?, url?}` -> SSE.

### Conversaciones
- `GET /conversations` -> `[{id, title, createdAt, updatedAt, preview}]`
- `GET /conversations/{id}/messages` -> `[{id, role: user|assistant|tool, content, toolName, meta, createdAt}]`
  - `role=tool`: `meta = {summary, ok, entity?, attachment?}` (tarjetas de acción).
  - `role=user`: `meta = {channel, attachments?}`.
- `DELETE /conversations/{id}`

## Hoy
- `GET /today?date=YYYY-MM-DD` -> `{date, dateLabel, greeting, events[], tasksDueToday[], tasksOverdue[], tasksInProgress[], reminders[], firedReminders[], recentNotes[], lists[], weather|null}`
  - `weather = {place, temperature, feelsLike, description, days: [{date, min, max, rainChance, description}]}`
- `POST /briefing` -> `{text}` (resumen hablable generado por el modelo)

## Recursos

**Note** `{id, title, content, tags[], pinned, source, attachments: [{id,url,contentType}], createdAt, updatedAt, score|null}`
- `GET /notes?q=&tag=&limit=&offset=` · `GET /notes/search?q=` (semántica) · `GET /notes/tags` -> `{tags}`
- `POST /notes {title?, content, tags?, pinned?}` · `GET|PATCH|DELETE /notes/{id}`

**Task** `{id, title, notes, status: todo|doing|done, priority: low|medium|high|urgent, dueDate, dueAt, projectId, projectName, tags[], sort, source, completedAt, createdAt, updatedAt}`
- `GET /tasks?status=&projectId=&includeDone=true`
- `POST /tasks {title, notes?, priority?, dueDate?, dueAt?, projectId?, project? (nombre), tags?, status?}`
- `PATCH /tasks/{id}` cualquier campo; `null` en `dueDate|dueAt|projectId` los borra · `DELETE /tasks/{id}`
- **Project** `{id, name, color, sort, createdAt}`: `GET|POST /projects`, `PATCH|DELETE /projects/{id}`

**Reminder** `{id, text, dueAt, rrule, timezone, placeId, placeName, placeTrigger: arrive|leave, status: scheduled|fired|done|cancelled, nextFireAt, lastFiredAt, taskId, source, createdAt, updatedAt}`
- `GET /reminders?status=scheduled,fired` · `POST /reminders {text, dueAt?, rrule?, placeId?, placeTrigger?}`
- `PATCH /reminders/{id}` · `POST /reminders/{id}/done` · `POST /reminders/{id}/snooze {minutes}` · `POST /reminders/{id}/cancel` · `DELETE /reminders/{id}`

**Place** `{id, name, latitude, longitude, radiusM, address, createdAt}`: `GET|POST /places`, `DELETE /places/{id}`

**List** `{id, name, icon, items: [{id, text, checked, sort, createdAt}], createdAt, updatedAt}`
- `GET|POST /lists` · `GET|PATCH|DELETE /lists/{id}` · `POST /lists/{id}/items {items: ["pan"]}`
- `PATCH /lists/{id}/items/{itemId} {text?, checked?, sort?}` · `DELETE /lists/{id}/items/{itemId}` · `POST /lists/{id}/clear-checked`

**Event** `{id, title, description, location, startAt, endAt, allDay, rrule, timezone, reminderMinutes[], color, source: kairo|google, googleEventId, createdAt, updatedAt}`
- `GET /events?from=&to=` (los recurrentes llegan expandidos: mismo `id`, distinto `startAt`)
- `POST /events {title, startAt, endAt?, allDay?, location?, description?, rrule?, reminderMinutes?, color?}` · `GET|PATCH|DELETE /events/{id}`

**Memory** `{id, content, category, createdAt, updatedAt}`: `GET|POST /memories`, `PATCH|DELETE /memories/{id}`
- categorías: `perfil, preferencias, personas, salud, trabajo, hogar, general`

**Recording** `{id, title, status: recording|processing|done|failed, durationS, transcript, summary, keyPoints[], actionItems[], noteId, error, segments, createdAt, updatedAt}`
- `POST /recordings {title?}` -> crear · `POST /recordings/{id}/segments` multipart `file`, `seq` (0..n), `duration` (s)
- `POST /recordings/{id}/finish {title?}` (202, procesa en segundo plano) · `POST /recordings/{id}/tasks` -> `{created}`
- `GET /recordings` · `GET|DELETE /recordings/{id}`

**Activity** `{id, kind: created|updated|deleted|completed|fired|restored, entityType, entityId, summary, source, undoable, undoneAt, createdAt}`
- `GET /activity?limit=&before=` · `POST /activity/{id}/undo`

## Ajustes y sistema
- `GET|PATCH /settings` -> `{settings: UserSettings, googleAvailable, ntfyAvailable}`
  - `UserSettings = {chatModel, fallbackModel, visionModel, imageModel, fastMode, voice, voiceSpeed, sttModel, language, wakeWord, briefingEnabled, briefingTime "HH:MM", lastBriefingDate, homeCity, homeLatitude, homeLongitude, ntfyTopic, googleCalendarId, defaultEventReminderMinutes, keepRecordingAudio}`
- `GET /geocode?q=Medellín` -> `[{name, admin, country, latitude, longitude, timezone}]`
- `GET /models` -> `{models: [...]}` (modelos disponibles en Nan)
- `GET /devices` -> `[{id, name, platform, createdAt, lastSeenAt, current}]` · `DELETE /devices/{id}`
- `GET /sync/device` -> `{serverTime, reminders[] (programados), places[]}` (para alarmas locales y geovallas)
- `GET /export` -> JSON con todos los datos
- Google: `GET /integrations/google` -> `{available, connected, calendarId, lastSyncAt, lastError, redirectUri}`,
  `POST /integrations/google/auth-url` -> `{url}`, `POST /integrations/google/sync`, `GET /integrations/google/calendars`,
  `POST /integrations/google/calendar {calendarId}`, `DELETE /integrations/google`
- MCP: `GET|POST /mcp-servers {name, url, headers?}`, `PATCH /mcp-servers/{id} {enabled}`, `DELETE /mcp-servers/{id}`, `POST /mcp-servers/{id}/test` -> `{tools}`
- `GET /health` (público)

## Tiempo real (WebSocket)
`GET /ws?access_token=...` envía `{type, data}`:
- `entity.changed` `{entity, id?, op}`: recargar esa colección.
- `reminder.fire` `{id, text, fireAt, recurring}`: mostrar notificación (deduplicar por `id+fireAt` con la alarma local).
- `briefing.ready` `{text, date}`
- `recording.updated` `{id}`
- `ping` cada 25 s (el cliente puede enviar `"ping"` y recibe `pong`).
