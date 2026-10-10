import { CalendarBlank, CaretLeft, CaretRight, GoogleLogo, MapPin, Plus, Trash } from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  addDays,
  addMonths,
  addWeeks,
  differenceInMinutes,
  eachDayOfInterval,
  endOfMonth,
  endOfWeek,
  format,
  isSameDay,
  isSameMonth,
  isToday,
  startOfDay,
  startOfMonth,
  startOfWeek,
} from 'date-fns'
import { es } from 'date-fns/locale'
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Button, IconButton } from '@/components/ui/button'
import { ConfirmDialog, Dialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Field, Input, Select, Switch, Textarea } from '@/components/ui/form'
import { Segmented } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { capitalize, isoDate, time, toDate } from '@/lib/format'
import { invalidateEntity, useEvents } from '@/lib/queries'
import type { CalendarEvent } from '@/lib/types'
import { undoLatest } from '@/lib/undo'
import { useStored } from '@/lib/useStored'

type View = 'month' | 'week' | 'agenda'
const WEEK = { weekStartsOn: 1 as const, locale: es }
const HOUR_PX = 48

type Draft = { start: Date; allDay?: boolean } | CalendarEvent

export default function Calendario() {
  const [view, setView] = useStored<View>('kairo-cal-view', 'month')
  const [cursor, setCursor] = useState(() => new Date())
  const [selected, setSelected] = useState(() => startOfDay(new Date()))
  const [editing, setEditing] = useState<Draft | null>(null)
  const [params, setParams] = useSearchParams()

  const range = useMemo(() => {
    if (view === 'month') return { from: startOfWeek(startOfMonth(cursor), WEEK), to: addDays(endOfWeek(endOfMonth(cursor), WEEK), 1) }
    if (view === 'week') return { from: startOfWeek(cursor, WEEK), to: addDays(endOfWeek(cursor, WEEK), 1) }
    return { from: startOfDay(cursor), to: addDays(startOfDay(cursor), 31) }
  }, [view, cursor])
  const events = useEvents(range.from, range.to)

  // ?evento=id abre el evento; ?nuevo=1 abre el formulario
  useEffect(() => {
    const eid = params.get('evento')
    if (eid) {
      api
        .get<CalendarEvent>(`/events/${eid}`)
        .then((e) => {
          setCursor(toDate(e.startAt))
          setSelected(startOfDay(toDate(e.startAt)))
          setEditing(e)
        })
        .catch((e) => toastError(e, 'No encontré ese evento'))
      params.delete('evento')
      setParams(params, { replace: true })
    }
    if (params.get('nuevo')) {
      setEditing({ start: nextSlot() })
      params.delete('nuevo')
      setParams(params, { replace: true })
    }
  }, [params, setParams])

  const move = (dir: 1 | -1) => {
    setCursor((c) => (view === 'month' ? addMonths(c, dir) : view === 'week' ? addWeeks(c, dir) : addDays(c, dir * 30)))
  }

  const title =
    view === 'month'
      ? capitalize(format(cursor, "MMMM 'de' yyyy", { locale: es }))
      : view === 'week'
        ? weekTitle(startOfWeek(cursor, WEEK))
        : `Desde el ${format(cursor, "d 'de' MMMM", { locale: es })}`

  const list = events.data ?? []

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <header className="flex flex-wrap items-center gap-x-4 gap-y-3">
        <h1 className="min-w-0 flex-1 text-lg font-semibold tracking-tight whitespace-nowrap md:text-xl">{title}</h1>
        <Button variant="primary" className="sm:order-last" icon={<Plus className="size-4" />} onClick={() => setEditing({ start: nextSlot(selected) })}>
          <span className="max-sm:sr-only">Nuevo evento</span>
        </Button>
        <div className="flex w-full flex-wrap items-center justify-between gap-2 sm:w-auto sm:justify-start">
          <div className="flex items-center">
            <IconButton label="Anterior" onClick={() => move(-1)}>
              <CaretLeft className="size-[18px]" />
            </IconButton>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setCursor(new Date())
                setSelected(startOfDay(new Date()))
              }}
            >
              Hoy
            </Button>
            <IconButton label="Siguiente" onClick={() => move(1)}>
              <CaretRight className="size-[18px]" />
            </IconButton>
          </div>
          <Segmented
            label="Vista del calendario"
            value={view}
            onChange={setView}
            options={[
              { value: 'month', label: 'Mes' },
              { value: 'week', label: 'Semana' },
              { value: 'agenda', label: 'Agenda' },
            ]}
          />
        </div>
      </header>

      <div className="mt-6">
        {events.isError ? (
          <ErrorState message={(events.error as Error).message} onRetry={() => void events.refetch()} />
        ) : view === 'month' ? (
          <MonthView
            cursor={cursor}
            events={list}
            loading={events.isLoading}
            selected={selected}
            onSelect={setSelected}
            onOpen={setEditing}
            onNew={(d) => setEditing({ start: set9(d) })}
          />
        ) : view === 'week' ? (
          <WeekView cursor={cursor} events={list} loading={events.isLoading} onOpen={setEditing} onNew={(d, allDay) => setEditing({ start: d, allDay })} />
        ) : (
          <AgendaView from={startOfDay(cursor)} days={31} events={list} loading={events.isLoading} onOpen={setEditing} />
        )}
      </div>

      <EventDialog draft={editing} onClose={() => setEditing(null)} />
    </div>
  )
}

function weekTitle(start: Date) {
  const end = addDays(start, 6)
  if (isSameMonth(start, end)) return `${format(start, 'd', { locale: es })} al ${format(end, "d 'de' MMMM", { locale: es })}`
  return `${format(start, "d 'de' MMM", { locale: es })} al ${format(end, "d 'de' MMM", { locale: es })}`.replace(/\./g, '')
}

function set9(d: Date) {
  const x = new Date(d)
  x.setHours(9, 0, 0, 0)
  return x
}

function nextSlot(day?: Date) {
  const now = new Date()
  if (day && !isSameDay(day, now)) return set9(day)
  const d = new Date(now)
  d.setMinutes(0, 0, 0)
  d.setHours(d.getHours() + 1)
  return d
}

function eventsOn(day: Date, events: CalendarEvent[]) {
  const start = startOfDay(day)
  const end = addDays(start, 1)
  return events
    .filter((e) => toDate(e.startAt) < end && toDate(e.endAt) > start && !(e.allDay && toDate(e.endAt).getTime() === start.getTime()))
    .sort((a, b) => Number(b.allDay) - Number(a.allDay) || a.startAt.localeCompare(b.startAt))
}

function SourceMark({ e }: { e: CalendarEvent }) {
  if (e.source !== 'google') return null
  return <GoogleLogo className="size-3 shrink-0 text-faint" aria-label="De Google Calendar" />
}

/* ---------------------------------------------------------------- Mes */

function MonthView({
  cursor,
  events,
  loading,
  selected,
  onSelect,
  onOpen,
  onNew,
}: {
  cursor: Date
  events: CalendarEvent[]
  loading: boolean
  selected: Date
  onSelect: (d: Date) => void
  onOpen: (e: CalendarEvent) => void
  onNew: (d: Date) => void
}) {
  const days = eachDayOfInterval({ start: startOfWeek(startOfMonth(cursor), WEEK), end: endOfWeek(endOfMonth(cursor), WEEK) })
  const weekdays = days.slice(0, 7).map((d) => format(d, 'EEE', { locale: es }).replace('.', ''))
  const selectedEvents = eventsOn(selected, events)

  return (
    <>
      <div className="overflow-hidden rounded-card border border-line bg-surface">
        <div className="grid grid-cols-7 border-b border-line">
          {weekdays.map((w) => (
            <div key={w} className="px-2 py-2 text-center text-xs font-medium text-muted capitalize md:text-left md:text-sm">
              {w}
            </div>
          ))}
        </div>
        <div className="grid grid-cols-7">
          {days.map((d, i) => {
            const list = eventsOn(d, events)
            const inMonth = isSameMonth(d, cursor)
            const sel = isSameDay(d, selected)
            return (
              <div
                key={d.toISOString()}
                className={cn(
                  'group relative min-h-16 border-line p-1 md:min-h-[118px] md:p-1.5',
                  i % 7 !== 6 && 'border-r',
                  i < days.length - 7 && 'border-b',
                  !inMonth && 'bg-bg/50',
                  sel && 'max-md:bg-surface-2',
                )}
              >
                <button
                  type="button"
                  onClick={() => onSelect(d)}
                  onDoubleClick={() => onNew(d)}
                  className="absolute inset-0 md:cursor-default"
                  aria-label={`${format(d, "EEEE d 'de' MMMM", { locale: es })}, ${list.length} eventos`}
                />
                <div className="pointer-events-none relative flex items-center justify-between">
                  <span
                    className={cn(
                      'flex size-6 items-center justify-center rounded-full font-mono text-sm tnum',
                      isToday(d) ? 'bg-accent font-medium text-on-accent' : inMonth ? 'text-fg' : 'text-faint',
                    )}
                  >
                    {format(d, 'd')}
                  </span>
                  <button
                    type="button"
                    onClick={() => onNew(d)}
                    aria-label="Nuevo evento este día"
                    className="pointer-events-auto hidden size-6 items-center justify-center rounded-[6px] text-faint opacity-0 transition-opacity group-hover:opacity-100 hover:bg-surface-2 hover:text-fg focus-visible:opacity-100 md:flex"
                  >
                    <Plus className="size-3.5" />
                  </button>
                </div>
                {/* Escritorio: títulos */}
                <div className="relative mt-1 hidden gap-0.5 md:grid">
                  {loading && i % 5 === 1 && <Skeleton className="h-4 w-4/5 rounded-[5px]" />}
                  {list.slice(0, 3).map((e) => (
                    <button
                      key={e.id + e.startAt}
                      type="button"
                      onClick={() => onOpen(e)}
                      className={cn(
                        'flex min-w-0 items-center gap-1.5 rounded-[6px] px-1.5 py-0.5 text-left text-xs transition-colors',
                        e.allDay ? 'bg-surface-2 text-fg hover:bg-line' : 'text-fg hover:bg-surface-2',
                      )}
                    >
                      {!e.allDay && <span className="hidden shrink-0 font-mono text-faint tnum lg:inline">{time(e.startAt)}</span>}
                      <span className="truncate">{e.title}</span>
                      <SourceMark e={e} />
                    </button>
                  ))}
                  {list.length > 3 && (
                    <button type="button" onClick={() => onSelect(d)} className="px-1.5 text-left text-xs text-muted hover:text-fg">
                      {list.length - 3} más
                    </button>
                  )}
                </div>
                {/* Móvil: marcas por evento */}
                <div className="pointer-events-none relative mt-1 grid gap-0.5 md:hidden" aria-hidden>
                  {list.slice(0, 3).map((e) => (
                    <span key={e.id + e.startAt} className={cn('h-1 rounded-full', e.allDay ? 'bg-muted' : 'bg-faint')} />
                  ))}
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Día seleccionado */}
      <section className="mt-5 rounded-card border border-line bg-surface p-4 md:p-5" aria-label="Día seleccionado">
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-md font-semibold tracking-tight">{capitalize(format(selected, "EEEE d 'de' MMMM", { locale: es }))}</h2>
          <Button size="sm" icon={<Plus className="size-4" />} onClick={() => onNew(selected)}>
            Agregar
          </Button>
        </div>
        {selectedEvents.length === 0 ? (
          <p className="mt-3 text-base text-muted">Sin eventos este día.</p>
        ) : (
          <div className="mt-2 divide-y divide-line">
            {selectedEvents.map((e) => (
              <EventRow key={e.id + e.startAt} e={e} onOpen={onOpen} />
            ))}
          </div>
        )}
      </section>
    </>
  )
}

function EventRow({ e, onOpen }: { e: CalendarEvent; onOpen: (e: CalendarEvent) => void }) {
  return (
    <button type="button" onClick={() => onOpen(e)} className="-mx-2 flex w-[calc(100%+1rem)] gap-4 rounded-control px-2 py-3 text-left transition-colors hover:bg-surface-2">
      <span className="w-14 shrink-0 text-right font-mono tnum">
        {e.allDay ? (
          <span className="block pt-0.5 font-sans text-xs text-muted">Todo el día</span>
        ) : (
          <>
            <span className="block text-base text-fg">{time(e.startAt)}</span>
            <span className="block text-xs text-faint">{time(e.endAt)}</span>
          </>
        )}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5 text-base font-medium text-fg">
          <span className="truncate">{e.title}</span>
          <SourceMark e={e} />
        </span>
        {e.location && (
          <span className="mt-0.5 flex items-center gap-1 text-sm text-muted">
            <MapPin className="size-3.5 shrink-0" />
            <span className="truncate">{e.location}</span>
          </span>
        )}
      </span>
    </button>
  )
}

/* ---------------------------------------------------------------- Semana */

interface Placed {
  e: CalendarEvent
  top: number
  height: number
  lane: number
  lanes: number
}

function layoutDay(day: Date, events: CalendarEvent[]): Placed[] {
  const start = startOfDay(day)
  const end = addDays(start, 1)
  const timed = events
    .filter((e) => !e.allDay && toDate(e.startAt) < end && toDate(e.endAt) > start)
    .sort((a, b) => a.startAt.localeCompare(b.startAt))
  const placed: Placed[] = []
  let cluster: Placed[] = []
  let clusterEnd = 0
  const laneEnds: number[] = []
  const flush = () => {
    const lanes = Math.max(1, ...cluster.map((p) => p.lane + 1))
    cluster.forEach((p) => (p.lanes = lanes))
    cluster = []
    laneEnds.length = 0
  }
  for (const e of timed) {
    const s = Math.max(0, differenceInMinutes(toDate(e.startAt), start))
    const f = Math.min(24 * 60, differenceInMinutes(toDate(e.endAt), start))
    if (cluster.length && s >= clusterEnd) flush()
    let lane = laneEnds.findIndex((end2) => end2 <= s)
    if (lane === -1) {
      lane = laneEnds.length
      laneEnds.push(f)
    } else laneEnds[lane] = f
    const p: Placed = { e, top: (s / 60) * HOUR_PX, height: Math.max(22, ((f - s) / 60) * HOUR_PX - 2), lane, lanes: 1 }
    cluster.push(p)
    placed.push(p)
    clusterEnd = Math.max(clusterEnd, f)
  }
  flush()
  return placed
}

function WeekView({
  cursor,
  events,
  loading,
  onOpen,
  onNew,
}: {
  cursor: Date
  events: CalendarEvent[]
  loading: boolean
  onOpen: (e: CalendarEvent) => void
  onNew: (d: Date, allDay?: boolean) => void
}) {
  const start = startOfWeek(cursor, WEEK)
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i))
  const scroller = useRef<HTMLDivElement>(null)
  const [now, setNow] = useState(() => new Date())

  useLayoutEffect(() => {
    if (scroller.current) scroller.current.scrollTop = 6.6 * HOUR_PX
  }, [])
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 60_000)
    return () => clearInterval(t)
  }, [])

  return (
    <>
      {/* Móvil: la semana como lista de días */}
      <div className="md:hidden">
        <AgendaView from={start} days={7} events={events} loading={loading} onOpen={onOpen} showEmptyDays />
      </div>

      <div className="hidden overflow-hidden rounded-card border border-line bg-surface md:block">
        <div className="grid grid-cols-[56px_repeat(7,minmax(0,1fr))] border-b border-line">
          <div />
          {days.map((d) => (
            <div key={d.toISOString()} className="border-l border-line px-2 py-2">
              <p className="text-xs text-muted capitalize">{format(d, 'EEE', { locale: es }).replace('.', '')}</p>
              <p className={cn('mt-0.5 inline-flex size-7 items-center justify-center rounded-full font-mono text-base tnum', isToday(d) ? 'bg-accent text-on-accent' : 'text-fg')}>
                {format(d, 'd')}
              </p>
            </div>
          ))}
        </div>
        {/* Todo el día */}
        <div className="grid grid-cols-[56px_repeat(7,minmax(0,1fr))] border-b border-line">
          <div className="px-2 py-1.5 text-right text-[11px] leading-4 text-faint">Todo el día</div>
          {days.map((d) => (
            <div key={d.toISOString()} className="grid content-start gap-0.5 border-l border-line p-1">
              {eventsOn(d, events)
                .filter((e) => e.allDay)
                .map((e) => (
                  <button
                    key={e.id + e.startAt}
                    type="button"
                    onClick={() => onOpen(e)}
                    className="flex items-center gap-1 truncate rounded-[6px] bg-surface-2 px-1.5 py-0.5 text-left text-xs text-fg hover:bg-line"
                  >
                    <span className="truncate">{e.title}</span>
                    <SourceMark e={e} />
                  </button>
                ))}
              <button type="button" aria-label="Nuevo evento de todo el día" onClick={() => onNew(set9(d), true)} className="h-3" />
            </div>
          ))}
        </div>
        <div ref={scroller} className="relative max-h-[min(640px,calc(100dvh-280px))] overflow-y-auto">
          <div className="grid grid-cols-[56px_repeat(7,minmax(0,1fr))]" style={{ height: 24 * HOUR_PX }}>
            <div className="relative">
              {Array.from({ length: 24 }, (_, h) => (
                <span key={h} className="absolute right-2 -translate-y-1/2 font-mono text-[11px] text-faint tnum" style={{ top: h * HOUR_PX }}>
                  {h === 0 ? '' : `${h}:00`}
                </span>
              ))}
            </div>
            {days.map((d) => {
              const placed = layoutDay(d, events)
              const nowTop = ((now.getHours() * 60 + now.getMinutes()) / 60) * HOUR_PX
              return (
                <div key={d.toISOString()} className="relative border-l border-line">
                  {Array.from({ length: 24 }, (_, h) => (
                    <button
                      key={h}
                      type="button"
                      aria-label={`Nuevo evento ${format(d, 'EEEE', { locale: es })} a las ${h}:00`}
                      onClick={() => {
                        const x = new Date(d)
                        x.setHours(h, 0, 0, 0)
                        onNew(x)
                      }}
                      className="absolute inset-x-0 border-t border-line/70 transition-colors hover:bg-surface-2/50"
                      style={{ top: h * HOUR_PX, height: HOUR_PX }}
                    />
                  ))}
                  {placed.map((p) => (
                    <button
                      key={p.e.id + p.e.startAt}
                      type="button"
                      onClick={() => onOpen(p.e)}
                      className="absolute overflow-hidden rounded-[8px] border border-line bg-surface-2 px-1.5 py-1 text-left transition-colors hover:border-faint"
                      style={{
                        top: p.top + 1,
                        height: p.height,
                        left: `calc(${(p.lane / p.lanes) * 100}% + 2px)`,
                        width: `calc(${100 / p.lanes}% - 4px)`,
                      }}
                    >
                      <span className="flex items-center gap-1 text-xs font-medium text-fg">
                        <span className="truncate">{p.e.title}</span>
                        <SourceMark e={p.e} />
                      </span>
                      {p.height > 34 && <span className="block truncate font-mono text-[11px] text-muted tnum">{time(p.e.startAt)} a {time(p.e.endAt)}</span>}
                    </button>
                  ))}
                  {isToday(d) && (
                    <div className="pointer-events-none absolute inset-x-0 flex items-center" style={{ top: nowTop }} aria-hidden>
                      <span className="-ml-[3px] size-1.5 rounded-full bg-accent" />
                      <span className="h-px flex-1 bg-accent" />
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </div>
      </div>
    </>
  )
}

/* ---------------------------------------------------------------- Agenda */

function AgendaView({
  from,
  days,
  events,
  loading,
  onOpen,
  showEmptyDays,
}: {
  from: Date
  days: number
  events: CalendarEvent[]
  loading: boolean
  onOpen: (e: CalendarEvent) => void
  showEmptyDays?: boolean
}) {
  const list = Array.from({ length: days }, (_, i) => addDays(from, i))
    .map((d) => ({ d, items: eventsOn(d, events) }))
    .filter((x) => showEmptyDays || x.items.length > 0)

  if (loading)
    return (
      <div className="grid gap-4 rounded-card border border-line bg-surface p-5" aria-busy="true">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="grid gap-2">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-10 w-full rounded-control" />
          </div>
        ))}
      </div>
    )
  if (list.length === 0)
    return (
      <div className="rounded-card border border-line bg-surface">
        <EmptyState icon={<CalendarBlank />} title="Sin eventos en estos días" description="Crea uno o pídele a Kairo que lo agende." />
      </div>
    )
  return (
    <div className="grid gap-3">
      {list.map(({ d, items }) => (
        <section key={d.toISOString()} className="rounded-card border border-line bg-surface px-4 pt-3 pb-1 md:px-5" aria-label={format(d, "EEEE d 'de' MMMM", { locale: es })}>
          <h2 className="flex items-baseline gap-2 text-base font-semibold">
            {capitalize(format(d, "EEEE d 'de' MMMM", { locale: es }))}
            {isToday(d) && <span className="text-sm font-medium text-accent-strong">Hoy</span>}
          </h2>
          {items.length === 0 ? (
            <p className="py-2.5 text-sm text-faint">Libre</p>
          ) : (
            <div className="divide-y divide-line">
              {items.map((e) => (
                <EventRow key={e.id + e.startAt} e={e} onOpen={onOpen} />
              ))}
            </div>
          )}
        </section>
      ))}
    </div>
  )
}

/* ---------------------------------------------------------------- Diálogo */

const REMINDER_OPTIONS = [
  { v: '', label: 'Sin aviso' },
  { v: '5', label: '5 minutos antes' },
  { v: '10', label: '10 minutos antes' },
  { v: '15', label: '15 minutos antes' },
  { v: '30', label: '30 minutos antes' },
  { v: '60', label: '1 hora antes' },
  { v: '1440', label: '1 día antes' },
]

function hhmm(d: Date) {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function EventDialog({ draft, onClose }: { draft: Draft | null; onClose: () => void }) {
  const qc = useQueryClient()
  const editing = draft && 'id' in draft ? draft : null
  const [title, setTitle] = useState('')
  const [allDay, setAllDay] = useState(false)
  const [startDate, setStartDate] = useState('')
  const [startTime, setStartTime] = useState('09:00')
  const [endDate, setEndDate] = useState('')
  const [endTime, setEndTime] = useState('10:00')
  const [location, setLocation] = useState('')
  const [description, setDescription] = useState('')
  const [reminder, setReminder] = useState('15')
  const [error, setError] = useState<string | null>(null)
  const [confirm, setConfirm] = useState(false)

  useEffect(() => {
    if (!draft) return
    setError(null)
    if ('id' in draft) {
      const s = toDate(draft.startAt)
      let e = toDate(draft.endAt)
      if (draft.allDay) e = addDays(e, -1)
      setTitle(draft.title)
      setAllDay(draft.allDay)
      setStartDate(isoDate(s))
      setStartTime(hhmm(s))
      setEndDate(isoDate(e))
      setEndTime(hhmm(toDate(draft.endAt)))
      setLocation(draft.location)
      setDescription(draft.description)
      setReminder(draft.reminderMinutes[0] !== undefined ? String(draft.reminderMinutes[0]) : '')
    } else {
      const s = draft.start
      const e = new Date(s.getTime() + 60 * 60_000)
      setTitle('')
      setAllDay(!!draft.allDay)
      setStartDate(isoDate(s))
      setStartTime(hhmm(s))
      setEndDate(isoDate(e))
      setEndTime(hhmm(e))
      setLocation('')
      setDescription('')
      setReminder('15')
    }
  }, [draft])

  const save = useMutation({
    mutationFn: () => {
      let startAt: Date
      let endAt: Date
      if (allDay) {
        startAt = new Date(`${startDate}T00:00`)
        endAt = addDays(new Date(`${endDate || startDate}T00:00`), 1)
      } else {
        startAt = new Date(`${startDate}T${startTime}`)
        endAt = new Date(`${endDate || startDate}T${endTime}`)
      }
      const body = {
        title: title.trim(),
        startAt: startAt.toISOString(),
        endAt: endAt.toISOString(),
        allDay,
        location: location.trim(),
        description,
        reminderMinutes: reminder ? [Number(reminder)] : [],
      }
      return editing ? api.patch<CalendarEvent>(`/events/${editing.id}`, body) : api.post<CalendarEvent>('/events', body)
    },
    onSuccess: (e) => {
      invalidateEntity(qc, 'event')
      onClose()
      if (editing) toast({ title: 'Evento guardado', description: e.title })
      else
        toast({
          title: 'Evento creado',
          description: e.title,
          tone: 'success',
          actions: [{ label: 'Deshacer', onClick: () => undoLatest(qc, 'event', e.id, 'created').catch((er) => toastError(er, 'No se pudo deshacer')) }],
        })
    },
    onError: (e) => setError(e instanceof Error ? e.message : 'No se pudo guardar'),
  })

  const remove = useMutation({
    mutationFn: (e: CalendarEvent) => api.del(`/events/${e.id}`).then(() => e),
    onSuccess: (e) => {
      invalidateEntity(qc, 'event')
      setConfirm(false)
      onClose()
      toast({
        title: 'Evento eliminado',
        description: e.title,
        actions: [{ label: 'Deshacer', onClick: () => undoLatest(qc, 'event', e.id, 'deleted').catch((er) => toastError(er, 'No se pudo deshacer')) }],
      })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar'),
  })

  const submit = (ev: FormEvent) => {
    ev.preventDefault()
    if (!title.trim()) return setError('Ponle un título al evento.')
    if (!startDate) return setError('Elige la fecha.')
    const s = allDay ? new Date(`${startDate}T00:00`) : new Date(`${startDate}T${startTime}`)
    const e = allDay ? new Date(`${endDate || startDate}T00:00`) : new Date(`${endDate || startDate}T${endTime}`)
    if (allDay ? e < s : e <= s) return setError('El final debe ser después del inicio.')
    setError(null)
    save.mutate()
  }

  return (
    <Dialog
      open={!!draft}
      onClose={onClose}
      title={editing ? 'Editar evento' : 'Nuevo evento'}
      description={
        editing?.source === 'google' ? (
          <span className="inline-flex items-center gap-1.5">
            <GoogleLogo className="size-3.5" /> Sincronizado con Google Calendar
          </span>
        ) : undefined
      }
      footer={
        <>
          {editing && (
            <Button variant="ghost" className="mr-auto text-danger hover:text-danger" icon={<Trash className="size-4" />} onClick={() => setConfirm(true)}>
              Eliminar
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" type="submit" form="event-form" loading={save.isPending}>
            Guardar
          </Button>
        </>
      }
    >
      <form id="event-form" onSubmit={submit} className="grid gap-4">
        <Field label="Título">
          {(id) => <Input id={id} value={title} onChange={(e) => setTitle(e.target.value)} autoFocus placeholder="Cita con la odontóloga" />}
        </Field>
        <label className="flex items-center justify-between gap-3 rounded-control border border-line px-3 py-2.5">
          <span className="text-base">Todo el día</span>
          <Switch checked={allDay} onChange={setAllDay} label="Todo el día" />
        </label>
        <div className="grid grid-cols-[minmax(0,1fr)_auto] gap-3">
          <Field label="Empieza">
            {(id) => (
              <Input
                id={id}
                type="date"
                value={startDate}
                onChange={(e) => {
                  setStartDate(e.target.value)
                  if (!endDate || endDate < e.target.value) setEndDate(e.target.value)
                }}
                className="font-mono"
              />
            )}
          </Field>
          {!allDay && (
            <Field label="Hora">
              {(id) => <Input id={id} type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} className="w-32 font-mono" />}
            </Field>
          )}
          <Field label="Termina">
            {(id) => <Input id={id} type="date" value={endDate} min={startDate} onChange={(e) => setEndDate(e.target.value)} className="font-mono" />}
          </Field>
          {!allDay && (
            <Field label="Hora">
              {(id) => <Input id={id} type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} className="w-32 font-mono" />}
            </Field>
          )}
        </div>
        <Field label="Lugar" optional>
          {(id) => <Input id={id} value={location} onChange={(e) => setLocation(e.target.value)} placeholder="Clínica Dental Laureles" />}
        </Field>
        <Field label="Aviso">
          {(id) => (
            <Select id={id} value={reminder} onChange={(e) => setReminder(e.target.value)}>
              {REMINDER_OPTIONS.map((o) => (
                <option key={o.v} value={o.v}>
                  {o.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Descripción" optional>
          {(id) => <Textarea id={id} value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />}
        </Field>
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
      </form>
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => editing && remove.mutate(editing)}
        loading={remove.isPending}
        title="¿Eliminar este evento?"
        description={editing?.rrule ? 'Es un evento recurrente: se eliminan todas sus repeticiones.' : 'Podrás deshacerlo desde el aviso o desde Actividad.'}
      />
    </Dialog>
  )
}
