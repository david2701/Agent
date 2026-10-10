import {
  closestCorners,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  TouchSensor,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import { arrayMove, SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { CheckSquare, FolderSimple, Kanban, ListBullets, PencilSimple, Plus, Trash } from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useReducedMotion } from 'motion/react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { DueText, PriorityTag, TaskRow } from '@/components/rows'
import { Button, IconButton } from '@/components/ui/button'
import { ConfirmDialog, Dialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback'
import { Field, Input, Select, Textarea } from '@/components/ui/form'
import { Chip, PageHeader, Segmented } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { PRIORITY_LABEL, STATUS_LABEL } from '@/lib/entities'
import { isoDate } from '@/lib/format'
import { useTaskActions, type TaskPatch } from '@/lib/mutations'
import { invalidateEntity, keys, useProjects, useTasks } from '@/lib/queries'
import type { Project, Task, TaskPriority, TaskStatus } from '@/lib/types'
import { undoLatest } from '@/lib/undo'
import { useStored } from '@/lib/useStored'

const COLUMNS: TaskStatus[] = ['todo', 'doing', 'done']
type Filter = 'all' | 'none' | string

export default function Tareas() {
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const tasks = useTasks()
  const projects = useProjects()
  const [view, setView] = useStored<'board' | 'list'>('kairo-tasks-view', 'board')
  const [filter, setFilter] = useState<Filter>(() => params.get('proyecto') ?? 'all')
  const [editing, setEditing] = useState<Task | 'new' | null>(null)
  const [projectsOpen, setProjectsOpen] = useState(false)
  const { update, complete } = useTaskActions()

  // Enlaces profundos: ?tarea=id, ?nueva=1, ?proyecto=id
  useEffect(() => {
    const tid = params.get('tarea')
    if (tid && tasks.data) {
      const t = tasks.data.find((x) => x.id === tid)
      if (t) setEditing(t)
      params.delete('tarea')
      setParams(params, { replace: true })
    }
    if (params.get('nueva')) {
      setEditing('new')
      params.delete('nueva')
      setParams(params, { replace: true })
    }
    const pid = params.get('proyecto')
    if (pid) {
      setFilter(pid)
      params.delete('proyecto')
      setParams(params, { replace: true })
    }
  }, [params, setParams, tasks.data])

  const visible = useMemo(() => {
    const all = tasks.data ?? []
    if (filter === 'all') return all
    if (filter === 'none') return all.filter((t) => !t.projectId)
    return all.filter((t) => t.projectId === filter)
  }, [tasks.data, filter])

  const quickAdd = useMutation({
    mutationFn: (title: string) =>
      api.post<Task>('/tasks', { title, status: 'todo', projectId: filter !== 'all' && filter !== 'none' ? filter : undefined }),
    onSuccess: (t) => {
      invalidateEntity(qc, 'task')
      toast({
        title: 'Tarea creada',
        description: t.title,
        tone: 'success',
        actions: [{ label: 'Deshacer', onClick: () => undoLatest(qc, 'task', t.id, 'created').catch((e) => toastError(e, 'No se pudo deshacer')) }],
      })
    },
    onError: (e) => toastError(e, 'No se pudo crear la tarea'),
  })

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: 0, none: 0 }
    for (const t of tasks.data ?? []) {
      if (t.status === 'done') continue
      c.all++
      c[t.projectId ?? 'none'] = (c[t.projectId ?? 'none'] ?? 0) + 1
    }
    return c
  }, [tasks.data])

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <PageHeader
        title="Tareas"
        actions={
          <>
            <Segmented
              label="Vista"
              value={view}
              onChange={setView}
              options={[
                { value: 'board', label: 'Tablero', icon: <Kanban className="size-4" /> },
                { value: 'list', label: 'Lista', icon: <ListBullets className="size-4" /> },
              ]}
            />
            <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setEditing('new')}>
              Nueva tarea
            </Button>
          </>
        }
      />

      <div className="mt-6 flex flex-wrap items-center gap-2">
        <div className="flex min-w-0 flex-1 gap-1.5 overflow-x-auto scrollbar-none max-sm:-mx-4 max-sm:px-4" role="group" aria-label="Filtrar por proyecto">
          <Chip selected={filter === 'all'} onClick={() => setFilter('all')}>
            Todas <span className="font-mono text-xs opacity-70">{counts.all}</span>
          </Chip>
          {(projects.data ?? []).map((p) => (
            <Chip key={p.id} selected={filter === p.id} onClick={() => setFilter(p.id)}>
              {p.name} <span className="font-mono text-xs opacity-70">{counts[p.id] ?? 0}</span>
            </Chip>
          ))}
          <Chip selected={filter === 'none'} onClick={() => setFilter('none')}>
            Sin proyecto <span className="font-mono text-xs opacity-70">{counts.none}</span>
          </Chip>
        </div>
        <Button size="sm" variant="ghost" icon={<FolderSimple className="size-4" />} onClick={() => setProjectsOpen(true)}>
          Proyectos
        </Button>
      </div>

      <QuickAdd onAdd={(t) => quickAdd.mutate(t)} loading={quickAdd.isPending} projectName={projects.data?.find((p) => p.id === filter)?.name} />

      {tasks.isLoading ? (
        <BoardSkeleton />
      ) : tasks.isError ? (
        <ErrorState message={(tasks.error as Error).message} onRetry={() => void tasks.refetch()} />
      ) : (tasks.data ?? []).length === 0 ? (
        <EmptyState
          icon={<CheckSquare />}
          title="No tienes tareas"
          description="Escribe una arriba o pídele a Kairo: “agrega comprar el regalo de mamá para el viernes”."
        />
      ) : view === 'board' ? (
        <Board
          tasks={visible}
          onOpen={setEditing}
          onMove={(t, patch) => update.mutate({ id: t.id, patch })}
        />
      ) : (
        <ListView tasks={visible} onOpen={setEditing} onToggle={complete} />
      )}

      <TaskDialog
        task={editing}
        projects={projects.data ?? []}
        defaultProjectId={filter !== 'all' && filter !== 'none' ? filter : null}
        onClose={() => setEditing(null)}
      />
      <ProjectsDialog open={projectsOpen} onClose={() => setProjectsOpen(false)} projects={projects.data ?? []} tasks={tasks.data ?? []} />
    </div>
  )
}

function QuickAdd({ onAdd, loading, projectName }: { onAdd: (t: string) => void; loading: boolean; projectName?: string }) {
  const [value, setValue] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const t = value.trim()
    if (!t) return
    onAdd(t)
    setValue('')
  }
  return (
    <form onSubmit={submit} className="mt-4 flex items-center gap-2 rounded-control border border-line bg-surface pr-1.5 pl-3 focus-within:border-accent">
      <Plus className="size-4 shrink-0 text-faint" />
      <input
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={projectName ? `Agregar tarea en ${projectName}` : 'Agregar una tarea y pulsar Enter'}
        aria-label="Agregar tarea rápida"
        className="h-11 min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-faint"
      />
      <Button size="sm" type="submit" variant={value.trim() ? 'primary' : 'ghost'} disabled={!value.trim()} loading={loading}>
        Agregar
      </Button>
    </form>
  )
}

function BoardSkeleton() {
  return (
    <div className="mt-6 grid gap-4 md:grid-cols-3" aria-busy="true" aria-label="Cargando tareas">
      {[4, 2, 3].map((n, c) => (
        <div key={c} className="grid content-start gap-2 rounded-card border border-line bg-surface p-3">
          <Skeleton className="mb-2 h-4 w-24" />
          {Array.from({ length: n }, (_, i) => (
            <Skeleton key={i} className="h-[72px] rounded-control" />
          ))}
        </div>
      ))}
    </div>
  )
}

/* ---------------------------------------------------------------- Tablero */

function sortOf(list: Task[]) {
  return [...list].sort((a, b) => a.sort - b.sort || a.createdAt.localeCompare(b.createdAt))
}

function Board({ tasks, onOpen, onMove }: { tasks: Task[]; onOpen: (t: Task) => void; onMove: (t: Task, patch: TaskPatch) => void }) {
  const byId = useMemo(() => new Map(tasks.map((t) => [t.id, t])), [tasks])
  const grouped = useMemo(() => {
    const g: Record<TaskStatus, string[]> = { todo: [], doing: [], done: [] }
    for (const s of COLUMNS) {
      const list = sortOf(tasks.filter((t) => t.status === s))
      g[s] = (s === 'done' ? [...list].sort((a, b) => (b.completedAt ?? b.updatedAt).localeCompare(a.completedAt ?? a.updatedAt)) : list).map((t) => t.id)
    }
    return g
  }, [tasks])
  const [cols, setCols] = useState(grouped)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [snapshot, setSnapshot] = useState(grouped)
  const reduce = useReducedMotion()

  useEffect(() => {
    if (!activeId) setCols(grouped)
  }, [grouped, activeId])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 200, tolerance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const findCol = (id: string): TaskStatus | null => {
    if ((COLUMNS as string[]).includes(id)) return id as TaskStatus
    return COLUMNS.find((c) => cols[c].includes(id)) ?? null
  }

  const onDragStart = (e: DragStartEvent) => {
    setActiveId(String(e.active.id))
    setSnapshot(cols)
  }

  const onDragOver = ({ active, over }: DragOverEvent) => {
    if (!over) return
    const from = findCol(String(active.id))
    const to = findCol(String(over.id))
    if (!from || !to || from === to) return
    setCols((prev) => {
      const fromList = prev[from].filter((x) => x !== active.id)
      const toList = [...prev[to]]
      const overIndex = toList.indexOf(String(over.id))
      const index = overIndex >= 0 ? overIndex : toList.length
      toList.splice(index, 0, String(active.id))
      return { ...prev, [from]: fromList, [to]: toList }
    })
  }

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    const id = String(active.id)
    setActiveId(null)
    if (!over) {
      setCols(snapshot)
      return
    }
    const col = findCol(id)
    const overCol = findCol(String(over.id))
    if (!col || !overCol) return
    let list = cols[col]
    if (col === overCol) {
      const oldIndex = list.indexOf(id)
      const newIndex = list.indexOf(String(over.id))
      if (newIndex >= 0 && oldIndex !== newIndex) list = arrayMove(list, oldIndex, newIndex)
    }
    const next = { ...cols, [col]: list }
    setCols(next)
    const task = byId.get(id)
    if (!task) return
    const i = list.indexOf(id)
    const prev = i > 0 ? byId.get(list[i - 1]) : undefined
    const nxt = i < list.length - 1 ? byId.get(list[i + 1]) : undefined
    let sort = task.sort
    if (prev && nxt) sort = (prev.sort + nxt.sort) / 2
    else if (prev) sort = prev.sort + 1
    else if (nxt) sort = nxt.sort - 1
    const statusChanged = task.status !== col
    const orderChanged = snapshot[col].indexOf(id) !== i || statusChanged
    if (!orderChanged) return
    onMove(task, statusChanged ? { status: col, sort } : { sort })
    if (statusChanged) {
      toast({
        title: `Movida a ${STATUS_LABEL[col]}`,
        description: task.title,
        tone: col === 'done' ? 'success' : 'default',
        actions: [{ label: 'Deshacer', onClick: () => onMove(task, { status: task.status, sort: task.sort }) }],
      })
    }
  }

  const active = activeId ? byId.get(activeId) : null

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCorners}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDragEnd={onDragEnd}
      onDragCancel={() => {
        setActiveId(null)
        setCols(snapshot)
      }}
      accessibility={{
        screenReaderInstructions: {
          draggable: 'Para mover una tarea, pulsa espacio. Usa las flechas para cambiarla de lugar o de columna y espacio otra vez para soltarla. Esc cancela.',
        },
        announcements: {
          onDragStart: ({ active }) => `Tomaste ${byId.get(String(active.id))?.title ?? 'la tarea'}.`,
          onDragOver: ({ over }) => (over ? `Sobre ${STATUS_LABEL[findCol(String(over.id)) ?? 'todo']}.` : 'Fuera de las columnas.'),
          onDragEnd: ({ over }) => (over ? `Soltaste la tarea en ${STATUS_LABEL[findCol(String(over.id)) ?? 'todo']}.` : 'Movimiento cancelado.'),
          onDragCancel: () => 'Movimiento cancelado.',
        },
      }}
    >
      <div className="mt-6 grid items-start gap-4 md:grid-cols-3">
        {COLUMNS.map((c) => (
          <Column key={c} status={c} ids={cols[c]} byId={byId} onOpen={onOpen} />
        ))}
      </div>
      <DragOverlay dropAnimation={reduce ? null : { duration: 220, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' }}>
        {active ? <CardBody task={active} overlay reduce={!!reduce} /> : null}
      </DragOverlay>
    </DndContext>
  )
}

function Column({ status, ids, byId, onOpen }: { status: TaskStatus; ids: string[]; byId: Map<string, Task>; onOpen: (t: Task) => void }) {
  const { setNodeRef, isOver } = useDroppable({ id: status })
  const [showAll, setShowAll] = useState(false)
  const limit = status === 'done' && !showAll ? 12 : Infinity
  const shown = ids.slice(0, limit)
  return (
    <section
      className={cn(
        'rounded-card border bg-surface transition-colors duration-200',
        isOver ? 'border-[color-mix(in_srgb,var(--accent)_45%,var(--border))]' : 'border-line',
      )}
      aria-label={STATUS_LABEL[status]}
    >
      <header className="flex items-center justify-between px-4 pt-3.5 pb-2">
        <h2 className="flex items-baseline gap-2 text-base font-semibold">
          {STATUS_LABEL[status]}
          <span className="font-mono text-sm font-normal text-faint tnum">{ids.length}</span>
        </h2>
      </header>
      <SortableContext items={shown} strategy={verticalListSortingStrategy}>
        <div ref={setNodeRef} className="grid min-h-24 content-start gap-2 px-2.5 pb-2.5">
          {shown.map((id) => {
            const t = byId.get(id)
            return t ? <SortableCard key={id} task={t} onOpen={onOpen} /> : null
          })}
          {ids.length === 0 && (
            <p className="rounded-control border border-dashed border-line px-3 py-6 text-center text-sm text-faint">
              {status === 'doing' ? 'Arrastra aquí lo que estás haciendo' : status === 'done' ? 'Lo terminado aparece aquí' : 'Nada por hacer'}
            </p>
          )}
          {ids.length > shown.length && (
            <button type="button" onClick={() => setShowAll(true)} className="py-1.5 text-sm text-muted hover:text-fg">
              Ver {ids.length - shown.length} más
            </button>
          )}
        </div>
      </SortableContext>
    </section>
  )
}

function SortableCard({ task, onOpen }: { task: Task; onOpen: (t: Task) => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: task.id })
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn('touch-manipulation', isDragging && 'opacity-35')}
      {...attributes}
      {...listeners}
      aria-roledescription="Tarea arrastrable"
      onClick={() => onOpen(task)}
      onKeyDown={(e) => {
        listeners?.onKeyDown?.(e)
        if (e.key === 'Enter' && !e.defaultPrevented) onOpen(task)
      }}
    >
      <CardBody task={task} />
    </div>
  )
}

function CardBody({ task, overlay, reduce }: { task: Task; overlay?: boolean; reduce?: boolean }) {
  const done = task.status === 'done'
  return (
    <div
      className={cn(
        'cursor-grab rounded-control border border-line bg-bg px-3 py-2.5 transition-[border-color] duration-200 hover:border-[color-mix(in_srgb,var(--text-faint)_45%,var(--border))] active:cursor-grabbing',
        overlay && 'cursor-grabbing bg-surface shadow-lift',
        overlay && !reduce && 'rotate-[1.2deg] scale-[1.02]',
        task.priority === 'urgent' && !done && 'border-l-2 border-l-danger',
      )}
    >
      <p className={cn('line-clamp-3 text-base leading-snug', done ? 'text-faint line-through' : 'text-fg')}>{task.title}</p>
      {(task.priority !== 'medium' || task.dueDate || task.dueAt || task.projectName) && (
        <div className="mt-2 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-sm">
          <PriorityTag priority={task.priority} />
          <DueText task={task} />
          {task.projectName && <span className="truncate text-faint">{task.projectName}</span>}
        </div>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- Lista */

function ListView({ tasks, onOpen, onToggle }: { tasks: Task[]; onOpen: (t: Task) => void; onToggle: (t: Task, done: boolean) => void }) {
  return (
    <div className="mt-6 grid gap-5">
      {COLUMNS.map((s) => {
        const list = sortOf(tasks.filter((t) => t.status === s))
        if (list.length === 0) return null
        return (
          <section key={s} className="rounded-card border border-line bg-surface px-4 pt-3.5 pb-1.5" aria-label={STATUS_LABEL[s]}>
            <h2 className="flex items-baseline gap-2 text-base font-semibold">
              {STATUS_LABEL[s]}
              <span className="font-mono text-sm font-normal text-faint tnum">{list.length}</span>
            </h2>
            <div className="mt-1 divide-y divide-line">
              {(s === 'done' ? list.slice(0, 30) : list).map((t) => (
                <TaskRow key={t.id} task={t} onOpen={() => onOpen(t)} onToggle={(d) => onToggle(t, d)} />
              ))}
            </div>
          </section>
        )
      })}
    </div>
  )
}

/* ---------------------------------------------------------------- Diálogo de tarea */

function splitDue(t: Task | null): { date: string; time: string } {
  if (!t) return { date: '', time: '' }
  if (t.dueAt) {
    const d = new Date(t.dueAt)
    return { date: isoDate(d), time: `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}` }
  }
  return { date: t.dueDate ?? '', time: '' }
}

function TaskDialog({
  task,
  projects,
  defaultProjectId,
  onClose,
}: {
  task: Task | 'new' | null
  projects: Project[]
  defaultProjectId: string | null
  onClose: () => void
}) {
  const qc = useQueryClient()
  const { remove } = useTaskActions()
  const editing = task && task !== 'new' ? task : null
  const [title, setTitle] = useState('')
  const [notes, setNotes] = useState('')
  const [status, setStatus] = useState<TaskStatus>('todo')
  const [priority, setPriority] = useState<TaskPriority>('medium')
  const [date, setDate] = useState('')
  const [timeV, setTimeV] = useState('')
  const [projectId, setProjectId] = useState<string>('')
  const [newProject, setNewProject] = useState('')
  const [tags, setTags] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [confirm, setConfirm] = useState(false)

  useEffect(() => {
    if (!task) return
    const t = task === 'new' ? null : task
    const due = splitDue(t)
    setTitle(t?.title ?? '')
    setNotes(t?.notes ?? '')
    setStatus(t?.status ?? 'todo')
    setPriority(t?.priority ?? 'medium')
    setDate(due.date)
    setTimeV(due.time)
    setProjectId(t ? (t.projectId ?? '') : (defaultProjectId ?? ''))
    setNewProject('')
    setTags(t?.tags.join(', ') ?? '')
    setError(null)
  }, [task, defaultProjectId])

  const save = useMutation({
    mutationFn: async () => {
      const dueAt = date && timeV ? new Date(`${date}T${timeV}`).toISOString() : null
      const body: Record<string, unknown> = {
        title: title.trim(),
        notes,
        status,
        priority,
        dueDate: date && !timeV ? date : null,
        dueAt,
        tags: tags
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean),
      }
      if (projectId === '__new') {
        if (editing) {
          const p = await api.post<Project>('/projects', { name: newProject.trim() })
          body.projectId = p.id
        } else body.project = newProject.trim()
      } else body.projectId = projectId || null
      if (editing) return api.patch<Task>(`/tasks/${editing.id}`, body)
      if (!body.projectId) delete body.projectId
      if (!body.dueDate) delete body.dueDate
      if (!body.dueAt) delete body.dueAt
      return api.post<Task>('/tasks', body)
    },
    onSuccess: (t) => {
      invalidateEntity(qc, 'task')
      void qc.invalidateQueries({ queryKey: keys.projects })
      onClose()
      if (editing) toast({ title: 'Tarea guardada', description: t.title })
      else
        toast({
          title: 'Tarea creada',
          description: t.title,
          tone: 'success',
          actions: [{ label: 'Deshacer', onClick: () => undoLatest(qc, 'task', t.id, 'created').catch((e) => toastError(e, 'No se pudo deshacer')) }],
        })
    },
    onError: (e) => setError(e instanceof Error ? e.message : 'No se pudo guardar'),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!title.trim()) return setError('La tarea necesita un título.')
    if (timeV && !date) return setError('Elige una fecha para la hora.')
    if (projectId === '__new' && !newProject.trim()) return setError('Escribe el nombre del proyecto nuevo.')
    setError(null)
    save.mutate()
  }

  return (
    <Dialog
      open={!!task}
      onClose={onClose}
      title={editing ? 'Editar tarea' : 'Nueva tarea'}
      size="md"
      footer={
        <>
          {editing && (
            <Button variant="ghost" className="mr-auto text-danger hover:text-danger" icon={<Trash className="size-4" />} onClick={() => setConfirm(true)}>
              Eliminar
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" type="submit" form="task-form" loading={save.isPending}>
            Guardar
          </Button>
        </>
      }
    >
      <form id="task-form" onSubmit={submit} className="grid gap-4">
        <Field label="Título" error={error && !title.trim() ? error : null}>
          {(id, d) => <Input id={id} value={title} onChange={(e) => setTitle(e.target.value)} autoFocus aria-describedby={d} aria-invalid={!!error && !title.trim()} />}
        </Field>
        <div className="grid gap-1.5">
          <span className="text-sm font-medium">Estado</span>
          <Segmented
            label="Estado"
            value={status}
            onChange={setStatus}
            options={COLUMNS.map((s) => ({ value: s, label: STATUS_LABEL[s] }))}
            className="w-fit"
          />
        </div>
        <div className="grid gap-1.5">
          <span className="text-sm font-medium">Prioridad</span>
          <Segmented
            label="Prioridad"
            value={priority}
            onChange={setPriority}
            options={(['low', 'medium', 'high', 'urgent'] as TaskPriority[]).map((p) => ({ value: p, label: PRIORITY_LABEL[p] }))}
            className="w-fit max-w-full overflow-x-auto"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Fecha" optional>
            {(id) => <Input id={id} type="date" value={date} onChange={(e) => setDate(e.target.value)} />}
          </Field>
          <Field label="Hora" optional>
            {(id) => <Input id={id} type="time" value={timeV} onChange={(e) => setTimeV(e.target.value)} disabled={!date} />}
          </Field>
        </div>
        <Field label="Proyecto" optional>
          {(id) => (
            <Select id={id} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
              <option value="">Sin proyecto</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
              <option value="__new">Nuevo proyecto…</option>
            </Select>
          )}
        </Field>
        {projectId === '__new' && (
          <Field label="Nombre del proyecto nuevo">
            {(id) => <Input id={id} value={newProject} onChange={(e) => setNewProject(e.target.value)} placeholder="Viaje a Cartagena" />}
          </Field>
        )}
        <Field label="Notas" optional>
          {(id) => <Textarea id={id} value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} />}
        </Field>
        <Field label="Etiquetas" optional hint="Separadas por comas.">
          {(id, d) => <Input id={id} value={tags} onChange={(e) => setTags(e.target.value)} aria-describedby={d} />}
        </Field>
        {error && title.trim() && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
      </form>
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => {
          if (editing) remove.mutate(editing)
          setConfirm(false)
          onClose()
        }}
        title="¿Eliminar esta tarea?"
        description="Podrás deshacerlo desde el aviso o desde Actividad."
      />
    </Dialog>
  )
}

/* ---------------------------------------------------------------- Proyectos */

function ProjectsDialog({ open, onClose, projects, tasks }: { open: boolean; onClose: () => void; projects: Project[]; tasks: Task[] }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [editing, setEditing] = useState<{ id: string; name: string } | null>(null)
  const [deleting, setDeleting] = useState<Project | null>(null)
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: keys.projects })
    void qc.invalidateQueries({ queryKey: keys.tasks })
  }
  const create = useMutation({
    mutationFn: (n: string) => api.post<Project>('/projects', { name: n }),
    onSuccess: () => {
      setName('')
      refresh()
    },
    onError: (e) => toastError(e, 'No se pudo crear el proyecto'),
  })
  const rename = useMutation({
    mutationFn: ({ id, name: n }: { id: string; name: string }) => api.patch<Project>(`/projects/${id}`, { name: n }),
    onSuccess: () => {
      setEditing(null)
      refresh()
    },
    onError: (e) => toastError(e, 'No se pudo renombrar'),
  })
  const remove = useMutation({
    mutationFn: (p: Project) => api.del(`/projects/${p.id}`),
    onSuccess: () => {
      setDeleting(null)
      refresh()
      toast({ title: 'Proyecto eliminado' })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar'),
  })
  const count = (id: string) => tasks.filter((t) => t.projectId === id && t.status !== 'done').length

  return (
    <Dialog open={open} onClose={onClose} title="Proyectos" description="Agrupa tus tareas. Borrar un proyecto no borra sus tareas.">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (name.trim()) create.mutate(name.trim())
        }}
        className="flex gap-2"
      >
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Nombre del proyecto" aria-label="Nombre del proyecto nuevo" />
        <Button type="submit" variant="primary" disabled={!name.trim()} loading={create.isPending}>
          Crear
        </Button>
      </form>
      <ul className="mt-4 divide-y divide-line">
        {projects.length === 0 && <li className="py-6 text-center text-base text-muted">Aún no tienes proyectos.</li>}
        {projects.map((p) => (
          <li key={p.id} className="flex items-center gap-2 py-2">
            {editing?.id === p.id ? (
              <form
                className="flex flex-1 gap-2"
                onSubmit={(e) => {
                  e.preventDefault()
                  if (editing.name.trim()) rename.mutate({ id: p.id, name: editing.name.trim() })
                }}
              >
                <Input value={editing.name} onChange={(e) => setEditing({ id: p.id, name: e.target.value })} autoFocus aria-label="Nuevo nombre" />
                <Button type="submit" size="md" loading={rename.isPending}>
                  Guardar
                </Button>
              </form>
            ) : (
              <>
                <FolderSimple className="size-4 shrink-0 text-faint" />
                <span className="min-w-0 flex-1 truncate text-base">{p.name}</span>
                <span className="font-mono text-sm text-faint">{count(p.id)}</span>
                <IconButton label={`Renombrar ${p.name}`} size="sm" onClick={() => setEditing({ id: p.id, name: p.name })}>
                  <PencilSimple className="size-4" />
                </IconButton>
                <IconButton label={`Eliminar ${p.name}`} size="sm" tone="danger" onClick={() => setDeleting(p)}>
                  <Trash className="size-4" />
                </IconButton>
              </>
            )}
          </li>
        ))}
      </ul>
      <ConfirmDialog
        open={!!deleting}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting)}
        loading={remove.isPending}
        title={`¿Eliminar ${deleting?.name ?? 'el proyecto'}?`}
        description="Sus tareas se conservan y quedan sin proyecto."
      />
    </Dialog>
  )
}
