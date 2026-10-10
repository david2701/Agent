import {
  ArrowLeft,
  CheckSquare,
  DotsThree,
  Microphone,
  Notepad,
  Stop,
  Trash,
  WarningCircle,
  Waveform,
} from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router'
import { VoiceOrb } from '@/components/VoiceOrb'
import { Button, ButtonLink, IconButton } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Input } from '@/components/ui/form'
import { Menu } from '@/components/ui/menu'
import { Panel, Tag } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { capitalize, compactWhen, duration, longDate, time } from '@/lib/format'
import { invalidateEntity, keys, useRecording, useRecordings } from '@/lib/queries'
import {
  clearSessionError,
  discardRecording,
  finishRecording,
  sessionElapsed,
  sessionLevel,
  startRecording,
  useRecordingSession,
} from '@/lib/recordingSession'
import type { Recording } from '@/lib/types'

/** Solo en desarrollo: permite acortar los segmentos para probar la subida por partes. */
function devSegmentMs(): number | undefined {
  if (!import.meta.env.DEV) return undefined
  const v = (window as unknown as { __segMs?: number }).__segMs
  return typeof v === 'number' ? v : undefined
}

const STATUS: Record<Recording['status'], { label: string; tone: 'neutral' | 'accent' | 'success' | 'danger' }> = {
  recording: { label: 'Grabando', tone: 'accent' },
  processing: { label: 'Procesando', tone: 'neutral' },
  done: { label: 'Lista', tone: 'success' },
  failed: { label: 'Falló', tone: 'danger' },
}

export default function Grabaciones() {
  const { id } = useParams<{ id?: string }>()
  const recordings = useRecordings()
  const session = useRecordingSession()
  const busy = session.status === 'recording' || session.status === 'starting' || session.status === 'stopping'

  useEffect(() => {
    if (session.status === 'error' && session.error) {
      toast({ title: session.error, tone: 'danger' })
      clearSessionError()
    }
  }, [session.status, session.error])

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <div className="grid items-start gap-5 md:grid-cols-[300px_minmax(0,1fr)] lg:grid-cols-[340px_minmax(0,1fr)] lg:gap-8">
        <aside className={cn('min-w-0', id && 'max-md:hidden')} aria-label="Grabaciones">
          <div className="flex items-center justify-between gap-3">
            <h1 className="text-lg font-semibold tracking-tight md:text-xl">Grabaciones</h1>
            <Button
              size="sm"
              variant="primary"
              icon={<Microphone className="size-4" />}
              disabled={busy}
              onClick={() => void startRecording(devSegmentMs())}
            >
              Grabar
            </Button>
          </div>
          <p className="mt-1 text-sm text-muted">Reuniones, clases o ideas largas. Kairo transcribe, resume y saca las tareas.</p>

          {busy && <RecorderPanel />}

          <div className="mt-5">
            {recordings.isLoading ? (
              <div className="grid gap-2" aria-busy="true">
                {Array.from({ length: 4 }, (_, i) => (
                  <Skeleton key={i} className="h-16 rounded-control" />
                ))}
              </div>
            ) : recordings.isError ? (
              <ErrorState message={(recordings.error as Error).message} onRetry={() => void recordings.refetch()} />
            ) : (recordings.data ?? []).length === 0 ? (
              <EmptyState compact icon={<Waveform />} title="Sin grabaciones" description="Toca Grabar para empezar. Puedes grabar horas: se sube en partes de 5 minutos." />
            ) : (
              <ul className="grid gap-1">
                {recordings.data!.map((r) => (
                  <li key={r.id}>
                    <NavLink
                      to={`/grabaciones/${r.id}`}
                      className={({ isActive }) =>
                        cn('block rounded-control px-3 py-2.5 transition-colors', isActive ? 'bg-surface-2' : 'hover:bg-surface-2/60')
                      }
                    >
                      <span className="flex items-center gap-2">
                        <span className="min-w-0 flex-1 truncate text-base font-medium text-fg">{r.title || 'Grabación sin título'}</span>
                        <Tag tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Tag>
                      </span>
                      <span className="mt-0.5 flex gap-3 font-mono text-xs text-faint">
                        <span>{compactWhen(r.createdAt)}</span>
                        {r.durationS > 0 && <span>{duration(r.durationS)}</span>}
                      </span>
                    </NavLink>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </aside>

        <section className={cn('min-w-0', !id && 'max-md:hidden')} aria-label="Detalle de la grabación">
          {id ? (
            <RecordingDetail key={id} id={id} />
          ) : (
            <Panel className="flex min-h-80 items-center justify-center">
              <EmptyState icon={<Waveform />} title="Elige una grabación" description="Verás el resumen, los puntos clave, las acciones y la transcripción." />
            </Panel>
          )}
        </section>
      </div>
    </div>
  )
}

function RecorderPanel() {
  const session = useRecordingSession()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [title, setTitle] = useState('')
  const [elapsed, setElapsed] = useState(0)
  const [confirm, setConfirm] = useState(false)

  useEffect(() => {
    const t = setInterval(() => setElapsed(sessionElapsed()), 500)
    return () => clearInterval(t)
  }, [])

  const stop = async () => {
    const id = await finishRecording(title)
    void qc.invalidateQueries({ queryKey: keys.recordings })
    if (id) {
      toast({ title: 'Grabación enviada', description: 'Kairo la está transcribiendo. Te avisa cuando termine.' })
      navigate(`/grabaciones/${id}`)
    }
  }

  return (
    <div className="mt-4 rounded-card border border-[color-mix(in_srgb,var(--accent)_40%,var(--border))] bg-surface p-4" role="status" aria-live="polite">
      <div className="flex items-center gap-4">
        <VoiceOrb state={session.status === 'stopping' ? 'thinking' : 'listening'} getLevel={sessionLevel} className="size-14 shrink-0" />
        <div className="min-w-0">
          <p className="text-base font-medium text-accent-strong">{session.status === 'stopping' ? 'Subiendo el final…' : session.status === 'starting' ? 'Preparando…' : 'Grabando'}</p>
          <p className="font-mono text-xl leading-tight font-medium text-fg tnum">{duration(elapsed)}</p>
          <p className="text-xs text-faint">
            {session.uploaded} {session.uploaded === 1 ? 'parte subida' : 'partes subidas'}
            {session.failed > 0 && <span className="text-danger">, {session.failed} con error</span>}
          </p>
        </div>
      </div>
      <Input className="mt-4" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Título (opcional)" aria-label="Título de la grabación" />
      <div className="mt-3 flex gap-2">
        <Button variant="primary" className="flex-1" icon={<Stop className="size-4" weight="fill" />} onClick={() => void stop()} loading={session.status === 'stopping'} disabled={session.status !== 'recording'}>
          Detener y procesar
        </Button>
        <Button variant="ghost" onClick={() => setConfirm(true)} disabled={session.status === 'stopping'}>
          Descartar
        </Button>
      </div>
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => {
          setConfirm(false)
          void discardRecording().then(() => qc.invalidateQueries({ queryKey: keys.recordings }))
        }}
        title="¿Descartar la grabación?"
        description="Se borra lo grabado hasta ahora."
        confirmLabel="Descartar"
      />
    </div>
  )
}

function RecordingDetail({ id }: { id: string }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const rec = useRecording(id)
  const [confirm, setConfirm] = useState(false)
  const [showTranscript, setShowTranscript] = useState(false)

  const createTasks = useMutation({
    mutationFn: () => api.post<{ created: number }>(`/recordings/${id}/tasks`),
    onSuccess: (r) => {
      invalidateEntity(qc, 'task')
      toast({
        title: r.created === 0 ? 'No había tareas nuevas' : r.created === 1 ? 'Se creó 1 tarea' : `Se crearon ${r.created} tareas`,
        tone: r.created ? 'success' : 'default',
        actions: r.created ? [{ label: 'Ver tareas', onClick: () => navigate('/tareas') }] : undefined,
      })
    },
    onError: (e) => toastError(e, 'No se pudieron crear las tareas'),
  })
  const remove = useMutation({
    mutationFn: () => api.del(`/recordings/${id}`),
    onSuccess: () => {
      setConfirm(false)
      void qc.invalidateQueries({ queryKey: keys.recordings })
      navigate('/grabaciones', { replace: true })
      toast({ title: 'Grabación eliminada' })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar'),
  })

  if (rec.isLoading)
    return (
      <Panel className="grid gap-4 p-6" aria-busy="true">
        <Skeleton className="h-7 w-1/2" />
        <Skeleton className="h-3 w-40" />
        <div className="mt-2 grid gap-2">
          <Skeleton className="h-3.5 w-full" />
          <Skeleton className="h-3.5 w-11/12" />
          <Skeleton className="h-3.5 w-3/4" />
        </div>
      </Panel>
    )
  if (rec.isError || !rec.data) return <ErrorState message={(rec.error as Error)?.message ?? 'No encontré la grabación.'} onRetry={() => void rec.refetch()} />

  const r = rec.data
  const st = STATUS[r.status]
  return (
    <Panel className="p-5 md:p-7">
      <div className="flex items-start gap-2">
        <IconButton label="Volver a grabaciones" className="-ml-2 md:hidden" onClick={() => navigate('/grabaciones')}>
          <ArrowLeft className="size-5" />
        </IconButton>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-lg font-semibold tracking-tight md:text-xl">{r.title || 'Grabación sin título'}</h2>
            <Tag tone={st.tone}>{st.label}</Tag>
          </div>
          <p className="mt-1 text-sm text-muted">
            {capitalize(longDate(r.createdAt))}, <span className="font-mono">{time(r.createdAt)}</span>
            {r.durationS > 0 && (
              <>
                {' '}
                · <span className="font-mono">{duration(r.durationS)}</span>
              </>
            )}
          </p>
        </div>
        <Menu
          items={[{ label: 'Eliminar grabación', icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => setConfirm(true) }]}
          trigger={(p) => (
            <IconButton label="Opciones" {...p}>
              <DotsThree className="size-5" weight="bold" />
            </IconButton>
          )}
        />
      </div>

      {r.status === 'processing' || r.status === 'recording' ? (
        <div className="mt-6 grid gap-2.5" aria-busy="true">
          <p className="mb-2 text-base text-muted">
            {r.status === 'recording' ? 'Esta grabación sigue en curso.' : 'Kairo está transcribiendo y resumiendo. Esta página se actualiza sola.'}
          </p>
          <Skeleton className="h-3.5 w-full" />
          <Skeleton className="h-3.5 w-11/12" />
          <Skeleton className="h-3.5 w-4/5" />
          <Skeleton className="mt-4 h-3.5 w-1/3" />
          <Skeleton className="h-3.5 w-3/5" />
        </div>
      ) : r.status === 'failed' ? (
        <div className="mt-6 flex items-start gap-3 rounded-control border border-line bg-bg p-4 text-base">
          <WarningCircle className="mt-0.5 size-5 shrink-0 text-danger" />
          <div>
            <p className="font-medium text-fg">No se pudo procesar</p>
            <p className="mt-0.5 text-muted">{r.error || 'El audio no se pudo transcribir.'}</p>
          </div>
        </div>
      ) : (
        <div className="mt-6 grid gap-7">
          {r.summary && (
            <section aria-labelledby="rec-sum">
              <h3 id="rec-sum" className="text-base font-semibold">
                Resumen
              </h3>
              <p className="mt-2 max-w-[70ch] text-md leading-relaxed text-fg">{r.summary}</p>
            </section>
          )}
          {r.keyPoints.length > 0 && (
            <section aria-labelledby="rec-kp">
              <h3 id="rec-kp" className="text-base font-semibold">
                Puntos clave
              </h3>
              <ul className="mt-2 grid max-w-[70ch] list-disc gap-1.5 pl-5 text-base text-fg marker:text-faint">
                {r.keyPoints.map((k, i) => (
                  <li key={i}>{k}</li>
                ))}
              </ul>
            </section>
          )}
          {r.actionItems.length > 0 && (
            <section aria-labelledby="rec-ai" className="rounded-control border border-line bg-bg p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <h3 id="rec-ai" className="text-base font-semibold">
                  Acciones <span className="font-mono text-sm font-normal text-faint">{r.actionItems.length}</span>
                </h3>
                <Button size="sm" variant="primary" icon={<CheckSquare className="size-4" />} loading={createTasks.isPending} onClick={() => createTasks.mutate()}>
                  Crear tareas
                </Button>
              </div>
              <ul className="mt-3 grid gap-2">
                {r.actionItems.map((a, i) => (
                  <li key={i} className="flex items-start gap-2.5 text-base text-fg">
                    <CheckSquare className="mt-0.5 size-4 shrink-0 text-faint" />
                    {a}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {r.transcript && (
            <section aria-labelledby="rec-tr">
              <div className="flex items-center justify-between gap-3">
                <h3 id="rec-tr" className="text-base font-semibold">
                  Transcripción
                </h3>
                <Button size="sm" variant="ghost" onClick={() => setShowTranscript((s) => !s)} aria-expanded={showTranscript}>
                  {showTranscript ? 'Ocultar' : 'Mostrar'}
                </Button>
              </div>
              {showTranscript ? (
                <p className="mt-2 max-w-[70ch] text-base leading-relaxed whitespace-pre-line text-muted">{r.transcript}</p>
              ) : (
                <p className="mt-1 line-clamp-2 max-w-[70ch] text-base text-faint">{r.transcript}</p>
              )}
            </section>
          )}
          {r.noteId && (
            <div>
              <ButtonLink to={`/notas/${r.noteId}`} icon={<Notepad className="size-4" />}>
                Abrir la nota
              </ButtonLink>
            </div>
          )}
        </div>
      )}
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title="¿Eliminar esta grabación?"
        description={r.noteId ? 'La nota con el resumen se conserva.' : 'Se borran la transcripción y el resumen.'}
      />
    </Panel>
  )
}
