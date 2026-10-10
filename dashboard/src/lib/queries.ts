import { keepPreviousData, useInfiniteQuery, useQuery, type QueryClient } from '@tanstack/react-query'
import { api, qs } from './api'
import type {
  Activity,
  CalendarEvent,
  Conversation,
  Device,
  GoogleCalendarInfo,
  GoogleStatus,
  KList,
  McpServer,
  Memory,
  Note,
  Place,
  Project,
  Recording,
  Reminder,
  SettingsResponse,
  StoredMessage,
  Task,
  Today,
  User,
  Voice,
} from './types'

export const keys = {
  today: ['today'] as const,
  briefing: ['briefing'] as const,
  notes: (q: { q?: string; tag?: string } = {}) => ['notes', 'list', q] as const,
  notesSearch: (q: string) => ['notes', 'search', q] as const,
  noteTags: ['notes', 'tags'] as const,
  note: (id: string) => ['notes', 'one', id] as const,
  tasks: ['tasks'] as const,
  projects: ['projects'] as const,
  reminders: ['reminders'] as const,
  places: ['places'] as const,
  lists: ['lists'] as const,
  events: (from: string, to: string) => ['events', from, to] as const,
  memories: ['memories'] as const,
  recordings: ['recordings'] as const,
  recording: (id: string) => ['recordings', id] as const,
  activity: ['activity'] as const,
  conversations: ['conversations'] as const,
  messages: (id: string) => ['conversations', id, 'messages'] as const,
  me: ['me'] as const,
  settings: ['settings'] as const,
  devices: ['devices'] as const,
  models: ['models'] as const,
  voices: ['voices'] as const,
  google: ['google'] as const,
  googleCalendars: ['google', 'calendars'] as const,
  mcp: ['mcp'] as const,
}

/** Qué cachés recargar cuando cambia una entidad (WebSocket o mutación propia). */
export function invalidateEntity(qc: QueryClient, entity: string) {
  const map: Record<string, readonly (readonly unknown[])[]> = {
    note: [['notes'], keys.today],
    task: [keys.tasks, keys.today, keys.projects],
    project: [keys.projects, keys.tasks],
    reminder: [keys.reminders, keys.today],
    place: [keys.places, keys.reminders],
    list: [keys.lists, keys.today],
    event: [['events'], keys.today],
    memory: [keys.memories],
    recording: [keys.recordings, ['notes']],
    conversation: [keys.conversations],
  }
  for (const key of map[entity] ?? [keys.today]) void qc.invalidateQueries({ queryKey: key })
  void qc.invalidateQueries({ queryKey: keys.activity })
}

export const useToday = () =>
  useQuery({ queryKey: keys.today, queryFn: ({ signal }) => api.get<Today>('/today', signal), refetchInterval: 5 * 60_000 })

export const useBriefing = () =>
  useQuery<{ text: string; date: string } | null>({
    queryKey: keys.briefing,
    queryFn: () => null,
    enabled: false,
    staleTime: Infinity,
  })

export const useNotes = (params: { q?: string; tag?: string }) =>
  useQuery({
    queryKey: keys.notes(params),
    queryFn: ({ signal }) => api.get<Note[]>(`/notes${qs({ ...params, limit: 200 })}`, signal),
    placeholderData: keepPreviousData,
  })

export const useNoteSearch = (q: string) =>
  useQuery({
    queryKey: keys.notesSearch(q),
    queryFn: ({ signal }) => api.get<Note[]>(`/notes/search${qs({ q })}`, signal),
    enabled: q.trim().length > 1,
    placeholderData: keepPreviousData,
  })

export const useNoteTags = () =>
  useQuery({ queryKey: keys.noteTags, queryFn: ({ signal }) => api.get<{ tags: string[] }>('/notes/tags', signal).then((r) => r.tags) })

export const useNote = (id: string | undefined) =>
  useQuery({ queryKey: keys.note(id ?? ''), queryFn: ({ signal }) => api.get<Note>(`/notes/${id}`, signal), enabled: !!id })

export const useTasks = () =>
  useQuery({ queryKey: keys.tasks, queryFn: ({ signal }) => api.get<Task[]>('/tasks?includeDone=true', signal) })

export const useProjects = () =>
  useQuery({ queryKey: keys.projects, queryFn: ({ signal }) => api.get<Project[]>('/projects', signal) })

export const useReminders = () =>
  useQuery({ queryKey: keys.reminders, queryFn: ({ signal }) => api.get<Reminder[]>('/reminders', signal) })

export const usePlaces = () => useQuery({ queryKey: keys.places, queryFn: ({ signal }) => api.get<Place[]>('/places', signal) })

export const useLists = () => useQuery({ queryKey: keys.lists, queryFn: ({ signal }) => api.get<KList[]>('/lists', signal) })

export const useEvents = (from: Date, to: Date) => {
  const f = from.toISOString()
  const t = to.toISOString()
  return useQuery({
    queryKey: keys.events(f, t),
    queryFn: ({ signal }) => api.get<CalendarEvent[]>(`/events${qs({ from: f, to: t })}`, signal),
    placeholderData: keepPreviousData,
  })
}

export const useMemories = () => useQuery({ queryKey: keys.memories, queryFn: ({ signal }) => api.get<Memory[]>('/memories', signal) })

export const useRecordings = () =>
  useQuery({
    queryKey: keys.recordings,
    queryFn: ({ signal }) => api.get<Recording[]>('/recordings', signal),
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'processing') ? 5000 : false),
  })

export const useRecording = (id: string | undefined) =>
  useQuery({
    queryKey: keys.recording(id ?? ''),
    queryFn: ({ signal }) => api.get<Recording>(`/recordings/${id}`, signal),
    enabled: !!id,
    refetchInterval: (q) => (q.state.data?.status === 'processing' ? 4000 : false),
  })

export const ACTIVITY_PAGE = 40

export const useActivity = () =>
  useInfiniteQuery({
    queryKey: keys.activity,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => api.get<Activity[]>(`/activity${qs({ limit: ACTIVITY_PAGE, before: pageParam })}`, signal),
    getNextPageParam: (last) => (last.length < ACTIVITY_PAGE ? undefined : last[last.length - 1]?.createdAt),
  })

export const useConversations = () =>
  useQuery({ queryKey: keys.conversations, queryFn: ({ signal }) => api.get<Conversation[]>('/conversations', signal) })

export const useMessages = (id: string | undefined) =>
  useQuery({
    queryKey: keys.messages(id ?? ''),
    queryFn: ({ signal }) => api.get<StoredMessage[]>(`/conversations/${id}/messages`, signal),
    enabled: !!id,
  })

export const useMe = () => useQuery({ queryKey: keys.me, queryFn: ({ signal }) => api.get<{ user: User }>('/me', signal).then((r) => r.user) })

export const useSettings = () =>
  useQuery({ queryKey: keys.settings, queryFn: ({ signal }) => api.get<SettingsResponse>('/settings', signal), staleTime: 60_000 })

export const useDevices = () => useQuery({ queryKey: keys.devices, queryFn: ({ signal }) => api.get<Device[]>('/devices', signal) })

export const useModels = () =>
  useQuery({
    queryKey: keys.models,
    queryFn: ({ signal }) => api.get<{ models: string[] }>('/models', signal).then((r) => r.models),
    staleTime: 10 * 60_000,
  })

export const useVoices = () =>
  useQuery({
    queryKey: keys.voices,
    queryFn: ({ signal }) => api.get<{ voices: Voice[] }>('/voice/voices', signal).then((r) => r.voices),
    staleTime: Infinity,
  })

export const useGoogle = () =>
  useQuery({ queryKey: keys.google, queryFn: ({ signal }) => api.get<GoogleStatus>('/integrations/google', signal) })

export const useGoogleCalendars = (enabled: boolean) =>
  useQuery({
    queryKey: keys.googleCalendars,
    queryFn: ({ signal }) => api.get<GoogleCalendarInfo[]>('/integrations/google/calendars', signal),
    enabled,
  })

export const useMcpServers = () => useQuery({ queryKey: keys.mcp, queryFn: ({ signal }) => api.get<McpServer[]>('/mcp-servers', signal) })
