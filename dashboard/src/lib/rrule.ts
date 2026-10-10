import { intlLocale, t } from './i18n'

/** Recurrence presets and their RRULE (RFC 5545). */

export type RecurrencePreset = 'none' | 'daily' | 'weekdays' | 'weekly' | 'monthly'

const BYDAY = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'] as const

/** Day name in the active language ("lunes" / "Monday"). 2024-01-07 was a Sunday. */
function dayName(code: string): string | undefined {
  const idx = BYDAY.indexOf(code as (typeof BYDAY)[number])
  if (idx < 0) return undefined
  return new Intl.DateTimeFormat(intlLocale(), { weekday: 'long' }).format(new Date(2024, 0, 7 + idx))
}

export function presetLabel(p: RecurrencePreset, date: Date | null): string {
  switch (p) {
    case 'none':
      return t('rrule.none')
    case 'daily':
      return t('rrule.daily')
    case 'weekdays':
      return t('rrule.weekdays')
    case 'weekly':
      return date ? t('rrule.weeklyOnDay', { day: dayName(BYDAY[date.getDay()]) ?? '' }) : t('rrule.weekly')
    case 'monthly':
      return date ? t('rrule.monthlyOnDay', { day: date.getDate() }) : t('rrule.monthly')
  }
}

export function presetToRrule(p: RecurrencePreset, date: Date | null): string | null {
  switch (p) {
    case 'none':
      return null
    case 'daily':
      return 'FREQ=DAILY'
    case 'weekdays':
      return 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'
    case 'weekly':
      return date ? `FREQ=WEEKLY;BYDAY=${BYDAY[date.getDay()]}` : 'FREQ=WEEKLY'
    case 'monthly':
      return date ? `FREQ=MONTHLY;BYMONTHDAY=${date.getDate()}` : 'FREQ=MONTHLY'
  }
}

export function rruleToPreset(rrule: string | null): RecurrencePreset {
  if (!rrule) return 'none'
  const p = parse(rrule)
  if (p.FREQ === 'DAILY' && !p.INTERVAL) return 'daily'
  if (p.FREQ === 'WEEKLY' && p.BYDAY === 'MO,TU,WE,TH,FR') return 'weekdays'
  if (p.FREQ === 'WEEKLY' && (!p.BYDAY || !p.BYDAY.includes(','))) return 'weekly'
  if (p.FREQ === 'MONTHLY') return 'monthly'
  return 'none'
}

function parse(rrule: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const part of rrule.replace(/^RRULE:/i, '').split(';')) {
    const [k, v] = part.split('=')
    if (k && v) out[k.toUpperCase()] = v.toUpperCase()
  }
  return out
}

/** Short description in the active language: "Every Monday", "Monday to Friday". */
export function describeRrule(rrule: string | null): string {
  if (!rrule) return ''
  const p = parse(rrule)
  const interval = Number(p.INTERVAL ?? '1') || 1
  const days = p.BYDAY?.split(',').map((d) => dayName(d.slice(-2))).filter((d): d is string => !!d) ?? []
  switch (p.FREQ) {
    case 'DAILY':
      return interval === 1 ? t('rrule.daily') : t('rrule.everyNDays', { count: interval })
    case 'WEEKLY':
      if (days.length === 5 && p.BYDAY === 'MO,TU,WE,TH,FR') return t('rrule.mondayToFriday')
      if (days.length) return interval === 1 ? t('rrule.weeklyOn', { days: joinList(days) }) : t('rrule.everyNWeeksOn', { count: interval, days: joinList(days) })
      return interval === 1 ? t('rrule.weekly') : t('rrule.everyNWeeks', { count: interval })
    case 'MONTHLY':
      if (p.BYMONTHDAY) return t('rrule.monthlyOnDay', { day: p.BYMONTHDAY })
      return interval === 1 ? t('rrule.monthly') : t('rrule.everyNMonths', { count: interval })
    case 'YEARLY':
      return t('rrule.yearly')
    case 'HOURLY':
      return interval === 1 ? t('rrule.hourly') : t('rrule.everyNHours', { count: interval })
    default:
      return t('rrule.recurring')
  }
}

function joinList(items: string[]) {
  return new Intl.ListFormat(intlLocale(), { style: 'long', type: 'conjunction' }).format(items)
}
