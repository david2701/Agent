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
  UsersThree,
  Waveform,
  type Icon,
} from '@phosphor-icons/react'
import type { TKey } from '@/lib/i18n'

export interface NavItem {
  to: string
  /** Translation key of the label (resolve with `t(item.labelKey)`). */
  labelKey: TKey
  icon: Icon
  /** shortcut: "g" followed by this key */
  key?: string
}

export const NAV_GROUPS: NavItem[][] = [
  [
    { to: '/today', labelKey: 'nav.today', icon: SunHorizon, key: 'h' },
    { to: '/chat', labelKey: 'nav.chat', icon: ChatCircleDots, key: 'c' },
  ],
  [
    { to: '/notes', labelKey: 'nav.notes', icon: Notepad, key: 'n' },
    { to: '/tasks', labelKey: 'nav.tasks', icon: CheckSquare, key: 't' },
    { to: '/reminders', labelKey: 'nav.reminders', icon: Bell, key: 'r' },
    { to: '/calendar', labelKey: 'nav.calendar', icon: CalendarBlank, key: 'k' },
    { to: '/lists', labelKey: 'nav.lists', icon: ListChecks, key: 'l' },
  ],
  [
    { to: '/family', labelKey: 'nav.family', icon: UsersThree, key: 'f' },
    { to: '/memory', labelKey: 'nav.memory', icon: Brain, key: 'm' },
    { to: '/recordings', labelKey: 'nav.recordings', icon: Waveform, key: 'g' },
    { to: '/activity', labelKey: 'nav.activity', icon: ClockCounterClockwise, key: 'a' },
  ],
]

export const SETTINGS_ITEM: NavItem = { to: '/settings', labelKey: 'nav.settings', icon: GearSix, key: 's' }

export const ALL_NAV: NavItem[] = [...NAV_GROUPS.flat(), SETTINGS_ITEM]

export const MOBILE_PRIMARY = ['/today', '/chat', '/tasks', '/notes']
