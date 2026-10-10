import { ArrowClockwise, WarningCircle } from '@phosphor-icons/react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { t } from '@/lib/i18n'
import { Button } from './button'

export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={cn('k-skeleton', className)} />
}

/** Skeleton rows that mimic a list with a title and metadata. */
export function SkeletonRows({ rows = 4, className, withIcon = true }: { rows?: number; className?: string; withIcon?: boolean }) {
  return (
    <div className={cn('grid gap-4', className)} aria-busy="true" aria-label={t('common.loading')}>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3">
          {withIcon && <Skeleton className="size-[18px] shrink-0 rounded-[6px]" />}
          <div className="grid flex-1 gap-2">
            <Skeleton className={cn('h-3.5', i % 3 === 0 ? 'w-3/5' : i % 3 === 1 ? 'w-4/5' : 'w-1/2')} />
            <Skeleton className="h-2.5 w-1/4" />
          </div>
        </div>
      ))}
    </div>
  )
}

export function EmptyState({
  icon,
  title,
  description,
  action,
  className,
  compact,
}: {
  icon?: ReactNode
  title: string
  description?: ReactNode
  action?: ReactNode
  className?: string
  compact?: boolean
}) {
  return (
    <div className={cn('flex flex-col items-center text-center', compact ? 'gap-2 px-4 py-6' : 'gap-3 px-6 py-12', className)}>
      {icon && (
        <div
          className={cn(
            'flex items-center justify-center rounded-full border border-line bg-surface-2 text-muted',
            compact ? 'size-10 [&_svg]:size-5' : 'size-12 [&_svg]:size-6',
          )}
        >
          {icon}
        </div>
      )}
      <div className="grid max-w-[40ch] gap-1">
        <p className={cn('font-medium text-fg', compact ? 'text-base' : 'text-md')}>{title}</p>
        {description && <p className="text-base text-muted">{description}</p>}
      </div>
      {action && <div className="mt-1">{action}</div>}
    </div>
  )
}

export function ErrorState({ message, onRetry, className }: { message: string; onRetry?: () => void; className?: string }) {
  return (
    <div role="alert" className={cn('flex flex-col items-center gap-3 px-6 py-10 text-center', className)}>
      <WarningCircle className="size-6 text-danger" />
      <div className="grid gap-1">
        <p className="text-base font-medium text-fg">{t('common.couldNotLoad')}</p>
        <p className="max-w-[44ch] text-sm text-muted">{message}</p>
      </div>
      {onRetry && (
        <Button size="sm" onClick={onRetry} icon={<ArrowClockwise className="size-4" />}>
          {t('common.retry')}
        </Button>
      )}
    </div>
  )
}

export function InlineError({ message, className }: { message: string; className?: string }) {
  return (
    <p role="alert" className={cn('flex items-center gap-2 text-sm text-danger', className)}>
      <WarningCircle className="size-4 shrink-0" />
      {message}
    </p>
  )
}
