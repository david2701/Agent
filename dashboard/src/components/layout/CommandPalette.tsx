import {
  ArrowRight,
  ArrowUp,
  Bell,
  CalendarPlus,
  ChatCircleDots,
  CheckSquare,
  Moon,
  NotePencil,
  PaperPlaneTilt,
  Sparkle,
  Sun,
  UserPlus,
} from '@phosphor-icons/react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router'
import { ThreadItems, turnToItems } from '@/components/chat/Thread'
import { KairoMark } from '@/components/KairoMark'
import { Kbd } from '@/components/ui/misc'
import { newTurn, useAgentRunner } from '@/lib/agent'
import { cn } from '@/lib/cn'
import { useFamilyInfo } from '@/lib/family'
import { useTheme } from '@/lib/theme'
import { ALL_NAV } from './nav'

interface Command {
  id: string
  label: string
  hint?: string
  icon: ReactNode
  run: () => void
  keywords?: string
}

function normalize(s: string) {
  return s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
}

/** Paleta de comandos (Ctrl/Cmd+K): preguntar a Kairo desde cualquier sitio o saltar a una sección. */
export function CommandPalette({ open, onClose, prefill }: { open: boolean; onClose: () => void; prefill: string }) {
  const ref = useRef<HTMLDialogElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()
  const { resolved, setMode } = useTheme()
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const [conversationId, setConversationId] = useState<string | undefined>()
  const runner = useAgentRunner()
  const fam = useFamilyInfo()

  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (open && !d.open) {
      d.showModal()
      setQuery(prefill)
      setActive(0)
      requestAnimationFrame(() => input.current?.focus())
    } else if (!open && d.open) {
      d.close()
    }
  }, [open, prefill])

  // Al cerrar se olvida la conversación rápida (salvo que siga respondiendo).
  useEffect(() => {
    if (!open && !runner.busy) {
      const t = setTimeout(() => {
        runner.reset()
        setConversationId(undefined)
      }, 300)
      return () => clearTimeout(t)
    }
  }, [open, runner.busy, runner])

  const go = (to: string) => {
    onClose()
    navigate(to)
  }

  const commands = useMemo<Command[]>(() => {
    const pages: Command[] = ALL_NAV.map((n) => {
      const Icon = n.icon
      return { id: `nav-${n.to}`, label: n.label, hint: 'Ir a', icon: <Icon className="size-[18px]" />, run: () => go(n.to) }
    })
    const actions: Command[] = [
      { id: 'new-note', label: 'Nueva nota', icon: <NotePencil className="size-[18px]" />, run: () => go('/notas/nueva'), keywords: 'crear escribir' },
      { id: 'new-task', label: 'Nueva tarea', icon: <CheckSquare className="size-[18px]" />, run: () => go('/tareas?nueva=1'), keywords: 'crear pendiente' },
      { id: 'new-reminder', label: 'Nuevo recordatorio', icon: <Bell className="size-[18px]" />, run: () => go('/recordatorios?nuevo=1'), keywords: 'crear aviso alarma' },
      { id: 'new-event', label: 'Nuevo evento', icon: <CalendarPlus className="size-[18px]" />, run: () => go('/calendario?nuevo=1'), keywords: 'crear cita reunion calendario' },
      ...(fam.isAdmin
        ? [{ id: 'invite', label: 'Invitar a la familia', icon: <UserPlus className="size-[18px]" />, run: () => go('/familia?invitar=1'), keywords: 'familia miembro codigo invitacion' }]
        : []),
      ...(fam.hasOthers
        ? [{ id: 'notice', label: 'Enviar aviso a la familia', icon: <PaperPlaneTilt className="size-[18px]" />, run: () => go('/familia#aviso'), keywords: 'familia mensaje avisar' }]
        : []),
      {
        id: 'theme',
        label: resolved === 'dark' ? 'Usar tema claro' : 'Usar tema oscuro',
        icon: resolved === 'dark' ? <Sun className="size-[18px]" /> : <Moon className="size-[18px]" />,
        run: () => {
          setMode(resolved === 'dark' ? 'light' : 'dark')
        },
        keywords: 'tema modo oscuro claro apariencia',
      },
    ]
    return [...actions, ...pages]
  }, [resolved, fam.isAdmin, fam.hasOthers])

  const q = normalize(query.trim())
  const filtered = q ? commands.filter((c) => normalize(`${c.label} ${c.keywords ?? ''}`).includes(q)) : commands
  const ask: Command | null = query.trim()
    ? {
        id: 'ask',
        label: query.trim(),
        hint: 'Preguntar a Kairo',
        icon: <Sparkle className="size-[18px]" />,
        run: () => void askKairo(query.trim()),
      }
    : null
  const options = ask ? [ask, ...filtered.slice(0, 6)] : filtered

  useEffect(() => {
    setActive(0)
  }, [query])

  const askKairo = async (text: string) => {
    setQuery('')
    await runner.run('/chat', { message: text, conversationId, channel: 'chat' }, newTurn('chat', text), {
      onStart: (id) => setConversationId(id),
    })
  }

  useEffect(() => {
    const el = scroller.current
    if (el && runner.turn) el.scrollTop = el.scrollHeight
  }, [runner.turn])

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((a) => Math.min(options.length - 1, a + 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((a) => Math.max(0, a - 1))
    } else if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      e.preventDefault()
      if (runner.turn && query.trim()) {
        void askKairo(query.trim())
        return
      }
      options[active]?.run()
    }
  }

  const answering = !!runner.turn
  const items = runner.turn ? turnToItems(runner.turn) : []

  return createPortal(
    <dialog
      ref={ref}
      aria-label="Preguntar a Kairo o buscar"
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onClick={(e) => {
        if (e.target === ref.current) onClose()
      }}
      className="k-dialog fixed m-0 mt-auto max-h-[88dvh] w-full max-w-none overflow-hidden rounded-t-card border border-line bg-surface p-0 text-fg shadow-pop sm:mx-auto sm:mt-[12vh] sm:mb-auto sm:max-h-[72vh] sm:w-[min(100%-2rem,40rem)] sm:rounded-card"
    >
      <div className="flex max-h-[inherit] flex-col">
        <div className="flex items-center gap-3 border-b border-line px-4">
          <KairoMark className="size-5" />
          <input
            ref={input}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={answering ? 'Pregunta algo más' : 'Pregúntale a Kairo o busca una sección'}
            aria-label="Pregunta o comando"
            role="combobox"
            aria-expanded={!answering}
            aria-controls="palette-options"
            aria-activedescendant={!answering && options[active] ? `opt-${options[active].id}` : undefined}
            className="h-14 min-w-0 flex-1 bg-transparent text-md outline-none placeholder:text-faint"
          />
          {answering && query.trim() ? (
            <button
              type="button"
              aria-label="Enviar"
              onClick={() => void askKairo(query.trim())}
              disabled={runner.busy}
              className="flex size-8 items-center justify-center rounded-full bg-accent text-on-accent active:scale-95 disabled:opacity-40"
            >
              <ArrowUp className="size-4" />
            </button>
          ) : (
            <Kbd className="hidden sm:inline-flex">Esc</Kbd>
          )}
        </div>

        <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto">
          {answering ? (
            <div className="grid gap-4 px-4 py-4">
              <ThreadItems items={items} animateNew compact />
              {runner.turn?.status === 'streaming' && !runner.turn.text && runner.turn.tools.length === 0 && (
                <p className="k-shimmer-text text-base">Pensando…</p>
              )}
            </div>
          ) : (
            <ul id="palette-options" role="listbox" className="grid gap-0.5 p-2">
              {options.map((c, i) => (
                <li key={c.id} id={`opt-${c.id}`} role="option" aria-selected={i === active}>
                  <button
                    type="button"
                    onMouseMove={() => setActive(i)}
                    onClick={() => c.run()}
                    className={cn(
                      'flex w-full items-center gap-3 rounded-control px-3 py-2.5 text-left text-base transition-colors',
                      i === active ? 'bg-surface-2 text-fg' : 'text-muted',
                    )}
                  >
                    <span className={cn('flex shrink-0', c.id === 'ask' ? 'text-accent-strong' : 'text-faint')}>{c.icon}</span>
                    <span className="min-w-0 flex-1 truncate">
                      {c.id === 'ask' && <span className="mr-1.5 text-muted">Preguntar a Kairo:</span>}
                      <span className={cn(c.id === 'ask' ? 'text-fg' : '')}>{c.label}</span>
                    </span>
                    {c.hint && c.id !== 'ask' && <span className="shrink-0 text-sm text-faint">{c.hint}</span>}
                    {i === active && <ArrowRight className="size-4 shrink-0 text-faint" />}
                  </button>
                </li>
              ))}
              {options.length === 0 && <li className="px-3 py-6 text-center text-base text-muted">Sin resultados</li>}
            </ul>
          )}
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-line px-4 py-2.5 text-sm text-faint">
          {answering ? (
            <>
              <span className="truncate">{runner.busy ? 'Kairo está respondiendo…' : 'Puedes seguir preguntando aquí.'}</span>
              {conversationId && (
                <button
                  type="button"
                  onClick={() => go(`/chat/${conversationId}`)}
                  className="flex shrink-0 items-center gap-1.5 rounded-[8px] px-2 py-1 font-medium text-fg transition-colors hover:bg-surface-2"
                >
                  <ChatCircleDots className="size-4" />
                  Abrir conversación
                </button>
              )}
            </>
          ) : (
            <>
              <span className="hidden items-center gap-1.5 sm:flex">
                <Kbd>↑</Kbd>
                <Kbd>↓</Kbd> para moverte
              </span>
              <span className="flex items-center gap-1.5">
                <Kbd>Enter</Kbd> para elegir
              </span>
            </>
          )}
        </div>
      </div>
    </dialog>,
    document.body,
  )
}
