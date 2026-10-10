import { useCallback, useEffect, useRef, useState } from 'react'
import { MicRecorder, micErrorMessage, micSupported, speak, unlockAudio, type Speech } from './audio'

export type VoicePhase = 'off' | 'listening' | 'thinking' | 'speaking'

/**
 * Sesión de voz: graba un turno (con corte automático al detectar silencio),
 * entrega el audio y puede reproducir la respuesta.
 */
export function useVoice(onAudio: (blob: Blob, ext: string) => void) {
  const [phase, setPhase] = useState<VoicePhase>('off')
  const [error, setError] = useState<string | null>(null)
  const rec = useRef<MicRecorder | null>(null)
  const speech = useRef<Speech | null>(null)
  const vad = useRef<ReturnType<typeof setInterval> | null>(null)
  const onAudioRef = useRef(onAudio)
  onAudioRef.current = onAudio

  const clearVad = () => {
    if (vad.current) clearInterval(vad.current)
    vad.current = null
  }

  const finish = useCallback(async () => {
    const r = rec.current
    if (!r) return
    clearVad()
    rec.current = null
    const elapsed = r.elapsed()
    const blob = await r.stop()
    if (elapsed < 0.6 || blob.size < 1500) {
      setPhase('off')
      setError('No alcancé a escucharte. Mantén el micrófono un poco más.')
      return
    }
    setPhase('thinking')
    onAudioRef.current(blob, r.mime.ext)
  }, [])

  const start = useCallback(async () => {
    setError(null)
    if (!micSupported()) {
      setError('Este navegador no permite grabar audio.')
      return
    }
    unlockAudio()
    speech.current?.stop()
    const r = new MicRecorder()
    try {
      await r.start()
    } catch (e) {
      setError(micErrorMessage(e))
      setPhase('off')
      return
    }
    rec.current = r
    setPhase('listening')
    // Detección simple de voz: tras hablar, 1,6 s de silencio cortan la grabación.
    let heard = false
    let quietSince = 0
    vad.current = setInterval(() => {
      const lvl = r.level()
      const now = performance.now()
      if (lvl > 0.12) {
        heard = true
        quietSince = 0
      } else if (heard && lvl < 0.06) {
        if (!quietSince) quietSince = now
        else if (now - quietSince > 1600) void finish()
      }
      if (r.elapsed() > 90) void finish()
    }, 100)
  }, [finish])

  const cancel = useCallback(() => {
    clearVad()
    rec.current?.cancel()
    rec.current = null
    speech.current?.stop()
    speech.current = null
    setPhase('off')
  }, [])

  const say = useCallback(async (text: string, opts: { voice?: string; speed?: number }) => {
    if (!text.trim()) {
      setPhase('off')
      return
    }
    try {
      setPhase('speaking')
      const s = await speak(text, opts)
      speech.current = s
      await s.done
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No pude reproducir la respuesta.')
    } finally {
      speech.current = null
      setPhase((p) => (p === 'speaking' ? 'off' : p))
    }
  }, [])

  const stopSpeaking = useCallback(() => {
    speech.current?.stop()
    speech.current = null
    setPhase('off')
  }, [])

  const level = useCallback(() => {
    if (rec.current) return rec.current.level()
    if (speech.current) return speech.current.level()
    return 0
  }, [])

  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      setTimeout(() => {
        if (mounted.current) return
        clearVad()
        rec.current?.cancel()
        speech.current?.stop()
      }, 0)
    }
  }, [])

  return { phase, setPhase, error, setError, start, finish, cancel, say, stopSpeaking, level }
}
