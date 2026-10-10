import { differenceInCalendarDays, format, isSameYear, isToday, isTomorrow, isYesterday, parseISO } from 'date-fns'
import { getLang, intlLocale, t } from './i18n'

export function toDate(v: string | Date): Date {
  return typeof v === 'string' ? (v.length === 10 ? parseISO(v) : new Date(v)) : v
}

/* ------------------------------------------------------------ Intl helpers (follow the active UI language) */

const formatters = new Map<string, Intl.DateTimeFormat>()

function dtf(opts: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const locale = intlLocale()
  const key = `${locale}|${JSON.stringify(opts)}`
  let f = formatters.get(key)
  if (!f) {
    f = new Intl.DateTimeFormat(locale, opts)
    formatters.set(key, f)
  }
  return f
}

/** Spanish reads better without the commas and abbreviation dots `Intl` adds ("sat 10 oct"). */
function tidy(s: string): string {
  return getLang() === 'es' ? s.replace(/[,.]/g, '').replace(/\s+/g, ' ').trim() : s
}

/** Lowercases the first letter in Spanish mid-sentence ("hoy 9:30"); English keeps "Today 9:30 AM". */
export function midSentence(s: string): string {
  return getLang() === 'es' ? s.charAt(0).toLowerCase() + s.slice(1) : s
}

/** Mon, lun */
export function weekdayShort(v: string | Date) {
  return tidy(dtf({ weekday: 'short' }).format(toDate(v)))
}

/** Monday, lunes */
export function weekdayLong(v: string | Date) {
  return dtf({ weekday: 'long' }).format(toDate(v))
}

/** October 2026, octubre de 2026 */
export function monthYear(v: string | Date) {
  return dtf({ month: 'long', year: 'numeric' }).format(toDate(v))
}

/** October 10, 10 de octubre */
export function dayMonthLong(v: string | Date) {
  return dtf({ day: 'numeric', month: 'long' }).format(toDate(v))
}

/** Oct 10, 10 oct */
export function dayMonthShort(v: string | Date) {
  return tidy(dtf({ day: 'numeric', month: 'short' }).format(toDate(v)))
}

/** Saturday, October 10 (with the year if it is not the current one) */
export function longDate(v: string | Date) {
  const d = toDate(v)
  const opts: Intl.DateTimeFormatOptions = { weekday: 'long', day: 'numeric', month: 'long' }
  if (!isSameYear(d, new Date())) opts.year = 'numeric'
  return getLang() === 'es' ? dtf(opts).format(d).replace(/,/g, '') : dtf(opts).format(d)
}

/** "Oct 5 – 11", "Sep 28 – Oct 4": a date range (e.g. the visible week). */
export function weekRange(start: Date, end: Date) {
  const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear()
  return tidyRange(dtf({ day: 'numeric', month: sameMonth ? 'long' : 'short' }).formatRange(start, end))
}

function tidyRange(s: string) {
  return getLang() === 'es' ? s.replace(/\./g, '') : s
}

/** First day of the week (0 = Sunday ... 6 = Saturday): Monday in Spanish, the browser's region in English. */
export function weekStartsOn(): 0 | 1 | 2 | 3 | 4 | 5 | 6 {
  if (getLang() === 'es') return 1
  try {
    const loc = new Intl.Locale(intlLocale()) as Intl.Locale & { getWeekInfo?: () => { firstDay: number }; weekInfo?: { firstDay: number } }
    const info = loc.getWeekInfo?.() ?? loc.weekInfo
    if (info?.firstDay) return (info.firstDay % 7) as 0 | 1 | 2 | 3 | 4 | 5 | 6
  } catch {
    /* unsupported */
  }
  return 0
}

/** Saturday, October 10 (always without the year; for section headings) */
export function dayHeading(v: string | Date) {
  const text = dtf({ weekday: 'long', day: 'numeric', month: 'long' }).format(toDate(v))
  return getLang() === 'es' ? text.replace(/,/g, '') : text
}

/** Sat, Oct 10 (with the year if it is not the current one) */
export function shortDate(v: string | Date) {
  const d = toDate(v)
  const sameYear = isSameYear(d, new Date())
  return tidy(dtf(sameYear ? { weekday: 'short', day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' }).format(d))
}

/** 9:30 AM, 14:00 */
export function time(v: string | Date) {
  return dtf({ hour: 'numeric', minute: '2-digit' }).format(toDate(v))
}

/** Hour of day for grids: 9 AM, 14:00 */
export function hourLabel(h: number) {
  return dtf({ hour: 'numeric' }).format(new Date(2000, 0, 1, h))
}

export function capitalize(s: string) {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s
}

/** Today, Tomorrow, Yesterday, or "Sat, Oct 10" */
export function dayLabel(v: string | Date) {
  const d = toDate(v)
  if (isToday(d)) return t('time.today')
  if (isTomorrow(d)) return t('time.tomorrow')
  if (isYesterday(d)) return t('time.yesterday')
  return capitalize(shortDate(d))
}

/** Today 9:30 AM, Tomorrow 2:00 PM, Sat, Oct 17 9:00 AM */
export function dayTime(v: string | Date) {
  return `${dayLabel(v)} ${time(v)}`
}

/** 5 min ago, in 2 hr */
export function relative(v: string | Date) {
  const d = toDate(v)
  const diff = d.getTime() - Date.now()
  const abs = Math.abs(diff)
  if (abs < 45_000) return t('time.now')
  const sign = diff < 0 ? -1 : 1
  const rtf = new Intl.RelativeTimeFormat(intlLocale(), { numeric: 'always', style: 'short' })
  // English abbreviations come with trailing dots ("5 min. ago"); drop them.
  const fmt = (n: number, unit: Intl.RelativeTimeFormatUnit) => rtf.format(sign * n, unit).replace(/\b(min|hr|sec|mo|yr|wk)\./g, '$1')
  const minutes = Math.round(abs / 60_000)
  if (minutes < 60) return fmt(minutes, 'minute')
  const hours = Math.round(abs / 3_600_000)
  if (hours < 24) return fmt(hours, 'hour')
  const days = Math.round(abs / 86_400_000)
  if (days < 30) return fmt(days, 'day')
  const months = Math.round(days / 30)
  if (months < 12) return fmt(months, 'month')
  return fmt(Math.round(days / 365), 'year')
}

/** For lists: the time if it is today, "Yesterday", or "Thu, Oct 8" */
export function compactWhen(v: string | Date) {
  const d = toDate(v)
  if (isToday(d)) return time(d)
  if (isYesterday(d)) return t('time.yesterday')
  return shortDate(d)
}

export function dueLabel(dueDate: string | null, dueAt: string | null): { text: string; overdue: boolean; today: boolean } | null {
  if (dueAt) {
    const d = toDate(dueAt)
    return { text: dayTime(d), overdue: d.getTime() < Date.now(), today: isToday(d) }
  }
  if (dueDate) {
    const d = parseISO(dueDate)
    const diff = differenceInCalendarDays(d, new Date())
    return { text: dayLabel(d), overdue: diff < 0, today: diff === 0 }
  }
  return null
}

export function duration(seconds: number) {
  const s = Math.max(0, Math.round(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  if (h) return `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
  return `${m}:${String(r).padStart(2, '0')}`
}

export function isoDate(d: Date) {
  return format(d, 'yyyy-MM-dd')
}

/** Value for <input type="datetime-local"> */
export function toLocalInput(d: Date) {
  return format(d, "yyyy-MM-dd'T'HH:mm")
}

export function fromLocalInput(v: string): Date {
  return new Date(v)
}

/** Strips basic markdown for one-line excerpts. */
export function plainText(md: string) {
  return md
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*[-*+]\s+\[[ xX]]\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/[*_`>~]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}
