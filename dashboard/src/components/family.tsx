import { House } from '@phosphor-icons/react'
import { Tag } from '@/components/ui/misc'
import { cn } from '@/lib/cn'
import { initials } from '@/lib/family'
import { t } from '@/lib/i18n'

const AVATAR_SIZES = {
  xs: 'size-5 text-[10px]',
  sm: 'size-6 text-[11px]',
  md: 'size-8 text-sm',
  lg: 'size-10 text-base',
}

/** Neutral avatar with initials (no colors: amber is kept for the primary action). */
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

/** Small "family" mark (a house) for rows, cards and events. */
export function FamilyIcon({ className, label = t('family.tag') }: { className?: string; label?: string }) {
  return <House role="img" aria-label={label} className={cn('size-3.5 shrink-0 text-faint', className)} />
}

/** "Family" tag, with who shared it if it was not me. */
export function FamilyTag({ by, className }: { by?: string | null; className?: string }) {
  return (
    <Tag className={className}>
      <House className="size-3" aria-hidden />
      {by ? t('family.tagFrom', { name: by }) : t('family.tag')}
    </Tag>
  )
}
