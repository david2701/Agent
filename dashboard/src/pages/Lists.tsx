import { ArrowLeft, Broom, DotsThree, House, ListChecks, LockSimple, PencilSimple, Plus, Trash, X } from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useEffect, useState, type FormEvent } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router'
import { FamilyIcon, FamilyTag } from '@/components/family'
import { Button, IconButton } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Checkbox, Input, Switch } from '@/components/ui/form'
import { Menu } from '@/components/ui/menu'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useFamilyInfo } from '@/lib/family'
import { useToggleItem } from '@/lib/mutations'
import { invalidateEntity, keys, useLists } from '@/lib/queries'
import type { KList, ListItem } from '@/lib/types'
import { undoLatest } from '@/lib/undo'

export default function Listas() {
  const { id } = useParams<{ id?: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const lists = useLists()
  const [name, setName] = useState('')
  const [shared, setShared] = useState(false)
  const [creating, setCreating] = useState(false)
  const fam = useFamilyInfo()

  // En escritorio, abre la primera lista si no hay una elegida
  useEffect(() => {
    if (!id && lists.data?.length && window.matchMedia('(min-width: 768px)').matches) navigate(`/listas/${lists.data[0].id}`, { replace: true })
  }, [id, lists.data, navigate])

  const create = useMutation({
    mutationFn: (n: string) => api.post<KList>('/lists', shared ? { name: n, shared: true } : { name: n }),
    onSuccess: (l) => {
      setName('')
      setShared(false)
      setCreating(false)
      invalidateEntity(qc, 'list')
      navigate(`/listas/${l.id}`)
    },
    onError: (e) => toastError(e, 'No se pudo crear la lista'),
  })

  const current = lists.data?.find((l) => l.id === id)

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-6 md:px-8 md:py-9">
      <div className="grid items-start gap-5 md:grid-cols-[260px_minmax(0,1fr)] lg:grid-cols-[300px_minmax(0,1fr)] lg:gap-8">
        <aside className={cn(id && 'max-md:hidden')} aria-label="Tus listas">
          <div className="flex items-center justify-between gap-3">
            <h1 className="text-lg font-semibold tracking-tight md:text-xl">Listas</h1>
            <Button size="sm" variant="primary" icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>
              Nueva
            </Button>
          </div>
          {creating && (
            <form
              className="mt-4 grid gap-2.5"
              onSubmit={(e) => {
                e.preventDefault()
                if (name.trim()) create.mutate(name.trim())
              }}
            >
              <div className="flex gap-2">
                <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Nombre de la lista" aria-label="Nombre de la lista" autoFocus />
                <Button type="submit" loading={create.isPending} disabled={!name.trim()}>
                  Crear
                </Button>
                <IconButton label="Cancelar" onClick={() => setCreating(false)}>
                  <X className="size-4" />
                </IconButton>
              </div>
              {fam.hasOthers && (
                <label className="flex items-center gap-2.5 px-0.5 text-sm text-muted">
                  <Switch checked={shared} onChange={setShared} label="Compartir con la familia" />
                  Compartir con la familia
                </label>
              )}
            </form>
          )}
          <div className="mt-4">
            {lists.isLoading ? (
              <div className="grid gap-2" aria-busy="true">
                {Array.from({ length: 4 }, (_, i) => (
                  <Skeleton key={i} className="h-11 rounded-control" />
                ))}
              </div>
            ) : lists.isError ? (
              <ErrorState message={(lists.error as Error).message} onRetry={() => void lists.refetch()} />
            ) : (lists.data ?? []).length === 0 ? (
              <EmptyState compact icon={<ListChecks />} title="Sin listas" description="Crea una o dile a Kairo: “agrega leche a la lista del mercado”." />
            ) : (
              <ul className="grid gap-0.5">
                {lists.data!.map((l) => {
                  const pending = l.items.filter((i) => !i.checked).length
                  return (
                    <li key={l.id}>
                      <NavLink
                        to={`/listas/${l.id}`}
                        className={({ isActive }) =>
                          cn(
                            'flex h-11 items-center gap-3 rounded-control px-3 text-base transition-colors',
                            isActive ? 'bg-surface-2 font-medium text-fg' : 'text-muted hover:bg-surface-2/60 hover:text-fg',
                          )
                        }
                      >
                        <ListChecks className="size-[18px] shrink-0 text-faint" />
                        <span className="min-w-0 flex-1 truncate">{l.name}</span>
                        {l.shared && <FamilyIcon label={fam.isMine(l.ownerId) ? 'Familiar' : `Familiar, de ${fam.nameOf(l.ownerId) ?? 'otra persona'}`} />}
                        <span className="font-mono text-sm text-faint tnum">{pending}</span>
                      </NavLink>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        </aside>

        <section className={cn(!id && 'max-md:hidden')} aria-label="Lista">
          {current ? (
            <ListDetail key={current.id} list={current} />
          ) : id && lists.isSuccess ? (
            <EmptyState icon={<ListChecks />} title="Esta lista ya no existe" action={<Button onClick={() => navigate('/listas')}>Ver listas</Button>} />
          ) : !id && (lists.data ?? []).length > 0 ? null : lists.isLoading ? (
            <Skeleton className="h-80 rounded-card" />
          ) : null}
        </section>
      </div>
    </div>
  )
}

function ListDetail({ list }: { list: KList }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const toggle = useToggleItem()
  const reduce = useReducedMotion()
  const fam = useFamilyInfo()
  const isOwner = fam.isMine(list.ownerId)
  const [text, setText] = useState('')
  const [renaming, setRenaming] = useState<string | null>(null)
  const [confirm, setConfirm] = useState(false)
  const pending = list.items.filter((i) => !i.checked)
  const checked = list.items.filter((i) => i.checked)

  const setList = (l: KList) => {
    const prev = qc.getQueryData<KList[]>(keys.lists)
    if (prev) qc.setQueryData<KList[]>(keys.lists, prev.map((x) => (x.id === l.id ? l : x)))
    void qc.invalidateQueries({ queryKey: keys.today })
  }

  const add = useMutation({
    mutationFn: (items: string[]) => api.post<KList>(`/lists/${list.id}/items`, { items }),
    onSuccess: setList,
    onError: (e) => toastError(e, 'No se pudo agregar'),
  })
  const removeItem = useMutation({
    mutationFn: (item: ListItem) => api.del<KList>(`/lists/${list.id}/items/${item.id}`),
    onMutate: (item) => {
      const prev = qc.getQueryData<KList[]>(keys.lists)
      if (prev) qc.setQueryData<KList[]>(keys.lists, prev.map((x) => (x.id === list.id ? { ...x, items: x.items.filter((i) => i.id !== item.id) } : x)))
      return { prev }
    },
    onSuccess: setList,
    onError: (e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(keys.lists, ctx.prev)
      toastError(e, 'No se pudo quitar')
    },
  })
  const editItem = useMutation({
    mutationFn: ({ item, value }: { item: ListItem; value: string }) => api.patch<KList>(`/lists/${list.id}/items/${item.id}`, { text: value }),
    onSuccess: setList,
    onError: (e) => toastError(e, 'No se pudo editar'),
  })
  const clear = useMutation({
    mutationFn: () => api.post<KList>(`/lists/${list.id}/clear-checked`),
    onSuccess: (l) => {
      setList(l)
      toast({ title: 'Marcados eliminados' })
    },
    onError: (e) => toastError(e, 'No se pudo limpiar'),
  })
  const rename = useMutation({
    mutationFn: (name: string) => api.patch<KList>(`/lists/${list.id}`, { name }),
    onSuccess: (l) => {
      setRenaming(null)
      setList(l)
    },
    onError: (e) => toastError(e, 'No se pudo renombrar'),
  })
  const share = useMutation({
    mutationFn: (shared: boolean) => api.patch<KList>(`/lists/${list.id}`, { shared }),
    onSuccess: (l) => {
      setList(l)
      invalidateEntity(qc, 'list')
      toast({
        title: l.shared ? 'Lista compartida con la familia' : 'La lista vuelve a ser personal',
        description: l.shared ? 'Todos en la familia pueden verla y agregar cosas.' : undefined,
      })
    },
    onError: (e) => toastError(e, 'No se pudo cambiar'),
  })
  const remove = useMutation({
    mutationFn: () => api.del(`/lists/${list.id}`),
    onSuccess: () => {
      setConfirm(false)
      invalidateEntity(qc, 'list')
      navigate('/listas', { replace: true })
      toast({
        title: 'Lista eliminada',
        description: list.name,
        actions: [
          {
            label: 'Deshacer',
            onClick: () =>
              undoLatest(qc, 'list', list.id, 'deleted')
                .then(() => navigate(`/listas/${list.id}`))
                .catch((e) => toastError(e, 'No se pudo deshacer')),
          },
        ],
      })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar'),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const items = text
      .split(/[,\n]/)
      .map((s) => s.trim())
      .filter(Boolean)
    if (!items.length) return
    add.mutate(items)
    setText('')
  }

  return (
    <div className="rounded-card border border-line bg-surface">
      <header className="flex items-center gap-2 border-b border-line px-3 py-3 md:px-5">
        <IconButton label="Volver a las listas" className="md:hidden" onClick={() => navigate('/listas')}>
          <ArrowLeft className="size-5" />
        </IconButton>
        {renaming !== null ? (
          <form
            className="flex flex-1 gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              if (renaming.trim()) rename.mutate(renaming.trim())
            }}
          >
            <Input value={renaming} onChange={(e) => setRenaming(e.target.value)} autoFocus aria-label="Nuevo nombre" />
            <Button type="submit" loading={rename.isPending}>
              Guardar
            </Button>
            <Button variant="ghost" onClick={() => setRenaming(null)}>
              Cancelar
            </Button>
          </form>
        ) : (
          <>
            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2.5 gap-y-1">
              <h2 className="min-w-0 truncate text-md font-semibold tracking-tight">{list.name}</h2>
              {list.shared && <FamilyTag by={isOwner ? null : fam.nameOf(list.ownerId)} />}
            </div>
            <span className="font-mono text-sm text-faint tnum">
              {pending.length}/{list.items.length}
            </span>
            <Menu
              items={[
                { label: 'Renombrar', icon: <PencilSimple className="size-4" />, onSelect: () => setRenaming(list.name) },
                isOwner &&
                  (fam.hasOthers || list.shared) &&
                  (list.shared
                    ? { label: 'Dejar de compartir', icon: <LockSimple className="size-4" />, onSelect: () => share.mutate(false) }
                    : { label: 'Compartir con la familia', icon: <House className="size-4" />, onSelect: () => share.mutate(true) }),
                checked.length > 0 && { label: 'Limpiar marcados', icon: <Broom className="size-4" />, onSelect: () => clear.mutate() },
                { label: 'Eliminar lista', icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => setConfirm(true) },
              ]}
              trigger={(p) => (
                <IconButton label="Opciones de la lista" {...p}>
                  <DotsThree className="size-5" weight="bold" />
                </IconButton>
              )}
            />
          </>
        )}
      </header>

      <form onSubmit={submit} className="flex items-center gap-2 border-b border-line px-3 py-2 md:px-5">
        <Plus className="size-4 shrink-0 text-faint" />
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Agregar, separa varios con comas"
          aria-label="Agregar elementos"
          className="h-10 min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-faint"
        />
        <Button type="submit" size="sm" variant={text.trim() ? 'primary' : 'ghost'} disabled={!text.trim()} loading={add.isPending}>
          Agregar
        </Button>
      </form>

      <div className="px-3 py-2 md:px-5">
        {list.items.length === 0 ? (
          <p className="py-8 text-center text-base text-muted">Lista vacía. Escribe arriba para agregar.</p>
        ) : (
          <>
            <ul className="grid">
              <AnimatePresence initial={false}>
                {pending.map((i) => (
                  <motion.li
                    key={i.id}
                    layout={!reduce}
                    initial={reduce ? false : { opacity: 0, y: -4 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0 }}
                    transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
                  >
                    <ItemRow
                      item={i}
                      onToggle={(c) => toggle.mutate({ listId: list.id, itemId: i.id, checked: c })}
                      onDelete={() => removeItem.mutate(i)}
                      onEdit={(value) => editItem.mutate({ item: i, value })}
                    />
                  </motion.li>
                ))}
              </AnimatePresence>
            </ul>
            {checked.length > 0 && (
              <div className="mt-2 border-t border-line pt-2">
                <div className="flex items-center justify-between py-1.5">
                  <p className="text-sm font-medium text-muted">
                    Marcados <span className="font-mono font-normal text-faint tnum">{checked.length}</span>
                  </p>
                  <Button size="sm" variant="ghost" icon={<Broom className="size-4" />} onClick={() => clear.mutate()} loading={clear.isPending}>
                    Limpiar marcados
                  </Button>
                </div>
                <ul className="grid">
                  {checked.map((i) => (
                    <li key={i.id}>
                      <ItemRow
                        item={i}
                        onToggle={(c) => toggle.mutate({ listId: list.id, itemId: i.id, checked: c })}
                        onDelete={() => removeItem.mutate(i)}
                        onEdit={(value) => editItem.mutate({ item: i, value })}
                      />
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
      </div>

      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title={`¿Eliminar ${list.name}?`}
        description="Se borran todos sus elementos. Podrás deshacerlo desde el aviso o desde Actividad."
      />
    </div>
  )
}

function ItemRow({ item, onToggle, onDelete, onEdit }: { item: ListItem; onToggle: (c: boolean) => void; onDelete: () => void; onEdit: (v: string) => void }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(item.text)
  return (
    <div className="group flex min-h-11 items-center gap-3 py-1">
      <Checkbox checked={item.checked} onChange={onToggle} label={item.checked ? `Desmarcar ${item.text}` : `Marcar ${item.text}`} />
      {editing ? (
        <input
          value={value}
          autoFocus
          onChange={(e) => setValue(e.target.value)}
          onBlur={() => {
            setEditing(false)
            if (value.trim() && value.trim() !== item.text) onEdit(value.trim())
            else setValue(item.text)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
            if (e.key === 'Escape') {
              setValue(item.text)
              setEditing(false)
            }
          }}
          aria-label="Editar elemento"
          className="h-8 min-w-0 flex-1 rounded-[8px] border border-accent bg-surface px-2 text-base outline-none"
        />
      ) : (
        <button
          type="button"
          onDoubleClick={() => setEditing(true)}
          onClick={() => onToggle(!item.checked)}
          className={cn('min-w-0 flex-1 py-1 text-left text-base break-words', item.checked ? 'text-faint line-through' : 'text-fg')}
          title="Doble clic para editar"
        >
          {item.text}
        </button>
      )}
      <IconButton label={`Editar ${item.text}`} size="sm" className="opacity-0 group-hover:opacity-100 focus-visible:opacity-100 max-md:hidden" onClick={() => setEditing(true)}>
        <PencilSimple className="size-4" />
      </IconButton>
      <IconButton label={`Quitar ${item.text}`} size="sm" tone="danger" className="opacity-0 group-hover:opacity-100 focus-visible:opacity-100 max-md:opacity-100" onClick={onDelete}>
        <X className="size-4" />
      </IconButton>
    </div>
  )
}
