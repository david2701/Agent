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
import { t, type TKey } from './i18n'

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

const ENTITY_TYPES = ['note', 'task', 'reminder', 'list', 'event', 'memory', 'place', 'project', 'recording', 'conversation']

/** Singular name of an entity type, or undefined if unknown. */
export function entityLabel(type: string): string | undefined {
  return ENTITY_TYPES.includes(type) ? t(`entity.${type}` as TKey) : undefined
}

/** Dashboard route where an entity lives. */
export function entityHref(type: string, id: string): string | null {
  switch (type) {
    case 'note':
      return `/notes/${id}`
    case 'task':
      return `/tasks?task=${id}`
    case 'reminder':
      return `/reminders?r=${id}`
    case 'list':
      return `/lists/${id}`
    case 'event':
      return `/calendar?event=${id}`
    case 'memory':
      return `/memory?m=${id}`
    case 'place':
      return '/reminders#places'
    case 'project':
      return `/tasks?project=${id}`
    case 'recording':
      return `/recordings/${id}`
    default:
      return null
  }
}

/** Sources the server reports (`scheduler` is shown as `system`). */
const SOURCES = ['chat', 'voice', 'wear', 'app', 'dashboard', 'system', 'recording', 'share', 'undo', 'google', 'briefing']

/** "from chat", "by voice"... for metadata phrases. */
export function sourcePhrase(s: string) {
  const key = s === 'scheduler' ? 'system' : s
  return SOURCES.includes(key) ? t(`sourcePhrase.${key}` as TKey) : t('sourcePhrase.other', { source: s })
}

export function sourceLabel(s: string) {
  const key = s === 'scheduler' ? 'system' : s
  return SOURCES.includes(key) ? t(`source.${key}` as TKey) : s.charAt(0).toUpperCase() + s.slice(1)
}

export function priorityLabel(p: string): string {
  return t(`priority.${p}` as TKey)
}

export function statusLabel(s: string): string {
  return t(`status.${s}` as TKey)
}

/** Memory categories. The ids are the API values (kept as they are); only the labels are translated. */
export function memoryCategories() {
  return (['perfil', 'preferencias', 'personas', 'salud', 'trabajo', 'hogar', 'general'] as const).map((id) => ({
    id,
    label: t(`memoryCategory.${id}` as TKey),
  }))
}
