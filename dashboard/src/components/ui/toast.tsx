import { CheckCircle, Info, WarningCircle, X } from '@phosphor-icons/react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useEffect, useSyncExternalStore, type ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface ToastAction {
  label: string
  onClick: () => void | Promise<void>
}

export interface ToastOptions {
  title: string
  description?: ReactNode
  tone?: 'default' | 'success' | 'danger'
  actions?: ToastAction[]
  /** ms; 0 = no se cierra solo */
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

/** Atajo para errores de mutaciones. */
export function toastError(e: unknown, title = 'Algo salió mal') {
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
            <ToastCard t={t} />
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}

function ToastCard({ t }: { t: ToastItem }) {
  const duration = t.duration ?? (t.actions?.length ? 7000 : 4500)
  useEffect(() => {
    if (!duration) return
    const timer = setTimeout(() => dismissToast(t.id), duration)
    return () => clearTimeout(timer)
  }, [t.id, duration])

  const icon =
    t.icon ??
    (t.tone === 'danger' ? (
      <WarningCircle className="size-5 text-danger" />
    ) : t.tone === 'success' ? (
      <CheckCircle className="size-5 text-success" />
    ) : (
      <Info className="size-5 text-muted" />
    ))

  return (
    <div role={t.tone === 'danger' ? 'alert' : 'status'} className="flex items-start gap-3 rounded-card border border-line bg-surface p-3 pr-2 shadow-pop">
      <span className="mt-px shrink-0">{icon}</span>
      <div className="min-w-0 flex-1 py-px">
        <p className="text-base font-medium text-fg">{t.title}</p>
        {t.description && <div className="mt-0.5 text-sm break-words text-muted">{t.description}</div>}
        {t.actions && t.actions.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {t.actions.map((a, i) => (
              <button
                key={a.label}
                type="button"
                onClick={async () => {
                  dismissToast(t.id)
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
        aria-label="Cerrar aviso"
        onClick={() => dismissToast(t.id)}
        className="flex size-7 shrink-0 items-center justify-center rounded-[8px] text-faint transition-colors hover:bg-surface-2 hover:text-fg"
      >
        <X className="size-3.5" />
      </button>
    </div>
  )
}
