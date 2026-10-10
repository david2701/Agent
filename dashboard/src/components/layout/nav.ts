import {
  Bell,
  Brain,
  CalendarBlank,
  ChatCircleDots,
  CheckSquare,
  ClockCounterClockwise,
  GearSix,
  ListChecks,
  Notepad,
  SunHorizon,
  Waveform,
  type Icon,
} from '@phosphor-icons/react'

export interface NavItem {
  to: string
  label: string
  icon: Icon
  /** atajo con "g" + tecla */
  key?: string
}

export const NAV_GROUPS: NavItem[][] = [
  [
    { to: '/', label: 'Hoy', icon: SunHorizon, key: 'h' },
    { to: '/chat', label: 'Conversación', icon: ChatCircleDots, key: 'c' },
  ],
  [
    { to: '/notas', label: 'Notas', icon: Notepad, key: 'n' },
    { to: '/tareas', label: 'Tareas', icon: CheckSquare, key: 't' },
    { to: '/recordatorios', label: 'Recordatorios', icon: Bell, key: 'r' },
    { to: '/calendario', label: 'Calendario', icon: CalendarBlank, key: 'k' },
    { to: '/listas', label: 'Listas', icon: ListChecks, key: 'l' },
  ],
  [
    { to: '/memoria', label: 'Memoria', icon: Brain, key: 'm' },
    { to: '/grabaciones', label: 'Grabaciones', icon: Waveform, key: 'g' },
    { to: '/actividad', label: 'Actividad', icon: ClockCounterClockwise, key: 'a' },
  ],
]

export const SETTINGS_ITEM: NavItem = { to: '/ajustes', label: 'Ajustes', icon: GearSix, key: 's' }

export const ALL_NAV: NavItem[] = [...NAV_GROUPS.flat(), SETTINGS_ITEM]

export const MOBILE_PRIMARY = ['/', '/chat', '/tareas', '/notas']
