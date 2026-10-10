import { cn } from '@/lib/cn'

/** Marca de Kairo: anillo abierto + punto ámbar en el hueco (design/icon). */
export function KairoMark({ className, title }: { className?: string; title?: string }) {
  return (
    <svg
      viewBox="10 10 44 44"
      className={cn('shrink-0', className)}
      role={title ? 'img' : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      <path
        d="M37.814 16.025 A17 17 0 1 0 48.742 29.048"
        fill="none"
        stroke="var(--mark)"
        strokeWidth="6"
        strokeLinecap="round"
      />
      <circle cx="45.023" cy="21.073" r="5" fill="var(--accent)" />
    </svg>
  )
}

export function KairoWordmark({ className }: { className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2.5', className)}>
      <KairoMark className="size-7" />
      <span className="text-md font-semibold tracking-tight text-fg">Kairo</span>
    </span>
  )
}
