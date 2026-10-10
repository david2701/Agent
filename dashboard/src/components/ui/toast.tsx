import { CheckCircle, Info, WarningCircle, X } from '@phosphor-icons/react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useEffect, useSyncExternalStore, type ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { t } from '@/lib/i18n'

export interface ToastAction {
  label: string
  onClick: () => void | Promise<void>
}

export interface ToastOptions {
  title: string
  description?: ReactNode
  tone?: 'default' | 'success' | 'danger'
  actions?: ToastAction[]
  /** ms; 0 = does not close on its own */
  duration?: number
  icon?: ReactNode
  id?: string
}

interface ToastItem extends ToastOptions {
  id: string
}

let items: ToastItem[] = []
const subs = new Set<() => void>()
let seq = 0

function publish(next: ToastItem[]) {
  items = next
  subs.forEach((s) => s())
}

export function toast(opts: ToastOptions): string {
  const id = opts.id ?? `t${++seq}`
  const existing = items.filter((t) => t.id !== id)
  publish([...existing, { ...opts, id }].slice(-4))
  return id
}

export function dismissToast(id: string) {
  publish(items.filter((t) => t.id !== id))
}

/** Shortcut for mutation errors. */
export function toastError(e: unknown, title = t('common.somethingWrong')) {
  const message = e instanceof Error ? e.message : String(e)
  toast({ title, description: message, tone: 'danger' })
}

function useToasts() {
  return useSyncExternalStore(
    (cb) => {
      subs.add(cb)
      return () => subs.delete(cb)
    },
    () => items,
  )
}

export function Toaster() {
  const list = useToasts()
  const reduce = useReducedMotion()
  return (
    <div
      aria-live="polite"
      aria-relevant="additions"
      className="pointer-events-none fixed inset-x-3 bottom-[calc(76px+env(safe-area-inset-bottom))] z-50 flex flex-col items-stretch gap-2 md:inset-x-auto md:right-5 md:bottom-5 md:w-[380px]"
    >
      <AnimatePresence initial={false}>
        {list.map((t) => (
          <motion.div
            key={t.id}
            layout={!reduce}
            initial={reduce ? { opacity: 0 } : { opacity: 0, y: 14, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.97, transition: { duration: 0.16 } }}
            transition={{ duration: 0.28, ease: [0.16, 1, 0.3, 1] }}
            className="pointer-events-auto"
          >
            <ToastCard item={t} />
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}

function ToastCard({ item }: { item: ToastItem }) {
  const duration = item.duration ?? (item.actions?.length ? 7000 : 4500)
  useEffect(() => {
    if (!duration) return
    const timer = setTimeout(() => dismissToast(item.id), duration)
    return () => clearTimeout(timer)
  }, [item.id, duration])

  const icon =
    item.icon ??
    (item.tone === 'danger' ? (
      <WarningCircle className="size-5 text-danger" />
    ) : item.tone === 'success' ? (
      <CheckCircle className="size-5 text-success" />
    ) : (
      <Info className="size-5 text-muted" />
    ))

  return (
    <div role={item.tone === 'danger' ? 'alert' : 'status'} className="flex items-start gap-3 rounded-card border border-line bg-surface p-3 pr-2 shadow-pop">
      <span className="mt-px shrink-0">{icon}</span>
      <div className="min-w-0 flex-1 py-px">
        <p className="text-base font-medium text-fg">{item.title}</p>
        {item.description && <div className="mt-0.5 text-sm break-words text-muted">{item.description}</div>}
        {item.actions && item.actions.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {item.actions.map((a, i) => (
              <button
                key={a.label}
                type="button"
                onClick={async () => {
                  dismissToast(item.id)
                  await a.onClick()
                }}
                className={cn(
                  'h-7 rounded-[8px] px-2.5 text-sm font-medium transition-[background-color,transform] active:scale-[0.97]',
                  i === 0 ? 'bg-surface-2 text-fg hover:bg-line' : 'text-muted hover:bg-surface-2 hover:text-fg',
                )}
              >
                {a.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <button
        type="button"
        aria-label={t('ui.closeNotice')}
        onClick={() => dismissToast(item.id)}
        className="flex size-7 shrink-0 items-center justify-center rounded-[8px] text-faint transition-colors hover:bg-surface-2 hover:text-fg"
      >
        <X className="size-3.5" />
      </button>
    </div>
  )
}
