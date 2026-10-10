export type ISODate = string // YYYY-MM-DD
export type ISOInstant = string // 2026-10-11T14:00:00Z

export interface User {
  id: string
  email: string
  displayName: string
  timezone: string
  createdAt: ISOInstant
}

export interface AuthResponse {
  user: User
  accessToken: string
  refreshToken: string | null
  expiresIn: number
  deviceId: string
}

export interface FileRef {
  id: string
  url: string
  contentType: string | null
}

export interface Note {
  id: string
  title: string
  content: string
  tags: string[]
  pinned: boolean
  source: string
  attachments: FileRef[]
  createdAt: ISOInstant
  updatedAt: ISOInstant
  score: number | null
}

export type TaskStatus = 'todo' | 'doing' | 'done'
export type TaskPriority = 'low' | 'medium' | 'high' | 'urgent'

export interface Task {
  id: string
  title: string
  notes: string
  status: TaskStatus
  priority: TaskPriority
  dueDate: ISODate | null
  dueAt: ISOInstant | null
  projectId: string | null
  projectName: string | null
  tags: string[]
  sort: number
  source: string
  completedAt: ISOInstant | null
  createdAt: ISOInstant
  updatedAt: ISOInstant
}

export interface Project {
  id: string
  name: string
  color: string | null
  sort: number
  createdAt: ISOInstant
}

export type ReminderStatus = 'scheduled' | 'fired' | 'done' | 'cancelled'

export interface Reminder {
  id: string
  text: string
  dueAt: ISOInstant | null
  rrule: string | null
  timezone: string
  placeId: string | null
  placeName: string | null
  placeTrigger: 'arrive' | 'leave' | null
  status: ReminderStatus
  nextFireAt: ISOInstant | null
  lastFiredAt: ISOInstant | null
  taskId: string | null
  source: string
  createdAt: ISOInstant
  updatedAt: ISOInstant
}

export interface Place {
  id: string
  name: string
  latitude: number
  longitude: number
  radiusM: number
  address: string | null
  createdAt: ISOInstant
}

export interface ListItem {
  id: string
  text: string
  checked: boolean
  sort: number
  createdAt: ISOInstant
}

export interface KList {
  id: string
  name: string
  icon: string | null
  items: ListItem[]
  createdAt: ISOInstant
  updatedAt: ISOInstant
}

export interface CalendarEvent {
  id: string
  title: string
  description: string
  location: string
  startAt: ISOInstant
  endAt: ISOInstant
  allDay: boolean
  rrule: string | null
  timezone: string
  reminderMinutes: number[]
  color: string | null
  source: 'kairo' | 'google' | string
  googleEventId: string | null
  createdAt: ISOInstant
  updatedAt: ISOInstant
}

export type MemoryCategory = 'perfil' | 'preferencias' | 'personas' | 'salud' | 'trabajo' | 'hogar' | 'general'

export interface Memory {
  id: string
  content: string
  category: MemoryCategory | string
  createdAt: ISOInstant
  updatedAt: ISOInstant
}

export type RecordingStatus = 'recording' | 'processing' | 'done' | 'failed'

export interface Recording {
  id: string
  title: string
  status: RecordingStatus
  durationS: number
  transcript: string
  summary: string
  keyPoints: string[]
  actionItems: string[]
  noteId: string | null
  error: string | null
  segments: number
  createdAt: ISOInstant
  updatedAt: ISOInstant
}

export type ActivityKind = 'created' | 'updated' | 'deleted' | 'completed' | 'fired' | 'restored'

export interface Activity {
  id: string
  kind: ActivityKind | string
  entityType: string
  entityId: string | null
  summary: string
  source: string
  undoable: boolean
  undoneAt: ISOInstant | null
  createdAt: ISOInstant
}

export interface Conversation {
  id: string
  title: string
  createdAt: ISOInstant
  updatedAt: ISOInstant
  preview: string | null
}

export type EntityType = 'note' | 'task' | 'reminder' | 'list' | 'event' | 'memory' | 'place' | 'project' | 'recording'

export interface EntityRef {
  type: EntityType | string
  id: string
  title: string
}

export interface StoredMessage {
  id: string
  role: 'user' | 'assistant' | 'tool'
  content: string
  toolName: string | null
  meta: {
    channel?: string
    attachments?: FileRef[]
    summary?: string
    ok?: boolean
    entity?: EntityRef
    attachment?: FileRef
    context?: string
  } | null
  createdAt: ISOInstant
}

export interface WeatherDay {
  date: ISODate
  min: number
  max: number
  rainChance: number | null
  description: string
}

export interface Weather {
  place: string
  temperature: number
  feelsLike: number | null
  description: string
  days: WeatherDay[]
}

export interface Today {
  date: ISODate
  dateLabel: string
  greeting: string
  events: CalendarEvent[]
  tasksDueToday: Task[]
  tasksOverdue: Task[]
  tasksInProgress: Task[]
  reminders: Reminder[]
  firedReminders: Reminder[]
  recentNotes: Note[]
  lists: KList[]
  weather: Weather | null
}

export interface UserSettings {
  chatModel: string
  fallbackModel: string
  visionModel: string
  imageModel: string
  fastMode: boolean
  voice: string
  voiceSpeed: number
  sttModel: string
  language: string
  wakeWord: string
  briefingEnabled: boolean
  briefingTime: string
  lastBriefingDate: string | null
  homeCity: string | null
  homeLatitude: number | null
  homeLongitude: number | null
  ntfyTopic: string | null
  googleCalendarId: string
  defaultEventReminderMinutes: number
  keepRecordingAudio: boolean
}

export interface SettingsResponse {
  settings: UserSettings
  googleAvailable: boolean
  ntfyAvailable: boolean
}

export interface GeoPlace {
  name: string
  admin: string | null
  country: string | null
  latitude: number
  longitude: number
  timezone: string | null
}

export interface Device {
  id: string
  name: string
  platform: string
  createdAt: ISOInstant
  lastSeenAt: ISOInstant
  current: boolean
}

export interface Voice {
  id: string
  name: string
  description: string
}

export interface GoogleStatus {
  available: boolean
  connected: boolean
  calendarId: string | null
  lastSyncAt: string | null
  lastError: string | null
  redirectUri: string
}

export interface GoogleCalendarInfo {
  id: string
  summary: string
  primary: boolean
}

export interface McpServer {
  id: string
  name: string
  url: string
  enabled: boolean
  hasHeaders: boolean
  createdAt: ISOInstant
}

/* Eventos del agente (SSE) */
export type DeviceAction =
  | { type: 'timer'; seconds: number; label?: string }
  | { type: 'alarm'; hour: number; minute: number; label?: string; days?: number[] }
  | { type: string; [k: string]: unknown }

export type AgentEvent =
  | { type: 'start'; conversationId: string }
  | { type: 'transcript'; text: string }
  | { type: 'delta'; text: string; step: number }
  | { type: 'tool_start'; callId: string; name: string; label: string }
  | { type: 'tool_end'; callId: string; name: string; ok: boolean; summary: string; entity: EntityRef | null }
  | { type: 'device_action'; action: DeviceAction }
  | { type: 'attachment'; file: FileRef }
  | { type: 'done'; conversationId: string; messageId: string; text: string; spoken: string }
  | { type: 'error'; message: string }

/* WebSocket */
export type ServerEvent =
  | { type: 'entity.changed'; data: { entity: string; id?: string; op: string } }
  | { type: 'reminder.fire'; data: { id: string; text: string; fireAt: string; recurring: boolean } }
  | { type: 'briefing.ready'; data: { text: string; date: string } }
  | { type: 'recording.updated'; data: { id: string } }
  | { type: 'ping' | 'pong'; data: Record<string, never> }
