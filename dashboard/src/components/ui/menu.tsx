import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface MenuItem {
  label: string
  icon?: ReactNode
  onSelect: () => void
  tone?: 'default' | 'danger'
  disabled?: boolean
}

interface MenuProps {
  trigger: (props: { ref: (el: HTMLButtonElement | null) => void; onClick: () => void; 'aria-expanded': boolean; 'aria-haspopup': 'menu'; 'aria-controls': string }) => ReactNode
  items: (MenuItem | null | false | undefined)[]
  align?: 'start' | 'end'
  className?: string
}

/** Accessible context menu (arrows, Esc, click outside). */
export function Menu({ trigger, items, align = 'end', className }: MenuProps) {
  const [open, setOpen] = useState(false)
  const [up, setUp] = useState(false)
  const id = useId()
  const wrap = useRef<HTMLDivElement>(null)
  const btn = useRef<HTMLButtonElement | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const visible = items.filter(Boolean) as MenuItem[]

  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      if (!wrap.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', onDown)
    requestAnimationFrame(() => list.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')?.focus())
    return () => document.removeEventListener('pointerdown', onDown)
  }, [open])

  const toggle = () => {
    if (!open && btn.current) {
      const r = btn.current.getBoundingClientRect()
      setUp(window.innerHeight - r.bottom < 40 * visible.length + 24)
    }
    setOpen((o) => !o)
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    const els = Array.from(list.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])
    const i = els.indexOf(document.activeElement as HTMLButtonElement)
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      els[(i + 1) % els.length]?.focus()
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      els[(i - 1 + els.length) % els.length]?.focus()
    } else if (e.key === 'Escape') {
      e.preventDefault()
      setOpen(false)
      btn.current?.focus()
    } else if (e.key === 'Tab') {
      setOpen(false)
    }
  }

  return (
    <div ref={wrap} className={cn('relative inline-flex', className)}>
      {trigger({
        ref: (el) => {
          btn.current = el
        },
        onClick: toggle,
        'aria-expanded': open,
        'aria-haspopup': 'menu',
        'aria-controls': id,
      })}
      <AnimatePresence>
        {open && (
          <motion.div
            ref={list}
            id={id}
            role="menu"
            onKeyDown={onKeyDown}
            initial={reduce ? { opacity: 0 } : { opacity: 0, y: up ? 4 : -4, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, transition: { duration: 0.12 } }}
            transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
            className={cn(
              'absolute z-30 min-w-48 rounded-control border border-line bg-surface p-1 shadow-pop',
              align === 'end' ? 'right-0' : 'left-0',
              up ? 'bottom-full mb-1 origin-bottom' : 'top-full mt-1 origin-top',
            )}
          >
            {visible.map((item) => (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                disabled={item.disabled}
                onClick={() => {
                  setOpen(false)
                  item.onSelect()
                }}
                className={cn(
                  'flex w-full items-center gap-2.5 rounded-[7px] px-2.5 py-2 text-left text-base outline-none transition-colors',
                  'hover:bg-surface-2 focus-visible:bg-surface-2 disabled:opacity-40',
                  item.tone === 'danger' ? 'text-danger' : 'text-fg',
                )}
              >
                {item.icon && <span className="flex size-4 items-center justify-center opacity-80">{item.icon}</span>}
                {item.label}
              </button>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
