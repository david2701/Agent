/** Presets de recurrencia y su RRULE (RFC 5545). */

export type RecurrencePreset = 'none' | 'daily' | 'weekdays' | 'weekly' | 'monthly'

const BYDAY = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'] as const
const DAY_NAMES: Record<string, string> = {
  MO: 'lunes',
  TU: 'martes',
  WE: 'miércoles',
  TH: 'jueves',
  FR: 'viernes',
  SA: 'sábado',
  SU: 'domingo',
}

export function presetLabel(p: RecurrencePreset, date: Date | null): string {
  switch (p) {
    case 'none':
      return 'No se repite'
    case 'daily':
      return 'Cada día'
    case 'weekdays':
      return 'Días hábiles (lunes a viernes)'
    case 'weekly':
      return date ? `Cada semana el ${DAY_NAMES[BYDAY[date.getDay()]]}` : 'Cada semana'
    case 'monthly':
      return date ? `Cada mes el día ${date.getDate()}` : 'Cada mes'
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

/** Descripción breve en español: "cada lunes", "de lunes a viernes". */
export function describeRrule(rrule: string | null): string {
  if (!rrule) return ''
  const p = parse(rrule)
  const interval = Number(p.INTERVAL ?? '1') || 1
  const days = p.BYDAY?.split(',').map((d) => DAY_NAMES[d.slice(-2)]).filter(Boolean) ?? []
  switch (p.FREQ) {
    case 'DAILY':
      return interval === 1 ? 'Cada día' : `Cada ${interval} días`
    case 'WEEKLY':
      if (days.length === 5 && p.BYDAY === 'MO,TU,WE,TH,FR') return 'De lunes a viernes'
      if (days.length) return `${interval === 1 ? 'Cada' : `Cada ${interval} semanas el`} ${joinEs(days)}`
      return interval === 1 ? 'Cada semana' : `Cada ${interval} semanas`
    case 'MONTHLY':
      if (p.BYMONTHDAY) return `Cada mes el día ${p.BYMONTHDAY}`
      return interval === 1 ? 'Cada mes' : `Cada ${interval} meses`
    case 'YEARLY':
      return 'Cada año'
    case 'HOURLY':
      return interval === 1 ? 'Cada hora' : `Cada ${interval} horas`
    default:
      return 'Recurrente'
  }
}

function joinEs(items: string[]) {
  if (items.length <= 1) return items.join('')
  return `${items.slice(0, -1).join(', ')} y ${items[items.length - 1]}`
}
