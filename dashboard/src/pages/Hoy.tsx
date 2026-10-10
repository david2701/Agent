import {
  ArrowRight,
  CalendarBlank,
  CalendarPlus,
  CheckSquare,
  Cloud,
  CloudFog,
  CloudLightning,
  CloudRain,
  CloudSun,
  GoogleLogo,
  ListChecks,
  MapPin,
  Notepad,
  Pause,
  Play,
  Sparkle,
  Sun,
  UserPlus,
  type Icon,
} from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isWithinInterval } from 'date-fns'
import { motion, useReducedMotion } from 'motion/react'
import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Composer } from '@/components/chat/Composer'
import { Avatar, FamilyIcon } from '@/components/family'
import { NoticesFeed } from '@/components/NoticesFeed'
import { ReminderRow, TaskRow } from '@/components/rows'
import { Button, ButtonLink } from '@/components/ui/button'
import { EmptyState, ErrorState, Skeleton, SkeletonRows } from '@/components/ui/feedback'
import { Checkbox } from '@/components/ui/form'
import { Panel, SectionHeader } from '@/components/ui/misc'
import { toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { speak, stopSpeaking, unlockAudio } from '@/lib/audio'
import { useAuth } from '@/lib/auth'
import { cn } from '@/lib/cn'
import { useFamilyInfo } from '@/lib/family'
import { capitalize, compactWhen, plainText, shortDate, time, toDate } from '@/lib/format'
import { useReminderActions, useTaskActions, useToggleItem } from '@/lib/mutations'
import { keys, useBriefing, useLists, useNotices, useSettings, useToday } from '@/lib/queries'
import type { CalendarEvent, Task, Today, Weather } from '@/lib/types'

const SUGGESTIONS = [
  '¿Qué tengo pendiente hoy?',
  'Recuérdame llamar a mi mamá a las 6 p. m.',
  'Agrega aguacates a la lista del supermercado',
]

export default function Hoy() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const today = useToday()
  const reduce = useReducedMotion()
  const data = today.data

  const ask = (text: string) => navigate('/chat', { state: { ask: text } })

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <motion.header
        initial={reduce ? false : { opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
      >
        {data ? (
          <>
            <p className="text-base text-muted">{data.dateLabel}</p>
            <h1 className="mt-1 text-xl font-semibold tracking-tight md:text-2xl">
              {data.greeting}
              {user?.displayName ? `, ${user.displayName}` : ''}
            </h1>
          </>
        ) : (
          <div className="grid gap-2" aria-hidden>
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-9 w-72 md:h-11" />
          </div>
        )}
      </motion.header>

      <section aria-label="Pregúntale a Kairo" className="mt-6 max-w-3xl">
        <Composer
          size="lg"
          placeholder="Pregúntale a Kairo"
          label="Pregúntale a Kairo"
          onSend={ask}
          onMic={() => {
            unlockAudio()
            navigate('/chat', { state: { voice: true } })
          }}
        />
        <div className="mt-3 flex gap-2 overflow-x-auto scrollbar-none max-sm:-mx-4 max-sm:px-4 sm:flex-wrap sm:overflow-visible">
          {SUGGESTIONS.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => ask(s)}
              className="h-8 shrink-0 rounded-full border border-line bg-surface px-3 text-sm text-muted transition-[color,border-color,transform] hover:border-[color-mix(in_srgb,var(--text-faint)_50%,var(--border))] hover:text-fg active:scale-[0.97]"
            >
              {s}
            </button>
          ))}
        </div>
      </section>

      {today.isError ? (
        <Panel className="mt-8">
          <ErrorState message={(today.error as Error).message} onRetry={() => void today.refetch()} />
        </Panel>
      ) : (
        <div className="mt-8 grid gap-5 lg:grid-cols-[minmax(0,1.55fr)_minmax(0,1fr)] lg:gap-6">
          <div className="grid content-start gap-5 lg:gap-6">
            <AgendaPanel data={data} />
            <TasksPanel data={data} />
          </div>
          <div className="grid content-start gap-5 lg:gap-6">
            <BriefingPanel />
            <FamilyPanel />
            {data?.weather && <WeatherPanel weather={data.weather} />}
            <RemindersPanel data={data} />
            <ListsPanel data={data} />
            <NotesPanel data={data} />
          </div>
        </div>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- Agenda */

function AgendaPanel({ data }: { data?: Today }) {
  const now = new Date()
  const events = data?.events ?? []
  const allDay = events.filter((e) => e.allDay)
  const timed = events.filter((e) => !e.allDay).sort((a, b) => a.startAt.localeCompare(b.startAt))
  const nowIndex = timed.findIndex((e) => toDate(e.endAt) > now)

  return (
    <Panel className="p-5" aria-labelledby="agenda-title">
      <SectionHeader
        id="agenda-title"
        title="Agenda de hoy"
        count={data ? events.length : undefined}
        action={
          <Link to="/calendario" className="flex items-center gap-1 text-sm text-muted transition-colors hover:text-fg">
            Calendario <ArrowRight className="size-3.5" />
          </Link>
        }
      />
      {!data ? (
        <SkeletonRows rows={3} className="mt-5" withIcon={false} />
      ) : events.length === 0 ? (
        <EmptyState
          compact
          icon={<CalendarBlank />}
          title="Nada en el calendario hoy"
          description="Tienes el día libre de reuniones."
          action={
            <ButtonLink to="/calendario?nuevo=1" size="sm" icon={<CalendarPlus className="size-4" />}>
              Agregar evento
            </ButtonLink>
          }
        />
      ) : (
        <div className="mt-4">
          {allDay.length > 0 && (
            <div className="mb-3 flex flex-wrap gap-2">
              {allDay.map((e) => (
                <Link
                  key={e.id + e.startAt}
                  to={`/calendario?evento=${e.id}`}
                  className="inline-flex h-7 items-center gap-2 rounded-full bg-surface-2 px-3 text-sm text-fg"
                >
                  <span className="text-faint">Todo el día</span>
                  {e.title}
                </Link>
              ))}
            </div>
          )}
          <ol className="relative grid">
            {timed.map((e, i) => (
              <li key={e.id + e.startAt}>
                {i === nowIndex && toDate(e.startAt) > now && <NowLine />}
                <AgendaRow e={e} now={now} />
              </li>
            ))}
            {nowIndex === -1 && timed.length > 0 && <NowLine />}
          </ol>
        </div>
      )}
    </Panel>
  )
}

function NowLine() {
  return (
    <div className="flex items-center gap-3 py-1.5" aria-label="Ahora">
      <span className="w-14 shrink-0 text-right font-mono text-xs text-accent-strong">{time(new Date())}</span>
      <span className="h-px flex-1 bg-accent" />
    </div>
  )
}

function AgendaRow({ e, now }: { e: CalendarEvent; now: Date }) {
  const start = toDate(e.startAt)
  const end = toDate(e.endAt)
  const past = end < now
  const ongoing = isWithinInterval(now, { start, end })
  return (
    <Link
      to={`/calendario?evento=${e.id}`}
      className={cn(
        'group -mx-2 flex gap-3 rounded-control px-2 py-2.5 transition-colors hover:bg-surface-2',
        past && 'opacity-55',
      )}
    >
      <div className="w-14 shrink-0 pt-px text-right font-mono tnum">
        <p className="text-base text-fg">{time(start)}</p>
        <p className="text-xs text-faint">{time(end)}</p>
      </div>
      <span className={cn('w-0.5 shrink-0 rounded-full', ongoing ? 'bg-accent' : 'bg-line')} aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-2 text-base font-medium text-fg">
          <span className="truncate">{e.title}</span>
          {ongoing && <span className="shrink-0 text-xs font-medium text-accent-strong">Ahora</span>}
          {e.shared && <FamilyIcon label="Evento familiar" />}
          {e.source === 'google' && <GoogleLogo className="size-3.5 shrink-0 text-faint" aria-label="De Google Calendar" />}
        </p>
        {e.location && (
          <p className="mt-0.5 flex items-center gap-1 truncate text-sm text-muted">
            <MapPin className="size-3.5 shrink-0" />
            <span className="truncate">{e.location}</span>
          </p>
        )}
      </div>
    </Link>
  )
}

/* ---------------------------------------------------------------- Tareas */

function TasksPanel({ data }: { data?: Today }) {
  const { complete } = useTaskActions()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [hidden, setHidden] = useState<Set<string>>(new Set())

  const groups = useMemo(() => {
    if (!data) return []
    const seen = new Set<string>()
    const take = (list: Task[]) =>
      list.filter((t) => {
        if (seen.has(t.id) || hidden.has(t.id)) return false
        seen.add(t.id)
        return true
      })
    return [
      { id: 'overdue', title: 'Vencidas', tasks: take(data.tasksOverdue), danger: true },
      { id: 'today', title: 'Para hoy', tasks: take(data.tasksDueToday) },
      { id: 'doing', title: 'En curso', tasks: take(data.tasksInProgress) },
    ].filter((g) => g.tasks.length > 0)
  }, [data, hidden])

  const total = groups.reduce((n, g) => n + g.tasks.length, 0)

  return (
    <Panel className="p-5" aria-labelledby="tasks-title">
      <SectionHeader
        id="tasks-title"
        title="Tareas"
        count={data ? total : undefined}
        action={
          <Link to="/tareas" className="flex items-center gap-1 text-sm text-muted transition-colors hover:text-fg">
            Todas <ArrowRight className="size-3.5" />
          </Link>
        }
      />
      {!data ? (
        <SkeletonRows rows={4} className="mt-5" />
      ) : total === 0 ? (
        <EmptyState
          compact
          icon={<CheckSquare />}
          title="Nada urgente por hoy"
          description="No tienes tareas vencidas, para hoy ni en curso."
          action={
            <ButtonLink to="/tareas?nueva=1" size="sm">
              Nueva tarea
            </ButtonLink>
          }
        />
      ) : (
        <div className="mt-3 grid gap-4">
          {groups.map((g) => (
            <div key={g.id}>
              <p className={cn('flex items-center gap-2 text-sm font-medium', g.danger ? 'text-danger' : 'text-muted')}>
                {g.title}
                <span className="font-mono font-normal tnum">{g.tasks.length}</span>
              </p>
              <div className="mt-1 divide-y divide-line">
                {g.tasks.map((t) => (
                  <TaskRow
                    key={t.id}
                    task={t}
                    onOpen={() => navigate(`/tareas?tarea=${t.id}`)}
                    onToggle={(done) => {
                      if (done) setHidden((h) => new Set(h).add(t.id))
                      complete(t, done)
                      void qc.invalidateQueries({ queryKey: keys.today })
                    }}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Resumen */

function BriefingPanel() {
  const qc = useQueryClient()
  const briefing = useBriefing()
  const settings = useSettings()
  const [playing, setPlaying] = useState(false)
  const gen = useMutation({
    mutationFn: () => api.post<{ text: string }>('/briefing'),
    onSuccess: (r) => qc.setQueryData(keys.briefing, { text: r.text, date: new Date().toISOString().slice(0, 10) }),
    onError: (e) => toastError(e, 'No se pudo preparar el resumen'),
  })
  const text = briefing.data?.text

  const play = async () => {
    if (!text) return
    if (playing) {
      stopSpeaking()
      setPlaying(false)
      return
    }
    unlockAudio()
    setPlaying(true)
    try {
      const s = await speak(text, { voice: settings.data?.settings.voice, speed: settings.data?.settings.voiceSpeed })
      await s.done
    } catch (e) {
      toastError(e, 'No se pudo reproducir')
    } finally {
      setPlaying(false)
    }
  }

  return (
    <Panel className="p-5" aria-labelledby="brief-title">
      <SectionHeader id="brief-title" title="Resumen del día" />
      {gen.isPending ? (
        <div className="mt-4 grid gap-2" aria-busy="true" aria-label="Preparando resumen">
          <Skeleton className="h-3.5 w-full" />
          <Skeleton className="h-3.5 w-11/12" />
          <Skeleton className="h-3.5 w-3/5" />
          <p className="mt-2 text-sm text-muted">Kairo está repasando tu día…</p>
        </div>
      ) : text ? (
        <>
          <p className="mt-3 text-base leading-relaxed whitespace-pre-line text-fg">{text}</p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button
              size="sm"
              variant={playing ? 'quiet' : 'secondary'}
              onClick={() => void play()}
              icon={playing ? <Pause className="size-4" weight="fill" /> : <Play className="size-4" weight="fill" />}
            >
              {playing ? 'Detener' : 'Escuchar'}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => gen.mutate()}>
              Actualizar
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="mt-2 text-base text-muted">Kairo te cuenta en un minuto lo importante de hoy: agenda, pendientes y clima.</p>
          <Button className="mt-4" variant="primary" onClick={() => gen.mutate()} icon={<Sparkle className="size-4" />}>
            Resumen del día
          </Button>
        </>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Familia */

/**
 * Lo último que pasó en la familia (avisos sin leer). Con una sola persona en el hogar se oculta y,
 * si quien mira administra la familia, queda una línea para invitar.
 */
function FamilyPanel() {
  const fam = useFamilyInfo()
  const notices = useNotices()

  if (fam.query.isLoading)
    return (
      <Panel className="p-5" aria-busy="true" aria-label="Cargando familia">
        <Skeleton className="h-5 w-24" />
        <SkeletonRows rows={2} className="mt-5" />
      </Panel>
    )
  if (fam.query.isError) return null
  if (!fam.hasOthers) {
    if (!fam.isAdmin) return null
    return (
      <Link
        to="/familia?invitar=1"
        className="group flex items-center gap-3 rounded-card border border-dashed border-line px-5 py-3.5 text-base text-muted transition-colors hover:border-[color-mix(in_srgb,var(--text-faint)_50%,var(--border))] hover:text-fg"
      >
        <UserPlus className="size-[18px] shrink-0 text-faint group-hover:text-muted" />
        <span className="min-w-0 flex-1">Invita a tu familia para compartir listas, eventos y tareas.</span>
        <ArrowRight className="size-3.5 shrink-0" />
      </Link>
    )
  }

  const unread = (notices.data?.notices ?? []).filter((n) => !n.readAt)
  const count = notices.data?.unread ?? unread.length
  return (
    <Panel className="p-5" aria-labelledby="family-title">
      <SectionHeader
        id="family-title"
        title="Familia"
        count={notices.data ? count : undefined}
        action={
          <span className="flex items-center gap-3">
            <span className="flex -space-x-1.5" aria-hidden>
              {fam.members.slice(0, 4).map((m) => (
                <Avatar key={m.id} name={m.displayName} size="sm" className="ring-2 ring-surface" />
              ))}
            </span>
            <Link to="/familia" className="flex items-center gap-1 text-sm text-muted transition-colors hover:text-fg">
              Abrir <ArrowRight className="size-3.5" />
            </Link>
          </span>
        }
      />
      {notices.isLoading ? (
        <SkeletonRows rows={2} className="mt-5" />
      ) : notices.isError ? (
        <p className="mt-3 text-base text-muted">No pude cargar las novedades de la familia.</p>
      ) : unread.length === 0 ? (
        <p className="mt-2 text-base text-muted">Estás al día. Nada nuevo de la familia.</p>
      ) : (
        <>
          <NoticesFeed notices={unread} loading={false} limit={3} className="mt-2" />
          {count > 3 && (
            <Link to="/familia#novedades" className="mt-1 inline-block text-sm text-muted hover:text-fg">
              Ver {count - 3} más
            </Link>
          )}
        </>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Clima */

function weatherIcon(desc: string): Icon {
  const d = desc.toLowerCase()
  if (d.includes('torment')) return CloudLightning
  if (d.includes('lluv') || d.includes('llovizn') || d.includes('chubasc')) return CloudRain
  if (d.includes('niebla') || d.includes('bruma')) return CloudFog
  if (d.includes('parcial') || d.includes('algo nublado') || d.includes('intervalos')) return CloudSun
  if (d.includes('nublad') || d.includes('cubierto')) return Cloud
  return Sun
}

function WeatherPanel({ weather }: { weather: Weather }) {
  const Icon = weatherIcon(weather.description)
  const days = weather.days.slice(1, 4)
  return (
    <Panel className="p-5" aria-label={`Clima en ${weather.place}`}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm text-muted">{weather.place}</p>
          <p className="mt-1 font-mono text-2xl leading-none font-medium tracking-tight tnum">{Math.round(weather.temperature)}°</p>
          <p className="mt-2 text-base text-fg">{capitalize(weather.description)}</p>
          {weather.feelsLike !== null && (
            <p className="text-sm text-muted">
              Sensación de <span className="font-mono tnum">{Math.round(weather.feelsLike)}°</span>
            </p>
          )}
        </div>
        <Icon className="size-10 text-muted" aria-hidden />
      </div>
      {days.length > 0 && (
        <div className="mt-4 grid gap-2 border-t border-line pt-3">
          {days.map((d) => {
            const DIcon = weatherIcon(d.description)
            return (
              <div key={d.date} className="grid grid-cols-[4.5rem_1.25rem_minmax(0,1fr)_auto] items-center gap-2 text-sm">
                <span className="text-muted">{capitalize(shortDate(d.date).split(' ')[0])}</span>
                <DIcon className="size-4 text-faint" aria-hidden />
                <span className="truncate text-muted">
                  {d.rainChance !== null && d.rainChance >= 30 ? (
                    <>
                      Lluvia <span className="font-mono tnum">{d.rainChance}%</span>
                    </>
                  ) : (
                    capitalize(d.description)
                  )}
                </span>
                <span className="font-mono text-fg tnum">
                  {Math.round(d.min)}° <span className="text-faint">/</span> {Math.round(d.max)}°
                </span>
              </div>
            )
          })}
        </div>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Recordatorios */

function RemindersPanel({ data }: { data?: Today }) {
  const { done, snooze } = useReminderActions()
  const fired = data?.firedReminders ?? []
  const upcoming = data?.reminders ?? []
  const total = fired.length + upcoming.length
  return (
    <Panel className="p-5" aria-labelledby="rem-title">
      <SectionHeader
        id="rem-title"
        title="Recordatorios"
        count={data ? total : undefined}
        action={
          <Link to="/recordatorios" className="flex items-center gap-1 text-sm text-muted transition-colors hover:text-fg">
            Todos <ArrowRight className="size-3.5" />
          </Link>
        }
      />
      {!data ? (
        <SkeletonRows rows={2} className="mt-5" />
      ) : total === 0 ? (
        <p className="mt-3 text-base text-muted">No hay recordatorios para lo que queda del día.</p>
      ) : (
        <div className="mt-1 divide-y divide-line">
          {fired.map((r) => (
            <ReminderRow
              key={r.id}
              r={r}
              actions={
                <>
                  <Button size="sm" variant="quiet" onClick={() => done.mutate(r)}>
                    Hecho
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => snooze.mutate({ r, minutes: 10 })}>
                    Posponer 10 min
                  </Button>
                </>
              }
            />
          ))}
          {upcoming.map((r) => (
            <ReminderRow key={r.id} r={r} />
          ))}
        </div>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Listas */

function ListsPanel({ data }: { data?: Today }) {
  const toggle = useToggleItem()
  const lists = useLists()
  // Usa la caché de listas (optimista) cuando existe; si no, lo que trae Hoy.
  const source = data?.lists.map((l) => lists.data?.find((x) => x.id === l.id) ?? l) ?? []
  const visible = source.filter((l) => l.items.some((i) => !i.checked))
  if (data && visible.length === 0) return null
  return (
    <Panel className="p-5" aria-labelledby="lists-title">
      <SectionHeader
        id="lists-title"
        title="Listas"
        action={
          <Link to="/listas" className="flex items-center gap-1 text-sm text-muted transition-colors hover:text-fg">
            Abrir <ArrowRight className="size-3.5" />
          </Link>
        }
      />
      {!data ? (
        <SkeletonRows rows={3} className="mt-5" />
      ) : (
        <div className="mt-3 grid gap-4">
          {visible.map((l) => {
            const pending = l.items.filter((i) => !i.checked)
            return (
              <div key={l.id}>
                <Link to={`/listas/${l.id}`} className="flex items-center gap-2 text-sm font-medium text-muted hover:text-fg">
                  <ListChecks className="size-4" />
                  {l.name}
                  <span className="font-mono font-normal text-faint tnum">{pending.length}</span>
                </Link>
                <ul className="mt-1.5 grid gap-0.5">
                  {pending.slice(0, 5).map((i) => (
                    <li key={i.id} className="flex items-center gap-3 py-1.5">
                      <Checkbox
                        checked={i.checked}
                        label={`Marcar ${i.text}`}
                        onChange={(checked) => toggle.mutate({ listId: l.id, itemId: i.id, checked })}
                      />
                      <span className="text-base text-fg">{i.text}</span>
                    </li>
                  ))}
                </ul>
                {pending.length > 5 && (
                  <Link to={`/listas/${l.id}`} className="mt-1 inline-block text-sm text-muted hover:text-fg">
                    {pending.length - 5} más
                  </Link>
                )}
              </div>
            )
          })}
        </div>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Notas */

function NotesPanel({ data }: { data?: Today }) {
  const notes = data?.recentNotes ?? []
  return (
    <Panel className="p-5" aria-labelledby="notes-title">
      <SectionHeader
        id="notes-title"
        title="Notas recientes"
        action={
          <Link to="/notas" className="flex items-center gap-1 text-sm text-muted transition-colors hover:text-fg">
            Todas <ArrowRight className="size-3.5" />
          </Link>
        }
      />
      {!data ? (
        <SkeletonRows rows={3} className="mt-5" withIcon={false} />
      ) : notes.length === 0 ? (
        <EmptyState compact icon={<Notepad />} title="Aún no hay notas" description="Dile a Kairo “anota que…” o crea una desde Notas." />
      ) : (
        <ul className="mt-2 grid">
          {notes.slice(0, 5).map((n) => (
            <li key={n.id}>
              <Link to={`/notas/${n.id}`} className="-mx-2 flex items-baseline gap-3 rounded-control px-2 py-2 transition-colors hover:bg-surface-2">
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-base text-fg">{n.title || plainText(n.content).slice(0, 60) || 'Sin título'}</span>
                  <span className="block truncate text-sm text-muted">{plainText(n.content).slice(0, 90)}</span>
                </span>
                <span className="shrink-0 font-mono text-xs text-faint">{compactWhen(n.updatedAt)}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}
