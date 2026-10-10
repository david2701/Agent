import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { BellRinging, UsersThree } from '@phosphor-icons/react'
import { createElement, useEffect, useSyncExternalStore } from 'react'
import { toast } from '@/components/ui/toast'
import { api, getAccessToken, refreshSession } from './api'
import { entityHref } from './entities'
import { firstName } from './family'
import { t } from './i18n'
import { invalidateEntity, keys } from './queries'
import type { FamilyInfo, NoticesResponse, ServerEvent } from './types'

/* Real-time connection state (for a discreet notice if it drops). */
type ConnState = 'connecting' | 'open' | 'offline'
let connState: ConnState = 'connecting'
const connSubs = new Set<() => void>()
function setConn(s: ConnState) {
  connState = s
  connSubs.forEach((f) => f())
}
export function useRealtimeState() {
  return useSyncExternalStore(
    (cb) => {
      connSubs.add(cb)
      return () => connSubs.delete(cb)
    },
    () => connState,
  )
}

/* Browser notifications */
const ASKED_KEY = 'kairo-notif-asked'

export function notificationsSupported() {
  return typeof window !== 'undefined' && 'Notification' in window
}

export async function requestNotifications(): Promise<NotificationPermission | 'unsupported'> {
  if (!notificationsSupported()) return 'unsupported'
  try {
    localStorage.setItem(ASKED_KEY, '1')
  } catch {
    /* no storage */
  }
  return Notification.requestPermission()
}

function alreadyAsked() {
  try {
    return localStorage.getItem(ASKED_KEY) === '1'
  } catch {
    return true
  }
}

/** System notification only when the tab is not visible (when it is, the on-screen notice is enough). */
function systemNotification(body: string, tag: string, onClick?: () => void, title = 'Kairo') {
  if (!notificationsSupported() || Notification.permission !== 'granted' || document.visibilityState === 'visible') return
  try {
    const n = new Notification(title, { body, tag, icon: '/favicon.svg' })
    n.onclick = () => {
      window.focus()
      onClick?.()
      n.close()
    }
  } catch {
    /* some browsers only allow notifications from a service worker */
  }
}

function memberName(qc: QueryClient, id: string | null | undefined) {
  if (!id) return null
  const m = qc.getQueryData<FamilyInfo>(keys.family)?.members.find((x) => x.id === id)
  return m ? firstName(m.displayName) : null
}

const seenFires = new Set<string>()

function onReminderFire(qc: QueryClient, data: { id: string; text: string; fireAt: string; recurring: boolean; createdBy?: string | null }) {
  const key = `${data.id}|${data.fireAt}`
  if (seenFires.has(key)) return
  seenFires.add(key)
  void qc.invalidateQueries({ queryKey: keys.reminders })
  void qc.invalidateQueries({ queryKey: keys.today })

  const actions = [
    {
      label: t('realtime.done'),
      onClick: async () => {
        await api.post(`/reminders/${data.id}/done`)
        invalidateEntity(qc, 'reminder')
      },
    },
    {
      label: t('realtime.snooze10'),
      onClick: async () => {
        await api.post(`/reminders/${data.id}/snooze`, { minutes: 10 })
        invalidateEntity(qc, 'reminder')
      },
    },
  ]
  const canAsk = notificationsSupported() && Notification.permission === 'default' && !alreadyAsked()
  if (canAsk) {
    actions.push({
      label: t('realtime.enableAlerts'),
      onClick: async () => {
        await requestNotifications()
      },
    })
  }
  const from = data.createdBy ? memberName(qc, data.createdBy) : null
  toast({
    id: `rem-${key}`,
    title: data.text,
    description: [data.recurring ? t('realtime.reminderRepeating') : t('realtime.reminder'), from && t('realtime.fromName', { name: from })]
      .filter(Boolean)
      .join(' '),
    actions,
    duration: 0,
    icon: createElement(BellRinging, { className: 'size-5 text-accent-strong' }),
  })

  systemNotification(data.text, key)
}

type NoticeEvent = Extract<ServerEvent, { type: 'family.notice' }>['data']
const seenNotices = new Set<string>()

/** Family notice: an on-screen card, a system notification if the tab is hidden, and a refresh of Updates. */
function onFamilyNotice(qc: QueryClient, data: NoticeEvent, navigate?: (to: string) => void) {
  if (seenNotices.has(data.id)) return
  seenNotices.add(data.id)
  // Optimistic count while the new list arrives (the bar indicator reacts instantly).
  const cached = qc.getQueryData<NoticesResponse>(keys.notices)
  if (cached && !cached.notices.some((n) => n.id === data.id)) {
    qc.setQueryData<NoticesResponse>(keys.notices, {
      unread: cached.unread + 1,
      notices: [
        {
          id: data.id,
          kind: data.kind,
          title: data.title,
          body: data.body,
          actorId: data.actorId ?? null,
          entityType: data.entityType ?? null,
          entityId: data.entityId ?? null,
          createdAt: data.createdAt,
          readAt: null,
        },
        ...cached.notices,
      ],
    })
  }
  void qc.invalidateQueries({ queryKey: keys.notices })

  const href = data.entityType && data.entityId ? (data.entityType === 'family' ? '/family' : entityHref(data.entityType, data.entityId)) : '/family'
  const open = () => {
    void api
      .post('/notices/read', { ids: [data.id] })
      .then(() => qc.invalidateQueries({ queryKey: keys.notices }))
      .catch(() => undefined)
    if (href) navigate?.(href)
  }
  toast({
    id: `notice-${data.id}`,
    title: data.title,
    description: data.body || undefined,
    actions: navigate && href ? [{ label: t('realtime.view'), onClick: open }] : undefined,
    icon: createElement(UsersThree, { className: 'size-5 text-muted' }),
  })
  systemNotification(data.body || data.title, `notice-${data.id}`, open, data.body ? data.title : 'Kairo')
}

/** WebSocket /api/ws with exponential reconnection. */
export function useRealtime(enabled: boolean, onBriefing?: () => void, onNavigate?: (to: string) => void) {
  const qc = useQueryClient()

  useEffect(() => {
    if (!enabled) return
    let ws: WebSocket | null = null
    let stopped = false
    let attempt = 0
    let timer: ReturnType<typeof setTimeout> | null = null
    let opened = false

    const handle = (msg: ServerEvent) => {
      switch (msg.type) {
        case 'entity.changed':
          invalidateEntity(qc, msg.data.entity)
          break
        case 'reminder.fire':
          onReminderFire(qc, msg.data)
          break
        case 'family.notice':
          onFamilyNotice(qc, msg.data, onNavigate)
          break
        case 'briefing.ready':
          qc.setQueryData(keys.briefing, msg.data)
          toast({
            id: 'briefing',
            title: t('realtime.briefingReady'),
            actions: onBriefing ? [{ label: t('realtime.view'), onClick: onBriefing }] : undefined,
          })
          break
        case 'recording.updated':
          void qc.invalidateQueries({ queryKey: keys.recordings })
          void qc.invalidateQueries({ queryKey: keys.recording(msg.data.id) })
          void qc.invalidateQueries({ queryKey: ['notes'] })
          break
        default:
          break
      }
    }

    const schedule = () => {
      if (stopped) return
      attempt++
      const base = Math.min(30_000, 1000 * 2 ** Math.min(attempt - 1, 5))
      const delay = base * (0.75 + Math.random() * 0.5)
      if (attempt > 1) setConn('offline')
      timer = setTimeout(connect, delay)
    }

    const connect = async () => {
      if (stopped) return
      setConn(attempt === 0 ? 'connecting' : connState)
      let token = getAccessToken()
      // If the previous connection never opened, the token may have expired.
      if (!token || (attempt > 0 && !opened)) token = (await refreshSession())?.accessToken ?? null
      if (!token || stopped) {
        if (!stopped) schedule()
        return
      }
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
      opened = false
      const sock = new WebSocket(`${proto}://${window.location.host}/api/ws?access_token=${encodeURIComponent(token)}`)
      ws = sock
      sock.onopen = () => {
        opened = true
        if (attempt > 0) void qc.invalidateQueries() // catch up on what happened while offline
        attempt = 0
        setConn('open')
      }
      sock.onmessage = (m) => {
        try {
          handle(JSON.parse(String(m.data)) as ServerEvent)
        } catch {
          /* unknown message */
        }
      }
      sock.onclose = (ev) => {
        if (ws === sock) ws = null
        // 1008: the server closed because the token expired; the next attempt renews it first
        if (ev.code === 1008) opened = false
        schedule()
      }
    }

    const onOnline = () => {
      if (!ws) {
        if (timer) clearTimeout(timer)
        attempt = 0
        void connect()
      }
    }

    void connect()
    window.addEventListener('online', onOnline)
    return () => {
      stopped = true
      window.removeEventListener('online', onOnline)
      if (timer) clearTimeout(timer)
      ws?.close()
      ws = null
    }
  }, [enabled, qc, onBriefing, onNavigate])
}
