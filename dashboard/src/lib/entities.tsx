import {
  Bell,
  Brain,
  CalendarBlank,
  ChatCircle,
  CheckSquare,
  FolderSimple,
  ListChecks,
  MapPin,
  Notepad,
  Waveform,
  Lightning,
  type Icon,
} from '@phosphor-icons/react'

export const ENTITY_ICON: Record<string, Icon> = {
  note: Notepad,
  task: CheckSquare,
  reminder: Bell,
  list: ListChecks,
  event: CalendarBlank,
  memory: Brain,
  place: MapPin,
  project: FolderSimple,
  recording: Waveform,
  conversation: ChatCircle,
}

export function entityIcon(type: string): Icon {
  return ENTITY_ICON[type] ?? Lightning
}

export const ENTITY_LABEL: Record<string, string> = {
  note: 'Nota',
  task: 'Tarea',
  reminder: 'Recordatorio',
  list: 'Lista',
  event: 'Evento',
  memory: 'Recuerdo',
  place: 'Lugar',
  project: 'Proyecto',
  recording: 'Grabación',
  conversation: 'Conversación',
}

/** Ruta del dashboard donde vive una entidad. */
export function entityHref(type: string, id: string): string | null {
  switch (type) {
    case 'note':
      return `/notas/${id}`
    case 'task':
      return `/tareas?tarea=${id}`
    case 'reminder':
      return `/recordatorios?r=${id}`
    case 'list':
      return `/listas/${id}`
    case 'event':
      return `/calendario?evento=${id}`
    case 'memory':
      return `/memoria?m=${id}`
    case 'place':
      return '/recordatorios#lugares'
    case 'project':
      return `/tareas?proyecto=${id}`
    case 'recording':
      return `/grabaciones/${id}`
    default:
      return null
  }
}

export const SOURCE_LABEL: Record<string, string> = {
  chat: 'Chat',
  voice: 'Voz',
  wear: 'Reloj',
  app: 'App',
  dashboard: 'Panel web',
  system: 'Sistema',
  recording: 'Grabación',
  share: 'Compartido',
  undo: 'Deshacer',
  google: 'Google',
  scheduler: 'Sistema',
  briefing: 'Resumen',
}

/** "desde el chat", "por voz"... para frases de metadatos. */
export function sourcePhrase(s: string) {
  const map: Record<string, string> = {
    chat: 'desde el chat',
    voice: 'por voz',
    wear: 'desde el reloj',
    app: 'desde la app',
    dashboard: 'desde el panel web',
    system: 'automáticamente',
    scheduler: 'automáticamente',
    recording: 'desde una grabación',
    share: 'al compartir',
    undo: 'al deshacer',
    google: 'desde Google Calendar',
    briefing: 'en el resumen',
  }
  return map[s] ?? `desde ${s}`
}

export function sourceLabel(s: string) {
  return SOURCE_LABEL[s] ?? s.charAt(0).toUpperCase() + s.slice(1)
}

export const PRIORITY_LABEL: Record<string, string> = {
  urgent: 'Urgente',
  high: 'Alta',
  medium: 'Media',
  low: 'Baja',
}

export const STATUS_LABEL: Record<string, string> = {
  todo: 'Por hacer',
  doing: 'En curso',
  done: 'Hecho',
}

export const MEMORY_CATEGORIES = [
  { id: 'perfil', label: 'Perfil' },
  { id: 'preferencias', label: 'Preferencias' },
  { id: 'personas', label: 'Personas' },
  { id: 'salud', label: 'Salud' },
  { id: 'trabajo', label: 'Trabajo' },
  { id: 'hogar', label: 'Hogar' },
  { id: 'general', label: 'General' },
] as const
