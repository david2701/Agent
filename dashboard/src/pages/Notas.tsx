import {
  ArrowLeft,
  DotsThree,
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
import { Markdown } from '@/components/Markdown'
import { AttachmentImage } from '@/components/chat/Thread'
import { Button, IconButton } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Menu } from '@/components/ui/menu'
import { Chip, Segmented } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { sourcePhrase } from '@/lib/entities'
import { compactWhen, longDate, plainText, relative, time } from '@/lib/format'
import { invalidateEntity, keys, useNote, useNotes, useNoteSearch, useNoteTags } from '@/lib/queries'
import type { Note } from '@/lib/types'
import { undoLatest } from '@/lib/undo'
import { useDebounced } from '@/lib/useStored'

const NEW = 'nueva'

export default function Notas() {
  const { id } = useParams<{ id?: string }>()
  const [q, setQ] = useState('')
  const [tag, setTag] = useState<string | null>(null)
  const dq = useDebounced(q.trim(), 300)
  const searching = dq.length > 1
  const notes = useNotes({ tag: tag ?? undefined })
  const search = useNoteSearch(dq)
  const tags = useNoteTags()

  const list = useMemo(() => {
    if (searching) return (search.data ?? []).filter((n) => !tag || n.tags.includes(tag))
    return notes.data ?? []
  }, [searching, search.data, notes.data, tag])

  const pinned = searching ? [] : list.filter((n) => n.pinned)
  const rest = searching ? list : list.filter((n) => !n.pinned)
  const loading = searching ? search.isLoading : notes.isLoading
  const error = searching ? search.error : notes.error

  return (
    <div className="flex h-[calc(100dvh-56px-68px-env(safe-area-inset-bottom))] md:h-[100dvh]">
      <aside
        className={cn('flex w-full min-w-0 flex-col border-line md:w-[320px] md:shrink-0 md:border-r xl:w-[360px]', id && 'max-md:hidden')}
        aria-label="Lista de notas"
      >
        <div className="flex items-center justify-between gap-3 px-4 pt-5 pb-3 md:pt-6">
          <h1 className="text-lg font-semibold tracking-tight md:text-xl">Notas</h1>
          <NavLinkButton to={`/notas/${NEW}`} />
        </div>
        <div className="px-4">
          <label className="relative block">
            <span className="sr-only">Buscar notas</span>
            <MagnifyingGlass className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" />
            <input
              type="search"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Buscar por tema o contenido"
              className="h-10 w-full rounded-control border border-line bg-surface pr-9 pl-9 text-base outline-none transition-colors focus:border-accent [&::-webkit-search-cancel-button]:appearance-none"
            />
            {q && (
              <button
                type="button"
                aria-label="Borrar búsqueda"
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
              {search.isFetching ? 'Buscando por significado…' : 'Resultados por significado, no solo por palabras exactas'}
            </p>
          )}
        </div>
        {(tags.data?.length ?? 0) > 0 && (
          <div className="mt-3 flex gap-1.5 overflow-x-auto px-4 pb-1 scrollbar-none" role="group" aria-label="Filtrar por etiqueta">
            <Chip selected={!tag} onClick={() => setTag(null)}>
              Todas
            </Chip>
            {tags.data!.map((t) => (
              <Chip key={t} selected={tag === t} onClick={() => setTag(tag === t ? null : t)}>
                #{t}
              </Chip>
            ))}
          </div>
        )}
        <div className="mt-2 min-h-0 flex-1 overflow-y-auto px-2 pb-4">
          {loading ? (
            <div className="grid gap-5 px-3 pt-3" aria-busy="true" aria-label="Cargando notas">
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
              title={searching ? 'Nada parecido' : tag ? `Sin notas con #${tag}` : 'Aún no tienes notas'}
              description={searching ? 'Prueba con otras palabras o quita el filtro.' : 'Escribe una aquí o dile a Kairo: “anota que…”.'}
            />
          ) : (
            <>
              {pinned.length > 0 && <ListLabel>Fijadas</ListLabel>}
              <ul className="grid gap-0.5">
                {pinned.map((n) => (
                  <NoteItem key={n.id} note={n} active={n.id === id} />
                ))}
              </ul>
              {pinned.length > 0 && rest.length > 0 && <ListLabel>Todas</ListLabel>}
              <ul className="grid gap-0.5">
                {rest.map((n) => (
                  <NoteItem key={n.id} note={n} active={n.id === id} showScore={searching} />
                ))}
              </ul>
            </>
          )}
        </div>
      </aside>

      <section className={cn('min-w-0 flex-1', !id && 'max-md:hidden')} aria-label="Nota">
        {id ? (
          <NoteEditor key={id} id={id} knownTags={tags.data ?? []} />
        ) : (
          <div className="flex h-full items-center justify-center">
            <EmptyState
              icon={<NotePencil />}
              title="Elige una nota"
              description="O empieza una nueva desde el botón de arriba. Puedes escribir en Markdown y verla formateada."
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
      Nueva nota
    </Button>
  )
}

function ListLabel({ children }: { children: string }) {
  return <p className="px-3 pt-3 pb-1 text-sm font-medium text-muted">{children}</p>
}

function NoteItem({ note, active, showScore }: { note: Note; active: boolean; showScore?: boolean }) {
  const excerpt = plainText(note.content)
  return (
    <li>
      <NavLink
        to={`/notas/${note.id}`}
        className={cn(
          'block rounded-control px-3 py-2.5 transition-colors',
          active ? 'bg-surface-2' : 'hover:bg-surface-2/60',
        )}
      >
        <span className="flex items-baseline gap-2">
          {note.pinned && <PushPin className="size-3.5 shrink-0 self-center text-faint" aria-label="Fijada" />}
          <span className="min-w-0 flex-1 truncate text-base font-medium text-fg">{note.title || excerpt.slice(0, 50) || 'Sin título'}</span>
          <span className="shrink-0 font-mono text-xs text-faint">{compactWhen(note.updatedAt)}</span>
        </span>
        {excerpt && <span className="mt-0.5 line-clamp-2 text-sm text-muted">{excerpt}</span>}
        {(note.tags.length > 0 || (showScore && note.score !== null)) && (
          <span className="mt-1.5 flex flex-wrap gap-x-2 text-xs text-faint">
            {note.tags.slice(0, 4).map((t) => (
              <span key={t}>#{t}</span>
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
}

const emptyDraft: Draft = { title: '', content: '', tags: [], pinned: false }

function sameDraft(a: Draft, b: Draft) {
  return a.title === b.title && a.content === b.content && a.pinned === b.pinned && a.tags.join('\u0000') === b.tags.join('\u0000')
}

function NoteEditor({ id, knownTags }: { id: string; knownTags: string[] }) {
  const isNew = id === NEW
  const qc = useQueryClient()
  const navigate = useNavigate()
  const note = useNote(isNew ? undefined : id)
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [base, setBase] = useState<Draft>(emptyDraft)
  const [mode, setMode] = useState<'edit' | 'preview'>(isNew ? 'edit' : 'preview')
  const [confirm, setConfirm] = useState(false)
  const allowNav = useRef(false)
  const titleRef = useRef<HTMLInputElement>(null)
  const dirty = !sameDraft(draft, base)

  // Carga inicial y cambios remotos cuando no hay edición en curso
  useEffect(() => {
    if (!note.data) return
    const d: Draft = { title: note.data.title, content: note.data.content, tags: note.data.tags, pinned: note.data.pinned }
    setBase(d)
    setDraft((cur) => (sameDraft(cur, base) ? d : cur))
  }, [note.data])

  useEffect(() => {
    if (isNew) titleRef.current?.focus()
  }, [isNew])


  const save = useMutation({
    mutationFn: async (d: Draft) => {
      if (isNew) return api.post<Note>('/notes', { title: d.title.trim(), content: d.content, tags: d.tags, pinned: d.pinned })
      return api.patch<Note>(`/notes/${id}`, { title: d.title.trim(), content: d.content, tags: d.tags, pinned: d.pinned })
    },
    onSuccess: (n) => {
      const d: Draft = { title: n.title, content: n.content, tags: n.tags, pinned: n.pinned }
      setBase(d)
      setDraft(d)
      qc.setQueryData(keys.note(n.id), n)
      invalidateEntity(qc, 'note')
      if (isNew) {
        allowNav.current = true
        navigate(`/notas/${n.id}`, { replace: true })
        toast({
          title: 'Nota creada',
          description: n.title || undefined,
          tone: 'success',
          actions: [
            {
              label: 'Deshacer',
              onClick: async () => {
                try {
                  navigate('/notas')
                  qc.removeQueries({ queryKey: keys.note(n.id) })
                  await undoLatest(qc, 'note', n.id, 'created')
                } catch (e) {
                  toastError(e, 'No se pudo deshacer')
                }
              },
            },
          ],
        })
      }
    },
    onError: (e) => toastError(e, 'No se pudo guardar la nota'),
  })

  const remove = useMutation({
    mutationFn: () => api.del(`/notes/${id}`),
    onSuccess: () => {
      const title = draft.title
      setConfirm(false)
      allowNav.current = true
      invalidateEntity(qc, 'note')
      navigate('/notas')
      toast({
        title: 'Nota eliminada',
        description: title || undefined,
        actions: [
          {
            label: 'Deshacer',
            onClick: async () => {
              try {
                await undoLatest(qc, 'note', id, 'deleted')
                navigate(`/notas/${id}`)
              } catch (e) {
                toastError(e, 'No se pudo deshacer')
              }
            },
          },
        ],
      })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar la nota'),
  })

  const togglePin = useMutation({
    mutationFn: (pinned: boolean) => api.patch<Note>(`/notes/${id}`, { pinned }),
    onSuccess: (n) => {
      setBase((b) => ({ ...b, pinned: n.pinned }))
      setDraft((d) => ({ ...d, pinned: n.pinned }))
      invalidateEntity(qc, 'note')
      toast({ title: n.pinned ? 'Nota fijada' : 'Nota sin fijar' })
    },
    onError: (e) => toastError(e, 'No se pudo cambiar'),
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
      <div className="mx-auto grid max-w-[72ch] gap-4 px-6 py-10" aria-busy="true" aria-label="Cargando nota">
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
        <IconButton label="Volver a la lista" className="md:hidden" onClick={() => navigate('/notas')}>
          <ArrowLeft className="size-5" />
        </IconButton>
        <Segmented
          label="Modo de la nota"
          size="sm"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'edit', label: 'Escribir' },
            { value: 'preview', label: 'Vista previa' },
          ]}
        />
        <span className="ml-auto hidden text-sm text-faint sm:inline" aria-live="polite">
          {save.isPending ? 'Guardando…' : dirty ? 'Cambios sin guardar' : n ? `Guardada ${relative(n.updatedAt)}` : ''}
        </span>
        <div className="ml-auto flex items-center gap-1 sm:ml-2">
          {!isNew && (
            <IconButton
              label={draft.pinned ? 'Quitar de fijadas' : 'Fijar nota'}
              tone={draft.pinned ? 'accent' : 'default'}
              onClick={() => togglePin.mutate(!draft.pinned)}
            >
              {draft.pinned ? <PushPinSlash className="size-[18px]" /> : <PushPin className="size-[18px]" />}
            </IconButton>
          )}
          {!isNew && (
            <Menu
              items={[{ label: 'Eliminar nota', icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => setConfirm(true) }]}
              trigger={(p) => (
                <IconButton label="Más opciones" {...p}>
                  <DotsThree className="size-5" weight="bold" />
                </IconButton>
              )}
            />
          )}
          <Button size="sm" variant={canSave ? 'primary' : 'secondary'} disabled={!canSave} loading={save.isPending} onClick={() => save.mutate(draft)}>
            Guardar
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
              placeholder="Título"
              aria-label="Título"
              className="w-full bg-transparent text-xl font-semibold tracking-tight text-fg outline-none placeholder:text-faint"
            />
          ) : (
            <h2 className="text-xl font-semibold tracking-tight text-fg">{draft.title || <span className="text-faint">Sin título</span>}</h2>
          )}
          {n && (
            <p className="mt-2 text-sm text-faint">
              {capitalizeFirst(longDate(n.createdAt))}, <span className="font-mono">{time(n.createdAt)}</span>
              {n.source && n.source !== 'dashboard' && <> · {sourcePhrase(n.source)}</>}
            </p>
          )}

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
                Esta nota está vacía. Toca para escribir.
              </button>
            )}
          </div>

          {n && n.attachments.length > 0 && (
            <div className="mt-8 border-t border-line pt-5">
              <p className="mb-3 text-sm font-medium text-muted">Adjuntos</p>
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
        title="¿Eliminar esta nota?"
        description="Podrás deshacerlo desde el aviso o desde Actividad."
      />
      {dirty && <UnsavedGuard allowRef={allowNav} />}
    </div>
  )
}

/** Pide confirmación al salir con cambios sin guardar (solo montado mientras hay cambios). */
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
      title="¿Salir sin guardar?"
      description="Tienes cambios en esta nota que se perderán."
      confirmLabel="Descartar cambios"
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
      placeholder="Escribe aquí. Usa # para títulos, - para listas y - [ ] para pendientes."
      aria-label="Contenido de la nota"
      className="w-full resize-none bg-transparent font-sans text-md leading-relaxed text-fg outline-none placeholder:text-faint"
    />
  )
}

function TagEditor({ tags, onChange, known }: { tags: string[]; onChange: (t: string[]) => void; known: string[] }) {
  const [value, setValue] = useState('')
  const add = (raw: string) => {
    const t = raw.trim().replace(/^#/, '').toLowerCase()
    if (t && !tags.includes(t)) onChange([...tags, t])
    setValue('')
  }
  return (
    <div className="mt-4 flex flex-wrap items-center gap-1.5">
      {tags.map((t) => (
        <span key={t} className="inline-flex h-7 items-center gap-1 rounded-full bg-surface-2 pr-1 pl-2.5 text-sm text-muted">
          #{t}
          <button
            type="button"
            aria-label={`Quitar etiqueta ${t}`}
            onClick={() => onChange(tags.filter((x) => x !== t))}
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
        placeholder={tags.length ? 'Otra etiqueta' : 'Agregar etiqueta'}
        aria-label="Agregar etiqueta"
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
