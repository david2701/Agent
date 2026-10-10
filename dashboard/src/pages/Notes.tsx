import {
  ArrowLeft,
  DotsThree,
  House,
  MagnifyingGlass,
  Notepad,
  NotePencil,
  PushPin,
  PushPinSlash,
  Sparkle,
  Trash,
  X,
} from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { NavLink, useBlocker, useNavigate, useParams } from 'react-router'
import { FamilyIcon } from '@/components/family'
import { Markdown } from '@/components/Markdown'
import { AttachmentImage } from '@/components/chat/Thread'
import { Button, IconButton } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Switch } from '@/components/ui/form'
import { Menu } from '@/components/ui/menu'
import { Chip, Segmented } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { sourcePhrase } from '@/lib/entities'
import { useFamilyInfo } from '@/lib/family'
import { compactWhen, longDate, plainText, relative, time } from '@/lib/format'
import { t } from '@/lib/i18n'
import { invalidateEntity, keys, useNote, useNotes, useNoteSearch, useNoteTags } from '@/lib/queries'
import type { Note } from '@/lib/types'
import { undoLatest } from '@/lib/undo'
import { useDebounced } from '@/lib/useStored'

const NEW = 'new'

type Scope = 'all' | 'mine' | 'family'
const scopes = (): { id: Scope; label: string }[] => [
  { id: 'all', label: t('notes.scopeAll') },
  { id: 'mine', label: t('notes.scopeMine') },
  { id: 'family', label: t('notes.scopeFamily') },
]

export default function NotesPage() {
  const { id } = useParams<{ id?: string }>()
  const [q, setQ] = useState('')
  const [tag, setTag] = useState<string | null>(null)
  const [scope, setScope] = useState<Scope>('all')
  const fam = useFamilyInfo()
  const dq = useDebounced(q.trim(), 300)
  const searching = dq.length > 1
  const notes = useNotes({ tag: tag ?? undefined })
  const search = useNoteSearch(dq)
  const tags = useNoteTags()

  const showScopes = fam.hasOthers || scope !== 'all'
  const list = useMemo(() => {
    const base = searching ? (search.data ?? []).filter((n) => !tag || n.tags.includes(tag)) : (notes.data ?? [])
    if (scope === 'mine') return base.filter((n) => fam.isMine(n.ownerId))
    if (scope === 'family') return base.filter((n) => n.shared)
    return base
  }, [searching, search.data, notes.data, tag, scope, fam])

  const pinned = searching ? [] : list.filter((n) => n.pinned)
  const rest = searching ? list : list.filter((n) => !n.pinned)
  const loading = searching ? search.isLoading : notes.isLoading
  const error = searching ? search.error : notes.error

  return (
    <div className="flex h-[calc(100dvh-56px-68px-env(safe-area-inset-bottom))] md:h-[100dvh]">
      <aside
        className={cn('flex w-full min-w-0 flex-col border-line md:w-[320px] md:shrink-0 md:border-r xl:w-[360px]', id && 'max-md:hidden')}
        aria-label={t('notes.list')}
      >
        <div className="flex items-center justify-between gap-3 px-4 pt-5 pb-3 md:pt-6">
          <h1 className="text-lg font-semibold tracking-tight md:text-xl">{t('nav.notes')}</h1>
          <NavLinkButton to={`/notes/${NEW}`} />
        </div>
        <div className="px-4">
          <label className="relative block">
            <span className="sr-only">{t('notes.search')}</span>
            <MagnifyingGlass className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" />
            <input
              type="search"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={t('notes.searchPlaceholder')}
              className="h-10 w-full rounded-control border border-line bg-surface pr-9 pl-9 text-base outline-none transition-colors focus:border-accent [&::-webkit-search-cancel-button]:appearance-none"
            />
            {q && (
              <button
                type="button"
                aria-label={t('notes.clearSearch')}
                onClick={() => setQ('')}
                className="absolute top-1/2 right-1.5 flex size-7 -translate-y-1/2 items-center justify-center rounded-[8px] text-faint transition-colors hover:bg-surface-2 hover:text-fg"
              >
                <X className="size-3.5" />
              </button>
            )}
          </label>
          {searching && (
            <p className="mt-2 flex items-center gap-1.5 text-xs text-muted">
              <Sparkle className="size-3.5 shrink-0 text-accent-strong" />
              {search.isFetching ? t('notes.searchingMeaning') : t('notes.resultsMeaning')}
            </p>
          )}
        </div>
        {showScopes && (
          <div className="mt-3 flex gap-1.5 overflow-x-auto px-4 scrollbar-none" role="group" aria-label={t('notes.filterOwner')}>
            {scopes().map((sc) => (
              <Chip key={sc.id} selected={scope === sc.id} onClick={() => setScope(sc.id)}>
                {sc.id === 'family' && <House className="size-3.5" />}
                {sc.label}
              </Chip>
            ))}
          </div>
        )}
        {(tags.data?.length ?? 0) > 0 && (
          <div className={cn('flex gap-1.5 overflow-x-auto px-4 pb-1 scrollbar-none', showScopes ? 'mt-2' : 'mt-3')} role="group" aria-label={t('notes.filterTag')}>
            <Chip selected={!tag} onClick={() => setTag(null)}>
              {showScopes ? t('notes.anyTag') : t('notes.allTags')}
            </Chip>
            {tags.data!.map((tg) => (
              <Chip key={tg} selected={tag === tg} onClick={() => setTag(tag === tg ? null : tg)}>
                #{tg}
              </Chip>
            ))}
          </div>
        )}
        <div className="mt-2 min-h-0 flex-1 overflow-y-auto px-2 pb-4">
          {loading ? (
            <div className="grid gap-5 px-3 pt-3" aria-busy="true" aria-label={t('notes.loading')}>
              {Array.from({ length: 6 }, (_, i) => (
                <div key={i} className="grid gap-2">
                  <Skeleton className="h-3.5 w-3/5" />
                  <Skeleton className="h-3 w-full" />
                  <Skeleton className="h-3 w-4/5" />
                </div>
              ))}
            </div>
          ) : error ? (
            <ErrorState message={(error as Error).message} onRetry={() => void (searching ? search.refetch() : notes.refetch())} />
          ) : list.length === 0 ? (
            <EmptyState
              compact
              icon={<Notepad />}
              title={
                searching
                  ? t('notes.emptySearch')
                  : tag
                    ? t('notes.emptyTag', { tag })
                    : scope === 'family'
                      ? t('notes.emptyFamily')
                      : scope === 'mine'
                        ? t('notes.emptyMine')
                        : t('notes.emptyAll')
              }
              description={
                searching
                  ? t('notes.emptySearchHint')
                  : scope === 'family'
                    ? t('notes.emptyFamilyHint')
                    : t('notes.emptyHint')
              }
            />
          ) : (
            <>
              {pinned.length > 0 && <ListLabel>{t('notes.pinned')}</ListLabel>}
              <ul className="grid gap-0.5">
                {pinned.map((n) => (
                  <NoteItem key={n.id} note={n} active={n.id === id} />
                ))}
              </ul>
              {pinned.length > 0 && rest.length > 0 && <ListLabel>{t('notes.all')}</ListLabel>}
              <ul className="grid gap-0.5">
                {rest.map((n) => (
                  <NoteItem key={n.id} note={n} active={n.id === id} showScore={searching} />
                ))}
              </ul>
            </>
          )}
        </div>
      </aside>

      <section className={cn('min-w-0 flex-1', !id && 'max-md:hidden')} aria-label={t('notes.note')}>
        {id ? (
          <NoteEditor key={id} id={id} knownTags={tags.data ?? []} />
        ) : (
          <div className="flex h-full items-center justify-center">
            <EmptyState
              icon={<NotePencil />}
              title={t('notes.pick')}
              description={t('notes.pickHint')}
            />
          </div>
        )}
      </section>
    </div>
  )
}

function NavLinkButton({ to }: { to: string }) {
  const navigate = useNavigate()
  return (
    <Button size="sm" variant="primary" icon={<NotePencil className="size-4" />} onClick={() => navigate(to)}>
      {t('notes.new')}
    </Button>
  )
}

function ListLabel({ children }: { children: string }) {
  return <p className="px-3 pt-3 pb-1 text-sm font-medium text-muted">{children}</p>
}

function NoteItem({ note, active, showScore }: { note: Note; active: boolean; showScore?: boolean }) {
  const excerpt = plainText(note.content)
  const fam = useFamilyInfo()
  const by = note.shared && !fam.isMine(note.ownerId) ? fam.nameOf(note.ownerId) : null
  return (
    <li>
      <NavLink
        to={`/notes/${note.id}`}
        className={cn(
          'block rounded-control px-3 py-2.5 transition-colors',
          active ? 'bg-surface-2' : 'hover:bg-surface-2/60',
        )}
      >
        <span className="flex items-baseline gap-2">
          {note.pinned && <PushPin className="size-3.5 shrink-0 self-center text-faint" aria-label={t('notes.pinnedLabel')} />}
          <span className="min-w-0 flex-1 truncate text-base font-medium text-fg">{note.title || excerpt.slice(0, 50) || t('notes.untitled')}</span>
          <span className="shrink-0 font-mono text-xs text-faint">{compactWhen(note.updatedAt)}</span>
        </span>
        {excerpt && <span className="mt-0.5 line-clamp-2 text-sm text-muted">{excerpt}</span>}
        {(note.tags.length > 0 || note.shared || (showScore && note.score !== null)) && (
          <span className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-faint">
            {note.shared && (
              <span className="inline-flex h-5 items-center gap-1 rounded-full bg-surface-2 px-2 font-medium text-muted">
                <House className="size-3" aria-hidden />
                {by ? t('family.tagFrom', { name: by }) : t('family.tag')}
              </span>
            )}
            {note.tags.slice(0, 4).map((tg) => (
              <span key={tg}>#{tg}</span>
            ))}
          </span>
        )}
      </NavLink>
    </li>
  )
}

interface Draft {
  title: string
  content: string
  tags: string[]
  pinned: boolean
  /** Only used when creating: on an existing note, sharing is saved instantly. */
  shared: boolean
}

const emptyDraft: Draft = { title: '', content: '', tags: [], pinned: false, shared: false }

function sameDraft(a: Draft, b: Draft) {
  return (
    a.title === b.title &&
    a.content === b.content &&
    a.pinned === b.pinned &&
    a.shared === b.shared &&
    a.tags.join('\u0000') === b.tags.join('\u0000')
  )
}

function NoteEditor({ id, knownTags }: { id: string; knownTags: string[] }) {
  const isNew = id === NEW
  const qc = useQueryClient()
  const navigate = useNavigate()
  const note = useNote(isNew ? undefined : id)
  const fam = useFamilyInfo()
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [base, setBase] = useState<Draft>(emptyDraft)
  const [mode, setMode] = useState<'edit' | 'preview'>(isNew ? 'edit' : 'preview')
  const [confirm, setConfirm] = useState(false)
  const allowNav = useRef(false)
  const titleRef = useRef<HTMLInputElement>(null)
  const dirty = !sameDraft(draft, base)

  // Initial load and remote changes when no edit is in progress
  useEffect(() => {
    if (!note.data) return
    const d: Draft = { title: note.data.title, content: note.data.content, tags: note.data.tags, pinned: note.data.pinned, shared: note.data.shared }
    setBase(d)
    setDraft((cur) => (sameDraft(cur, base) ? d : cur))
  }, [note.data])

  useEffect(() => {
    if (isNew) titleRef.current?.focus()
  }, [isNew])


  const save = useMutation({
    mutationFn: async (d: Draft) => {
      if (isNew) return api.post<Note>('/notes', { title: d.title.trim(), content: d.content, tags: d.tags, pinned: d.pinned, shared: d.shared })
      return api.patch<Note>(`/notes/${id}`, { title: d.title.trim(), content: d.content, tags: d.tags, pinned: d.pinned })
    },
    onSuccess: (n) => {
      const d: Draft = { title: n.title, content: n.content, tags: n.tags, pinned: n.pinned, shared: n.shared }
      setBase(d)
      setDraft(d)
      qc.setQueryData(keys.note(n.id), n)
      invalidateEntity(qc, 'note')
      if (isNew) {
        allowNav.current = true
        navigate(`/notes/${n.id}`, { replace: true })
        toast({
          title: t('notes.created'),
          description: n.title || undefined,
          tone: 'success',
          actions: [
            {
              label: t('common.undo'),
              onClick: async () => {
                try {
                  navigate('/notes')
                  qc.removeQueries({ queryKey: keys.note(n.id) })
                  await undoLatest(qc, 'note', n.id, 'created')
                } catch (e) {
                  toastError(e, t('common.couldNotUndo'))
                }
              },
            },
          ],
        })
      }
    },
    onError: (e) => toastError(e, t('notes.err.save')),
  })

  const remove = useMutation({
    mutationFn: () => api.del(`/notes/${id}`),
    onSuccess: () => {
      const title = draft.title
      setConfirm(false)
      allowNav.current = true
      invalidateEntity(qc, 'note')
      navigate('/notes')
      toast({
        title: t('notes.deleted'),
        description: title || undefined,
        actions: [
          {
            label: t('common.undo'),
            onClick: async () => {
              try {
                await undoLatest(qc, 'note', id, 'deleted')
                navigate(`/notes/${id}`)
              } catch (e) {
                toastError(e, t('common.couldNotUndo'))
              }
            },
          },
        ],
      })
    },
    onError: (e) => toastError(e, t('notes.err.delete')),
  })

  const togglePin = useMutation({
    mutationFn: (pinned: boolean) => api.patch<Note>(`/notes/${id}`, { pinned }),
    onSuccess: (n) => {
      setBase((b) => ({ ...b, pinned: n.pinned }))
      setDraft((d) => ({ ...d, pinned: n.pinned }))
      invalidateEntity(qc, 'note')
      toast({ title: n.pinned ? t('notes.pinnedToast') : t('notes.unpinnedToast') })
    },
    onError: (e) => toastError(e, t('common.couldNotChange')),
  })

  const toggleShared = useMutation({
    mutationFn: (shared: boolean) => api.patch<Note>(`/notes/${id}`, { shared }),
    onMutate: (shared) => {
      setBase((b) => ({ ...b, shared }))
      setDraft((d) => ({ ...d, shared }))
    },
    onSuccess: (n) => {
      qc.setQueryData(keys.note(n.id), n)
      invalidateEntity(qc, 'note')
      toast({
        title: n.shared ? t('notes.sharedToast') : t('notes.personalToast'),
        description: n.shared ? t('notes.sharedToastBody') : undefined,
      })
    },
    onError: (e, shared) => {
      setBase((b) => ({ ...b, shared: !shared }))
      setDraft((d) => ({ ...d, shared: !shared }))
      toastError(e, t('common.couldNotChange'))
    },
  })

  const canSave = (draft.title.trim() || draft.content.trim()) && dirty && !save.isPending

  const onKeyDown = (e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
      e.preventDefault()
      if (canSave) save.mutate(draft)
    }
  }

  if (!isNew && note.isLoading) {
    return (
      <div className="mx-auto grid max-w-[72ch] gap-4 px-6 py-10" aria-busy="true" aria-label={t('notes.loadingNote')}>
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-3 w-40" />
        <div className="mt-4 grid gap-2.5">
          {Array.from({ length: 7 }, (_, i) => (
            <Skeleton key={i} className={cn('h-3.5', i % 3 === 2 ? 'w-2/3' : 'w-full')} />
          ))}
        </div>
      </div>
    )
  }
  if (!isNew && note.isError) {
    return (
      <div className="flex h-full items-center justify-center">
        <ErrorState message={(note.error as Error).message} onRetry={() => void note.refetch()} />
      </div>
    )
  }

  const n = note.data
  return (
    <div className="flex h-full flex-col" onKeyDown={onKeyDown}>
      <div className="flex h-14 shrink-0 items-center gap-2 border-b border-line px-3 md:h-16 md:px-5">
        <IconButton label={t('notes.backToList')} className="md:hidden" onClick={() => navigate('/notes')}>
          <ArrowLeft className="size-5" />
        </IconButton>
        <Segmented
          label={t('notes.mode')}
          size="sm"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'edit', label: t('notes.write') },
            { value: 'preview', label: t('notes.preview') },
          ]}
        />
        <span className="ml-auto hidden text-sm text-faint sm:inline" aria-live="polite">
          {save.isPending ? t('notes.saving') : dirty ? t('notes.unsaved') : n ? t('notes.savedAgo', { when: relative(n.updatedAt) }) : ''}
        </span>
        <div className="ml-auto flex items-center gap-1 sm:ml-2">
          {!isNew && (
            <IconButton
              label={draft.pinned ? t('notes.unpin') : t('notes.pin')}
              tone={draft.pinned ? 'accent' : 'default'}
              onClick={() => togglePin.mutate(!draft.pinned)}
            >
              {draft.pinned ? <PushPinSlash className="size-[18px]" /> : <PushPin className="size-[18px]" />}
            </IconButton>
          )}
          {!isNew && (
            <Menu
              items={[{ label: t('notes.delete'), icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => setConfirm(true) }]}
              trigger={(p) => (
                <IconButton label={t('chat.moreOptions')} {...p}>
                  <DotsThree className="size-5" weight="bold" />
                </IconButton>
              )}
            />
          )}
          <Button size="sm" variant={canSave ? 'primary' : 'secondary'} disabled={!canSave} loading={save.isPending} onClick={() => save.mutate(draft)}>
            {t('common.save')}
          </Button>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <article className="mx-auto max-w-[72ch] px-5 py-6 md:px-8 md:py-9">
          {mode === 'edit' ? (
            <input
              ref={titleRef}
              value={draft.title}
              onChange={(e) => setDraft({ ...draft, title: e.target.value })}
              placeholder={t('notes.titlePlaceholder')}
              aria-label={t('notes.titlePlaceholder')}
              className="w-full bg-transparent text-xl font-semibold tracking-tight text-fg outline-none placeholder:text-faint"
            />
          ) : (
            <h2 className="text-xl font-semibold tracking-tight text-fg">{draft.title || <span className="text-faint">{t('notes.untitled')}</span>}</h2>
          )}
          {n && (
            <p className="mt-2 text-sm text-faint">
              {capitalizeFirst(longDate(n.createdAt))}, <span className="font-mono">{time(n.createdAt)}</span>
              {n.source && n.source !== 'dashboard' && <> · {sourcePhrase(n.source)}</>}
            </p>
          )}

          <ShareRow
            fam={fam}
            note={n}
            shared={draft.shared}
            onChange={(v) => (isNew ? setDraft({ ...draft, shared: v }) : toggleShared.mutate(v))}
            busy={toggleShared.isPending}
          />

          <TagEditor tags={draft.tags} onChange={(tags) => setDraft({ ...draft, tags })} known={knownTags} />

          <div className="mt-6">
            {mode === 'edit' ? (
              <AutoTextarea
                value={draft.content}
                onChange={(content) => setDraft({ ...draft, content })}
                autoFocus={!isNew}
              />
            ) : draft.content.trim() ? (
              <Markdown className="text-md leading-relaxed">{draft.content}</Markdown>
            ) : (
              <button type="button" onClick={() => setMode('edit')} className="text-base text-faint hover:text-muted">
                {t('notes.emptyNote')}
              </button>
            )}
          </div>

          {n && n.attachments.length > 0 && (
            <div className="mt-8 border-t border-line pt-5">
              <p className="mb-3 text-sm font-medium text-muted">{t('notes.attachments')}</p>
              <div className="flex flex-wrap gap-3">
                {n.attachments.map((f) => (
                  <AttachmentImage key={f.id} file={f} />
                ))}
              </div>
            </div>
          )}
        </article>
      </div>

      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title={t('notes.deleteTitle')}
        description={t('notes.deleteBody')}
      />
      {dirty && <UnsavedGuard allowRef={allowNav} />}
    </div>
  )
}

/**
 * "Share with the family": only whoever created the note can change it; everyone else sees who shared it.
 * Hidden when nobody else is in the household (unless the note is already shared).
 */
function ShareRow({
  fam,
  note,
  shared,
  onChange,
  busy,
}: {
  fam: ReturnType<typeof useFamilyInfo>
  note: Note | undefined
  shared: boolean
  onChange: (v: boolean) => void
  busy: boolean
}) {
  if (!fam.hasOthers && !shared) return null
  const owner = note && !fam.isMine(note.ownerId)
  if (owner)
    return (
      <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
        <FamilyIcon className="text-muted" />
        {t('notes.sharedBy', { name: fam.nameOf(note.ownerId) ?? t('reminder.someoneElse') })}
      </p>
    )
  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1">
      <label className="flex items-center gap-2.5 text-sm text-fg">
        <Switch checked={shared} onChange={onChange} disabled={busy} label={t('notes.shareSwitch')} />
        {t('notes.shareSwitch')}
      </label>
      <span className="text-sm text-muted">{shared ? t('notes.sharedWith', { household: fam.household?.name ?? t('notes.theFamily') }) : t('notes.onlyYou')}</span>
    </div>
  )
}

/** Asks for confirmation when leaving with unsaved changes (only mounted while there are changes). */
function UnsavedGuard({ allowRef }: { allowRef: { current: boolean } }) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => !allowRef.current && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => {
    const onUnload = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', onUnload)
    return () => window.removeEventListener('beforeunload', onUnload)
  }, [])
  return (
    <ConfirmDialog
      open={blocker.state === 'blocked'}
      onClose={() => blocker.reset?.()}
      onConfirm={() => blocker.proceed?.()}
      title={t('notes.leaveTitle')}
      description={t('notes.leaveBody')}
      confirmLabel={t('notes.discardChanges')}
    />
  )
}

function capitalizeFirst(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

function AutoTextarea({ value, onChange, autoFocus }: { value: string; onChange: (v: string) => void; autoFocus?: boolean }) {
  const ref = useRef<HTMLTextAreaElement>(null)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = '0px'
    el.style.height = `${Math.max(320, el.scrollHeight)}px`
  }, [value])
  return (
    <textarea
      ref={ref}
      value={value}
      autoFocus={autoFocus}
      onChange={(e) => onChange(e.target.value)}
      placeholder={t('notes.contentPlaceholder')}
      aria-label={t('notes.contentLabel')}
      className="w-full resize-none bg-transparent font-sans text-md leading-relaxed text-fg outline-none placeholder:text-faint"
    />
  )
}

function TagEditor({ tags, onChange, known }: { tags: string[]; onChange: (tags: string[]) => void; known: string[] }) {
  const [value, setValue] = useState('')
  const add = (raw: string) => {
    const tag = raw.trim().replace(/^#/, '').toLowerCase()
    if (tag && !tags.includes(tag)) onChange([...tags, tag])
    setValue('')
  }
  return (
    <div className="mt-4 flex flex-wrap items-center gap-1.5">
      {tags.map((tag) => (
        <span key={tag} className="inline-flex h-7 items-center gap-1 rounded-full bg-surface-2 pr-1 pl-2.5 text-sm text-muted">
          #{tag}
          <button
            type="button"
            aria-label={t('notes.removeTag', { tag })}
            onClick={() => onChange(tags.filter((x) => x !== tag))}
            className="flex size-5 items-center justify-center rounded-full text-faint hover:bg-line hover:text-fg"
          >
            <X className="size-3" />
          </button>
        </span>
      ))}
      <input
        value={value}
        list="note-tags"
        onChange={(e) => {
          const v = e.target.value
          if (v.endsWith(',')) add(v.slice(0, -1))
          else setValue(v)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            add(value)
          } else if (e.key === 'Backspace' && !value && tags.length) onChange(tags.slice(0, -1))
        }}
        onBlur={() => value.trim() && add(value)}
        placeholder={tags.length ? t('notes.anotherTag') : t('notes.addTag')}
        aria-label={t('notes.addTag')}
        className="h-7 min-w-28 flex-1 bg-transparent px-1 text-sm text-fg outline-none placeholder:text-faint"
      />
      <datalist id="note-tags">
        {known.filter((k) => !tags.includes(k)).map((k) => (
          <option key={k} value={k} />
        ))}
      </datalist>
    </div>
  )
}
