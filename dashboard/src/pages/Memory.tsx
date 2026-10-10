import {
  Brain,
  Briefcase,
  DownloadSimple,
  FirstAid,
  HouseLine,
  Info,
  PencilSimple,
  Sliders,
  Trash,
  UserCircle,
  UsersThree,
  type Icon,
} from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Button, IconButton } from '@/components/ui/button'
import { EmptyState, ErrorState, SkeletonRows } from '@/components/ui/feedback'
import { Field, Select, Textarea } from '@/components/ui/form'
import { PageHeader, Panel } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { memoryCategories } from '@/lib/entities'
import { relative } from '@/lib/format'
import { t } from '@/lib/i18n'
import { invalidateEntity, useMemories } from '@/lib/queries'
import type { Memory } from '@/lib/types'
import { undoLatest } from '@/lib/undo'

const CAT_ICON: Record<string, Icon> = {
  perfil: UserCircle,
  preferencias: Sliders,
  personas: UsersThree,
  salud: FirstAid,
  trabajo: Briefcase,
  hogar: HouseLine,
  general: Brain,
}

export default function MemoryPage() {
  const qc = useQueryClient()
  const memories = useMemories()
  const [params, setParams] = useSearchParams()
  const [editing, setEditing] = useState<string | null>(null)
  const [highlight, setHighlight] = useState<string | null>(null)

  useEffect(() => {
    const m = params.get('m')
    if (m && memories.data) {
      setHighlight(m)
      params.delete('m')
      setParams(params, { replace: true })
      requestAnimationFrame(() => document.getElementById(`mem-${m}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }))
      setTimeout(() => setHighlight(null), 2400)
    }
  }, [params, setParams, memories.data])

  const grouped = useMemo(() => {
    const categories = memoryCategories()
    const out = categories.map((c) => ({ ...c, items: (memories.data ?? []).filter((m) => m.category === c.id) }))
    const known = new Set<string>(categories.map((c) => c.id))
    const other = (memories.data ?? []).filter((m) => !known.has(m.category))
    if (other.length) out.find((g) => g.id === 'general')!.items.push(...other)
    return out.filter((g) => g.items.length > 0)
  }, [memories.data])

  const remove = useMutation({
    mutationFn: (m: Memory) => api.del(`/memories/${m.id}`).then(() => m),
    onSuccess: (m) => {
      invalidateEntity(qc, 'memory')
      toast({
        title: t('memory.forgot'),
        description: m.content,
        actions: [{ label: t('common.undo'), onClick: () => undoLatest(qc, 'memory', m.id, 'deleted').catch((e) => toastError(e, t('common.couldNotUndo'))) }],
      })
    },
    onError: (e) => toastError(e, t('memory.err.delete')),
  })

  const total = memories.data?.length ?? 0

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-6 md:px-8 md:py-9">
      <PageHeader
        title={t('memory.title')}
        description={t('memory.subtitle')}
      />

      <div className="mt-7 grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px] lg:gap-6">
        <div className="grid gap-5">
          {memories.isLoading ? (
            <Panel className="p-5">
              <SkeletonRows rows={5} />
            </Panel>
          ) : memories.isError ? (
            <Panel>
              <ErrorState message={(memories.error as Error).message} onRetry={() => void memories.refetch()} />
            </Panel>
          ) : total === 0 ? (
            <Panel>
              <EmptyState
                icon={<Brain />}
                title={t('memory.emptyTitle')}
                description={t('memory.emptyHint')}
              />
            </Panel>
          ) : (
            grouped.map((g) => {
              const GIcon = CAT_ICON[g.id] ?? Brain
              return (
                <Panel key={g.id} className="px-5 pt-4 pb-2" aria-labelledby={`cat-${g.id}`}>
                  <h2 id={`cat-${g.id}`} className="flex items-center gap-2.5 text-md font-semibold tracking-tight">
                    <GIcon className="size-[18px] text-faint" />
                    {g.label}
                    <span className="font-mono text-sm font-normal text-faint tnum">{g.items.length}</span>
                  </h2>
                  <ul className="mt-1 divide-y divide-line">
                    {g.items.map((m) => (
                      <li
                        key={m.id}
                        id={`mem-${m.id}`}
                        className={cn('-mx-3 rounded-control px-3 transition-colors duration-700', highlight === m.id && 'bg-[color-mix(in_srgb,var(--accent)_12%,transparent)]')}
                      >
                        {editing === m.id ? (
                          <MemoryForm memory={m} onDone={() => setEditing(null)} />
                        ) : (
                          <div className="group flex items-start gap-3 py-3">
                            <div className="min-w-0 flex-1">
                              <p className="text-base leading-relaxed break-words text-fg">{m.content}</p>
                              <p className="mt-0.5 text-xs text-faint">{t('memory.updated', { when: relative(m.updatedAt) })}</p>
                            </div>
                            <div className="flex shrink-0 gap-0.5 md:opacity-0 md:transition-opacity md:group-focus-within:opacity-100 md:group-hover:opacity-100">
                              <IconButton label={t('common.edit')} size="sm" onClick={() => setEditing(m.id)}>
                                <PencilSimple className="size-4" />
                              </IconButton>
                              <IconButton label={t('memory.forget')} size="sm" tone="danger" onClick={() => remove.mutate(m)}>
                                <Trash className="size-4" />
                              </IconButton>
                            </div>
                          </div>
                        )}
                      </li>
                    ))}
                  </ul>
                </Panel>
              )
            })
          )}
        </div>

        <div className="grid gap-5 lg:sticky lg:top-6">
          <Panel className="p-5" aria-labelledby="mem-new">
            <h2 id="mem-new" className="text-md font-semibold tracking-tight">
              {t('memory.teach')}
            </h2>
            <MemoryForm />
          </Panel>
          <Panel className="p-5">
            <div className="flex gap-3">
              <Info className="mt-0.5 size-[18px] shrink-0 text-muted" />
              <div className="grid gap-2 text-sm text-muted">
                <p>{t('memory.privacy1')}</p>
                <p>{t('memory.privacy2')}</p>
                <Link to="/settings#data" className="inline-flex items-center gap-1.5 font-medium text-fg hover:underline">
                  <DownloadSimple className="size-4" />
                  {t('memory.exportAll')}
                </Link>
              </div>
            </div>
          </Panel>
        </div>
      </div>
    </div>
  )
}

function MemoryForm({ memory, onDone }: { memory?: Memory; onDone?: () => void }) {
  const qc = useQueryClient()
  const [content, setContent] = useState(memory?.content ?? '')
  const [category, setCategory] = useState(memory?.category ?? 'general')
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () =>
      memory
        ? api.patch<Memory>(`/memories/${memory.id}`, { content: content.trim(), category })
        : api.post<Memory>('/memories', { content: content.trim(), category }),
    onSuccess: (m) => {
      invalidateEntity(qc, 'memory')
      if (!memory) {
        setContent('')
        toast({
          title: t('memory.willRemember'),
          description: m.content,
          tone: 'success',
          actions: [{ label: t('common.undo'), onClick: () => undoLatest(qc, 'memory', m.id, 'created').catch((e) => toastError(e, t('common.couldNotUndo'))) }],
        })
      }
      onDone?.()
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('places.err.save')),
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!content.trim()) return setError(t('memory.err.empty'))
    setError(null)
    save.mutate()
  }
  return (
    <form onSubmit={submit} className={cn('grid gap-3', memory ? 'py-3' : 'mt-4')}>
      <Field label={memory ? t('memory.fieldEdit') : t('memory.fieldNew')} error={error}>
        {(id, d) => (
          <Textarea
            id={id}
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={3}
            autoFocus={!!memory}
            placeholder={memory ? undefined : t('memory.placeholder')}
            aria-describedby={d}
            aria-invalid={!!error}
          />
        )}
      </Field>
      <div className="flex flex-wrap items-end gap-2">
        <Field label={t('memory.category')} className="min-w-40 flex-1">
          {(id) => (
            <Select id={id} value={category} onChange={(e) => setCategory(e.target.value)}>
              {memoryCategories().map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        {memory && (
          <Button variant="ghost" onClick={onDone}>
            {t('common.cancel')}
          </Button>
        )}
        <Button type="submit" variant="primary" loading={save.isPending}>
          {t('common.save')}
        </Button>
      </div>
    </form>
  )
}
