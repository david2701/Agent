import { api } from './api'

/** Utilidades de audio del navegador: grabar el micrófono, medir nivel y reproducir la voz de Kairo. */

let ctx: AudioContext | null = null

export function audioContext(): AudioContext | null {
  if (typeof window === 'undefined' || !('AudioContext' in window)) return null
  if (!ctx) ctx = new AudioContext()
  return ctx
}

/** Llamar dentro de un gesto del usuario para permitir audio posterior. */
export function unlockAudio() {
  const c = audioContext()
  if (c && c.state === 'suspended') void c.resume()
}

export function micSupported() {
  return typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof MediaRecorder !== 'undefined'
}

export function pickMime(): { mimeType: string; ext: string } {
  const candidates: [string, string][] = [
    ['audio/webm;codecs=opus', 'webm'],
    ['audio/webm', 'webm'],
    ['audio/mp4', 'm4a'],
    ['audio/ogg;codecs=opus', 'ogg'],
  ]
  for (const [m, ext] of candidates) {
    if (typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(m)) return { mimeType: m, ext }
  }
  return { mimeType: '', ext: 'webm' }
}

function rmsLevel(analyser: AnalyserNode, buf: Uint8Array<ArrayBuffer>) {
  analyser.getByteTimeDomainData(buf)
  let sum = 0
  for (let i = 0; i < buf.length; i++) {
    const v = (buf[i] - 128) / 128
    sum += v * v
  }
  const rms = Math.sqrt(sum / buf.length)
  return Math.min(1, rms * 4.5)
}

export function micErrorMessage(e: unknown): string {
  const name = (e as { name?: string })?.name
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'Permite el acceso al micrófono en tu navegador para hablar con Kairo.'
  if (name === 'NotFoundError') return 'No encontré un micrófono conectado.'
  if (name === 'NotReadableError') return 'Otra aplicación está usando el micrófono.'
  return 'No pude iniciar el micrófono.'
}

/** Grabación corta (un turno de voz). */
export class MicRecorder {
  private stream: MediaStream | null = null
  private recorder: MediaRecorder | null = null
  private chunks: Blob[] = []
  private analyser: AnalyserNode | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private buf: Uint8Array<ArrayBuffer> | null = null
  readonly mime = pickMime()
  startedAt = 0

  async start() {
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    })
    const c = audioContext()
    if (c) {
      if (c.state === 'suspended') await c.resume().catch(() => undefined)
      this.source = c.createMediaStreamSource(this.stream)
      this.analyser = c.createAnalyser()
      this.analyser.fftSize = 1024
      this.buf = new Uint8Array(new ArrayBuffer(this.analyser.fftSize))
      this.source.connect(this.analyser)
    }
    this.chunks = []
    this.recorder = new MediaRecorder(this.stream, this.mime.mimeType ? { mimeType: this.mime.mimeType } : undefined)
    this.recorder.ondataavailable = (e) => {
      if (e.data.size > 0) this.chunks.push(e.data)
    }
    this.recorder.start(250)
    this.startedAt = performance.now()
  }

  level = () => (this.analyser && this.buf ? rmsLevel(this.analyser, this.buf) : 0)

  elapsed() {
    return this.startedAt ? (performance.now() - this.startedAt) / 1000 : 0
  }

  stop(): Promise<Blob> {
    return new Promise((resolve) => {
      const r = this.recorder
      if (!r || r.state === 'inactive') {
        this.release()
        resolve(new Blob(this.chunks, { type: this.mime.mimeType || 'audio/webm' }))
        return
      }
      r.onstop = () => {
        const blob = new Blob(this.chunks, { type: r.mimeType || this.mime.mimeType || 'audio/webm' })
        this.release()
        resolve(blob)
      }
      r.stop()
    })
  }

  cancel() {
    try {
      if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop()
    } catch {
      /* ya detenido */
    }
    this.release()
  }

  private release() {
    this.stream?.getTracks().forEach((t) => t.stop())
    this.source?.disconnect()
    this.stream = null
    this.source = null
    this.analyser = null
  }
}

/**
 * Grabación larga por segmentos: reinicia el MediaRecorder cada `segmentMs` para que cada
 * segmento sea un archivo completo, y entrega cada uno a `onSegment`.
 */
export class SegmentRecorder {
  private stream: MediaStream | null = null
  private recorder: MediaRecorder | null = null
  private chunks: Blob[] = []
  private timer: ReturnType<typeof setTimeout> | null = null
  private analyser: AnalyserNode | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private buf: Uint8Array<ArrayBuffer> | null = null
  private segStart = 0
  private seq = 0
  private stopping = false
  private finalResolve: (() => void) | null = null
  readonly mime = pickMime()
  startedAt = 0

  constructor(
    private onSegment: (blob: Blob, seq: number, durationS: number, ext: string) => Promise<void> | void,
    private segmentMs = 5 * 60_000,
  ) {}

  async start() {
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
    const c = audioContext()
    if (c) {
      if (c.state === 'suspended') await c.resume().catch(() => undefined)
      this.source = c.createMediaStreamSource(this.stream)
      this.analyser = c.createAnalyser()
      this.analyser.fftSize = 1024
      this.buf = new Uint8Array(new ArrayBuffer(this.analyser.fftSize))
      this.source.connect(this.analyser)
    }
    this.startedAt = performance.now()
    this.startSegment()
  }

  level = () => (this.analyser && this.buf ? rmsLevel(this.analyser, this.buf) : 0)

  elapsed() {
    return this.startedAt ? (performance.now() - this.startedAt) / 1000 : 0
  }

  get segments() {
    return this.seq
  }

  private startSegment() {
    if (!this.stream) return
    this.chunks = []
    const r = new MediaRecorder(this.stream, this.mime.mimeType ? { mimeType: this.mime.mimeType } : undefined)
    this.recorder = r
    r.ondataavailable = (e) => {
      if (e.data.size > 0) this.chunks.push(e.data)
    }
    r.onstop = () => {
      const blob = new Blob(this.chunks, { type: r.mimeType || 'audio/webm' })
      const dur = (performance.now() - this.segStart) / 1000
      const seq = this.seq++
      void Promise.resolve(this.onSegment(blob, seq, Math.round(dur), this.mime.ext)).finally(() => {
        if (this.stopping) {
          this.release()
          this.finalResolve?.()
        }
      })
      if (!this.stopping) this.startSegment()
    }
    this.segStart = performance.now()
    r.start(1000)
    this.timer = setTimeout(() => {
      if (r.state !== 'inactive') r.stop()
    }, this.segmentMs)
  }

  /** Detiene y espera a que el último segmento se entregue. */
  stop(): Promise<void> {
    this.stopping = true
    if (this.timer) clearTimeout(this.timer)
    return new Promise((resolve) => {
      this.finalResolve = resolve
      if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop()
      else {
        this.release()
        resolve()
      }
    })
  }

  cancel() {
    this.stopping = true
    if (this.timer) clearTimeout(this.timer)
    this.onSegment = () => undefined
    try {
      if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop()
    } catch {
      /* nada */
    }
    this.release()
  }

  private release() {
    this.stream?.getTracks().forEach((t) => t.stop())
    this.source?.disconnect()
    this.stream = null
  }
}

export interface Speech {
  level: () => number
  stop: () => void
  done: Promise<void>
}

let current: Speech | null = null

/** Pide el audio a /voice/speak y lo reproduce. Devuelve un control con nivel de salida. */
export async function speak(text: string, opts: { voice?: string; speed?: number } = {}): Promise<Speech> {
  current?.stop()
  const blob = await api.blob('/voice/speak', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: text.slice(0, 4000), voice: opts.voice, speed: opts.speed }),
  })
  const url = URL.createObjectURL(blob)
  const audio = new Audio(url)
  let analyser: AnalyserNode | null = null
  let buf: Uint8Array<ArrayBuffer> | null = null
  const c = audioContext()
  // Solo se enruta por el AudioContext si está activo; si no, el audio sonaría mudo.
  if (c && c.state === 'running') {
    try {
      const src = c.createMediaElementSource(audio)
      analyser = c.createAnalyser()
      analyser.fftSize = 1024
      buf = new Uint8Array(new ArrayBuffer(analyser.fftSize))
      src.connect(analyser)
      analyser.connect(c.destination)
    } catch {
      analyser = null
    }
  }
  let resolveDone: () => void = () => undefined
  const done = new Promise<void>((r) => {
    resolveDone = r
  })
  const finish = () => {
    URL.revokeObjectURL(url)
    resolveDone()
    if (current === speech) current = null
  }
  audio.onended = finish
  audio.onerror = finish
  const t0 = performance.now()
  const speech: Speech = {
    level: () => {
      if (analyser && buf) return rmsLevel(analyser, buf) * 1.2
      // Sin analizador: una ondulación suave para el orbe
      const t = (performance.now() - t0) / 1000
      return audio.paused ? 0 : 0.35 + 0.25 * Math.sin(t * 9) * Math.sin(t * 2.3)
    },
    stop: () => {
      audio.pause()
      finish()
    },
    done,
  }
  current = speech
  try {
    await audio.play()
  } catch {
    finish()
    throw new Error('El navegador bloqueó la reproducción. Toca el botón para escuchar.')
  }
  return speech
}

export function stopSpeaking() {
  current?.stop()
}
