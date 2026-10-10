import {
  ArrowsClockwise,
  Bell,
  ChatCircleText,
  CheckSquare,
  House,
  UserPlus,
  UsersThree,
  type Icon,
} from '@phosphor-icons/react'
import { useNavigate } from 'react-router'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { cn } from '@/lib/cn'
import { entityHref } from '@/lib/entities'
import { relative } from '@/lib/format'
import { useMarkNoticesRead } from '@/lib/mutations'
import type { Notice } from '@/lib/types'

function noticeIcon(n: Notice): Icon {
  if (n.entityType === 'family') return UserPlus
  switch (n.kind) {
    case 'shared':
      return House
    case 'updated':
      return ArrowsClockwise
    case 'assigned':
      return CheckSquare
    case 'reminder':
      return Bell
    case 'message':
      return ChatCircleText
    default:
      return UsersThree
  }
}

/** Adónde lleva un aviso al tocarlo (la entidad compartida o la página de Familia). */
export function noticeHref(n: Pick<Notice, 'entityType' | 'entityId'>): string | null {
  if (!n.entityType || !n.entityId) return null
  if (n.entityType === 'family') return '/familia'
  return entityHref(n.entityType, n.entityId)
}

export function NoticesSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="grid gap-4 py-1" aria-busy="true" aria-label="Cargando novedades">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-start gap-3">
          <Skeleton className="size-8 shrink-0 rounded-full" />
          <div className="grid flex-1 gap-2 pt-1">
            <Skeleton className={cn('h-3.5', i % 2 ? 'w-1/2' : 'w-3/4')} />
            <Skeleton className="h-3 w-2/3" />
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Lista de avisos de la familia (Novedades). Lo no leído lleva punto ámbar y título destacado;
 * tocar un aviso lo marca como leído y abre lo que compartieron.
 */
export function NoticesFeed({
  notices,
  loading,
  error,
  onRetry,
  limit,
  onNavigate,
  className,
}: {
  notices: Notice[] | undefined
  loading: boolean
  error?: Error | null
  onRetry?: () => void
  limit?: number
  /** Se llama antes de navegar (por ejemplo, para cerrar el diálogo). */
  onNavigate?: () => void
  className?: string
}) {
  const navigate = useNavigate()
  const markRead = useMarkNoticesRead()

  if (loading) return <NoticesSkeleton />
  if (error) return <ErrorState message={error.message} onRetry={onRetry} className="py-6" />
  const list = (notices ?? []).slice(0, limit)
  if (list.length === 0)
    return (
      <EmptyState
        compact
        icon={<UsersThree />}
        title="Sin novedades"
        description="Aquí verás lo que tu familia comparte contigo, las tareas que te asignan y sus avisos."
      />
    )

  return (
    <ul className={cn('grid gap-0.5', className)}>
      {list.map((n) => {
        const Icon = noticeIcon(n)
        const unread = !n.readAt
        const href = noticeHref(n)
        const open = () => {
          if (unread) markRead.mutate([n.id])
          if (href) {
            onNavigate?.()
            navigate(href)
          }
        }
        const content = (
          <>
            <span className="relative mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full border border-line bg-surface-2 text-muted">
              <Icon className="size-4" />
              {unread && (
                <span className="absolute -top-0.5 -right-0.5 size-2.5 rounded-full border-2 border-surface bg-accent" aria-hidden />
              )}
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-baseline gap-3">
                <span className={cn('min-w-0 flex-1 text-base leading-snug break-words', unread ? 'font-medium text-fg' : 'text-muted')}>
                  {unread && <span className="sr-only">Sin leer: </span>}
                  {n.title}
                </span>
                <time dateTime={n.createdAt} className="shrink-0 font-mono text-xs text-faint tnum">
                  {relative(n.createdAt)}
                </time>
              </span>
              {n.body && <span className={cn('mt-0.5 line-clamp-2 block text-sm break-words', unread ? 'text-muted' : 'text-faint')}>{n.body}</span>}
            </span>
          </>
        )
        return (
          <li key={n.id}>
            {href || unread ? (
              <button
                type="button"
                onClick={open}
                className="-mx-2 flex w-[calc(100%+1rem)] items-start gap-3 rounded-control px-2 py-2.5 text-left transition-colors hover:bg-surface-2"
                title={href ? 'Abrir' : 'Marcar como leído'}
              >
                {content}
              </button>
            ) : (
              <div className="-mx-2 flex items-start gap-3 px-2 py-2.5">{content}</div>
            )}
          </li>
        )
      })}
    </ul>
  )
}
