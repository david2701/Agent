import { ArrowsClockwise, Bell, BellRinging, CalendarBlank, FolderSimple, MapPin } from '@phosphor-icons/react'
import type { ReactNode } from 'react'
import { Avatar, FamilyIcon } from '@/components/family'
import { Checkbox } from '@/components/ui/form'
import { Tag } from '@/components/ui/misc'
import { cn } from '@/lib/cn'
import { useFamilyInfo } from '@/lib/family'
import { dayTime, dueLabel, relative } from '@/lib/format'
import { describeRrule } from '@/lib/rrule'
import type { Reminder, Task } from '@/lib/types'

export function PriorityTag({ priority }: { priority: Task['priority'] }) {
  if (priority === 'urgent') return <Tag tone="danger">Urgente</Tag>
  if (priority === 'high') return <Tag>Alta</Tag>
  if (priority === 'low') return <span className="text-sm text-faint">Baja</span>
  return null
}

export function DueText({ task, className }: { task: Pick<Task, 'dueDate' | 'dueAt' | 'status'>; className?: string }) {
  const due = dueLabel(task.dueDate, task.dueAt)
  if (!due) return null
  const overdue = due.overdue && task.status !== 'done'
  return (
    <span className={cn('inline-flex items-center gap-1', overdue ? 'text-danger' : due.today ? 'text-fg' : 'text-muted', className)}>
      <CalendarBlank className="size-3.5" />
      {overdue ? `Vencida, ${due.text.toLowerCase()}` : due.text}
    </span>
  )
}

/** Marca familiar y responsable (iniciales) de una tarea, para tarjetas y filas. */
export function TaskPeople({ task, className }: { task: Pick<Task, 'shared' | 'assigneeId' | 'ownerId'>; className?: string }) {
  const fam = useFamilyInfo()
  if (!task.shared && !task.assigneeId) return null
  const assignee = fam.member(task.assigneeId)
  const owner = fam.isMine(task.ownerId) ? null : fam.nameOf(task.ownerId)
  return (
    <span className={cn('inline-flex shrink-0 items-center gap-1.5', className)}>
      {task.shared && <FamilyIcon label={owner ? `Familiar, de ${owner}` : 'Familiar'} />}
      {assignee && (
        <Avatar
          name={assignee.displayName}
          size="xs"
          title={assignee.id === fam.me ? 'Responsable: tú' : `Responsable: ${assignee.displayName}`}
        />
      )}
    </span>
  )
}

export function TaskRow({
  task,
  onToggle,
  onOpen,
  showProject = true,
  className,
  trailing,
}: {
  task: Task
  onToggle: (done: boolean) => void
  onOpen?: () => void
  showProject?: boolean
  className?: string
  trailing?: ReactNode
}) {
  const done = task.status === 'done'
  const meta = (
    <>
      <PriorityTag priority={task.priority} />
      <DueText task={task} />
      {showProject && task.projectName && (
        <span className="inline-flex min-w-0 items-center gap-1 text-muted">
          <FolderSimple className="size-3.5 shrink-0" />
          <span className="truncate">{task.projectName}</span>
        </span>
      )}
      <TaskPeople task={task} />
    </>
  )
  const hasMeta = task.priority !== 'medium' || task.dueDate || task.dueAt || (showProject && task.projectName) || task.shared || task.assigneeId
  return (
    <div className={cn('group flex items-start gap-3 py-2.5', className)}>
      <span className="pt-0.5">
        <Checkbox checked={done} onChange={onToggle} label={done ? `Reabrir ${task.title}` : `Completar ${task.title}`} />
      </span>
      <button type="button" onClick={onOpen} className="min-w-0 flex-1 rounded-[6px] text-left" disabled={!onOpen}>
        <p className={cn('text-base leading-snug break-words', done ? 'text-faint line-through decoration-faint' : 'text-fg')}>{task.title}</p>
        {hasMeta && <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">{meta}</div>}
      </button>
      {trailing}
    </div>
  )
}

export function reminderWhen(r: Reminder): string {
  if (r.status === 'fired') return r.lastFiredAt ? `Sonó ${relative(r.lastFiredAt)}` : 'Sonó'
  if (r.placeId) return `${r.placeTrigger === 'leave' ? 'Al salir de' : 'Al llegar a'} ${r.placeName ?? 'un lugar'}`
  const when = r.nextFireAt ?? r.dueAt
  const rec = describeRrule(r.rrule)
  if (when && rec) return `${rec}, próximo ${dayTime(when).replace(/^Hoy/, 'hoy').replace(/^Mañana/, 'mañana')}`
  if (when) return dayTime(when)
  return rec
}

export function ReminderIcon({ r, className }: { r: Reminder; className?: string }) {
  const Icon = r.status === 'fired' ? BellRinging : r.placeId ? MapPin : r.rrule ? ArrowsClockwise : Bell
  return <Icon className={cn('size-[18px]', r.status === 'fired' ? 'text-accent-strong' : 'text-faint', className)} />
}

/** "Para Valentina" (lo programé para otra persona) o "De Valentina" (alguien me lo programó). */
export function ReminderPerson({ r }: { r: Reminder }) {
  const fam = useFamilyInfo()
  const forOther = r.ownerId && fam.me && r.ownerId !== fam.me
  const fromOther = !forOther && r.createdBy && fam.me && r.createdBy !== fam.me
  if (!forOther && !fromOther) return null
  const person = fam.member(forOther ? r.ownerId : r.createdBy)
  const name = fam.nameOf(forOther ? r.ownerId : r.createdBy) ?? 'otra persona'
  return (
    <span className="inline-flex items-center gap-1.5 text-muted">
      <Avatar name={person?.displayName ?? name} size="xs" />
      {forOther ? `Para ${name}` : `De ${name}`}
    </span>
  )
}

export function ReminderRow({ r, actions, className }: { r: Reminder; actions?: ReactNode; className?: string }) {
  const done = r.status === 'done' || r.status === 'cancelled'
  return (
    <div className={cn('flex flex-wrap items-start gap-x-3 gap-y-2 py-3', className)}>
      <span className="pt-0.5">
        <ReminderIcon r={r} />
      </span>
      <div className="min-w-0 flex-1 basis-48">
        <p className={cn('text-base leading-snug break-words', done ? 'text-faint line-through' : 'text-fg')}>{r.text}</p>
        <p className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
          <span className={r.status === 'fired' ? 'text-accent-strong' : 'text-muted'}>
            {done ? (r.status === 'cancelled' ? 'Cancelado' : 'Hecho') : reminderWhen(r)}
          </span>
          <ReminderPerson r={r} />
        </p>
      </div>
      {actions && <div className="flex shrink-0 items-center gap-1.5 max-sm:pl-[30px]">{actions}</div>}
    </div>
  )
}
