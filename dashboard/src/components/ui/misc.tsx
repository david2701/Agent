import { motion, useReducedMotion } from 'motion/react'
import { useId, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { cn } from '@/lib/cn'

export function Panel({ className, children, as: As = 'section', ...rest }: { className?: string; children: ReactNode; as?: 'section' | 'div' | 'article' } & Record<string, unknown>) {
  return (
    <As className={cn('rounded-card border border-line bg-surface', className)} {...rest}>
      {children}
    </As>
  )
}

export function SectionHeader({
  title,
  count,
  action,
  className,
  as: As = 'h2',
  id,
}: {
  title: ReactNode
  count?: number
  action?: ReactNode
  className?: string
  as?: 'h2' | 'h3'
  id?: string
}) {
  return (
    <div className={cn('flex min-h-8 items-center justify-between gap-3', className)}>
      <As id={id} className="flex items-baseline gap-2 text-md font-semibold tracking-tight">
        {title}
        {count !== undefined && <span className="font-mono text-sm font-normal text-faint tnum">{count}</span>}
      </As>
      {action}
    </div>
  )
}

export function Chip({
  selected,
  className,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { selected?: boolean }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      className={cn(
        'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-full border px-3 text-sm whitespace-nowrap transition-[background-color,border-color,color,transform] duration-200 ease-kairo active:scale-[0.97]',
        selected
          ? 'border-[color-mix(in_srgb,var(--accent)_55%,var(--border))] bg-[color-mix(in_srgb,var(--accent)_14%,var(--surface))] text-accent-strong'
          : 'border-line bg-surface text-muted hover:border-[color-mix(in_srgb,var(--text-faint)_50%,var(--border))] hover:text-fg',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  )
}

/** Small static tag (not interactive). */
export function Tag({ children, tone = 'neutral', className }: { children: ReactNode; tone?: 'neutral' | 'danger' | 'success' | 'accent'; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex h-5 shrink-0 items-center gap-1 rounded-full px-2 text-xs font-medium whitespace-nowrap',
        tone === 'neutral' && 'bg-surface-2 text-muted',
        tone === 'danger' && 'bg-[color-mix(in_srgb,var(--danger)_13%,transparent)] text-danger',
        tone === 'success' && 'bg-[color-mix(in_srgb,var(--success)_14%,transparent)] text-success',
        tone === 'accent' && 'bg-[color-mix(in_srgb,var(--accent)_16%,transparent)] text-accent-strong',
        className,
      )}
    >
      {children}
    </span>
  )
}

export function Segmented<T extends string>({
  value,
  onChange,
  options,
  label,
  size = 'md',
  className,
}: {
  value: T
  onChange: (v: T) => void
  options: { value: T; label: string; icon?: ReactNode }[]
  label: string
  size?: 'sm' | 'md'
  className?: string
}) {
  const id = useId()
  const reduce = useReducedMotion()
  return (
    <div role="radiogroup" aria-label={label} className={cn('inline-flex rounded-control border border-line bg-surface-2 p-0.5', className)}>
      {options.map((o) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={cn(
              'relative inline-flex items-center gap-1.5 rounded-[8px] font-medium transition-colors duration-200',
              size === 'sm' ? 'h-7 px-2.5 text-sm' : 'h-8 px-3 text-sm',
              active ? 'text-fg' : 'text-muted hover:text-fg',
            )}
          >
            {active && (
              <motion.span
                layoutId={`seg-${id}`}
                className="absolute inset-0 rounded-[8px] border border-line bg-surface shadow-[0_1px_2px_rgb(var(--shadow-tint)/0.08)]"
                transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 420, damping: 34 }}
              />
            )}
            <span className="relative z-10 inline-flex items-center gap-1.5">
              {o.icon}
              {o.label}
            </span>
          </button>
        )
      })}
    </div>
  )
}

export function Kbd({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <kbd
      className={cn(
        'inline-flex h-5 min-w-5 items-center justify-center rounded-[5px] border border-line bg-surface px-1 font-mono text-[11px] text-faint',
        className,
      )}
    >
      {children}
    </kbd>
  )
}

export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  className?: string
}) {
  return (
    <header className={cn('flex flex-wrap items-end justify-between gap-x-6 gap-y-3', className)}>
      <div className="min-w-0">
        <h1 className="text-lg font-semibold tracking-tight md:text-xl">{title}</h1>
        {description && <p className="mt-1 max-w-[65ch] text-base text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  )
}
