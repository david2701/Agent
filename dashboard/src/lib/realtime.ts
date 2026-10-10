import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { BellRinging } from '@phosphor-icons/react'
import { createElement, useEffect, useSyncExternalStore } from 'react'
import { toast } from '@/components/ui/toast'
import { api, getAccessToken, refreshSession } from './api'
import { invalidateEntity, keys } from './queries'
import type { ServerEvent } from './types'

/* Estado de la conexión en tiempo real (para un aviso discreto si se cae). */
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

/* Notificaciones del navegador */
const ASKED_KEY = 'kairo-notif-asked'

export function notificationsSupported() {
  return typeof window !== 'undefined' && 'Notification' in window
}

export async function requestNotifications(): Promise<NotificationPermission | 'unsupported'> {
  if (!notificationsSupported()) return 'unsupported'
  try {
    localStorage.setItem(ASKED_KEY, '1')
  } catch {
    /* nada */
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

const seenFires = new Set<string>()

function onReminderFire(qc: QueryClient, data: { id: string; text: string; fireAt: string; recurring: boolean }) {
  const key = `${data.id}|${data.fireAt}`
  if (seenFires.has(key)) return
  seenFires.add(key)
  void qc.invalidateQueries({ queryKey: keys.reminders })
  void qc.invalidateQueries({ queryKey: keys.today })

  const actions = [
    {
      label: 'Hecho',
      onClick: async () => {
        await api.post(`/reminders/${data.id}/done`)
        invalidateEntity(qc, 'reminder')
      },
    },
    {
      label: 'Posponer 10 min',
      onClick: async () => {
        await api.post(`/reminders/${data.id}/snooze`, { minutes: 10 })
        invalidateEntity(qc, 'reminder')
      },
    },
  ]
  const canAsk = notificationsSupported() && Notification.permission === 'default' && !alreadyAsked()
  if (canAsk) {
    actions.push({
      label: 'Activar avisos',
      onClick: async () => {
        await requestNotifications()
      },
    })
  }
  toast({
    id: `rem-${key}`,
    title: data.text,
    description: data.recurring ? 'Recordatorio que se repite' : 'Recordatorio',
    actions,
    duration: 0,
    icon: createElement(BellRinging, { className: 'size-5 text-accent-strong' }),
  })

  if (notificationsSupported() && Notification.permission === 'granted' && document.visibilityState !== 'visible') {
    try {
      const n = new Notification('Kairo', { body: data.text, tag: key, icon: '/favicon.svg' })
      n.onclick = () => {
        window.focus()
        n.close()
      }
    } catch {
      /* algunos navegadores solo permiten notificaciones desde un service worker */
    }
  }
}

/** WebSocket /api/ws con reconexión exponencial. */
export function useRealtime(enabled: boolean, onBriefing?: () => void) {
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
        case 'briefing.ready':
          qc.setQueryData(keys.briefing, msg.data)
          toast({
            id: 'briefing',
            title: 'Tu resumen del día está listo',
            actions: onBriefing ? [{ label: 'Ver', onClick: onBriefing }] : undefined,
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
      // Si la conexión anterior nunca abrió, puede ser un token vencido.
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
        if (attempt > 0) void qc.invalidateQueries() // recupera lo que pasó sin conexión
        attempt = 0
        setConn('open')
      }
      sock.onmessage = (m) => {
        try {
          handle(JSON.parse(String(m.data)) as ServerEvent)
        } catch {
          /* mensaje desconocido */
        }
      }
      sock.onclose = () => {
        if (ws === sock) ws = null
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
  }, [enabled, qc, onBriefing])
}
