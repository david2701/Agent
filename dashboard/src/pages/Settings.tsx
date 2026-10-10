import {
  ArrowsClockwise,
  Browser,
  DeviceMobile,
  DownloadSimple,
  GoogleLogo,
  MagnifyingGlass,
  MapPin,
  Play,
  Plug,
  Plus,
  SignOut,
  Trash,
  Watch,
} from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import { PlacesManager } from '@/components/PlacesManager'
import { Button, IconButton } from '@/components/ui/button'
import { CopyField } from '@/components/ui/copy'
import { ConfirmDialog } from '@/components/ui/dialog'
import { Skeleton, SkeletonRows } from '@/components/ui/feedback'
import { Field, FormError, Input, Select, Switch, Textarea } from '@/components/ui/form'
import { PageHeader, Panel, Tag } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api, qs } from '@/lib/api'
import { speak, unlockAudio } from '@/lib/audio'
import { useAuth } from '@/lib/auth'
import { cn } from '@/lib/cn'
import { relative, shortDate } from '@/lib/format'
import {
  keys,
  useDevices,
  useGoogle,
  useGoogleCalendars,
  useMcpServers,
  useModels,
  usePushConfig,
  useSettings,
  useVoices,
} from '@/lib/queries'
import { notificationsSupported, requestNotifications } from '@/lib/realtime'
import type { GeoPlace, McpServer, SettingsResponse, User, UserSettings } from '@/lib/types'

const SECTIONS = [
  { id: 'perfil', label: 'Perfil' },
  { id: 'asistente', label: 'Asistente' },
  { id: 'voz', label: 'Voz' },
  { id: 'resumen', label: 'Resumen diario' },
  { id: 'ubicacion', label: 'Ubicación' },
  { id: 'lugares', label: 'Lugares' },
  { id: 'notificaciones', label: 'Notificaciones' },
  { id: 'google', label: 'Google Calendar' },
  { id: 'mcp', label: 'Conexiones MCP' },
  { id: 'dispositivos', label: 'Dispositivos' },
  { id: 'seguridad', label: 'Seguridad' },
  { id: 'datos', label: 'Datos' },
]

function usePatchSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (patch: Partial<UserSettings>) => api.patch<SettingsResponse>('/settings', patch),
    onSuccess: (r) => {
      qc.setQueryData(keys.settings, r)
      void qc.invalidateQueries({ queryKey: keys.today })
      toast({ id: 'settings-saved', title: 'Ajuste guardado', duration: 2000 })
    },
    onError: (e) => toastError(e, 'No se pudo guardar el ajuste'),
  })
}

export default function Ajustes() {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const settings = useSettings()

  useEffect(() => {
    const g = params.get('google')
    if (!g) return
    if (g === 'conectado') toast({ title: 'Google Calendar conectado', description: 'Sincronizando tus eventos.', tone: 'success' })
    else if (g === 'cancelado') toast({ title: 'Conexión con Google cancelada' })
    else toast({ title: 'No se pudo conectar Google Calendar', description: 'Inténtalo de nuevo.', tone: 'danger' })
    params.delete('google')
    setParams(params, { replace: true })
    requestAnimationFrame(() => document.getElementById('google')?.scrollIntoView({ block: 'start' }))
  }, [params, setParams])

  useEffect(() => {
    if (location.hash) requestAnimationFrame(() => document.getElementById(location.hash.slice(1))?.scrollIntoView({ block: 'start' }))
  }, [location.hash])

  const s = settings.data?.settings

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-6 md:px-8 md:py-9">
      <PageHeader title="Ajustes" />
      <div className="mt-7 grid items-start gap-8 lg:grid-cols-[200px_minmax(0,1fr)]">
        <nav className="sticky top-6 hidden lg:block" aria-label="Secciones de ajustes">
          <ul className="grid gap-0.5">
            {SECTIONS.map((sec) => (
              <li key={sec.id}>
                <a
                  href={`#${sec.id}`}
                  onClick={(e) => {
                    e.preventDefault()
                    document.getElementById(sec.id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                    history.replaceState(null, '', `#${sec.id}`)
                  }}
                  className="block rounded-control px-3 py-1.5 text-base text-muted transition-colors hover:bg-surface-2 hover:text-fg"
                >
                  {sec.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <div className="grid min-w-0 gap-5">
          <ProfileSection />
          {!s ? (
            <Panel className="grid gap-4 p-5" aria-busy="true">
              <Skeleton className="h-5 w-40" />
              <Skeleton className="h-10 w-full rounded-control" />
              <Skeleton className="h-10 w-full rounded-control" />
            </Panel>
          ) : (
            <>
              <AssistantSection s={s} />
              <VoiceSection s={s} />
              <BriefingSection s={s} />
              <LocationSection s={s} />
            </>
          )}
          <Section id="lugares" title="Lugares" description="Para recordatorios al llegar o salir de un sitio.">
            <PlacesManager />
          </Section>
          <NotificationsSection s={s} ntfy={settings.data?.ntfyAvailable ?? false} />
          <GoogleSection />
          <McpSection />
          <DevicesSection />
          <SecuritySection />
          <DataSection />
        </div>
      </div>
    </div>
  )
}

function Section({ id, title, description, children }: { id: string; title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <Panel className="scroll-mt-6 p-5 md:p-6" aria-labelledby={`${id}-title`} id={id}>
      <h2 id={`${id}-title`} className="text-md font-semibold tracking-tight">
        {title}
      </h2>
      {description && <p className="mt-1 max-w-[65ch] text-base text-muted">{description}</p>}
      <div className="mt-4">{children}</div>
    </Panel>
  )
}

function Row({ label, description, children, htmlFor }: { label: string; description?: ReactNode; children: ReactNode; htmlFor?: string }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 border-b border-line py-3.5 first:pt-0 last:border-0 last:pb-0">
      <div className="min-w-0 flex-1 basis-56">
        <label htmlFor={htmlFor} className="text-base font-medium text-fg">
          {label}
        </label>
        {description && <p className="mt-0.5 text-sm text-muted">{description}</p>}
      </div>
      <div className="flex min-w-0 shrink-0 items-center gap-2 max-sm:w-full">{children}</div>
    </div>
  )
}

/* ---------------------------------------------------------------- Perfil */

function ProfileSection() {
  const { user, setUser } = useAuth()
  const [name, setName] = useState(user?.displayName ?? '')
  const [tz, setTz] = useState(user?.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone)
  const zones = useMemo(() => {
    try {
      return (Intl as unknown as { supportedValuesOf: (k: string) => string[] }).supportedValuesOf('timeZone')
    } catch {
      return [tz]
    }
  }, [tz])
  const save = useMutation({
    mutationFn: () => api.patch<{ user: User }>('/me', { displayName: name.trim(), timezone: tz }),
    onSuccess: (r) => {
      setUser(r.user)
      toast({ title: 'Perfil guardado', tone: 'success' })
    },
    onError: (e) => toastError(e, 'No se pudo guardar el perfil'),
  })
  const dirty = name.trim() !== (user?.displayName ?? '') || tz !== user?.timezone
  return (
    <Section id="perfil" title="Perfil" description="Así te saluda Kairo y así calcula tus horarios.">
      <form
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault()
          if (name.trim()) save.mutate()
        }}
      >
        <Field label="Nombre">{(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} autoComplete="given-name" />}</Field>
        <Field label="Zona horaria" hint={`Navegador: ${Intl.DateTimeFormat().resolvedOptions().timeZone}`}>
          {(id, d) => (
            <Select id={id} value={tz} onChange={(e) => setTz(e.target.value)} aria-describedby={d}>
              {zones.includes(tz) ? null : <option value={tz}>{tz}</option>}
              {zones.map((z) => (
                <option key={z} value={z}>
                  {z.replace(/_/g, ' ')}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <div className="sm:col-span-2">
          <Button type="submit" variant={dirty ? 'primary' : 'secondary'} disabled={!dirty || !name.trim()} loading={save.isPending}>
            Guardar perfil
          </Button>
        </div>
      </form>
    </Section>
  )
}

/* ---------------------------------------------------------------- Asistente */

function ModelSelect({ id, value, models, onChange }: { id: string; value: string; models: string[]; onChange: (v: string) => void }) {
  const list = models.includes(value) ? models : [value, ...models]
  return (
    <Select id={id} value={value} onChange={(e) => onChange(e.target.value)} className="font-mono text-sm sm:w-64">
      {list.map((m) => (
        <option key={m} value={m}>
          {m}
        </option>
      ))}
    </Select>
  )
}

const NOT_CHAT = /embedding|whisper|kokoro|rerank|flux|image/i

function AssistantSection({ s }: { s: UserSettings }) {
  const models = useModels()
  const patch = usePatchSettings()
  const list = (models.data ?? []).filter((m) => !NOT_CHAT.test(m))
  return (
    <Section
      id="asistente"
      title="Asistente"
      description={models.isError || (models.isSuccess && list.length === 0) ? 'No pude leer la lista de modelos del servidor; se muestran los actuales.' : 'Los modelos que usa Kairo para conversar y entender imágenes.'}
    >
      <Row label="Modelo de chat" description="El que responde y usa las herramientas." htmlFor="m-chat">
        {models.isLoading ? <Skeleton className="h-10 w-64 rounded-control" /> : <ModelSelect id="m-chat" value={s.chatModel} models={list} onChange={(v) => patch.mutate({ chatModel: v })} />}
      </Row>
      <Row label="Modelo de respaldo" description="Si el principal falla antes de responder." htmlFor="m-fb">
        {models.isLoading ? <Skeleton className="h-10 w-64 rounded-control" /> : <ModelSelect id="m-fb" value={s.fallbackModel} models={list} onChange={(v) => patch.mutate({ fallbackModel: v })} />}
      </Row>
      <Row label="Modelo de visión" description="Para fotos, facturas y capturas que compartes." htmlFor="m-vis">
        {models.isLoading ? <Skeleton className="h-10 w-64 rounded-control" /> : <ModelSelect id="m-vis" value={s.visionModel} models={list} onChange={(v) => patch.mutate({ visionModel: v })} />}
      </Row>
      <Row label="Modo rápido" description="Respuestas más ágiles con menos razonamiento. Recomendado para el día a día.">
        <Switch checked={s.fastMode} onChange={(v) => patch.mutate({ fastMode: v })} label="Modo rápido" />
      </Row>
    </Section>
  )
}

/* ---------------------------------------------------------------- Voz */

function VoiceSection({ s }: { s: UserSettings }) {
  const voices = useVoices()
  const patch = usePatchSettings()
  const [speed, setSpeed] = useState(s.voiceSpeed)
  const [testing, setTesting] = useState(false)
  useEffect(() => setSpeed(s.voiceSpeed), [s.voiceSpeed])

  const test = async () => {
    unlockAudio()
    setTesting(true)
    try {
      const sp = await speak('Hola, soy Kairo. Así sueno con esta voz y esta velocidad.', { voice: s.voice, speed })
      await sp.done
    } catch (e) {
      toastError(e, 'No se pudo reproducir la voz')
    } finally {
      setTesting(false)
    }
  }

  return (
    <Section id="voz" title="Voz" description="Cómo suena Kairo cuando te responde en voz alta.">
      <Row label="Voz" htmlFor="voice">
        {voices.isLoading ? (
          <Skeleton className="h-10 w-48 rounded-control" />
        ) : (
          <Select id="voice" value={s.voice} onChange={(e) => patch.mutate({ voice: e.target.value })} className="sm:w-48">
            {(voices.data ?? []).map((v) => (
              <option key={v.id} value={v.id}>
                {v.name}, {v.description}
              </option>
            ))}
          </Select>
        )}
        <Button onClick={() => void test()} loading={testing} icon={<Play className="size-4" weight="fill" />}>
          Probar voz
        </Button>
      </Row>
      <Row label="Velocidad" htmlFor="speed" description="1,0 es la velocidad normal.">
        <input
          id="speed"
          type="range"
          min={0.7}
          max={1.4}
          step={0.05}
          value={speed}
          onChange={(e) => setSpeed(Number(e.target.value))}
          onPointerUp={() => speed !== s.voiceSpeed && patch.mutate({ voiceSpeed: speed })}
          onKeyUp={() => speed !== s.voiceSpeed && patch.mutate({ voiceSpeed: speed })}
          className="w-48 accent-[var(--accent)] max-sm:flex-1"
        />
        <span className="w-12 text-right font-mono text-base tnum">{speed.toFixed(2).replace('.', ',')}×</span>
      </Row>
    </Section>
  )
}

/* ---------------------------------------------------------------- Resumen */

function BriefingSection({ s }: { s: UserSettings }) {
  const patch = usePatchSettings()
  const [t, setT] = useState(s.briefingTime)
  useEffect(() => setT(s.briefingTime), [s.briefingTime])
  return (
    <Section id="resumen" title="Resumen diario" description="Cada mañana Kairo prepara un resumen de tu día y te avisa cuando está listo.">
      <Row label="Enviar resumen cada día">
        <Switch checked={s.briefingEnabled} onChange={(v) => patch.mutate({ briefingEnabled: v })} label="Resumen diario" />
      </Row>
      <Row label="Hora" htmlFor="brief-time">
        <Input
          id="brief-time"
          type="time"
          value={t}
          disabled={!s.briefingEnabled}
          onChange={(e) => setT(e.target.value)}
          onBlur={() => t && t !== s.briefingTime && patch.mutate({ briefingTime: t })}
          className="w-32 font-mono"
        />
      </Row>
    </Section>
  )
}

/* ---------------------------------------------------------------- Ubicación */

function LocationSection({ s }: { s: UserSettings }) {
  const patch = usePatchSettings()
  const [q, setQ] = useState('')
  const search = useMutation({
    mutationFn: (query: string) => api.get<GeoPlace[]>(`/geocode${qs({ q: query })}`),
    onError: (e) => toastError(e, 'No se pudo buscar la ciudad'),
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (q.trim().length > 1) search.mutate(q.trim())
  }
  return (
    <Section id="ubicacion" title="Ubicación" description="Tu ciudad para el clima de Hoy y para entender lo que dices de tu zona.">
      <div className="flex items-center gap-3 rounded-control border border-line bg-bg px-3 py-2.5">
        <MapPin className="size-[18px] shrink-0 text-faint" />
        {s.homeCity ? (
          <div className="min-w-0 flex-1">
            <p className="text-base text-fg">{s.homeCity}</p>
            {s.homeLatitude !== null && s.homeLongitude !== null && (
              <p className="font-mono text-xs text-faint">
                {s.homeLatitude.toFixed(4)}, {s.homeLongitude.toFixed(4)}
              </p>
            )}
          </div>
        ) : (
          <p className="flex-1 text-base text-muted">Sin ciudad configurada</p>
        )}
        {s.homeCity && (
          <Button size="sm" variant="ghost" onClick={() => patch.mutate({ homeCity: null, homeLatitude: null, homeLongitude: null })}>
            Quitar
          </Button>
        )}
      </div>
      <form onSubmit={submit} className="mt-3 flex gap-2">
        <label className="relative min-w-0 flex-1">
          <span className="sr-only">Buscar ciudad</span>
          <MagnifyingGlass className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar ciudad, por ejemplo Bogotá" className="pl-9" />
        </label>
        <Button type="submit" loading={search.isPending} disabled={q.trim().length < 2}>
          Buscar
        </Button>
      </form>
      {search.data && (
        <ul className="mt-2 divide-y divide-line rounded-control border border-line">
          {search.data.length === 0 && <li className="px-3 py-3 text-base text-muted">No encontré esa ciudad.</li>}
          {search.data.slice(0, 6).map((p) => (
            <li key={`${p.latitude},${p.longitude}`}>
              <button
                type="button"
                onClick={() => {
                  patch.mutate({ homeCity: p.name, homeLatitude: p.latitude, homeLongitude: p.longitude })
                  search.reset()
                  setQ('')
                }}
                className="flex w-full items-center justify-between gap-3 px-3 py-2.5 text-left transition-colors hover:bg-surface-2"
              >
                <span>
                  <span className="text-base text-fg">{p.name}</span>
                  <span className="ml-2 text-sm text-muted">{[p.admin, p.country].filter(Boolean).join(', ')}</span>
                </span>
                <span className="text-sm text-accent-strong">Usar</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/* ---------------------------------------------------------------- Notificaciones */

function NotificationsSection({ s, ntfy }: { s?: UserSettings; ntfy: boolean }) {
  const patch = usePatchSettings()
  const [perm, setPerm] = useState<NotificationPermission | 'unsupported'>(() => (notificationsSupported() ? Notification.permission : 'unsupported'))
  const [topic, setTopic] = useState(s?.ntfyTopic ?? '')
  useEffect(() => setTopic(s?.ntfyTopic ?? ''), [s?.ntfyTopic])

  const push = usePushConfig()
  const ask = async () => setPerm(await requestNotifications())
  const testNotif = () => {
    try {
      new Notification('Kairo', { body: 'Así te avisaré de tus recordatorios.', icon: '/favicon.svg' })
    } catch {
      toast({ title: 'Este navegador no muestra notificaciones de prueba' })
    }
  }

  return (
    <Section id="notificaciones" title="Notificaciones" description="Kairo te avisa en este navegador cuando suena un recordatorio o llega un aviso de tu familia, aunque estés en otra pestaña.">
      <Row
        label="Avisos del navegador"
        description={
          perm === 'granted'
            ? 'Activados en este navegador.'
            : perm === 'denied'
              ? 'Bloqueados. Cámbialo en los permisos del sitio de tu navegador.'
              : perm === 'unsupported'
                ? 'Este navegador no admite notificaciones.'
                : 'Aún no has dado permiso.'
        }
      >
        {perm === 'granted' ? (
          <>
            <Tag tone="success">Activos</Tag>
            <Button size="sm" onClick={testNotif}>
              Probar
            </Button>
          </>
        ) : perm === 'default' ? (
          <Button size="sm" variant="primary" onClick={() => void ask()}>
            Activar
          </Button>
        ) : null}
      </Row>
      <Row
        label="Avisos en el teléfono"
        description="Kairo envía sus propios avisos cifrados a la app del teléfono con UnifiedPush, por ejemplo a través de un ntfy autoalojado. No pasa por Firebase ni por los servidores de Google."
      >
        {push.isLoading ? (
          <Skeleton className="h-5 w-36" />
        ) : push.isError ? (
          <span className="text-sm text-muted">No pude consultarlo</span>
        ) : (
          <span className="text-sm whitespace-nowrap text-muted">
            Teléfonos con push: <span className="font-mono text-fg tnum">{push.data?.registeredDevices ?? 0}</span>
          </span>
        )}
      </Row>
      <Row
        label="Tema de ntfy"
        htmlFor="ntfy"
        description={ntfy ? 'Recibe los avisos también en la app ntfy con este tema.' : 'El servidor no tiene ntfy configurado.'}
      >
        <Input
          id="ntfy"
          value={topic}
          disabled={!ntfy || !s}
          onChange={(e) => setTopic(e.target.value)}
          onBlur={() => s && topic.trim() !== (s.ntfyTopic ?? '') && patch.mutate({ ntfyTopic: topic.trim() || null })}
          placeholder="kairo-david"
          className="font-mono sm:w-56"
        />
      </Row>
    </Section>
  )
}

/* ---------------------------------------------------------------- Google */

function GoogleSection() {
  const qc = useQueryClient()
  const g = useGoogle()
  const calendars = useGoogleCalendars(!!g.data?.connected)
  const [confirm, setConfirm] = useState(false)
  const connect = useMutation({
    mutationFn: () => api.post<{ url: string }>('/integrations/google/auth-url'),
    onSuccess: (r) => {
      window.location.href = r.url
    },
    onError: (e) => toastError(e, 'No se pudo iniciar la conexión'),
  })
  const sync = useMutation({
    mutationFn: () => api.post<{ pushed: number; pulled: number; deleted: number }>('/integrations/google/sync'),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: keys.google })
      void qc.invalidateQueries({ queryKey: ['events'] })
      toast({ title: 'Calendario sincronizado', description: `${r.pulled} traídos, ${r.pushed} enviados, ${r.deleted} eliminados.`, tone: 'success' })
    },
    onError: (e) => toastError(e, 'No se pudo sincronizar'),
  })
  const pick = useMutation({
    mutationFn: (calendarId: string) => api.post('/integrations/google/calendar', { calendarId }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.google })
      toast({ title: 'Calendario elegido' })
    },
    onError: (e) => toastError(e, 'No se pudo cambiar el calendario'),
  })
  const pickFamily = useMutation({
    mutationFn: (calendarId: string | null) => api.post('/integrations/google/family-calendar', { calendarId }),
    onSuccess: (_r, calendarId) => {
      void qc.invalidateQueries({ queryKey: keys.google })
      toast({ title: calendarId ? 'Calendario familiar elegido' : 'Los eventos familiares van a tu calendario de siempre' })
    },
    onError: (e) => toastError(e, 'No se pudo cambiar el calendario familiar'),
  })
  const disconnect = useMutation({
    mutationFn: () => api.del('/integrations/google'),
    onSuccess: () => {
      setConfirm(false)
      void qc.invalidateQueries({ queryKey: keys.google })
      toast({ title: 'Google Calendar desconectado' })
    },
    onError: (e) => toastError(e, 'No se pudo desconectar'),
  })

  const st = g.data
  return (
    <Section id="google" title="Google Calendar" description="Sincroniza tus eventos en ambos sentidos. Los que vienen de Google se marcan con su logo.">
      {g.isLoading ? (
        <SkeletonRows rows={2} />
      ) : !st ? (
        <FormError>{(g.error as Error)?.message ?? 'No se pudo leer el estado.'}</FormError>
      ) : !st.available ? (
        <div className="grid gap-3">
          <div className="flex items-center gap-2">
            <GoogleLogo className="size-5 text-muted" />
            <Tag>No configurado en el servidor</Tag>
          </div>
          <p className="max-w-[65ch] text-base text-muted">
            Para activarlo, crea credenciales OAuth en Google Cloud Console, define <code className="font-mono text-sm text-fg">GOOGLE_CLIENT_ID</code> y{' '}
            <code className="font-mono text-sm text-fg">GOOGLE_CLIENT_SECRET</code> en el servidor y registra esta URL de redirección autorizada:
          </p>
          <CopyField value={st.redirectUri} />
        </div>
      ) : !st.connected ? (
        <div className="grid gap-3">
          <p className="text-base text-muted">Aún no está conectado.</p>
          <div>
            <Button variant="primary" icon={<GoogleLogo className="size-4" />} onClick={() => connect.mutate()} loading={connect.isPending}>
              Conectar
            </Button>
          </div>
          <details className="text-sm text-muted">
            <summary className="cursor-pointer text-fg">URL de redirección</summary>
            <div className="mt-2">
              <CopyField value={st.redirectUri} />
            </div>
          </details>
        </div>
      ) : (
        <div className="grid gap-1">
          <Row label="Estado" description={st.lastSyncAt ? `Última sincronización ${relative(st.lastSyncAt)}` : 'Aún no se ha sincronizado.'}>
            <Tag tone={st.lastError ? 'danger' : 'success'}>{st.lastError ? 'Con errores' : 'Conectado'}</Tag>
          </Row>
          {st.lastError && <FormError>{st.lastError}</FormError>}
          <Row label="Calendario" htmlFor="gcal">
            {calendars.isLoading ? (
              <Skeleton className="h-10 w-56 rounded-control" />
            ) : (
              <Select id="gcal" value={st.calendarId ?? 'primary'} onChange={(e) => pick.mutate(e.target.value)} className="sm:w-64">
                {!(calendars.data ?? []).some((c) => c.id === (st.calendarId ?? 'primary')) && <option value={st.calendarId ?? 'primary'}>{st.calendarId ?? 'primary'}</option>}
                {(calendars.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.summary}
                    {c.primary ? ' (principal)' : ''}
                  </option>
                ))}
              </Select>
            )}
          </Row>
          <Row
            label="Calendario para eventos familiares"
            htmlFor="gcal-family"
            description="Elige un calendario de Google que compartas con tu familia. Ahí se guardan los eventos familiares que creas."
          >
            {calendars.isLoading ? (
              <Skeleton className="h-10 w-56 rounded-control" />
            ) : (
              <Select
                id="gcal-family"
                value={st.familyCalendarId ?? ''}
                onChange={(e) => pickFamily.mutate(e.target.value || null)}
                disabled={pickFamily.isPending}
                className="sm:w-64"
              >
                <option value="">El mismo de siempre</option>
                {st.familyCalendarId && !(calendars.data ?? []).some((c) => c.id === st.familyCalendarId) && (
                  <option value={st.familyCalendarId}>{st.familyCalendarId}</option>
                )}
                {(calendars.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.summary}
                    {c.primary ? ' (principal)' : ''}
                  </option>
                ))}
              </Select>
            )}
          </Row>
          <div className="flex flex-wrap gap-2 pt-3">
            <Button icon={<ArrowsClockwise className="size-4" />} onClick={() => sync.mutate()} loading={sync.isPending}>
              Sincronizar ahora
            </Button>
            <Button variant="danger" onClick={() => setConfirm(true)}>
              Desconectar
            </Button>
          </div>
        </div>
      )}
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => disconnect.mutate()}
        loading={disconnect.isPending}
        title="¿Desconectar Google Calendar?"
        description="Los eventos que ya están en Kairo se conservan. Dejarán de sincronizarse."
        confirmLabel="Desconectar"
      />
    </Section>
  )
}

/* ---------------------------------------------------------------- MCP */

function McpSection() {
  const qc = useQueryClient()
  const servers = useMcpServers()
  const [adding, setAdding] = useState(false)
  const [tools, setTools] = useState<Record<string, string[] | string>>({})
  const [deleting, setDeleting] = useState<McpServer | null>(null)
  const refresh = () => void qc.invalidateQueries({ queryKey: keys.mcp })

  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => api.patch<McpServer>(`/mcp-servers/${id}`, { enabled }),
    onSuccess: refresh,
    onError: (e) => toastError(e, 'No se pudo cambiar'),
  })
  const test = useMutation({
    mutationFn: (id: string) => api.post<{ tools: string[] }>(`/mcp-servers/${id}/test`).then((r) => ({ id, tools: r.tools })),
    onSuccess: ({ id, tools: t }) => setTools((x) => ({ ...x, [id]: t })),
    onError: (e, id) => setTools((x) => ({ ...x, [id]: e instanceof Error ? e.message : 'Falló la prueba' })),
  })
  const remove = useMutation({
    mutationFn: (s: McpServer) => api.del(`/mcp-servers/${s.id}`),
    onSuccess: () => {
      setDeleting(null)
      refresh()
      toast({ title: 'Conexión eliminada' })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar'),
  })

  return (
    <Section id="mcp" title="Conexiones MCP" description="Servidores MCP (HTTP) que le dan a Kairo herramientas extra, como tu CRM o tu domótica.">
      {servers.isLoading ? (
        <SkeletonRows rows={2} />
      ) : (servers.data ?? []).length === 0 && !adding ? (
        <p className="text-base text-muted">No hay conexiones.</p>
      ) : (
        <ul className="divide-y divide-line">
          {(servers.data ?? []).map((sv) => {
            const t = tools[sv.id]
            return (
              <li key={sv.id} className="py-3 first:pt-0">
                <div className="flex flex-wrap items-center gap-3">
                  <Plug className="size-[18px] shrink-0 text-faint" />
                  <div className="min-w-0 flex-1 basis-40">
                    <p className="truncate text-base font-medium text-fg">{sv.name}</p>
                    <p className="truncate font-mono text-xs text-faint">{sv.url}</p>
                  </div>
                  <Button size="sm" onClick={() => test.mutate(sv.id)} loading={test.isPending && test.variables === sv.id}>
                    Probar
                  </Button>
                  <Switch checked={sv.enabled} onChange={(v) => toggle.mutate({ id: sv.id, enabled: v })} label={`Activar ${sv.name}`} />
                  <IconButton label={`Eliminar ${sv.name}`} size="sm" tone="danger" onClick={() => setDeleting(sv)}>
                    <Trash className="size-4" />
                  </IconButton>
                </div>
                {t !== undefined &&
                  (typeof t === 'string' ? (
                    <p className="mt-2 text-sm text-danger">{t}</p>
                  ) : (
                    <div className="mt-2 flex flex-wrap gap-1.5 pl-8">
                      {t.length === 0 ? <span className="text-sm text-muted">Conectó, pero no ofrece herramientas.</span> : t.map((x) => <Tag key={x}>{x}</Tag>)}
                    </div>
                  ))}
              </li>
            )
          })}
        </ul>
      )}
      {adding ? (
        <McpForm onDone={() => setAdding(false)} />
      ) : (
        <Button size="sm" className="mt-3" icon={<Plus className="size-4" />} onClick={() => setAdding(true)}>
          Agregar conexión
        </Button>
      )}
      <ConfirmDialog
        open={!!deleting}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting)}
        loading={remove.isPending}
        title={`¿Eliminar ${deleting?.name ?? 'la conexión'}?`}
        description="Kairo dejará de usar sus herramientas."
      />
    </Section>
  )
}

function McpForm({ onDone }: { onDone: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [headers, setHeaders] = useState('')
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => {
      const h: Record<string, string> = {}
      for (const line of headers.split('\n')) {
        const i = line.indexOf(':')
        if (i > 0) h[line.slice(0, i).trim()] = line.slice(i + 1).trim()
      }
      return api.post<McpServer>('/mcp-servers', { name: name.trim(), url: url.trim(), headers: h })
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.mcp })
      toast({ title: 'Conexión agregada', tone: 'success' })
      onDone()
    },
    onError: (e) => setError(e instanceof Error ? e.message : 'No se pudo guardar'),
  })
  return (
    <form
      className="mt-3 grid gap-3 rounded-control border border-line bg-bg p-3"
      onSubmit={(e) => {
        e.preventDefault()
        if (!name.trim()) return setError('Ponle un nombre.')
        if (!/^https?:\/\//.test(url.trim())) return setError('La URL debe empezar por http:// o https://')
        setError(null)
        save.mutate()
      }}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Nombre">{(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} placeholder="Domótica" autoFocus />}</Field>
        <Field label="URL">{(id) => <Input id={id} value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://mcp.midominio.co/mcp" className="font-mono text-sm" />}</Field>
      </div>
      <Field label="Cabeceras" optional hint="Una por línea, como Authorization: Bearer token">
        {(id, d) => <Textarea id={id} value={headers} onChange={(e) => setHeaders(e.target.value)} rows={2} className="min-h-0 font-mono text-sm" aria-describedby={d} />}
      </Field>
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onDone}>
          Cancelar
        </Button>
        <Button type="submit" variant="primary" loading={save.isPending}>
          Guardar
        </Button>
      </div>
    </form>
  )
}

/* ---------------------------------------------------------------- Dispositivos */

function DevicesSection() {
  const qc = useQueryClient()
  const devices = useDevices()
  const [revoking, setRevoking] = useState<string | null>(null)
  const [showAll, setShowAll] = useState(false)
  const revoke = useMutation({
    mutationFn: (id: string) => api.del(`/devices/${id}`),
    onSuccess: () => {
      setRevoking(null)
      void qc.invalidateQueries({ queryKey: keys.devices })
      toast({ title: 'Acceso revocado' })
    },
    onError: (e) => toastError(e, 'No se pudo revocar'),
  })
  const icon = (p: string) => (p === 'android' ? <DeviceMobile className="size-5" /> : p === 'wear' ? <Watch className="size-5" /> : <Browser className="size-5" />)
  const target = devices.data?.find((d) => d.id === revoking)
  return (
    <Section id="dispositivos" title="Dispositivos" description="Dónde tienes la sesión abierta. Revoca los que ya no uses.">
      {devices.isLoading ? (
        <SkeletonRows rows={3} />
      ) : (
        <ul className="divide-y divide-line">
          {[...(devices.data ?? [])]
            .sort((a, b) => Number(b.current) - Number(a.current) || b.lastSeenAt.localeCompare(a.lastSeenAt))
            .slice(0, showAll ? undefined : 6)
            .map((d) => (
            <li key={d.id} className="flex items-center gap-3 py-3 first:pt-0 last:pb-0">
              <span className="flex size-9 shrink-0 items-center justify-center rounded-control bg-surface-2 text-muted">{icon(d.platform)}</span>
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2 text-base text-fg">
                  <span className="truncate">{d.name || 'Dispositivo'}</span>
                  {d.current && <Tag tone="accent">Este dispositivo</Tag>}
                </p>
                <p className="text-sm text-muted">
                  Activo {relative(d.lastSeenAt)}, desde el {shortDate(d.createdAt)}
                </p>
              </div>
              {!d.current && (
                <Button size="sm" variant="ghost" className="text-danger hover:text-danger" onClick={() => setRevoking(d.id)}>
                  Revocar
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {!showAll && (devices.data?.length ?? 0) > 6 && (
        <Button size="sm" variant="ghost" className="mt-2" onClick={() => setShowAll(true)}>
          Ver los {devices.data!.length} dispositivos
        </Button>
      )}
      <ConfirmDialog
        open={!!revoking}
        onClose={() => setRevoking(null)}
        onConfirm={() => revoking && revoke.mutate(revoking)}
        loading={revoke.isPending}
        title={`¿Revocar ${target?.name ?? 'este dispositivo'}?`}
        description="Tendrá que volver a iniciar sesión."
        confirmLabel="Revocar"
      />
    </Section>
  )
}

/* ---------------------------------------------------------------- Seguridad */

function SecuritySection() {
  const { logout } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<{ current?: string; next?: string; confirm?: string; form?: string }>({})
  const change = useMutation({
    mutationFn: () => api.post('/auth/password', { currentPassword: current, newPassword: next }),
    onSuccess: () => {
      setCurrent('')
      setNext('')
      setConfirm('')
      toast({ title: 'Contraseña cambiada', tone: 'success' })
    },
    onError: (e) => setErrors({ form: e instanceof Error ? e.message : 'No se pudo cambiar' }),
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const errs: typeof errors = {}
    if (!current) errs.current = 'Escribe tu contraseña actual.'
    if (next.length < 10) errs.next = 'Usa al menos 10 caracteres.'
    if (confirm !== next) errs.confirm = 'No coincide con la nueva contraseña.'
    setErrors(errs)
    if (Object.keys(errs).length === 0) change.mutate()
  }
  return (
    <Section id="seguridad" title="Seguridad">
      <form onSubmit={submit} className="grid gap-4 sm:grid-cols-3" noValidate>
        <Field label="Contraseña actual" error={errors.current}>
          {(id, d) => <Input id={id} type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} aria-invalid={!!errors.current} aria-describedby={d} />}
        </Field>
        <Field label="Nueva contraseña" error={errors.next} hint="Mínimo 10 caracteres.">
          {(id, d) => <Input id={id} type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} aria-invalid={!!errors.next} aria-describedby={d} />}
        </Field>
        <Field label="Repite la nueva" error={errors.confirm}>
          {(id, d) => <Input id={id} type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} aria-invalid={!!errors.confirm} aria-describedby={d} />}
        </Field>
        {errors.form && (
          <div className="sm:col-span-3">
            <FormError>{errors.form}</FormError>
          </div>
        )}
        <div className="flex flex-wrap gap-2 sm:col-span-3">
          <Button type="submit" variant="primary" loading={change.isPending}>
            Cambiar contraseña
          </Button>
          <Button variant="ghost" className={cn('text-danger hover:text-danger', 'ml-auto')} icon={<SignOut className="size-4" />} onClick={() => void logout()}>
            Cerrar sesión
          </Button>
        </div>
      </form>
    </Section>
  )
}

/* ---------------------------------------------------------------- Datos */

function DataSection() {
  const exp = useMutation({
    mutationFn: () => api.blob('/export'),
    onSuccess: (blob) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `kairo-export-${new Date().toISOString().slice(0, 10)}.json`
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 2000)
      toast({ title: 'Exportación lista', description: 'Se descargó el archivo JSON.', tone: 'success' })
    },
    onError: (e) => toastError(e, 'No se pudo exportar'),
  })
  return (
    <Section id="datos" title="Datos" description="Descarga todo lo que Kairo guarda: notas, tareas, recordatorios, eventos, listas, memoria y grabaciones.">
      <Button icon={<DownloadSimple className="size-4" />} onClick={() => exp.mutate()} loading={exp.isPending}>
        Exportar JSON
      </Button>
    </Section>
  )
}
