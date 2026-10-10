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
import { t } from '@/lib/i18n'
import { useToggleItem } from '@/lib/mutations'
import { invalidateEntity, keys, useLists } from '@/lib/queries'
import type { KList, ListItem } from '@/lib/types'
import { undoLatest } from '@/lib/undo'

export default function ListsPage() {
  const { id } = useParams<{ id?: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const lists = useLists()
  const [name, setName] = useState('')
  const [shared, setShared] = useState(false)
  const [creating, setCreating] = useState(false)
  const fam = useFamilyInfo()

  // On desktop, open the first list if none is selected
  useEffect(() => {
    if (!id && lists.data?.length && window.matchMedia('(min-width: 768px)').matches) navigate(`/lists/${lists.data[0].id}`, { replace: true })
  }, [id, lists.data, navigate])

  const create = useMutation({
    mutationFn: (n: string) => api.post<KList>('/lists', shared ? { name: n, shared: true } : { name: n }),
    onSuccess: (l) => {
      setName('')
      setShared(false)
      setCreating(false)
      invalidateEntity(qc, 'list')
      navigate(`/lists/${l.id}`)
    },
    onError: (e) => toastError(e, t('lists.err.create')),
  })

  const current = lists.data?.find((l) => l.id === id)

  return (
    <div className="mx-auto max-w-[1200px] px-4 py-6 md:px-8 md:py-9">
      <div className="grid items-start gap-5 md:grid-cols-[260px_minmax(0,1fr)] lg:grid-cols-[300px_minmax(0,1fr)] lg:gap-8">
        <aside className={cn(id && 'max-md:hidden')} aria-label={t('lists.yourLists')}>
          <div className="flex items-center justify-between gap-3">
            <h1 className="text-lg font-semibold tracking-tight md:text-xl">{t('nav.lists')}</h1>
            <Button size="sm" variant="primary" icon={<Plus className="size-4" />} onClick={() => setCreating(true)}>
              {t('common.new')}
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
                <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t('lists.nameLabel')} aria-label={t('lists.nameLabel')} autoFocus />
                <Button type="submit" loading={create.isPending} disabled={!name.trim()}>
                  {t('common.create')}
                </Button>
                <IconButton label={t('common.cancel')} onClick={() => setCreating(false)}>
                  <X className="size-4" />
                </IconButton>
              </div>
              {fam.hasOthers && (
                <label className="flex items-center gap-2.5 px-0.5 text-sm text-muted">
                  <Switch checked={shared} onChange={setShared} label={t('lists.shareWithFamily')} />
                  {t('lists.shareWithFamily')}
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
              <EmptyState compact icon={<ListChecks />} title={t('lists.empty')} description={t('lists.emptyHint')} />
            ) : (
              <ul className="grid gap-0.5">
                {lists.data!.map((l) => {
                  const pending = l.items.filter((i) => !i.checked).length
                  return (
                    <li key={l.id}>
                      <NavLink
                        to={`/lists/${l.id}`}
                        className={({ isActive }) =>
                          cn(
                            'flex h-11 items-center gap-3 rounded-control px-3 text-base transition-colors',
                            isActive ? 'bg-surface-2 font-medium text-fg' : 'text-muted hover:bg-surface-2/60 hover:text-fg',
                          )
                        }
                      >
                        <ListChecks className="size-[18px] shrink-0 text-faint" />
                        <span className="min-w-0 flex-1 truncate">{l.name}</span>
                        {l.shared && <FamilyIcon label={fam.isMine(l.ownerId) ? t('family.tag') : t('family.tagFrom', { name: fam.nameOf(l.ownerId) ?? t('reminder.someoneElse') })} />}
                        <span className="font-mono text-sm text-faint tnum">{pending}</span>
                      </NavLink>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        </aside>

        <section className={cn(!id && 'max-md:hidden')} aria-label={t('lists.list')}>
          {current ? (
            <ListDetail key={current.id} list={current} />
          ) : id && lists.isSuccess ? (
            <EmptyState icon={<ListChecks />} title={t('lists.gone')} action={<Button onClick={() => navigate('/lists')}>{t('lists.seeLists')}</Button>} />
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
    onError: (e) => toastError(e, t('lists.err.add')),
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
      toastError(e, t('lists.err.remove'))
    },
  })
  const editItem = useMutation({
    mutationFn: ({ item, value }: { item: ListItem; value: string }) => api.patch<KList>(`/lists/${list.id}/items/${item.id}`, { text: value }),
    onSuccess: setList,
    onError: (e) => toastError(e, t('lists.err.edit')),
  })
  const clear = useMutation({
    mutationFn: () => api.post<KList>(`/lists/${list.id}/clear-checked`),
    onSuccess: (l) => {
      setList(l)
      toast({ title: t('lists.checkedRemoved') })
    },
    onError: (e) => toastError(e, t('lists.err.clear')),
  })
  const rename = useMutation({
    mutationFn: (name: string) => api.patch<KList>(`/lists/${list.id}`, { name }),
    onSuccess: (l) => {
      setRenaming(null)
      setList(l)
    },
    onError: (e) => toastError(e, t('lists.err.rename')),
  })
  const share = useMutation({
    mutationFn: (shared: boolean) => api.patch<KList>(`/lists/${list.id}`, { shared }),
    onSuccess: (l) => {
      setList(l)
      invalidateEntity(qc, 'list')
      toast({
        title: l.shared ? t('lists.sharedToast') : t('lists.personalToast'),
        description: l.shared ? t('lists.sharedToastBody') : undefined,
      })
    },
    onError: (e) => toastError(e, t('lists.err.change')),
  })
  const remove = useMutation({
    mutationFn: () => api.del(`/lists/${list.id}`),
    onSuccess: () => {
      setConfirm(false)
      invalidateEntity(qc, 'list')
      navigate('/lists', { replace: true })
      toast({
        title: t('lists.deleted'),
        description: list.name,
        actions: [
          {
            label: t('common.undo'),
            onClick: () =>
              undoLatest(qc, 'list', list.id, 'deleted')
                .then(() => navigate(`/lists/${list.id}`))
                .catch((e) => toastError(e, t('common.couldNotUndo'))),
          },
        ],
      })
    },
    onError: (e) => toastError(e, t('common.couldNotDelete')),
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
        <IconButton label={t('lists.back')} className="md:hidden" onClick={() => navigate('/lists')}>
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
            <Input value={renaming} onChange={(e) => setRenaming(e.target.value)} autoFocus aria-label={t('lists.newName')} />
            <Button type="submit" loading={rename.isPending}>
              {t('common.save')}
            </Button>
            <Button variant="ghost" onClick={() => setRenaming(null)}>
              {t('common.cancel')}
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
                { label: t('common.rename'), icon: <PencilSimple className="size-4" />, onSelect: () => setRenaming(list.name) },
                isOwner &&
                  (fam.hasOthers || list.shared) &&
                  (list.shared
                    ? { label: t('lists.stopSharing'), icon: <LockSimple className="size-4" />, onSelect: () => share.mutate(false) }
                    : { label: t('lists.shareWithFamily'), icon: <House className="size-4" />, onSelect: () => share.mutate(true) }),
                checked.length > 0 && { label: t('lists.clearChecked'), icon: <Broom className="size-4" />, onSelect: () => clear.mutate() },
                { label: t('lists.delete'), icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => setConfirm(true) },
              ]}
              trigger={(p) => (
                <IconButton label={t('lists.options')} {...p}>
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
          placeholder={t('lists.addPlaceholder')}
          aria-label={t('lists.addLabel')}
          className="h-10 min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-faint"
        />
        <Button type="submit" size="sm" variant={text.trim() ? 'primary' : 'ghost'} disabled={!text.trim()} loading={add.isPending}>
          {t('common.add')}
        </Button>
      </form>

      <div className="px-3 py-2 md:px-5">
        {list.items.length === 0 ? (
          <p className="py-8 text-center text-base text-muted">{t('lists.emptyList')}</p>
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
                    {t('lists.checked')} <span className="font-mono font-normal text-faint tnum">{checked.length}</span>
                  </p>
                  <Button size="sm" variant="ghost" icon={<Broom className="size-4" />} onClick={() => clear.mutate()} loading={clear.isPending}>
                    {t('lists.clearChecked')}
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
        title={t('lists.deleteTitle', { name: list.name })}
        description={t('lists.deleteBody')}
      />
    </div>
  )
}

function ItemRow({ item, onToggle, onDelete, onEdit }: { item: ListItem; onToggle: (c: boolean) => void; onDelete: () => void; onEdit: (v: string) => void }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(item.text)
  return (
    <div className="group flex min-h-11 items-center gap-3 py-1">
      <Checkbox checked={item.checked} onChange={onToggle} label={item.checked ? t('lists.uncheck', { text: item.text }) : t('lists.check', { text: item.text })} />
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
          aria-label={t('lists.editItem')}
          className="h-8 min-w-0 flex-1 rounded-[8px] border border-accent bg-surface px-2 text-base outline-none"
        />
      ) : (
        <button
          type="button"
          onDoubleClick={() => setEditing(true)}
          onClick={() => onToggle(!item.checked)}
          className={cn('min-w-0 flex-1 py-1 text-left text-base break-words', item.checked ? 'text-faint line-through' : 'text-fg')}
          title={t('lists.dblClickEdit')}
        >
          {item.text}
        </button>
      )}
      <IconButton label={t('lists.editNamed', { text: item.text })} size="sm" className="opacity-0 group-hover:opacity-100 focus-visible:opacity-100 max-md:hidden" onClick={() => setEditing(true)}>
        <PencilSimple className="size-4" />
      </IconButton>
      <IconButton label={t('lists.removeNamed', { text: item.text })} size="sm" tone="danger" className="opacity-0 group-hover:opacity-100 focus-visible:opacity-100 max-md:opacity-100" onClick={onDelete}>
        <X className="size-4" />
      </IconButton>
    </div>
  )
}
