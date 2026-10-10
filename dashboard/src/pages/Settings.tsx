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
import { intlLocale, t, useI18n, type LangPref } from '@/lib/i18n'
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

const sections = () => [
  { id: 'profile', label: t('settings.sec.profile') },
  { id: 'assistant', label: t('settings.sec.assistant') },
  { id: 'voice', label: t('settings.sec.voice') },
  { id: 'briefing', label: t('settings.sec.briefing') },
  { id: 'location', label: t('settings.sec.location') },
  { id: 'places', label: t('settings.sec.places') },
  { id: 'notifications', label: t('settings.sec.notifications') },
  { id: 'google', label: t('settings.sec.google') },
  { id: 'mcp', label: t('settings.sec.mcp') },
  { id: 'devices', label: t('settings.sec.devices') },
  { id: 'security', label: t('settings.sec.security') },
  { id: 'data', label: t('settings.sec.data') },
]

function usePatchSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (patch: Partial<UserSettings>) => api.patch<SettingsResponse>('/settings', patch),
    onSuccess: (r) => {
      qc.setQueryData(keys.settings, r)
      void qc.invalidateQueries({ queryKey: keys.today })
      toast({ id: 'settings-saved', title: t('settings.saved'), duration: 2000 })
    },
    onError: (e) => toastError(e, t('settings.err.save')),
  })
}

export default function SettingsPage() {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const settings = useSettings()

  useEffect(() => {
    const g = params.get('google')
    if (!g) return
    // The server redirects back with ?google=connected|canceled (the legacy Spanish values are still understood).
    if (g === 'connected' || g === 'conectado') toast({ title: t('settings.googleConnected'), description: t('settings.googleSyncing'), tone: 'success' })
    else if (g === 'canceled' || g === 'cancelled' || g === 'cancelado') toast({ title: t('settings.googleCanceled') })
    else toast({ title: t('settings.googleFailed'), description: t('settings.tryAgain'), tone: 'danger' })
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
      <PageHeader title={t('nav.settings')} />
      <div className="mt-7 grid items-start gap-8 lg:grid-cols-[200px_minmax(0,1fr)]">
        <nav className="sticky top-6 hidden lg:block" aria-label={t('settings.sections')}>
          <ul className="grid gap-0.5">
            {sections().map((sec) => (
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
          <Section id="places" title={t('settings.sec.places')} description={t('settings.placesHint')}>
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

/* ---------------------------------------------------------------- Profile */

function ProfileSection() {
  const { user, setUser } = useAuth()
  const { pref, setPref } = useI18n()
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
      toast({ title: t('settings.profileSaved'), tone: 'success' })
    },
    onError: (e) => toastError(e, t('settings.err.profile')),
  })
  const dirty = name.trim() !== (user?.displayName ?? '') || tz !== user?.timezone
  return (
    <Section id="profile" title={t('settings.sec.profile')} description={t('settings.profileHint')}>
      <form
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault()
          if (name.trim()) save.mutate()
        }}
      >
        <Field label={t('common.name')}>{(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} autoComplete="given-name" />}</Field>
        <Field label={t('settings.timezone')} hint={t('settings.browserTz', { tz: Intl.DateTimeFormat().resolvedOptions().timeZone })}>
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
            {t('settings.saveProfile')}
          </Button>
        </div>
      </form>
      <div className="mt-5 border-t border-line pt-4">
        <Row label={t('settings.uiLanguage')} description={t('settings.uiLanguageHint')} htmlFor="ui-lang">
          <Select id="ui-lang" value={pref} onChange={(e) => setPref(e.target.value as LangPref)} className="sm:w-48">
            <option value="system">{t('settings.langSystem')}</option>
            <option value="en">{t('settings.langEnglish')}</option>
            <option value="es">{t('settings.langSpanish')}</option>
          </Select>
        </Row>
      </div>
    </Section>
  )
}

/* ---------------------------------------------------------------- Assistant */

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
      id="assistant"
      title={t('settings.sec.assistant')}
      description={models.isError || (models.isSuccess && list.length === 0) ? t('settings.modelsError') : t('settings.modelsHint')}
    >
      <Row label={t('settings.chatModel')} description={t('settings.chatModelHint')} htmlFor="m-chat">
        {models.isLoading ? <Skeleton className="h-10 w-64 rounded-control" /> : <ModelSelect id="m-chat" value={s.chatModel} models={list} onChange={(v) => patch.mutate({ chatModel: v })} />}
      </Row>
      <Row label={t('settings.fallbackModel')} description={t('settings.fallbackModelHint')} htmlFor="m-fb">
        {models.isLoading ? <Skeleton className="h-10 w-64 rounded-control" /> : <ModelSelect id="m-fb" value={s.fallbackModel} models={list} onChange={(v) => patch.mutate({ fallbackModel: v })} />}
      </Row>
      <Row label={t('settings.visionModel')} description={t('settings.visionModelHint')} htmlFor="m-vis">
        {models.isLoading ? <Skeleton className="h-10 w-64 rounded-control" /> : <ModelSelect id="m-vis" value={s.visionModel} models={list} onChange={(v) => patch.mutate({ visionModel: v })} />}
      </Row>
      <Row label={t('settings.fastMode')} description={t('settings.fastModeHint')}>
        <Switch checked={s.fastMode} onChange={(v) => patch.mutate({ fastMode: v })} label={t('settings.fastMode')} />
      </Row>
      <Row label={t('settings.assistantLanguage')} description={t('settings.assistantLanguageHint')} htmlFor="assistant-lang">
        <Select
          id="assistant-lang"
          value={s.language === 'en' || s.language === 'es' ? s.language : 'auto'}
          onChange={(e) => patch.mutate({ language: e.target.value })}
          className="sm:w-56"
        >
          <option value="en">{t('settings.assistantEnglish')}</option>
          <option value="es">{t('settings.assistantSpanish')}</option>
          <option value="auto">{t('settings.assistantAuto')}</option>
        </Select>
      </Row>
    </Section>
  )
}

/* ---------------------------------------------------------------- Voice */

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
      const sp = await speak(t('settings.voiceTest'), { voice: s.voice, speed })
      await sp.done
    } catch (e) {
      toastError(e, t('settings.err.voiceTest'))
    } finally {
      setTesting(false)
    }
  }

  return (
    <Section id="voice" title={t('settings.sec.voice')} description={t('settings.voiceHint')}>
      <Row label={t('settings.voice')} htmlFor="voice">
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
          {t('settings.testVoice')}
        </Button>
      </Row>
      <Row label={t('settings.speed')} htmlFor="speed" description={t('settings.speedHint')}>
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
        <span className="w-12 text-right font-mono text-base tnum">{new Intl.NumberFormat(intlLocale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(speed)}×</span>
      </Row>
    </Section>
  )
}

/* ---------------------------------------------------------------- Briefing */

function BriefingSection({ s }: { s: UserSettings }) {
  const patch = usePatchSettings()
  const [at, setAt] = useState(s.briefingTime)
  useEffect(() => setAt(s.briefingTime), [s.briefingTime])
  return (
    <Section id="briefing" title={t('settings.sec.briefing')} description={t('settings.briefingHint')}>
      <Row label={t('settings.briefingSend')}>
        <Switch checked={s.briefingEnabled} onChange={(v) => patch.mutate({ briefingEnabled: v })} label={t('settings.sec.briefing')} />
      </Row>
      <Row label={t('settings.briefingTime')} htmlFor="brief-time">
        <Input
          id="brief-time"
          type="time"
          value={at}
          disabled={!s.briefingEnabled}
          onChange={(e) => setAt(e.target.value)}
          onBlur={() => at && at !== s.briefingTime && patch.mutate({ briefingTime: at })}
          className="w-32 font-mono"
        />
      </Row>
    </Section>
  )
}

/* ---------------------------------------------------------------- Location */

function LocationSection({ s }: { s: UserSettings }) {
  const patch = usePatchSettings()
  const [q, setQ] = useState('')
  const search = useMutation({
    mutationFn: (query: string) => api.get<GeoPlace[]>(`/geocode${qs({ q: query })}`),
    onError: (e) => toastError(e, t('settings.err.search')),
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (q.trim().length > 1) search.mutate(q.trim())
  }
  return (
    <Section id="location" title={t('settings.sec.location')} description={t('settings.locationHint')}>
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
          <p className="flex-1 text-base text-muted">{t('settings.noCity')}</p>
        )}
        {s.homeCity && (
          <Button size="sm" variant="ghost" onClick={() => patch.mutate({ homeCity: null, homeLatitude: null, homeLongitude: null })}>
            {t('settings.remove')}
          </Button>
        )}
      </div>
      <form onSubmit={submit} className="mt-3 flex gap-2">
        <label className="relative min-w-0 flex-1">
          <span className="sr-only">{t('settings.searchCity')}</span>
          <MagnifyingGlass className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('settings.searchCityPlaceholder')} className="pl-9" />
        </label>
        <Button type="submit" loading={search.isPending} disabled={q.trim().length < 2}>
          {t('settings.search')}
        </Button>
      </form>
      {search.data && (
        <ul className="mt-2 divide-y divide-line rounded-control border border-line">
          {search.data.length === 0 && <li className="px-3 py-3 text-base text-muted">{t('settings.cityNotFound')}</li>}
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
                <span className="text-sm text-accent-strong">{t('settings.use')}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/* ---------------------------------------------------------------- Notifications */

function NotificationsSection({ s, ntfy }: { s?: UserSettings; ntfy: boolean }) {
  const patch = usePatchSettings()
  const [perm, setPerm] = useState<NotificationPermission | 'unsupported'>(() => (notificationsSupported() ? Notification.permission : 'unsupported'))
  const [topic, setTopic] = useState(s?.ntfyTopic ?? '')
  useEffect(() => setTopic(s?.ntfyTopic ?? ''), [s?.ntfyTopic])

  const push = usePushConfig()
  const ask = async () => setPerm(await requestNotifications())
  const testNotif = () => {
    try {
      new Notification('Kairo', { body: t('settings.testNotifBody'), icon: '/favicon.svg' })
    } catch {
      toast({ title: t('settings.testNotifFailed') })
    }
  }

  return (
    <Section id="notifications" title={t('settings.sec.notifications')} description={t('settings.notifHint')}>
      <Row
        label={t('settings.browserAlerts')}
        description={
          perm === 'granted'
            ? t('settings.permGranted')
            : perm === 'denied'
              ? t('settings.permDenied')
              : perm === 'unsupported'
                ? t('settings.permUnsupported')
                : t('settings.permDefault')
        }
      >
        {perm === 'granted' ? (
          <>
            <Tag tone="success">{t('settings.on')}</Tag>
            <Button size="sm" onClick={testNotif}>
              {t('settings.test')}
            </Button>
          </>
        ) : perm === 'default' ? (
          <Button size="sm" variant="primary" onClick={() => void ask()}>
            {t('settings.turnOn')}
          </Button>
        ) : null}
      </Row>
      <Row label={t('settings.phoneAlerts')} description={t('settings.phoneAlertsHint')}>
        {push.isLoading ? (
          <Skeleton className="h-5 w-36" />
        ) : push.isError ? (
          <span className="text-sm text-muted">{t('settings.cantCheck')}</span>
        ) : (
          <span className="text-sm whitespace-nowrap text-muted">
            {t('settings.pushPhones')} <span className="font-mono text-fg tnum">{push.data?.registeredDevices ?? 0}</span>
          </span>
        )}
      </Row>
      <Row
        label={t('settings.ntfyTopic')}
        htmlFor="ntfy"
        description={ntfy ? t('settings.ntfyOn') : t('settings.ntfyOff')}
      >
        <Input
          id="ntfy"
          value={topic}
          disabled={!ntfy || !s}
          onChange={(e) => setTopic(e.target.value)}
          onBlur={() => s && topic.trim() !== (s.ntfyTopic ?? '') && patch.mutate({ ntfyTopic: topic.trim() || null })}
          placeholder={t('settings.ntfyPlaceholder')}
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
    onError: (e) => toastError(e, t('settings.err.googleStart')),
  })
  const sync = useMutation({
    mutationFn: () => api.post<{ pushed: number; pulled: number; deleted: number }>('/integrations/google/sync'),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: keys.google })
      void qc.invalidateQueries({ queryKey: ['events'] })
      toast({ title: t('settings.googleSynced'), description: t('settings.googleSyncBody', { pulled: r.pulled, pushed: r.pushed, deleted: r.deleted }), tone: 'success' })
    },
    onError: (e) => toastError(e, t('settings.err.sync')),
  })
  const pick = useMutation({
    mutationFn: (calendarId: string) => api.post('/integrations/google/calendar', { calendarId }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.google })
      toast({ title: t('settings.calendarPicked') })
    },
    onError: (e) => toastError(e, t('settings.err.calendar')),
  })
  const pickFamily = useMutation({
    mutationFn: (calendarId: string | null) => api.post('/integrations/google/family-calendar', { calendarId }),
    onSuccess: (_r, calendarId) => {
      void qc.invalidateQueries({ queryKey: keys.google })
      toast({ title: calendarId ? t('settings.familyCalendarPicked') : t('settings.familyCalendarDefault') })
    },
    onError: (e) => toastError(e, t('settings.err.familyCalendar')),
  })
  const disconnect = useMutation({
    mutationFn: () => api.del('/integrations/google'),
    onSuccess: () => {
      setConfirm(false)
      void qc.invalidateQueries({ queryKey: keys.google })
      toast({ title: t('settings.googleDisconnected') })
    },
    onError: (e) => toastError(e, t('settings.err.disconnect')),
  })

  const st = g.data
  return (
    <Section id="google" title={t('settings.sec.google')} description={t('settings.googleHint')}>
      {g.isLoading ? (
        <SkeletonRows rows={2} />
      ) : !st ? (
        <FormError>{(g.error as Error)?.message ?? t('settings.err.status')}</FormError>
      ) : !st.available ? (
        <div className="grid gap-3">
          <div className="flex items-center gap-2">
            <GoogleLogo className="size-5 text-muted" />
            <Tag>{t('settings.notConfigured')}</Tag>
          </div>
          <p className="max-w-[65ch] text-base text-muted">
            {t('settings.googleSetup1')} <code className="font-mono text-sm text-fg">GOOGLE_CLIENT_ID</code> {t('settings.googleSetup2')}{' '}
            <code className="font-mono text-sm text-fg">GOOGLE_CLIENT_SECRET</code> {t('settings.googleSetup3')}
          </p>
          <CopyField value={st.redirectUri} />
        </div>
      ) : !st.connected ? (
        <div className="grid gap-3">
          <p className="text-base text-muted">{t('settings.notConnected')}</p>
          <div>
            <Button variant="primary" icon={<GoogleLogo className="size-4" />} onClick={() => connect.mutate()} loading={connect.isPending}>
              {t('settings.connect')}
            </Button>
          </div>
          <details className="text-sm text-muted">
            <summary className="cursor-pointer text-fg">{t('settings.redirectUrl')}</summary>
            <div className="mt-2">
              <CopyField value={st.redirectUri} />
            </div>
          </details>
        </div>
      ) : (
        <div className="grid gap-1">
          <Row label={t('settings.status')} description={st.lastSyncAt ? t('settings.lastSync', { when: relative(st.lastSyncAt) }) : t('settings.neverSynced')}>
            <Tag tone={st.lastError ? 'danger' : 'success'}>{st.lastError ? t('settings.withErrors') : t('settings.connected')}</Tag>
          </Row>
          {st.lastError && <FormError>{st.lastError}</FormError>}
          <Row label={t('nav.calendar')} htmlFor="gcal">
            {calendars.isLoading ? (
              <Skeleton className="h-10 w-56 rounded-control" />
            ) : (
              <Select id="gcal" value={st.calendarId ?? 'primary'} onChange={(e) => pick.mutate(e.target.value)} className="sm:w-64">
                {!(calendars.data ?? []).some((c) => c.id === (st.calendarId ?? 'primary')) && <option value={st.calendarId ?? 'primary'}>{st.calendarId ?? 'primary'}</option>}
                {(calendars.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.summary}
                    {c.primary ? ` ${t('settings.primarySuffix')}` : ''}
                  </option>
                ))}
              </Select>
            )}
          </Row>
          <Row
            label={t('settings.familyCalendar')}
            htmlFor="gcal-family"
            description={t('settings.familyCalendarHint')}
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
                <option value="">{t('settings.usualCalendar')}</option>
                {st.familyCalendarId && !(calendars.data ?? []).some((c) => c.id === st.familyCalendarId) && (
                  <option value={st.familyCalendarId}>{st.familyCalendarId}</option>
                )}
                {(calendars.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.summary}
                    {c.primary ? ` ${t('settings.primarySuffix')}` : ''}
                  </option>
                ))}
              </Select>
            )}
          </Row>
          <div className="flex flex-wrap gap-2 pt-3">
            <Button icon={<ArrowsClockwise className="size-4" />} onClick={() => sync.mutate()} loading={sync.isPending}>
              {t('settings.syncNow')}
            </Button>
            <Button variant="danger" onClick={() => setConfirm(true)}>
              {t('settings.disconnect')}
            </Button>
          </div>
        </div>
      )}
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => disconnect.mutate()}
        loading={disconnect.isPending}
        title={t('settings.disconnectTitle')}
        description={t('settings.disconnectBody')}
        confirmLabel={t('settings.disconnect')}
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
    onError: (e) => toastError(e, t('common.couldNotChange')),
  })
  const test = useMutation({
    mutationFn: (id: string) => api.post<{ tools: string[] }>(`/mcp-servers/${id}/test`).then((r) => ({ id, tools: r.tools })),
    onSuccess: ({ id, tools: names }) => setTools((x) => ({ ...x, [id]: names })),
    onError: (e, id) => setTools((x) => ({ ...x, [id]: e instanceof Error ? e.message : t('settings.testFailed') })),
  })
  const remove = useMutation({
    mutationFn: (s: McpServer) => api.del(`/mcp-servers/${s.id}`),
    onSuccess: () => {
      setDeleting(null)
      refresh()
      toast({ title: t('settings.mcpDeleted') })
    },
    onError: (e) => toastError(e, t('common.couldNotDelete')),
  })

  return (
    <Section id="mcp" title={t('settings.sec.mcp')} description={t('settings.mcpHint')}>
      {servers.isLoading ? (
        <SkeletonRows rows={2} />
      ) : (servers.data ?? []).length === 0 && !adding ? (
        <p className="text-base text-muted">{t('settings.noConnections')}</p>
      ) : (
        <ul className="divide-y divide-line">
          {(servers.data ?? []).map((sv) => {
            const result = tools[sv.id]
            return (
              <li key={sv.id} className="py-3 first:pt-0">
                <div className="flex flex-wrap items-center gap-3">
                  <Plug className="size-[18px] shrink-0 text-faint" />
                  <div className="min-w-0 flex-1 basis-40">
                    <p className="truncate text-base font-medium text-fg">{sv.name}</p>
                    <p className="truncate font-mono text-xs text-faint">{sv.url}</p>
                  </div>
                  <Button size="sm" onClick={() => test.mutate(sv.id)} loading={test.isPending && test.variables === sv.id}>
                    {t('settings.test')}
                  </Button>
                  <Switch checked={sv.enabled} onChange={(v) => toggle.mutate({ id: sv.id, enabled: v })} label={t('settings.turnOnNamed', { name: sv.name })} />
                  <IconButton label={t('common.deleteNamed', { name: sv.name })} size="sm" tone="danger" onClick={() => setDeleting(sv)}>
                    <Trash className="size-4" />
                  </IconButton>
                </div>
                {result !== undefined &&
                  (typeof result === 'string' ? (
                    <p className="mt-2 text-sm text-danger">{result}</p>
                  ) : (
                    <div className="mt-2 flex flex-wrap gap-1.5 pl-8">
                      {result.length === 0 ? <span className="text-sm text-muted">{t('settings.noTools')}</span> : result.map((x) => <Tag key={x}>{x}</Tag>)}
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
          {t('settings.addConnection')}
        </Button>
      )}
      <ConfirmDialog
        open={!!deleting}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting)}
        loading={remove.isPending}
        title={t('settings.deleteConnTitle', { name: deleting?.name ?? t('settings.theConnection') })}
        description={t('settings.deleteConnBody')}
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
      toast({ title: t('settings.connectionAdded'), tone: 'success' })
      onDone()
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('places.err.save')),
  })
  return (
    <form
      className="mt-3 grid gap-3 rounded-control border border-line bg-bg p-3"
      onSubmit={(e) => {
        e.preventDefault()
        if (!name.trim()) return setError(t('settings.err.needName'))
        if (!/^https?:\/\//.test(url.trim())) return setError(t('settings.err.badUrl'))
        setError(null)
        save.mutate()
      }}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={t('common.name')}>{(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} placeholder={t('settings.mcpNamePlaceholder')} autoFocus />}</Field>
        <Field label={t('settings.url')}>{(id) => <Input id={id} value={url} onChange={(e) => setUrl(e.target.value)} placeholder={t('settings.mcpUrlPlaceholder')} className="font-mono text-sm" />}</Field>
      </div>
      <Field label={t('settings.headers')} optional hint={t('settings.headersHint')}>
        {(id, d) => <Textarea id={id} value={headers} onChange={(e) => setHeaders(e.target.value)} rows={2} className="min-h-0 font-mono text-sm" aria-describedby={d} />}
      </Field>
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="primary" loading={save.isPending}>
          {t('common.save')}
        </Button>
      </div>
    </form>
  )
}

/* ---------------------------------------------------------------- Devices */

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
      toast({ title: t('settings.accessRevoked') })
    },
    onError: (e) => toastError(e, t('family.err.revoke')),
  })
  const icon = (p: string) => (p === 'android' ? <DeviceMobile className="size-5" /> : p === 'wear' ? <Watch className="size-5" /> : <Browser className="size-5" />)
  const target = devices.data?.find((d) => d.id === revoking)
  return (
    <Section id="devices" title={t('settings.sec.devices')} description={t('settings.devicesHint')}>
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
                  <span className="truncate">{d.name || t('settings.device')}</span>
                  {d.current && <Tag tone="accent">{t('settings.thisDevice')}</Tag>}
                </p>
                <p className="text-sm text-muted">
                  {t('settings.deviceActive', { when: relative(d.lastSeenAt), date: shortDate(d.createdAt) })}
                </p>
              </div>
              {!d.current && (
                <Button size="sm" variant="ghost" className="text-danger hover:text-danger" onClick={() => setRevoking(d.id)}>
                  {t('family.revoke')}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {!showAll && (devices.data?.length ?? 0) > 6 && (
        <Button size="sm" variant="ghost" className="mt-2" onClick={() => setShowAll(true)}>
          {t('settings.seeAllDevices', { count: devices.data!.length })}
        </Button>
      )}
      <ConfirmDialog
        open={!!revoking}
        onClose={() => setRevoking(null)}
        onConfirm={() => revoking && revoke.mutate(revoking)}
        loading={revoke.isPending}
        title={t('settings.revokeTitle', { name: target?.name ?? t('settings.thisDeviceLower') })}
        description={t('settings.revokeBody')}
        confirmLabel={t('family.revoke')}
      />
    </Section>
  )
}

/* ---------------------------------------------------------------- Security */

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
      toast({ title: t('settings.passwordChanged'), tone: 'success' })
    },
    onError: (e) => setErrors({ form: e instanceof Error ? e.message : t('settings.err.password') }),
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const errs: typeof errors = {}
    if (!current) errs.current = t('settings.err.needCurrent')
    if (next.length < 10) errs.next = t('join.minChars', { min: 10 })
    if (confirm !== next) errs.confirm = t('settings.err.noMatch')
    setErrors(errs)
    if (Object.keys(errs).length === 0) change.mutate()
  }
  return (
    <Section id="security" title={t('settings.sec.security')}>
      <form onSubmit={submit} className="grid gap-4 sm:grid-cols-3" noValidate>
        <Field label={t('settings.currentPassword')} error={errors.current}>
          {(id, d) => <Input id={id} type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} aria-invalid={!!errors.current} aria-describedby={d} />}
        </Field>
        <Field label={t('settings.newPassword')} error={errors.next} hint={t('join.passwordHint', { min: 10 })}>
          {(id, d) => <Input id={id} type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} aria-invalid={!!errors.next} aria-describedby={d} />}
        </Field>
        <Field label={t('settings.repeatNew')} error={errors.confirm}>
          {(id, d) => <Input id={id} type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} aria-invalid={!!errors.confirm} aria-describedby={d} />}
        </Field>
        {errors.form && (
          <div className="sm:col-span-3">
            <FormError>{errors.form}</FormError>
          </div>
        )}
        <div className="flex flex-wrap gap-2 sm:col-span-3">
          <Button type="submit" variant="primary" loading={change.isPending}>
            {t('settings.changePassword')}
          </Button>
          <Button variant="ghost" className={cn('text-danger hover:text-danger', 'ml-auto')} icon={<SignOut className="size-4" />} onClick={() => void logout()}>
            {t('app.signOut')}
          </Button>
        </div>
      </form>
    </Section>
  )
}

/* ---------------------------------------------------------------- Data */

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
      toast({ title: t('settings.exportReady'), description: t('settings.exportReadyBody'), tone: 'success' })
    },
    onError: (e) => toastError(e, t('settings.err.export')),
  })
  return (
    <Section id="data" title={t('settings.sec.data')} description={t('settings.dataHint')}>
      <Button icon={<DownloadSimple className="size-4" />} onClick={() => exp.mutate()} loading={exp.isPending}>
        {t('settings.exportJson')}
      </Button>
    </Section>
  )
}
