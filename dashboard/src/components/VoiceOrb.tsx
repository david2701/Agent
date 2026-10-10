import { useReducedMotion } from 'motion/react'
import { memo, useEffect, useRef } from 'react'
import { cn } from '@/lib/cn'

export type OrbState = 'idle' | 'listening' | 'thinking' | 'speaking'

/*
 * Orbe de voz: la misma geometría de la marca (anillo de 300° con hueco arriba a la derecha
 * y punto ámbar en el centro del hueco). Respira en reposo, se deforma con el micrófono al
 * escuchar, gira al pensar y ondula al hablar. Se dibuja fuera del ciclo de React (rAF + refs).
 */

const C = 100
const R = 60
const START = (-10 * Math.PI) / 180 // el arco va de -10° a 290° (sentido horario en pantalla)
const SPAN = (300 * Math.PI) / 180
const DOT_ANGLE = (-40 * Math.PI) / 180
const N = 120

function arcPath(radiusAt: (theta: number) => number) {
  let d = ''
  for (let i = 0; i <= N; i++) {
    const th = START + (SPAN * i) / N
    const r = radiusAt(th)
    const x = C + r * Math.cos(th)
    const y = C + r * Math.sin(th)
    d += `${i === 0 ? 'M' : 'L'}${x.toFixed(2)} ${y.toFixed(2)}`
  }
  return d
}

const STATIC_PATH = arcPath(() => R)

interface Props {
  state: OrbState
  /** Nivel de audio 0..1 (micrófono o reproducción). */
  getLevel?: () => number
  className?: string
  label?: string
}

export const VoiceOrb = memo(function VoiceOrb({ state, getLevel, className, label }: Props) {
  const reduce = useReducedMotion()
  const path = useRef<SVGPathElement>(null)
  const dot = useRef<SVGCircleElement>(null)
  const group = useRef<SVGGElement>(null)
  const stateRef = useRef(state)
  const levelRef = useRef(getLevel)
  stateRef.current = state
  levelRef.current = getLevel

  useEffect(() => {
    if (reduce) {
      path.current?.setAttribute('d', STATIC_PATH)
      group.current?.setAttribute('transform', '')
      dot.current?.setAttribute('cx', String(C + R * Math.cos(DOT_ANGLE)))
      dot.current?.setAttribute('cy', String(C + R * Math.sin(DOT_ANGLE)))
      dot.current?.setAttribute('r', '13')
      return
    }
    let raf = 0
    let last = performance.now()
    const t0 = last
    let level = 0
    let rot = 0
    let amp = 0
    let scale = 1

    const frame = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000)
      last = now
      const t = (now - t0) / 1000
      const s = stateRef.current
      const target = s === 'listening' || s === 'speaking' ? Math.min(1, levelRef.current?.() ?? 0) : 0
      level += (target - level) * (target > level ? 0.35 : 0.12)

      let targetAmp = 0
      let targetScale = 1
      if (s === 'idle') {
        targetScale = 1 + 0.022 * Math.sin(t * 1.3)
      } else if (s === 'listening') {
        targetAmp = 0.025 + level * 0.2
        targetScale = 1 + level * 0.07
      } else if (s === 'thinking') {
        targetAmp = 0.018
        targetScale = 0.97
        rot += dt * 150
      } else if (s === 'speaking') {
        targetAmp = 0.02 + level * 0.13
        targetScale = 1 + level * 0.04
      }
      if (s !== 'thinking') {
        // Vuelve suavemente a la orientación de la marca
        const back = ((rot % 360) + 360) % 360
        rot = back > 180 ? rot + (360 - back) * Math.min(1, dt * 4) : rot - back * Math.min(1, dt * 4)
      }
      amp += (targetAmp - amp) * Math.min(1, dt * 8)
      scale += (targetScale - scale) * Math.min(1, dt * 6)

      const radiusAt = (th: number) => {
        let w = 0
        if (s === 'speaking') w = Math.sin(7 * th - t * 7.5)
        else if (s === 'thinking') w = Math.sin(2 * th + t * 3)
        else w = 0.6 * Math.sin(3 * th + t * 2.3) + 0.4 * Math.sin(5 * th - t * 3.4)
        return R * scale * (1 + amp * w)
      }
      path.current?.setAttribute('d', arcPath(radiusAt))
      const dr = radiusAt(DOT_ANGLE)
      dot.current?.setAttribute('cx', (C + dr * Math.cos(DOT_ANGLE)).toFixed(2))
      dot.current?.setAttribute('cy', (C + dr * Math.sin(DOT_ANGLE)).toFixed(2))
      dot.current?.setAttribute('r', (13 * (1 + (s === 'listening' ? level * 0.35 : 0))).toFixed(2))
      group.current?.setAttribute('transform', `rotate(${rot.toFixed(2)} ${C} ${C})`)
      raf = requestAnimationFrame(frame)
    }
    raf = requestAnimationFrame(frame)
    return () => cancelAnimationFrame(raf)
  }, [reduce])

  return (
    <svg
      viewBox="0 0 200 200"
      className={cn('overflow-visible', className)}
      role="img"
      aria-label={label ?? ORB_LABELS[state]}
      data-state={state}
    >
      <g ref={group}>
        <path
          ref={path}
          d={STATIC_PATH}
          fill="none"
          strokeWidth="13"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="transition-[stroke] duration-300"
          style={{ stroke: state === 'listening' ? 'var(--accent)' : 'var(--mark)' }}
        />
        <circle ref={dot} cx={C + R * Math.cos(DOT_ANGLE)} cy={C + R * Math.sin(DOT_ANGLE)} r="13" fill="var(--accent)" />
      </g>
    </svg>
  )
})

export const ORB_LABELS: Record<OrbState, string> = {
  idle: 'Kairo en reposo',
  listening: 'Escuchando',
  thinking: 'Pensando',
  speaking: 'Hablando',
}
