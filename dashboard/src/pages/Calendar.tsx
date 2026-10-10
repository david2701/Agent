import { CalendarBlank, CaretLeft, CaretRight, GoogleLogo, House, MapPin, Plus, Trash } from '@phosphor-icons/react'
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
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { FamilyIcon } from '@/components/family'
import { Button, IconButton } from '@/components/ui/button'
import { ConfirmDialog, Dialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Field, Input, Select, Switch, Textarea } from '@/components/ui/form'
import { Segmented } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useFamilyInfo, type FamilyView } from '@/lib/family'
import { capitalize, dayHeading, dayMonthLong, hourLabel, isoDate, monthYear, time, toDate, weekdayLong, weekdayShort, weekRange, weekStartsOn } from '@/lib/format'
import { t, tn } from '@/lib/i18n'
import { invalidateEntity, useEvents } from '@/lib/queries'
import type { CalendarEvent } from '@/lib/types'
import { undoLatest } from '@/lib/undo'
import { useStored } from '@/lib/useStored'

type View = 'month' | 'week' | 'agenda'
/** First day of the week follows the language (Monday in Spanish; the browser's region in English). */
const week = () => ({ weekStartsOn: weekStartsOn() })
const HOUR_PX = 48

type Draft = { start: Date; allDay?: boolean } | CalendarEvent

export default function CalendarPage() {
  const [view, setView] = useStored<View>('kairo-cal-view', 'month')
  const [cursor, setCursor] = useState(() => new Date())
  const [selected, setSelected] = useState(() => startOfDay(new Date()))
  const [editing, setEditing] = useState<Draft | null>(null)
  const [params, setParams] = useSearchParams()

  const range = useMemo(() => {
    if (view === 'month') return { from: startOfWeek(startOfMonth(cursor), week()), to: addDays(endOfWeek(endOfMonth(cursor), week()), 1) }
    if (view === 'week') return { from: startOfWeek(cursor, week()), to: addDays(endOfWeek(cursor, week()), 1) }
    return { from: startOfDay(cursor), to: addDays(startOfDay(cursor), 31) }
  }, [view, cursor])
  const events = useEvents(range.from, range.to)

  // ?event=id opens the event; ?new=1 opens the form
  useEffect(() => {
    const eid = params.get('event')
    if (eid) {
      api
        .get<CalendarEvent>(`/events/${eid}`)
        .then((e) => {
          setCursor(toDate(e.startAt))
          setSelected(startOfDay(toDate(e.startAt)))
          setEditing(e)
        })
        .catch((e) => toastError(e, t('calendar.err.notFound')))
      params.delete('event')
      setParams(params, { replace: true })
    }
    if (params.get('new')) {
      setEditing({ start: nextSlot() })
      params.delete('new')
      setParams(params, { replace: true })
    }
  }, [params, setParams])

  const move = (dir: 1 | -1) => {
    setCursor((c) => (view === 'month' ? addMonths(c, dir) : view === 'week' ? addWeeks(c, dir) : addDays(c, dir * 30)))
  }

  const title =
    view === 'month'
      ? capitalize(monthYear(cursor))
      : view === 'week'
        ? weekTitle(startOfWeek(cursor, week()))
        : t('calendar.fromDate', { date: dayMonthLong(cursor) })

  const list = events.data ?? []
  const fam = useFamilyInfo()
  const hasFamilyEvents = fam.hasOthers && list.some((e) => e.shared)

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <header className="flex flex-wrap items-center gap-x-4 gap-y-3">
        <h1 className="min-w-0 flex-1 text-lg font-semibold tracking-tight whitespace-nowrap md:text-xl">{title}</h1>
        <Button variant="primary" className="sm:order-last" icon={<Plus className="size-4" />} onClick={() => setEditing({ start: nextSlot(selected) })}>
          <span className="max-sm:sr-only">{t('calendar.new')}</span>
        </Button>
        <div className="flex w-full flex-wrap items-center justify-between gap-2 sm:w-auto sm:justify-start">
          <div className="flex items-center">
            <IconButton label={t('calendar.previous')} onClick={() => move(-1)}>
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
              {t('calendar.today')}
            </Button>
            <IconButton label={t('calendar.next')} onClick={() => move(1)}>
              <CaretRight className="size-[18px]" />
            </IconButton>
          </div>
          <Segmented
            label={t('calendar.viewLabel')}
            value={view}
            onChange={setView}
            options={[
              { value: 'month', label: t('calendar.month') },
              { value: 'week', label: t('calendar.week') },
              { value: 'agenda', label: t('calendar.agenda') },
            ]}
          />
        </div>
      </header>

      {hasFamilyEvents && (
        <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
          <House className="size-3.5 shrink-0 text-faint" aria-hidden />
          {t('calendar.familyNote')}
        </p>
      )}

      <div className={hasFamilyEvents ? 'mt-4' : 'mt-6'}>
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
  return weekRange(start, addDays(start, 6))
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
  return (
    <>
      {e.shared && <FamilyIcon className="size-3" label={t('today.familyEvent')} />}
      {e.source === 'google' && <GoogleLogo className="size-3 shrink-0 text-faint" aria-label={t('today.fromGoogle')} />}
    </>
  )
}

/** "Scheduled by Valentina" / "Scheduled by you" */
function scheduledBy(e: CalendarEvent, fam: FamilyView) {
  if (fam.isMine(e.ownerId)) return t('calendar.byYou')
  return t('calendar.byName', { name: fam.nameOf(e.ownerId) ?? t('reminder.someoneElse') })
}

/* ---------------------------------------------------------------- Month */

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
  const days = eachDayOfInterval({ start: startOfWeek(startOfMonth(cursor), week()), end: endOfWeek(endOfMonth(cursor), week()) })
  const weekdays = days.slice(0, 7).map((d) => weekdayShort(d))
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
                  aria-label={tn('calendar.dayLabel', list.length, { date: dayHeading(d) })}
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
                    aria-label={t('calendar.newOnDay')}
                    className="pointer-events-auto hidden size-6 items-center justify-center rounded-[6px] text-faint opacity-0 transition-opacity group-hover:opacity-100 hover:bg-surface-2 hover:text-fg focus-visible:opacity-100 md:flex"
                  >
                    <Plus className="size-3.5" />
                  </button>
                </div>
                {/* Desktop: titles */}
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
                      {t('today.moreCount', { count: list.length - 3 })}
                    </button>
                  )}
                </div>
                {/* Mobile: a mark per event */}
                <div className="pointer-events-none relative mt-1 grid gap-0.5 md:hidden" aria-hidden>
                  {list.slice(0, 3).map((e) => (
                    <span
                      key={e.id + e.startAt}
                      className={cn('h-1 rounded-full', e.shared ? 'border border-muted' : e.allDay ? 'bg-muted' : 'bg-faint')}
                    />
                  ))}
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Selected day */}
      <section className="mt-5 rounded-card border border-line bg-surface p-4 md:p-5" aria-label={t('calendar.selectedDay')}>
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-md font-semibold tracking-tight">{capitalize(dayHeading(selected))}</h2>
          <Button size="sm" icon={<Plus className="size-4" />} onClick={() => onNew(selected)}>
            {t('common.add')}
          </Button>
        </div>
        {selectedEvents.length === 0 ? (
          <p className="mt-3 text-base text-muted">{t('calendar.noEventsDay')}</p>
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
  const fam = useFamilyInfo()
  return (
    <button type="button" onClick={() => onOpen(e)} className="-mx-2 flex w-[calc(100%+1rem)] gap-4 rounded-control px-2 py-3 text-left transition-colors hover:bg-surface-2">
      <span className="w-[4.75rem] shrink-0 text-right font-mono whitespace-nowrap tnum">
        {e.allDay ? (
          <span className="block pt-0.5 font-sans text-xs text-muted">{t('today.allDay')}</span>
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
        {(e.location || e.shared) && (
          <span className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-sm text-muted">
            {e.location && (
              <span className="flex min-w-0 items-center gap-1">
                <MapPin className="size-3.5 shrink-0" />
                <span className="truncate">{e.location}</span>
              </span>
            )}
            {e.shared && (
              <span className="flex items-center gap-1">
                <House className="size-3.5 shrink-0" aria-hidden />
                {scheduledBy(e, fam)}
              </span>
            )}
          </span>
        )}
      </span>
    </button>
  )
}

/* ---------------------------------------------------------------- Week */

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
  const start = startOfWeek(cursor, week())
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i))
  const scroller = useRef<HTMLDivElement>(null)
  const [now, setNow] = useState(() => new Date())

  useLayoutEffect(() => {
    if (scroller.current) scroller.current.scrollTop = 6.6 * HOUR_PX
  }, [])
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 60_000)
    return () => clearInterval(timer)
  }, [])

  return (
    <>
      {/* Mobile: the week as a list of days */}
      <div className="md:hidden">
        <AgendaView from={start} days={7} events={events} loading={loading} onOpen={onOpen} showEmptyDays />
      </div>

      <div className="hidden overflow-hidden rounded-card border border-line bg-surface md:block">
        <div className="grid grid-cols-[56px_repeat(7,minmax(0,1fr))] border-b border-line">
          <div />
          {days.map((d) => (
            <div key={d.toISOString()} className="border-l border-line px-2 py-2">
              <p className="text-xs text-muted capitalize">{weekdayShort(d)}</p>
              <p className={cn('mt-0.5 inline-flex size-7 items-center justify-center rounded-full font-mono text-base tnum', isToday(d) ? 'bg-accent text-on-accent' : 'text-fg')}>
                {format(d, 'd')}
              </p>
            </div>
          ))}
        </div>
        {/* All day */}
        <div className="grid grid-cols-[56px_repeat(7,minmax(0,1fr))] border-b border-line">
          <div className="px-2 py-1.5 text-right text-[11px] leading-4 text-faint">{t('today.allDay')}</div>
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
              <button type="button" aria-label={t('calendar.newAllDay')} onClick={() => onNew(set9(d), true)} className="h-3" />
            </div>
          ))}
        </div>
        <div ref={scroller} className="relative max-h-[min(640px,calc(100dvh-280px))] overflow-y-auto">
          <div className="grid grid-cols-[56px_repeat(7,minmax(0,1fr))]" style={{ height: 24 * HOUR_PX }}>
            <div className="relative">
              {Array.from({ length: 24 }, (_, h) => (
                <span key={h} className="absolute right-2 -translate-y-1/2 font-mono text-[11px] text-faint tnum" style={{ top: h * HOUR_PX }}>
                  {h === 0 ? '' : hourLabel(h)}
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
                      aria-label={t('calendar.newAt', { day: weekdayLong(d), time: hourLabel(h) })}
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
                      className={cn(
                        'absolute overflow-hidden rounded-[8px] border px-1.5 py-1 text-left transition-colors hover:border-faint',
                        p.e.shared ? 'border-[color-mix(in_srgb,var(--text-faint)_70%,var(--border))] bg-surface' : 'border-line bg-surface-2',
                      )}
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
                      {p.height > 34 && <span className="block truncate font-mono text-[11px] text-muted tnum">{t('calendar.timeRange', { start: time(p.e.startAt), end: time(p.e.endAt) })}</span>}
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
        <EmptyState icon={<CalendarBlank />} title={t('calendar.emptyAgenda')} description={t('calendar.emptyAgendaHint')} />
      </div>
    )
  return (
    <div className="grid gap-3">
      {list.map(({ d, items }) => (
        <section key={d.toISOString()} className="rounded-card border border-line bg-surface px-4 pt-3 pb-1 md:px-5" aria-label={dayHeading(d)}>
          <h2 className="flex items-baseline gap-2 text-base font-semibold">
            {capitalize(dayHeading(d))}
            {isToday(d) && <span className="text-sm font-medium text-accent-strong">{t('time.today')}</span>}
          </h2>
          {items.length === 0 ? (
            <p className="py-2.5 text-sm text-faint">{t('calendar.free')}</p>
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

/* ---------------------------------------------------------------- Dialog */

const reminderOptions = () => [
  { v: '', label: t('calendar.noAlert') },
  { v: '5', label: t('calendar.minBefore', { count: 5 }) },
  { v: '10', label: t('calendar.minBefore', { count: 10 }) },
  { v: '15', label: t('calendar.minBefore', { count: 15 }) },
  { v: '30', label: t('calendar.minBefore', { count: 30 }) },
  { v: '60', label: t('calendar.hourBefore') },
  { v: '1440', label: t('calendar.dayBefore') },
]

function hhmm(d: Date) {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function EventDialog({ draft, onClose }: { draft: Draft | null; onClose: () => void }) {
  const qc = useQueryClient()
  const fam = useFamilyInfo()
  const editing = draft && 'id' in draft ? draft : null
  const [shared, setShared] = useState(false)
  const isOwner = fam.isMine(editing?.ownerId)
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
      setShared(draft.shared)
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
      setShared(false)
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
      const body: Record<string, unknown> = {
        title: title.trim(),
        startAt: startAt.toISOString(),
        endAt: endAt.toISOString(),
        allDay,
        location: location.trim(),
        description,
        reminderMinutes: reminder ? [Number(reminder)] : [],
      }
      // Only whoever created it can change whether it is a family event (the server answers 400 to everyone else).
      if (editing ? isOwner && shared !== editing.shared : shared) body.shared = shared
      return editing ? api.patch<CalendarEvent>(`/events/${editing.id}`, body) : api.post<CalendarEvent>('/events', body)
    },
    onSuccess: (e) => {
      invalidateEntity(qc, 'event')
      onClose()
      if (editing) toast({ title: t('calendar.saved'), description: e.title })
      else
        toast({
          title: t('calendar.created'),
          description: e.title,
          tone: 'success',
          actions: [{ label: t('common.undo'), onClick: () => undoLatest(qc, 'event', e.id, 'created').catch((er) => toastError(er, t('common.couldNotUndo'))) }],
        })
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('places.err.save')),
  })

  const remove = useMutation({
    mutationFn: (e: CalendarEvent) => api.del(`/events/${e.id}`).then(() => e),
    onSuccess: (e) => {
      invalidateEntity(qc, 'event')
      setConfirm(false)
      onClose()
      toast({
        title: t('calendar.deleted'),
        description: e.title,
        actions: [{ label: t('common.undo'), onClick: () => undoLatest(qc, 'event', e.id, 'deleted').catch((er) => toastError(er, t('common.couldNotUndo'))) }],
      })
    },
    onError: (e) => toastError(e, t('common.couldNotDelete')),
  })

  const submit = (ev: FormEvent) => {
    ev.preventDefault()
    if (!title.trim()) return setError(t('calendar.err.needTitle'))
    if (!startDate) return setError(t('calendar.err.needDate'))
    const s = allDay ? new Date(`${startDate}T00:00`) : new Date(`${startDate}T${startTime}`)
    const e = allDay ? new Date(`${endDate || startDate}T00:00`) : new Date(`${endDate || startDate}T${endTime}`)
    if (allDay ? e < s : e <= s) return setError(t('calendar.err.endBeforeStart'))
    setError(null)
    save.mutate()
  }

  return (
    <Dialog
      open={!!draft}
      onClose={onClose}
      title={editing ? t('calendar.editTitle') : t('calendar.new')}
      description={
        editing?.source === 'google' || editing?.shared ? (
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            {editing.shared && (
              <span className="inline-flex items-center gap-1.5">
                <House className="size-3.5" /> {t('today.familyEvent')}. {scheduledBy(editing, fam)}
              </span>
            )}
            {editing.source === 'google' && (
              <span className="inline-flex items-center gap-1.5">
                <GoogleLogo className="size-3.5" /> {t('calendar.googleSynced')}
              </span>
            )}
          </span>
        ) : undefined
      }
      footer={
        <>
          {editing && (
            <Button variant="ghost" className="mr-auto text-danger hover:text-danger" icon={<Trash className="size-4" />} onClick={() => setConfirm(true)}>
              {t('common.delete')}
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="event-form" loading={save.isPending}>
            {t('common.save')}
          </Button>
        </>
      }
    >
      <form id="event-form" onSubmit={submit} className="grid gap-4">
        <Field label={t('common.title')}>
          {(id) => <Input id={id} value={title} onChange={(e) => setTitle(e.target.value)} autoFocus placeholder={t('calendar.titlePlaceholder')} />}
        </Field>
        <label className="flex items-center justify-between gap-3 rounded-control border border-line px-3 py-2.5">
          <span className="text-base">{t('calendar.allDay')}</span>
          <Switch checked={allDay} onChange={setAllDay} label={t('calendar.allDay')} />
        </label>
        {(fam.hasOthers || editing?.shared) && (
          <label className="flex items-center justify-between gap-3 rounded-control border border-line px-3 py-2.5">
            <span className="min-w-0">
              <span className="flex items-center gap-1.5 text-base">
                <House className="size-4 text-faint" aria-hidden />
                {t('today.familyEvent')}
              </span>
              <span className="mt-0.5 block text-sm text-muted">
                {!isOwner
                  ? t('calendar.sharedByOther', { by: scheduledBy(editing!, fam) })
                  : shared
                    ? t('calendar.sharedHint')
                    : t('calendar.onlyYou')}
              </span>
            </span>
            <Switch checked={shared} onChange={setShared} disabled={!isOwner} label={t('today.familyEvent')} />
          </label>
        )}
        <div className="grid grid-cols-[minmax(0,1fr)_auto] gap-3">
          <Field label={t('calendar.starts')}>
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
            <Field label={t('tasks.time')}>
              {(id) => <Input id={id} type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} className="w-32 font-mono" />}
            </Field>
          )}
          <Field label={t('calendar.ends')}>
            {(id) => <Input id={id} type="date" value={endDate} min={startDate} onChange={(e) => setEndDate(e.target.value)} className="font-mono" />}
          </Field>
          {!allDay && (
            <Field label={t('tasks.time')}>
              {(id) => <Input id={id} type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} className="w-32 font-mono" />}
            </Field>
          )}
        </div>
        <Field label={t('calendar.location')} optional>
          {(id) => <Input id={id} value={location} onChange={(e) => setLocation(e.target.value)} placeholder={t('calendar.locationPlaceholder')} />}
        </Field>
        <Field label={t('calendar.alert')}>
          {(id) => (
            <Select id={id} value={reminder} onChange={(e) => setReminder(e.target.value)}>
              {reminderOptions().map((o) => (
                <option key={o.v} value={o.v}>
                  {o.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={t('calendar.description')} optional>
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
        title={t('calendar.deleteTitle')}
        description={editing?.rrule ? t('calendar.deleteRecurring') : t('notes.deleteBody')}
      />
    </Dialog>
  )
}
