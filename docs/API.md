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
| GET | `/auth/invite/{code}` | (público) | `{kind: invite\|reset, householdName, name, email\|null}` o 404 |
| POST | `/auth/join` | `{code, email?, password, displayName?, deviceName, platform, timezone?}` | `AuthResponse` |

`User = {id, email, displayName, timezone, createdAt, householdId, role: admin|member, color}`.
`AuthResponse = {user, accessToken, refreshToken|null, expiresIn: 3600, deviceId}`.
- `/auth/join` con una invitación crea la cuenta del nuevo miembro (pide `email`); con un código de acceso
  (`kind: reset`) define una contraseña nueva para un miembro existente y cierra sus otras sesiones.
  Los códigos son `XXXX-XXXX` (se aceptan en minúsculas, sin guion o con espacios). 400 si no vale.
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
La vista previa de `url` sólo se descarga de direcciones públicas (cada redirección, como mucho 3, se vuelve a validar),
con un máximo de 512 KB y 12 s; si no se puede, el agente recibe sólo el enlace.

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

Todo es personal salvo lo que se marca `shared: true` (familiar): eventos, listas, tareas, notas y lugares.
Lo familiar lo ven y editan todos los miembros del hogar; sólo quien lo creó (`ownerId`) puede dejar de
compartirlo (400 si lo intenta otro). Lo personal es privado, también para el administrador.

**Note** `{id, title, content, tags[], pinned, source, attachments: [{id,url,contentType}], createdAt, updatedAt, score|null, shared, ownerId}`
- `GET /notes?q=&tag=&limit=&offset=` · `GET /notes/search?q=` (semántica) · `GET /notes/tags` -> `{tags}`
- `POST /notes {title?, content, tags?, pinned?, shared?}` · `GET|PATCH|DELETE /notes/{id}` (`PATCH {shared}` comparte o deja de compartir)

**Task** `{id, title, notes, status: todo|doing|done, priority: low|medium|high|urgent, dueDate, dueAt, projectId, projectName, tags[], sort, source, completedAt, createdAt, updatedAt, shared, ownerId, assigneeId}`
- `GET /tasks?status=&projectId=&includeDone=true&assignee=me|<userId>`
- `POST /tasks {title, notes?, priority?, dueDate?, dueAt?, projectId?, project? (nombre), tags?, status?, shared?, assigneeId?}`
  (asignar a otro miembro la vuelve familiar y le llega un aviso)
- `PATCH /tasks/{id}` cualquier campo, incluidos `shared` y `assigneeId`; `null` en `dueDate|dueAt|projectId|assigneeId` los borra · `DELETE /tasks/{id}`
- **Project** `{id, name, color, sort, createdAt}`: `GET|POST /projects`, `PATCH|DELETE /projects/{id}`

**Reminder** `{id, text, dueAt, rrule, timezone, placeId, placeName, placeTrigger: arrive|leave, status: scheduled|fired|done|cancelled, nextFireAt, lastFiredAt, taskId, source, createdAt, updatedAt, ownerId, createdBy}`
- `GET /reminders?status=scheduled,fired` (incluye los que programaste para otros: `ownerId` distinto de ti)
- `POST /reminders {text, dueAt?, rrule?, placeId?, placeTrigger?, forUserId?}` (`forUserId`: otro miembro; suena en su teléfono y le llega un aviso)
- `PATCH /reminders/{id}` · `POST /reminders/{id}/done` · `POST /reminders/{id}/snooze {minutes}` · `POST /reminders/{id}/cancel` · `DELETE /reminders/{id}`

**Place** `{id, name, latitude, longitude, radiusM, address, createdAt, shared, ownerId}`: `GET|POST /places {..., shared?}`, `PATCH /places/{id} {shared}`, `DELETE /places/{id}`

**List** `{id, name, icon, items: [{id, text, checked, sort, createdAt}], createdAt, updatedAt, shared, ownerId}`
- `GET|POST /lists {name, icon?, shared?}` · `GET|PATCH {name?, icon?, shared?}|DELETE /lists/{id}` · `POST /lists/{id}/items {items: ["pan"]}`
- `PATCH /lists/{id}/items/{itemId} {text?, checked?, sort?}` · `DELETE /lists/{id}/items/{itemId}` · `POST /lists/{id}/clear-checked`

**Event** `{id, title, description, location, startAt, endAt, allDay, rrule, timezone, reminderMinutes[], color, source: kairo|google, googleEventId, createdAt, updatedAt, shared, ownerId}`
- `GET /events?from=&to=` (los recurrentes llegan expandidos: mismo `id`, distinto `startAt`; incluye los familiares)
- `POST /events {title, startAt, endAt?, allDay?, location?, description?, rrule?, reminderMinutes?, color?, shared?}` · `GET|PATCH|DELETE /events/{id}`
  (crear, mover o cancelar un evento familiar avisa al resto de la familia)

**Memory** `{id, content, category, createdAt, updatedAt}`: `GET|POST /memories`, `PATCH|DELETE /memories/{id}`
- categorías: `perfil, preferencias, personas, salud, trabajo, hogar, general`

**Recording** `{id, title, status: recording|processing|done|failed, durationS, transcript, summary, keyPoints[], actionItems[], noteId, error, segments, createdAt, updatedAt}`
- `POST /recordings {title?}` -> crear · `POST /recordings/{id}/segments` multipart `file`, `seq` (0..n), `duration` (s)
- `POST /recordings/{id}/finish {title?}` (202, procesa en segundo plano) · `POST /recordings/{id}/tasks` -> `{created}`
- `GET /recordings` · `GET|DELETE /recordings/{id}`

**Activity** `{id, kind: created|updated|deleted|completed|fired|restored, entityType, entityId, summary, source, undoable, undoneAt, createdAt}`
- `GET /activity?limit=&before=` · `POST /activity/{id}/undo`

## Familia

- `GET /family` -> `FamilyInfo = {household: {id, name, createdAt}, members: [Member], me, isAdmin}`
  - `Member = {id, displayName, email, role: admin|member, joinedAt}`
- `PATCH /family {name}` (admin)
- `GET /family/invites` (admin) -> `[Invitation]` vigentes y usadas en los últimos 7 días
  - `Invitation = {id, kind: invite|reset, name, role, targetUserId, createdAt, expiresAt, usedAt}`
- `POST /family/invites {name?, role?: member|admin}` (admin) -> `{invitation, code, link}` (7 días, un uso).
  `link = <KAIRO_PUBLIC_URL>/unirse?codigo=XXXX-XXXX`
- `DELETE /family/invites/{id}` (admin)
- `POST /family/members/{id}/access-code` (admin) -> `{invitation, code, link}`: código de 24 h para que ese miembro defina una contraseña nueva
- `PATCH /family/members/{id} {role}` (admin; siempre queda al menos un admin)
- `DELETE /family/members/{id}` (admin): borra la cuenta y lo personal; lo que había compartido pasa a quien lo quita
- `POST /family/message {text, to?: [userId]}` -> `{sent}`: aviso a la familia (sin `to`, a todos menos a ti)

Errores de permiso: 403 `{"error": "Solo un administrador de la familia puede hacer esto"}`.

**Notice** `{id, kind: shared|updated|assigned|reminder|message, title, body, actorId, entityType, entityId, createdAt, readAt}`
- `GET /notices?unread=true&limit=50` -> `{notices, unread}` · `POST /notices/read {ids?}` (sin `ids`, todos)
- Se generan al compartir algo nuevo, mover o cancelar un evento familiar, asignar o completar una tarea familiar,
  programar un recordatorio para otro miembro, enviar un mensaje y cuando alguien se une.

## Push propio (sin Firebase)

El servidor envía Web Push cifrado (RFC 8291 `aes128gcm`, firmado con VAPID RFC 8292) a los endpoints que
registra cada teléfono con UnifiedPush (por ejemplo, un ntfy autoalojado). El contenido es el mismo `{type, data}`
que llega por WebSocket; el distribuidor no puede leerlo.
- `GET /push` -> `{vapidPublicKey (base64url, 87 caracteres), registeredDevices}`
- `POST /push/register {endpoint, p256dh, auth}` (claves del conector UnifiedPush; ligado al dispositivo de la sesión)
- `DELETE /push/register` · `POST /push/test` -> `{delivered}` (envía un `family.notice` de prueba sólo por push:
  Web Push y APNs; `delivered` suma los dispositivos que lo aceptaron por ambas vías)

Los endpoints deben ser `https` (`KAIRO_PUSH_ALLOW_HTTP=true` permite `http` para pruebas o una red local) y apuntar a
una dirección pública, salvo el host de `NTFY_PUBLIC_URL` / `NTFY_URL` (tu ntfy) o con `KAIRO_PUSH_ALLOW_PRIVATE=true`
(redes privadas; nunca loopback ni enlace local). Se comprueba al registrar y antes de cada envío. Un endpoint que ya
registró otra cuenta no se reasigna: `400`.

### Push de Apple (APNs)

Opcional (ver [PUSH.md](PUSH.md#push-de-apple-apns-para-iphone)). El servidor envía alertas por HTTP/2 a APNs con un
token de proveedor ES256 firmado con la clave `.p8`; cada token del iPhone queda ligado al dispositivo de la sesión.
- `GET /push/apns` -> `{available, registeredDevices}` (`available`: el servidor tiene credenciales de APNs válidas;
  `registeredDevices`: tokens de APNs de este usuario)
- `POST /push/apns/register {token, environment: "sandbox"|"production", kind: "device", activityId?}` -> `{available, registeredDevices}`
  - `token`: el token del dispositivo en hexadecimal (64 a 200 caracteres); si no, `400`. Se actualiza si ya existía
    para la misma cuenta; si lo registró otra cuenta, `400` (hay que cerrar sesión en ella primero)
    y reemplaza al token anterior del mismo dispositivo. `environment` inválido o `kind` distinto de `device` -> `400`.
  - Se guarda aunque el servidor no tenga APNs configurado (`available: false`): empieza a llegar en cuanto se configure.
- `DELETE /push/apns/register` -> `{ok}` (borra los tokens de APNs de este dispositivo; también se borran al cerrar
  o revocar la sesión)

Contenido de cada push (`apns-push-type: alert`, `apns-priority: 10`, `apns-expiration` = 1 hora,
`apns-collapse-id` = la misma clave con la que la app deduplica, p. ej. `rem:{id}:{fireAt}` o `notice:{id}`):
```json
{
  "aps": {
    "alert": {"title": "Kairo", "body": "Llevar el carnet de salud"},
    "sound": "default", "thread-id": "reminder", "interruption-level": "time-sensitive",
    "category": "kairo.reminder", "content-available": 1
  },
  "kairo": {"type": "reminder.fire", "data": {"id": "…", "text": "Llevar el carnet de salud", "fireAt": "…", "recurring": false}},
  "entityId": "…", "destination": "reminders"
}
```
- `kairo` es el mismo `{type, data}` del WebSocket. `interruption-level` es `time-sensitive` para `reminder.fire` y
  `event.alert`, y `active` para el resto. `thread-id`: `reminder`, `event`, `briefing` o `family`.
- `category` (`kairo.reminder` con "Hecho"/"En 10 min", `kairo.briefing` con "Escuchar"), `entityId` y `destination`
  son los mismos que usan las notificaciones locales de la app.
- El contenido nunca pasa de 4 KB: si hace falta, se recortan el texto de la alerta y los textos largos de `data`
  (terminan en `…`); ids y fechas no se tocan.
- Si Apple responde `410`, o `400` con `BadDeviceToken` / `DeviceTokenNotForTopic`, el token se borra.

## Ajustes y sistema
- `GET|PATCH /settings` -> `{settings: UserSettings, googleAvailable, ntfyAvailable}`
  - `UserSettings = {chatModel, fallbackModel, visionModel, imageModel, fastMode, voice, voiceSpeed, sttModel, language, wakeWord, briefingEnabled, briefingTime "HH:MM", lastBriefingDate, homeCity, homeLatitude, homeLongitude, ntfyTopic, googleCalendarId, defaultEventReminderMinutes, keepRecordingAudio}`
  - Los modelos (`chatModel`, `fallbackModel`, `visionModel`, `imageModel`, `sttModel`) deben estar en `GET /models`
    (si Nan no responde, al menos tener un nombre válido: letras, números y `._:/@+-`, máximo 100); el valor actual y el
    de fábrica siempre se aceptan. `ntfyTopic`: letras, números, `-` y `_` (máximo 64). Si no, `400`.
- `GET /geocode?q=Medellín` -> `[{name, admin, country, latitude, longitude, timezone}]`
- `GET /models` -> `{models: [...]}` (modelos disponibles en Nan)
- `GET /devices` -> `[{id, name, platform, createdAt, lastSeenAt, current}]` · `DELETE /devices/{id}`
- `GET /sync/device` -> `{serverTime, reminders[] (programados), places[], eventAlerts[] (próximos 7 días: {eventId, title, startAt, alertAt, minutes, location, shared}), briefingEnabled, briefingTime, notices[] (sin leer, últimas 48 h), pushRegistered}` (para alarmas locales, avisos de eventos, resumen matinal, geovallas y los avisos de la familia que no llegaron por push)
- `GET /export` -> JSON con todos los datos
- Google: `GET /integrations/google` -> `{available, connected, calendarId, familyCalendarId, lastSyncAt, lastError, redirectUri}`,
  `POST /integrations/google/auth-url` -> `{url}`, `POST /integrations/google/sync`, `GET /integrations/google/calendars`,
  `POST /integrations/google/calendar {calendarId}`, `POST /integrations/google/family-calendar {calendarId|null}`
  (calendario de Google compartido con la familia donde van los eventos familiares que creas), `DELETE /integrations/google`
- MCP: `GET|POST /mcp-servers {name, url, headers?}`, `PATCH /mcp-servers/{id} {enabled}`, `DELETE /mcp-servers/{id}`, `POST /mcp-servers/{id}/test` -> `{tools}`
  (ver [Servidores MCP](#servidores-mcp))
- `GET /health` (público)

### Servidores MCP

Para que nadie use Kairo para llegar a servicios internos (SSRF), la URL de un servidor MCP se valida al guardarla y
antes de cada petición:
- Sólo `http`/`https`, sin `usuario:contraseña@` ni formas raras de IP (`2130706433`, `0x7f.1`...).
- Todas las direcciones a las que resuelve deben ser públicas. Con `KAIRO_MCP_ALLOW_PRIVATE=true` se aceptan también
  las redes privadas y CGNAT (Home Assistant en la LAN o por Tailscale), pero nunca loopback, enlace local
  (`169.254.x.x`, metadatos de la nube) ni los servicios internos `db`, `kairo`, `ntfy`, `backup` o `localhost`.
- `KAIRO_MCP_ALLOWED_HOSTS=host1,host2`: hosts exactos que el operador permite siempre (por ejemplo otro contenedor).
- No se siguen redirecciones y las respuestas se cortan a 2 MB.
- `POST /mcp-servers/{id}/test` y las herramientas del agente sólo dicen "No se pudo conectar con el servidor MCP"
  cuando algo falla; el motivo (conexión rechazada, código HTTP...) queda en el log del servidor.
- Las sesiones MCP y la caché de herramientas se separan por URL y cabeceras: dos personas con la misma URL y distintas
  credenciales nunca comparten sesión.

## Tiempo real (WebSocket)
`GET /ws?access_token=...` envía `{type, data}`:
- `entity.changed` `{entity, id?, op}`: recargar esa colección.
- `entity.changed` también llega a toda la familia cuando cambia algo compartido (`entity` puede ser `family` o `notice`).
- `reminder.fire` `{id, text, fireAt, recurring, createdBy?}`: mostrar notificación (deduplicar por `id+fireAt` con la alarma local).
- `event.alert` `{id, title, startAt, alertAt, minutes, location, shared}`: aviso previo de un evento del calendario (deduplicar por `id+startAt+minutes` con la alarma local).
- `family.notice` `{id, kind, title, body, actorId?, entityType?, entityId?, createdAt}`: aviso de la familia (deduplicar por `id`).

Los mismos eventos `reminder.fire`, `event.alert`, `briefing.ready` y `family.notice` llegan también por push
(Web Push y, en iPhone, APNs dentro de la clave `kairo`).
- `briefing.ready` `{text, date}`
- `recording.updated` `{id}`
- `ping` cada 25 s (el cliente puede enviar `"ping"` y recibe `pong`).
