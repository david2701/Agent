import { useSyncExternalStore } from 'react'
import { api } from './api'
import { micErrorMessage, micSupported, SegmentRecorder, unlockAudio } from './audio'
import type { Recording } from './types'

/**
 * Grabación larga desde el navegador: segmentos de ~5 min que se suben a
 * /recordings/{id}/segments y al final /finish. Vive fuera de React para que
 * siga grabando aunque cambies de página.
 */

export interface SessionState {
  status: 'idle' | 'starting' | 'recording' | 'stopping' | 'error'
  recordingId: string | null
  uploaded: number
  failed: number
  error: string | null
}

let state: SessionState = { status: 'idle', recordingId: null, uploaded: 0, failed: 0, error: null }
let recorder: SegmentRecorder | null = null
const subs = new Set<() => void>()

function setState(patch: Partial<SessionState>) {
  state = { ...state, ...patch }
  subs.forEach((f) => f())
}

export function useRecordingSession() {
  return useSyncExternalStore(
    (cb) => {
      subs.add(cb)
      return () => subs.delete(cb)
    },
    () => state,
  )
}

export function sessionLevel() {
  return recorder?.level() ?? 0
}

export function sessionElapsed() {
  return recorder?.elapsed() ?? 0
}

const onBeforeUnload = (e: BeforeUnloadEvent) => {
  e.preventDefault()
}

async function upload(id: string, blob: Blob, seq: number, duration: number, ext: string) {
  const send = () => {
    const fd = new FormData()
    fd.append('file', blob, `segmento-${seq}.${ext}`)
    fd.append('seq', String(seq))
    fd.append('duration', String(duration))
    return api.post(`/recordings/${id}/segments`, fd)
  }
  try {
    await send()
  } catch {
    await new Promise((r) => setTimeout(r, 1500))
    await send()
  }
}

export async function startRecording(segmentMs = 5 * 60_000): Promise<void> {
  if (state.status === 'recording' || state.status === 'starting') return
  if (!micSupported()) {
    setState({ status: 'error', error: 'Este navegador no permite grabar audio.' })
    return
  }
  unlockAudio()
  setState({ status: 'starting', error: null, uploaded: 0, failed: 0 })
  let rec: Recording
  try {
    rec = await api.post<Recording>('/recordings', { title: '' })
  } catch (e) {
    setState({ status: 'error', error: e instanceof Error ? e.message : 'No se pudo crear la grabación.' })
    return
  }
  const id = rec.id
  recorder = new SegmentRecorder(async (blob, seq, dur, ext) => {
    try {
      await upload(id, blob, seq, dur, ext)
      setState({ uploaded: state.uploaded + 1 })
    } catch {
      setState({ failed: state.failed + 1 })
    }
  }, segmentMs)
  try {
    await recorder.start()
  } catch (e) {
    recorder = null
    await api.del(`/recordings/${id}`).catch(() => undefined)
    setState({ status: 'error', recordingId: null, error: micErrorMessage(e) })
    return
  }
  window.addEventListener('beforeunload', onBeforeUnload)
  setState({ status: 'recording', recordingId: id })
}

/** Detiene, sube lo pendiente y pide el procesamiento. Devuelve el id. */
export async function finishRecording(title: string): Promise<string | null> {
  const id = state.recordingId
  if (!recorder || !id) return null
  setState({ status: 'stopping' })
  await recorder.stop()
  recorder = null
  window.removeEventListener('beforeunload', onBeforeUnload)
  try {
    await api.post(`/recordings/${id}/finish`, title.trim() ? { title: title.trim() } : {})
  } catch (e) {
    setState({ status: 'error', recordingId: null, error: e instanceof Error ? e.message : 'No se pudo procesar la grabación.' })
    return id
  }
  setState({ status: 'idle', recordingId: null })
  return id
}

export async function discardRecording() {
  const id = state.recordingId
  recorder?.cancel()
  recorder = null
  window.removeEventListener('beforeunload', onBeforeUnload)
  setState({ status: 'idle', recordingId: null, error: null })
  if (id) await api.del(`/recordings/${id}`).catch(() => undefined)
}

export function clearSessionError() {
  setState({ status: 'idle', error: null })
}
