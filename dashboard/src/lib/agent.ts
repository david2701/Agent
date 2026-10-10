import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, streamAgent } from './api'
import { t } from './i18n'
import { invalidateEntity, keys } from './queries'
import type { AgentEvent, DeviceAction, EntityRef, FileRef } from './types'

export interface TurnTool {
  callId: string
  name: string
  label: string
  status: 'running' | 'done'
  ok?: boolean
  summary?: string
  entity?: EntityRef | null
}

export interface Turn {
  id: string
  channel: 'chat' | 'voice'
  status: 'transcribing' | 'streaming' | 'done' | 'error'
  userText: string | null
  text: string
  step: number
  tools: TurnTool[]
  attachments: FileRef[]
  deviceActions: DeviceAction[]
  conversationId?: string
  messageId?: string
  spoken?: string
  error?: string
}

export function applyEvent(t: Turn, ev: AgentEvent): Turn {
  switch (ev.type) {
    case 'start':
      return { ...t, conversationId: ev.conversationId }
    case 'transcript':
      return { ...t, userText: ev.text, status: 'streaming' }
    case 'delta':
      // Each new step replaces the previous text (the preamble before using tools).
      if (ev.step !== t.step) return { ...t, step: ev.step, text: ev.text, status: 'streaming' }
      return { ...t, text: t.text + ev.text, status: 'streaming' }
    case 'tool_start':
      return {
        ...t,
        tools: [...t.tools.filter((x) => x.callId !== ev.callId), { callId: ev.callId, name: ev.name, label: ev.label, status: 'running' }],
      }
    case 'tool_end':
      return {
        ...t,
        tools: t.tools.some((x) => x.callId === ev.callId)
          ? t.tools.map((x) =>
              x.callId === ev.callId ? { ...x, status: 'done', ok: ev.ok, summary: ev.summary, entity: ev.entity } : x,
            )
          : [...t.tools, { callId: ev.callId, name: ev.name, label: ev.summary, status: 'done', ok: ev.ok, summary: ev.summary, entity: ev.entity }],
      }
    case 'device_action':
      return { ...t, deviceActions: [...t.deviceActions, ev.action] }
    case 'attachment':
      return { ...t, attachments: [...t.attachments, ev.file] }
    case 'done':
      return { ...t, status: 'done', text: ev.text, spoken: ev.spoken, conversationId: ev.conversationId, messageId: ev.messageId }
    case 'error':
      return { ...t, status: 'error', error: ev.message }
  }
}

export function newTurn(channel: 'chat' | 'voice', userText: string | null): Turn {
  return {
    id: `turn-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    channel,
    status: userText === null ? 'transcribing' : 'streaming',
    userText,
    text: '',
    step: -1,
    tools: [],
    attachments: [],
    deviceActions: [],
  }
}

interface RunOptions {
  onStart?: (conversationId: string) => void
  onDone?: (turn: Turn) => void | Promise<void>
}

/** Runs agent turns (text or voice) and exposes the state of the turn in progress. */
export function useAgentRunner() {
  const qc = useQueryClient()
  const [turn, setTurn] = useState<Turn | null>(null)
  const abort = useRef<AbortController | null>(null)
  const latest = useRef<Turn | null>(null)

  // Aborts on a real unmount (StrictMode's double mount does not cut the stream).
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      setTimeout(() => {
        if (!mounted.current) abort.current?.abort()
      }, 0)
    }
  }, [])

  const run = useCallback(
    async (endpoint: '/chat' | '/voice/turn' | '/capture', body: Record<string, unknown> | FormData, initial: Turn, opts: RunOptions = {}) => {
      abort.current?.abort()
      const ctrl = new AbortController()
      abort.current = ctrl
      latest.current = initial
      setTurn(initial)
      const update = (ev: AgentEvent) => {
        const next = applyEvent(latest.current ?? initial, ev)
        latest.current = next
        setTurn(next)
        if (ev.type === 'start') opts.onStart?.(ev.conversationId)
        if (ev.type === 'tool_end' && ev.entity) invalidateEntity(qc, ev.entity.type)
      }
      try {
        await streamAgent(endpoint, body, update, ctrl.signal)
        const final = latest.current
        if (final && final.status !== 'done' && final.status !== 'error') {
          const broken: Turn = { ...final, status: 'error', error: t('agent.cutOff') }
          latest.current = broken
          setTurn(broken)
        }
      } catch (e) {
        if (ctrl.signal.aborted) return
        const msg = e instanceof ApiError || e instanceof Error ? e.message : t('agent.cantReach')
        const broken: Turn = { ...(latest.current ?? initial), status: 'error', error: msg }
        latest.current = broken
        setTurn(broken)
      }
      const final = latest.current
      void qc.invalidateQueries({ queryKey: keys.conversations })
      if (final) await opts.onDone?.(final)
    },
    [qc],
  )

  const cancel = useCallback(() => {
    abort.current?.abort()
    abort.current = null
  }, [])

  const reset = useCallback(() => {
    latest.current = null
    setTurn(null)
  }, [])

  const busy = !!turn && (turn.status === 'streaming' || turn.status === 'transcribing')
  return { turn, run, cancel, reset, busy, setTurn }
}
