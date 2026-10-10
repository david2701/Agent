import {
  Alarm,
  ArrowRight,
  ArrowsClockwise,
  Bell,
  Brain,
  CalendarBlank,
  CheckSquare,
  CloudSun,
  ImageSquare,
  ListChecks,
  SunHorizon,
  DeviceMobile,
  Globe,
  Lightning,
  MagnifyingGlass,
  Microphone,
  Timer,
  WarningCircle,
} from '@phosphor-icons/react'
import { motion, useReducedMotion } from 'motion/react'
import { memo, type ReactNode } from 'react'
import { Link } from 'react-router'
import { KairoMark } from '@/components/KairoMark'
import { Markdown } from '@/components/Markdown'
import type { Turn } from '@/lib/agent'
import { cn } from '@/lib/cn'
import { entityHref, entityIcon, entityLabel } from '@/lib/entities'
import { time } from '@/lib/format'
import { t } from '@/lib/i18n'
import type { DeviceAction, EntityRef, FileRef, StoredMessage } from '@/lib/types'

export type ThreadItem =
  | { kind: 'user'; id: string; text: string; attachments: FileRef[]; channel?: string; at?: string }
  | { kind: 'assistant'; id: string; text: string; streaming?: boolean; at?: string }
  | { kind: 'tool'; id: string; name: string; ok: boolean; summary: string; entity?: EntityRef | null; running?: boolean; label?: string }
  | { kind: 'device'; id: string; action: DeviceAction }
  | { kind: 'image'; id: string; file: FileRef }
  | { kind: 'error'; id: string; message: string }

const DEVICE_TOOLS = new Set(['set_timer', 'set_alarm', 'cancel_timer'])

/** Kairo's writing style avoids long dashes: they are shown as a comma or a plain hyphen. */
export function tidyDashes(text: string) {
  return text.replace(/\s+[—–]\s+/g, ', ').replace(/[—–]/g, '-')
}

/** Turns the stored history into thread items (hides the preambles that come before tool calls). */
export function storedToItems(messages: StoredMessage[]): ThreadItem[] {
  const out: ThreadItem[] = []
  let held: StoredMessage | null = null
  const flush = () => {
    if (held && held.content.trim()) out.push({ kind: 'assistant', id: held.id, text: held.content, at: held.createdAt })
    held = null
  }
  for (const m of messages) {
    if (m.role === 'user') {
      flush()
      out.push({
        kind: 'user',
        id: m.id,
        text: m.content,
        attachments: m.meta?.attachments ?? [],
        channel: m.meta?.channel,
        at: m.createdAt,
      })
    } else if (m.role === 'assistant') {
      flush()
      held = m
    } else if (m.role === 'tool') {
      held = null
      const meta = m.meta ?? {}
      if (m.toolName && DEVICE_TOOLS.has(m.toolName) && meta.ok !== false) {
        out.push({ kind: 'device', id: m.id, action: { type: m.toolName === 'set_alarm' ? 'alarm' : 'timer', label: meta.summary } as DeviceAction })
      } else {
        out.push({ kind: 'tool', id: m.id, name: m.toolName ?? '', ok: meta.ok !== false, summary: meta.summary ?? '', entity: meta.entity ?? null })
      }
      if (meta.attachment) out.push({ kind: 'image', id: `${m.id}-att`, file: meta.attachment })
    }
  }
  flush()
  return out
}

export function turnToItems(turn: Turn): ThreadItem[] {
  const out: ThreadItem[] = []
  if (turn.userText !== null) out.push({ kind: 'user', id: `${turn.id}-u`, text: turn.userText, attachments: [], channel: turn.channel })
  for (const tool of turn.tools) {
    if (DEVICE_TOOLS.has(tool.name) && tool.status === 'done' && tool.ok !== false) continue
    out.push({
      kind: 'tool',
      id: `${turn.id}-${tool.callId}`,
      name: tool.name,
      ok: tool.ok !== false,
      summary: tool.summary ?? tool.label,
      entity: tool.entity,
      running: tool.status === 'running',
      label: tool.label,
    })
  }
  turn.deviceActions.forEach((a, i) => out.push({ kind: 'device', id: `${turn.id}-d${i}`, action: a }))
  turn.attachments.forEach((f) => out.push({ kind: 'image', id: `${turn.id}-${f.id}`, file: f }))
  if (turn.text || turn.status === 'done') out.push({ kind: 'assistant', id: `${turn.id}-a`, text: turn.text, streaming: turn.status === 'streaming' })
  if (turn.status === 'error' && turn.error) out.push({ kind: 'error', id: `${turn.id}-e`, message: turn.error })
  return out
}

const enter = { initial: { opacity: 0, y: 8 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.32, ease: [0.16, 1, 0.3, 1] as const } }

function Appear({ children, className, animate = true }: { children: ReactNode; className?: string; animate?: boolean }) {
  const reduce = useReducedMotion()
  if (!animate || reduce) return <div className={className}>{children}</div>
  return (
    <motion.div {...enter} className={className}>
      {children}
    </motion.div>
  )
}

export const ThreadItems = memo(function ThreadItems({ items, animateNew = false, compact = false }: { items: ThreadItem[]; animateNew?: boolean; compact?: boolean }) {
  return (
    <>
      {items.map((it) => {
        switch (it.kind) {
          case 'user':
            return (
              <Appear key={it.id} animate={animateNew} className="flex justify-end">
                <div className="max-w-[85%] md:max-w-[75%]">
                  {it.attachments.length > 0 && (
                    <div className="mb-2 flex flex-wrap justify-end gap-2">
                      {it.attachments.map((f) => (
                        <AttachmentImage key={f.id} file={f} />
                      ))}
                    </div>
                  )}
                  <div className="rounded-[16px] rounded-br-[6px] bg-surface-2 px-3.5 py-2.5 text-base leading-relaxed whitespace-pre-wrap text-fg">
                    {it.text}
                  </div>
                  {it.channel === 'voice' && (
                    <p className="mt-1 flex items-center justify-end gap-1 text-xs text-faint">
                      <Microphone className="size-3" /> {t('thread.byVoice')}
                    </p>
                  )}
                </div>
              </Appear>
            )
          case 'assistant':
            return (
              <Appear key={it.id} animate={animateNew} className="flex gap-3">
                {!compact && (
                  <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full border border-line bg-surface">
                    <KairoMark className="size-4" />
                  </span>
                )}
                <div className="min-w-0 flex-1 pt-0.5">
                  {it.text ? (
                    <Markdown className={cn(it.streaming && 'k-streaming')}>{tidyDashes(it.text)}</Markdown>
                  ) : (
                    <p className="text-base text-faint">{t('thread.done')}</p>
                  )}
                  {it.at && !it.streaming && <p className="mt-1.5 font-mono text-xs text-faint">{time(it.at)}</p>}
                </div>
              </Appear>
            )
          case 'tool':
            return it.running ? (
              <ToolRunning key={it.id} label={it.label ?? t('thread.working')} name={it.name} compact={compact} />
            ) : (
              <Appear key={it.id} animate={animateNew} className={cn(!compact && 'pl-10')}>
                <ActionCard name={it.name} ok={it.ok} summary={it.summary} entity={it.entity} />
              </Appear>
            )
          case 'device':
            return (
              <Appear key={it.id} animate={animateNew} className={cn(!compact && 'pl-10')}>
                <DeviceChip action={it.action} />
              </Appear>
            )
          case 'image':
            return (
              <Appear key={it.id} animate={animateNew} className={cn(!compact && 'pl-10')}>
                <AttachmentImage file={it.file} large />
              </Appear>
            )
          case 'error':
            return (
              <Appear key={it.id} animate={animateNew} className={cn(!compact && 'pl-10')}>
                <p role="alert" className="flex items-start gap-2 text-base text-danger">
                  <WarningCircle className="mt-0.5 size-4 shrink-0" />
                  {it.message}
                </p>
              </Appear>
            )
        }
      })}
    </>
  )
})

const TOOL_ICON: Record<string, typeof Lightning> = {
  get_weather: CloudSun,
  get_agenda: CalendarBlank,
  get_daily_briefing: SunHorizon,
  list_tasks: CheckSquare,
  list_reminders: Bell,
  get_list: ListChecks,
  search_notes: MagnifyingGlass,
  search_everything: MagnifyingGlass,
  generate_image: ImageSquare,
  remember: Brain,
  recall: Brain,
  web_search: Globe,
  sync_calendar: ArrowsClockwise,
}

function toolIcon(name: string, entity?: EntityRef | null) {
  if (entity) return entityIcon(entity.type)
  if (TOOL_ICON[name]) return TOOL_ICON[name]
  if (name.startsWith('search') || name.startsWith('find') || name.startsWith('list')) return MagnifyingGlass
  return Lightning
}

export function ActionCard({ name, ok, summary, entity }: { name: string; ok: boolean; summary: string; entity?: EntityRef | null }) {
  const Icon = ok ? toolIcon(name, entity) : WarningCircle
  const href = entity ? entityHref(entity.type, entity.id) : null
  const body = (
    <div
      className={cn(
        'group flex items-center gap-3 rounded-card border border-line bg-surface px-3 py-2.5 transition-colors',
        href && 'hover:border-[color-mix(in_srgb,var(--text-faint)_45%,var(--border))]',
      )}
    >
      <span
        className={cn(
          'flex size-8 shrink-0 items-center justify-center rounded-control',
          ok ? 'bg-surface-2 text-fg' : 'bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] text-danger',
        )}
      >
        <Icon className="size-[18px]" />
      </span>
      <div className="min-w-0 flex-1">
        <p className={cn('text-base leading-snug', ok ? 'text-fg' : 'text-muted')}>{tidyDashes(summary) || (ok ? t('thread.actionDone') : t('thread.actionFailed'))}</p>
        {entity && (
          <p className="mt-0.5 truncate text-sm text-faint">
            {entityLabel(entity.type) ?? t('entity.item')}
            {entity.title && !summary.includes(entity.title) ? `: ${entity.title}` : ''}
          </p>
        )}
      </div>
      {href && (
        <span className="flex shrink-0 items-center gap-1 text-sm text-muted transition-colors group-hover:text-fg">
          {t('thread.open')}
          <ArrowRight className="size-3.5 transition-transform duration-200 group-hover:translate-x-0.5" />
        </span>
      )}
    </div>
  )
  return href ? (
    <Link to={href} className="block rounded-card">
      {body}
    </Link>
  ) : (
    body
  )
}

function ToolRunning({ label, name, compact }: { label: string; name: string; compact: boolean }) {
  const Icon = toolIcon(name)
  return (
    <div className={cn('flex items-center gap-2.5 text-base text-muted', !compact && 'pl-10')} role="status">
      <Icon className="size-4 text-faint" />
      <span className="k-shimmer-text">{label}…</span>
    </div>
  )
}

export function DeviceChip({ action }: { action: DeviceAction }) {
  const isAlarm = action.type === 'alarm'
  const Icon = isAlarm ? Alarm : Timer
  const detail =
    action.type === 'timer' && typeof action.seconds === 'number'
      ? formatSeconds(action.seconds)
      : action.type === 'alarm' && typeof action.hour === 'number'
        ? `${action.hour}:${String(action.minute ?? 0).padStart(2, '0')}`
        : typeof action.label === 'string'
          ? action.label.replace(/^(Temporizador|Alarma|Timer|Alarm):\s*/i, '')
          : ''
  return (
    <span className="inline-flex max-w-full items-center gap-2 rounded-full border border-line bg-surface px-3 py-1.5 text-sm text-muted">
      <Icon className="size-4 shrink-0" />
      <span className="truncate">
        {isAlarm ? t('thread.alarmOnPhone') : t('thread.timerOnPhone')}
        {detail && <span className="ml-1.5 font-mono text-fg">{detail}</span>}
      </span>
      <DeviceMobile className="size-3.5 shrink-0 text-faint" aria-hidden />
    </span>
  )
}

function formatSeconds(s: number) {
  if (s < 60) return `${s} s`
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min`
  const h = Math.floor(m / 60)
  const r = m % 60
  return r ? `${h} h ${r} min` : `${h} h`
}

export function AttachmentImage({ file, large }: { file: FileRef; large?: boolean }) {
  if (file.contentType && !file.contentType.startsWith('image/')) {
    return (
      <a href={file.url} target="_blank" rel="noreferrer" className="text-sm text-accent-strong underline underline-offset-2">
        {t('thread.viewAttachment')}
      </a>
    )
  }
  return (
    <a href={file.url} target="_blank" rel="noreferrer" className="block overflow-hidden rounded-card border border-line bg-surface-2">
      <img
        src={file.url}
        alt={t('thread.attachedImage')}
        loading="lazy"
        className={cn('block object-cover', large ? 'max-h-80 w-auto max-w-full' : 'h-28 w-auto max-w-[220px]')}
      />
    </a>
  )
}
