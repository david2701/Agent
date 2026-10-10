import { House } from '@phosphor-icons/react'
import { Tag } from '@/components/ui/misc'
import { cn } from '@/lib/cn'
import { initials } from '@/lib/family'

const AVATAR_SIZES = {
  xs: 'size-5 text-[10px]',
  sm: 'size-6 text-[11px]',
  md: 'size-8 text-sm',
  lg: 'size-10 text-base',
}

/** Avatar neutro con iniciales (sin colores: el ámbar queda para la acción principal). */
export function Avatar({
  name,
  size = 'md',
  className,
  title,
}: {
  name: string | null | undefined
  size?: keyof typeof AVATAR_SIZES
  className?: string
  title?: string
}) {
  return (
    <span
      title={title}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      role={title ? 'img' : undefined}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full border border-line bg-surface-2 font-medium tracking-tight text-fg select-none',
        AVATAR_SIZES[size],
        className,
      )}
    >
      {initials(name)}
    </span>
  )
}

/** Marca pequeña de "familiar" (casa) para filas, tarjetas y eventos. */
export function FamilyIcon({ className, label = 'Familiar' }: { className?: string; label?: string }) {
  return <House role="img" aria-label={label} className={cn('size-3.5 shrink-0 text-faint', className)} />
}

/** Etiqueta "Familiar", con quién lo compartió si no fui yo. */
export function FamilyTag({ by, className }: { by?: string | null; className?: string }) {
  return (
    <Tag className={className}>
      <House className="size-3" aria-hidden />
      {by ? `Familiar, de ${by}` : 'Familiar'}
    </Tag>
  )
}
