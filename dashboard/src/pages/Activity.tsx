import { ArrowCounterClockwise, ClockCounterClockwise } from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { format, isToday, isYesterday } from 'date-fns'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Chip, PageHeader, Panel } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { cn } from '@/lib/cn'
import { entityHref, entityIcon, entityLabel, sourcePhrase } from '@/lib/entities'
import { capitalize, dayHeading, time } from '@/lib/format'
import { t, type TKey } from '@/lib/i18n'
import { useActivity } from '@/lib/queries'
import type { Activity } from '@/lib/types'
import { undoActivity } from '@/lib/undo'

const KINDS = ['created', 'updated', 'deleted', 'completed', 'fired', 'restored']

function kindLabel(kind: string) {
  return KINDS.includes(kind) ? t(`activity.kind.${kind}` as TKey) : capitalize(kind)
}

const FILTERS: { id: string; labelKey: TKey; match: (s: string) => boolean }[] = [
  { id: 'all', labelKey: 'common.all', match: () => true },
  { id: 'chat', labelKey: 'source.chat', match: (s) => s === 'chat' || s === 'share' },
  { id: 'voice', labelKey: 'source.voice', match: (s) => s === 'voice' || s === 'wear' },
  { id: 'app', labelKey: 'source.app', match: (s) => s === 'app' },
  { id: 'dashboard', labelKey: 'source.dashboard', match: (s) => s === 'dashboard' || s === 'undo' },
  { id: 'system', labelKey: 'source.system', match: (s) => !['chat', 'share', 'voice', 'wear', 'app', 'dashboard', 'undo'].includes(s) },
]

function dayTitle(d: Date) {
  if (isToday(d)) return t('time.today')
  if (isYesterday(d)) return t('time.yesterday')
  return capitalize(dayHeading(d))
}

export default function ActivityPage() {
  const qc = useQueryClient()
  const activity = useActivity()
  const [filter, setFilter] = useState('all')

  const undo = useMutation({
    mutationFn: (a: Activity) => undoActivity(qc, a).then(() => a),
    onSuccess: (a) => toast({ title: t('activity.undone'), description: a.summary, tone: 'success' }),
    onError: (e) => toastError(e, t('common.couldNotUndo')),
  })

  const groups = useMemo(() => {
    const all = (activity.data?.pages ?? []).flat()
    const f = FILTERS.find((x) => x.id === filter)!
    const out: { key: string; title: string; items: Activity[] }[] = []
    for (const a of all.filter((x) => f.match(x.source))) {
      const d = new Date(a.createdAt)
      const key = format(d, 'yyyy-MM-dd')
      let g = out[out.length - 1]
      if (!g || g.key !== key) {
        g = { key, title: dayTitle(d), items: [] }
        out.push(g)
      }
      g.items.push(a)
    }
    return out
  }, [activity.data, filter])

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <div className="max-w-[920px]">
      <PageHeader title={t('activity.title')} description={t('activity.subtitle')} />

      <div className="mt-6 flex gap-1.5 overflow-x-auto scrollbar-none max-sm:-mx-4 max-sm:px-4" role="group" aria-label={t('activity.filterBySource')}>
        {FILTERS.map((f) => (
          <Chip key={f.id} selected={filter === f.id} onClick={() => setFilter(f.id)}>
            {t(f.labelKey)}
          </Chip>
        ))}
      </div>

      <div className="mt-5 grid gap-5">
        {activity.isLoading ? (
          <Panel className="grid gap-5 p-5" aria-busy="true" aria-label={t('activity.loading')}>
            {Array.from({ length: 6 }, (_, i) => (
              <div key={i} className="flex items-center gap-3">
                <Skeleton className="h-3 w-10" />
                <Skeleton className="size-7 rounded-full" />
                <div className="grid flex-1 gap-1.5">
                  <Skeleton className={cn('h-3.5', i % 2 ? 'w-1/2' : 'w-3/4')} />
                  <Skeleton className="h-2.5 w-24" />
                </div>
              </div>
            ))}
          </Panel>
        ) : activity.isError ? (
          <Panel>
            <ErrorState message={(activity.error as Error).message} onRetry={() => void activity.refetch()} />
          </Panel>
        ) : groups.length === 0 ? (
          <Panel>
            <EmptyState
              icon={<ClockCounterClockwise />}
              title={filter === 'all' ? t('activity.emptyAll') : t('activity.emptySource')}
              description={t('activity.emptyHint')}
            />
          </Panel>
        ) : (
          groups.map((g) => (
            <Panel key={g.key} className="px-4 pt-3.5 pb-1.5 md:px-5" aria-label={g.title}>
              <h2 className="text-base font-semibold">{g.title}</h2>
              <ol className="mt-1 divide-y divide-line">
                {g.items.map((a) => (
                  <ActivityRow key={a.id} a={a} onUndo={() => undo.mutate(a)} undoing={undo.isPending && undo.variables?.id === a.id} />
                ))}
              </ol>
            </Panel>
          ))
        )}
        {activity.hasNextPage && (
          <div className="flex justify-center">
            <Button onClick={() => void activity.fetchNextPage()} loading={activity.isFetchingNextPage}>
              {t('common.loadMore')}
            </Button>
          </div>
        )}
      </div>
      </div>
    </div>
  )
}

function ActivityRow({ a, onUndo, undoing }: { a: Activity; onUndo: () => void; undoing: boolean }) {
  const Icon = entityIcon(a.entityType)
  const href = a.entityId && a.kind !== 'deleted' ? entityHref(a.entityType, a.entityId) : null
  const summary = href ? (
    <Link to={href} className="hover:underline hover:decoration-faint hover:underline-offset-2">
      {a.summary}
    </Link>
  ) : (
    a.summary
  )
  return (
    <li className="flex items-start gap-3 py-3">
      <span className="w-10 shrink-0 pt-1 text-right font-mono text-xs text-faint tnum">{time(a.createdAt)}</span>
      <span
        className={cn(
          'flex size-7 shrink-0 items-center justify-center rounded-full border border-line',
          a.kind === 'deleted' ? 'text-danger' : a.kind === 'completed' ? 'text-success' : 'text-muted',
        )}
      >
        <Icon className="size-4" />
      </span>
      <div className="min-w-0 flex-1">
        <p className={cn('text-base leading-snug break-words', a.undoneAt ? 'text-faint line-through' : 'text-fg')}>{summary}</p>
        <p className="mt-0.5 text-sm text-muted">
          <span className="sr-only">{entityLabel(a.entityType) ?? a.entityType}. </span>
          {kindLabel(a.kind)} {sourcePhrase(a.source)}
        </p>
      </div>
      {a.undoneAt ? (
        <span className="shrink-0 pt-1 text-sm text-faint">{t('activity.undone')}</span>
      ) : a.undoable ? (
        <Button size="sm" variant="ghost" icon={<ArrowCounterClockwise className="size-4" />} onClick={onUndo} loading={undoing} className="shrink-0">
          {t('common.undo')}
        </Button>
      ) : null}
    </li>
  )
}
