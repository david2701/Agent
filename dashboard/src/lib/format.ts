import {
  differenceInCalendarDays,
  format,
  formatDistanceToNowStrict,
  isSameYear,
  isToday,
  isTomorrow,
  isYesterday,
  parseISO,
} from 'date-fns'
import { es } from 'date-fns/locale'

export const locale = es

export function toDate(v: string | Date): Date {
  return typeof v === 'string' ? (v.length === 10 ? parseISO(v) : new Date(v)) : v
}

/** 9:30, 14:00 */
export function time(v: string | Date) {
  return format(toDate(v), 'H:mm', { locale: es })
}

/** sábado 10 de octubre (con año si no es el actual) */
export function longDate(v: string | Date) {
  const d = toDate(v)
  return format(d, isSameYear(d, new Date()) ? "EEEE d 'de' MMMM" : "EEEE d 'de' MMMM 'de' yyyy", { locale: es })
}

/** sáb 10 oct */
export function shortDate(v: string | Date) {
  const d = toDate(v)
  return format(d, isSameYear(d, new Date()) ? 'EEE d MMM' : 'd MMM yyyy', { locale: es }).replace(/\./g, '')
}

export function capitalize(s: string) {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s
}

/** Hoy, Mañana, Ayer, o "sáb 10 oct" */
export function dayLabel(v: string | Date) {
  const d = toDate(v)
  if (isToday(d)) return 'Hoy'
  if (isTomorrow(d)) return 'Mañana'
  if (isYesterday(d)) return 'Ayer'
  return capitalize(shortDate(d))
}

/** Hoy 9:30, Mañana 14:00, sáb 17 oct 9:00 */
export function dayTime(v: string | Date) {
  return `${dayLabel(v)} ${time(v)}`
}

/** hace 5 min, en 2 h */
export function relative(v: string | Date) {
  const d = toDate(v)
  const diff = Math.abs(Date.now() - d.getTime())
  if (diff < 45_000) return 'ahora'
  const s = formatDistanceToNowStrict(d, { locale: es, addSuffix: true })
  return s
    .replace(/ minutos?/, ' min')
    .replace(/ horas?/, ' h')
    .replace(/ segundos?/, ' s')
}

/** Para listas: "9:30" si es hoy, "Ayer", "jue 8 oct" */
export function compactWhen(v: string | Date) {
  const d = toDate(v)
  if (isToday(d)) return time(d)
  if (isYesterday(d)) return 'Ayer'
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

/** Valor para <input type="datetime-local"> */
export function toLocalInput(d: Date) {
  return format(d, "yyyy-MM-dd'T'HH:mm")
}

export function fromLocalInput(v: string): Date {
  return new Date(v)
}

export function plural(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`
}

/** Quita el markdown básico para extractos de una línea. */
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
