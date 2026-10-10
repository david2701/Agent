import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast, toastError } from '@/components/ui/toast'
import { api } from './api'
import { invalidateEntity, keys } from './queries'
import type { KList, Reminder, Task } from './types'
import { undoLatest } from './undo'

export type TaskPatch = Partial<Pick<Task, 'title' | 'notes' | 'status' | 'priority' | 'dueDate' | 'dueAt' | 'projectId' | 'tags' | 'sort'>>

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
      toastError(e, 'No se pudo actualizar la tarea')
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
              title: 'Tarea completada',
              description: task.title,
              tone: 'success',
              actions: [{ label: 'Deshacer', onClick: () => update.mutate({ id: task.id, patch: { status: prevStatus === 'done' ? 'todo' : prevStatus } }) }],
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
        title: 'Tarea eliminada',
        description: task.title,
        actions: [
          {
            label: 'Deshacer',
            onClick: () => undoLatest(qc, 'task', task.id, 'deleted').catch((e) => toastError(e, 'No se pudo deshacer')),
          },
        ],
      })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar la tarea'),
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
      toast({ title: r.rrule ? 'Marcado como hecho por hoy' : 'Recordatorio hecho', description: r.text, tone: 'success' })
    },
    onError: (e) => toastError(e, 'No se pudo marcar como hecho'),
  })
  const snooze = useMutation({
    mutationFn: ({ r, minutes }: { r: Reminder; minutes: number }) => api.post<Reminder>(`/reminders/${r.id}/snooze`, { minutes }),
    onSuccess: (_d, { minutes }) => {
      after()
      toast({ title: `Pospuesto ${minutes} min` })
    },
    onError: (e) => toastError(e, 'No se pudo posponer'),
  })
  const cancel = useMutation({
    mutationFn: (r: Reminder) => api.post<Reminder>(`/reminders/${r.id}/cancel`),
    onSuccess: () => {
      after()
      toast({ title: 'Recordatorio cancelado' })
    },
    onError: (e) => toastError(e, 'No se pudo cancelar'),
  })
  const remove = useMutation({
    mutationFn: (r: Reminder) => api.del(`/reminders/${r.id}`).then(() => r),
    onSuccess: (r) => {
      after()
      toast({
        title: 'Recordatorio eliminado',
        description: r.text,
        actions: [{ label: 'Deshacer', onClick: () => undoLatest(qc, 'reminder', r.id, 'deleted').catch((e) => toastError(e, 'No se pudo deshacer')) }],
      })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar'),
  })
  return { done, snooze, cancel, remove }
}

/** Marcar/desmarcar un elemento de lista con actualización optimista. */
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
      toastError(e, 'No se pudo actualizar la lista')
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
