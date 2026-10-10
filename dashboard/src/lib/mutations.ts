import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast, toastError } from '@/components/ui/toast'
import { api } from './api'
import { t } from './i18n'
import { invalidateEntity, keys } from './queries'
import type { KList, NoticesResponse, Reminder, Task } from './types'
import { undoLatest } from './undo'

export type TaskPatch = Partial<
  Pick<Task, 'title' | 'notes' | 'status' | 'priority' | 'dueDate' | 'dueAt' | 'projectId' | 'tags' | 'sort' | 'shared' | 'assigneeId'>
>

export function useTaskActions() {
  const qc = useQueryClient()

  const update = useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: TaskPatch }) => api.patch<Task>(`/tasks/${id}`, patch),
    onMutate: async ({ id, patch }) => {
      await qc.cancelQueries({ queryKey: keys.tasks })
      const prev = qc.getQueryData<Task[]>(keys.tasks)
      if (prev) qc.setQueryData<Task[]>(keys.tasks, prev.map((t) => (t.id === id ? { ...t, ...patch } : t)))
      return { prev }
    },
    onError: (e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(keys.tasks, ctx.prev)
      toastError(e, t('task.err.update'))
    },
    onSettled: () => invalidateEntity(qc, 'task'),
  })

  const complete = (task: Task, done: boolean) => {
    const prevStatus = task.status
    update.mutate(
      { id: task.id, patch: { status: done ? 'done' : 'todo' } },
      {
        onSuccess: () => {
          if (done)
            toast({
              title: t('task.toast.completed'),
              description: task.title,
              tone: 'success',
              actions: [{ label: t('common.undo'), onClick: () => update.mutate({ id: task.id, patch: { status: prevStatus === 'done' ? 'todo' : prevStatus } }) }],
            })
        },
      },
    )
  }

  const remove = useMutation({
    mutationFn: (task: Task) => api.del(`/tasks/${task.id}`).then(() => task),
    onSuccess: (task) => {
      invalidateEntity(qc, 'task')
      toast({
        title: t('task.toast.deleted'),
        description: task.title,
        actions: [
          {
            label: t('common.undo'),
            onClick: () => undoLatest(qc, 'task', task.id, 'deleted').catch((e) => toastError(e, t('common.couldNotUndo'))),
          },
        ],
      })
    },
    onError: (e) => toastError(e, t('task.err.delete')),
  })

  return { update, complete, remove }
}

export function useReminderActions() {
  const qc = useQueryClient()
  const after = () => invalidateEntity(qc, 'reminder')

  const done = useMutation({
    mutationFn: (r: Reminder) => api.post<Reminder>(`/reminders/${r.id}/done`),
    onSuccess: (_d, r) => {
      after()
      toast({ title: r.rrule ? t('reminder.toast.doneToday') : t('reminder.toast.done'), description: r.text, tone: 'success' })
    },
    onError: (e) => toastError(e, t('reminder.err.markDone')),
  })
  const snooze = useMutation({
    mutationFn: ({ r, minutes }: { r: Reminder; minutes: number }) => api.post<Reminder>(`/reminders/${r.id}/snooze`, { minutes }),
    onSuccess: (_d, { minutes }) => {
      after()
      toast({ title: t('reminder.toast.snoozed', { minutes }) })
    },
    onError: (e) => toastError(e, t('reminder.err.snooze')),
  })
  const cancel = useMutation({
    mutationFn: (r: Reminder) => api.post<Reminder>(`/reminders/${r.id}/cancel`),
    onSuccess: () => {
      after()
      toast({ title: t('reminder.toast.canceled') })
    },
    onError: (e) => toastError(e, t('reminder.err.cancel')),
  })
  const remove = useMutation({
    mutationFn: (r: Reminder) => api.del(`/reminders/${r.id}`).then(() => r),
    onSuccess: (r) => {
      after()
      toast({
        title: t('reminder.toast.deleted'),
        description: r.text,
        actions: [{ label: t('common.undo'), onClick: () => undoLatest(qc, 'reminder', r.id, 'deleted').catch((e) => toastError(e, t('common.couldNotUndo'))) }],
      })
    },
    onError: (e) => toastError(e, t('reminder.err.delete')),
  })
  return { done, snooze, cancel, remove }
}

/** Check/uncheck a list item with an optimistic update. */
export function useToggleItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ listId, itemId, checked }: { listId: string; itemId: string; checked: boolean }) =>
      api.patch<KList>(`/lists/${listId}/items/${itemId}`, { checked }),
    onMutate: async ({ listId, itemId, checked }) => {
      await qc.cancelQueries({ queryKey: keys.lists })
      const prev = qc.getQueryData<KList[]>(keys.lists)
      if (prev)
        qc.setQueryData<KList[]>(
          keys.lists,
          prev.map((l) => (l.id === listId ? { ...l, items: l.items.map((i) => (i.id === itemId ? { ...i, checked } : i)) } : l)),
        )
      return { prev }
    },
    onError: (e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(keys.lists, ctx.prev)
      toastError(e, t('list.err.update'))
    },
    onSuccess: (list) => {
      const prev = qc.getQueryData<KList[]>(keys.lists)
      if (prev) qc.setQueryData<KList[]>(keys.lists, prev.map((l) => (l.id === list.id ? list : l)))
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: keys.today })
    },
  })
}

/** Marks family notices as read (all of them when no ids are given) with an optimistic update. */
export function useMarkNoticesRead() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (ids?: string[]) => api.post('/notices/read', ids ? { ids } : {}),
    onMutate: async (ids) => {
      await qc.cancelQueries({ queryKey: keys.notices })
      const prev = qc.getQueryData<NoticesResponse>(keys.notices)
      if (prev) {
        const now = new Date().toISOString()
        const notices = prev.notices.map((n) => (!n.readAt && (!ids || ids.includes(n.id)) ? { ...n, readAt: now } : n))
        const marked = prev.notices.filter((n) => !n.readAt && (!ids || ids.includes(n.id))).length
        qc.setQueryData<NoticesResponse>(keys.notices, { notices, unread: ids ? Math.max(0, prev.unread - marked) : 0 })
      }
      return { prev }
    },
    onError: (e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(keys.notices, ctx.prev)
      toastError(e, t('notices.err.markRead'))
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: keys.notices })
    },
  })
}
