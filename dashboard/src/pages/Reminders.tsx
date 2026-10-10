import { Bell, Check, Clock, DotsThree, PencilSimple, Plus, Prohibit, Trash } from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { addDays, addHours, set } from 'date-fns'
import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import { PlacesManager } from '@/components/PlacesManager'
import { ReminderRow } from '@/components/rows'
import { Button, IconButton } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { EmptyState, ErrorState, SkeletonRows } from '@/components/ui/feedback'
import { Field, Input, Select } from '@/components/ui/form'
import { Menu } from '@/components/ui/menu'
import { Chip, PageHeader, Panel, SectionHeader, Segmented } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useFamilyInfo } from '@/lib/family'
import { fromLocalInput, toLocalInput } from '@/lib/format'
import { useReminderActions } from '@/lib/mutations'
import { invalidateEntity, usePlaces, useReminders } from '@/lib/queries'
import { presetLabel, presetToRrule, rruleToPreset, type RecurrencePreset } from '@/lib/rrule'
import type { Reminder } from '@/lib/types'
import { undoLatest } from '@/lib/undo'

export default function Recordatorios() {
  const reminders = useReminders()
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const [editing, setEditing] = useState<Reminder | 'new' | null>(null)
  const [highlight, setHighlight] = useState<string | null>(null)
  const [showAllDone, setShowAllDone] = useState(false)
  const actions = useReminderActions()

  useEffect(() => {
    if (params.get('nuevo')) {
      setEditing('new')
      params.delete('nuevo')
      setParams(params, { replace: true })
    }
    const rid = params.get('r')
    if (rid && reminders.data) {
      setHighlight(rid)
      params.delete('r')
      setParams(params, { replace: true })
      requestAnimationFrame(() => document.getElementById(`rem-${rid}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }))
      setTimeout(() => setHighlight(null), 2400)
    }
  }, [params, setParams, reminders.data])

  useEffect(() => {
    if (location.hash === '#lugares') document.getElementById('lugares')?.scrollIntoView({ behavior: 'smooth' })
  }, [location.hash])

  const groups = useMemo(() => {
    const all = reminders.data ?? []
    const scheduled = all.filter((r) => r.status === 'scheduled')
    const byNext = (a: Reminder, b: Reminder) => (a.nextFireAt ?? a.dueAt ?? '').localeCompare(b.nextFireAt ?? b.dueAt ?? '')
    return {
      fired: all.filter((r) => r.status === 'fired'),
      upcoming: scheduled.filter((r) => !r.rrule && !r.placeId).sort(byNext),
      recurring: scheduled.filter((r) => r.rrule && !r.placeId).sort(byNext),
      place: scheduled.filter((r) => r.placeId),
      done: all
        .filter((r) => r.status === 'done' || r.status === 'cancelled')
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
    }
  }, [reminders.data])

  const menu = (r: Reminder) => (
    <Menu
      items={[
        r.status !== 'done' && r.status !== 'cancelled' && { label: 'Editar', icon: <PencilSimple className="size-4" />, onSelect: () => setEditing(r) },
        r.status === 'scheduled' && { label: 'Marcar como hecho', icon: <Check className="size-4" />, onSelect: () => actions.done.mutate(r) },
        (r.status === 'scheduled' || r.status === 'fired') && !r.placeId && { label: 'Posponer 10 min', icon: <Clock className="size-4" />, onSelect: () => actions.snooze.mutate({ r, minutes: 10 }) },
        r.status === 'fired' && { label: 'Posponer 1 hora', icon: <Clock className="size-4" />, onSelect: () => actions.snooze.mutate({ r, minutes: 60 }) },
        (r.status === 'scheduled' || r.status === 'fired') && { label: 'Cancelar', icon: <Prohibit className="size-4" />, onSelect: () => actions.cancel.mutate(r) },
        { label: 'Eliminar', icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => actions.remove.mutate(r) },
      ]}
      trigger={(p) => (
        <IconButton label={`Opciones de ${r.text}`} size="sm" {...p}>
          <DotsThree className="size-5" weight="bold" />
        </IconButton>
      )}
    />
  )

  const row = (r: Reminder, extra?: ReactNode) => (
    <div
      key={r.id}
      id={`rem-${r.id}`}
      className={cn('-mx-3 rounded-control px-3 transition-colors duration-700', highlight === r.id && 'bg-[color-mix(in_srgb,var(--accent)_12%,transparent)]')}
    >
      <ReminderRow
        r={r}
        actions={
          <>
            {extra}
            {menu(r)}
          </>
        }
      />
    </div>
  )

  const total = reminders.data?.length ?? 0
  const sections: { id: string; title: string; items: Reminder[]; extra?: (r: Reminder) => ReactNode; empty?: string }[] = [
    {
      id: 'fired',
      title: 'Sonaron',
      items: groups.fired,
      extra: (r) => (
        <>
          <Button size="sm" variant="quiet" onClick={() => actions.done.mutate(r)}>
            Hecho
          </Button>
          <Button size="sm" variant="ghost" className="max-sm:hidden" onClick={() => actions.snooze.mutate({ r, minutes: 10 })}>
            Posponer 10 min
          </Button>
        </>
      ),
    },
    { id: 'upcoming', title: 'Próximos', items: groups.upcoming, empty: 'No hay recordatorios con fecha.' },
    { id: 'recurring', title: 'Recurrentes', items: groups.recurring },
    { id: 'place', title: 'Por lugar', items: groups.place },
  ]

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <PageHeader
        title="Recordatorios"
        description="Kairo te avisa aquí, en tu teléfono y en el reloj."
        actions={
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setEditing('new')}>
            Nuevo recordatorio
          </Button>
        }
      />

      <div className="mt-7 grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_360px] lg:gap-6">
        <div className="grid gap-5">
          {reminders.isLoading ? (
            <Panel className="p-5">
              <SkeletonRows rows={5} />
            </Panel>
          ) : reminders.isError ? (
            <Panel>
              <ErrorState message={(reminders.error as Error).message} onRetry={() => void reminders.refetch()} />
            </Panel>
          ) : total === 0 ? (
            <Panel>
              <EmptyState
                icon={<Bell />}
                title="Sin recordatorios"
                description="Crea uno aquí o dile a Kairo: “recuérdame sacar la basura los martes a las 8 p. m.”."
              />
            </Panel>
          ) : (
            <>
              {sections
                .filter((s) => s.items.length > 0 || s.id === 'upcoming')
                .map((s) => (
                  <Panel key={s.id} className="px-5 pt-4 pb-2" aria-labelledby={`sec-${s.id}`}>
                    <SectionHeader id={`sec-${s.id}`} title={s.title} count={s.items.length} />
                    {s.items.length === 0 ? (
                      <p className="py-3 text-base text-muted">{s.empty}</p>
                    ) : (
                      <div className="divide-y divide-line">{s.items.map((r) => row(r, s.extra?.(r)))}</div>
                    )}
                  </Panel>
                ))}
              {groups.done.length > 0 && (
                <Panel className="px-5 pt-4 pb-2" aria-labelledby="sec-done">
                  <SectionHeader id="sec-done" title="Hechos" count={groups.done.length} />
                  <div className="divide-y divide-line">{(showAllDone ? groups.done : groups.done.slice(0, 6)).map((r) => row(r))}</div>
                  {groups.done.length > 6 && !showAllDone && (
                    <button type="button" onClick={() => setShowAllDone(true)} className="py-3 text-sm text-muted hover:text-fg">
                      Ver {groups.done.length - 6} más
                    </button>
                  )}
                </Panel>
              )}
            </>
          )}
        </div>

        <Panel className="p-5 lg:sticky lg:top-6" aria-labelledby="lugares">
          <SectionHeader id="lugares" title="Lugares" />
          <div className="mt-2">
            <PlacesManager />
          </div>
        </Panel>
      </div>

      <ReminderDialog reminder={editing} onClose={() => setEditing(null)} />
    </div>
  )
}

function defaultDue() {
  const d = addHours(new Date(), 1)
  return set(d, { minutes: d.getMinutes() < 30 ? 30 : 0, seconds: 0, milliseconds: 0, hours: d.getMinutes() < 30 ? d.getHours() : d.getHours() + 1 })
}

function ReminderDialog({ reminder, onClose }: { reminder: Reminder | 'new' | null; onClose: () => void }) {
  const qc = useQueryClient()
  const places = usePlaces()
  const fam = useFamilyInfo()
  const editing = reminder && reminder !== 'new' ? reminder : null
  const [forUser, setForUser] = useState('')
  const [text, setText] = useState('')
  const [kind, setKind] = useState<'time' | 'place'>('time')
  const [when, setWhen] = useState(toLocalInput(defaultDue()))
  const [preset, setPreset] = useState<RecurrencePreset>('none')
  const [placeId, setPlaceId] = useState('')
  const [trigger, setTrigger] = useState<'arrive' | 'leave'>('arrive')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!reminder) return
    const r = reminder === 'new' ? null : reminder
    setText(r?.text ?? '')
    setKind(r?.placeId ? 'place' : 'time')
    setWhen(toLocalInput(r?.dueAt ? new Date(r.nextFireAt ?? r.dueAt) : defaultDue()))
    setPreset(rruleToPreset(r?.rrule ?? null))
    setPlaceId(r?.placeId ?? '')
    setTrigger(r?.placeTrigger ?? 'arrive')
    setForUser('')
    setError(null)
  }, [reminder])

  // Para otra persona solo sirven los lugares familiares (los personales no los ve).
  const forOther = !editing && !!forUser && forUser !== fam.me
  const forName = forOther ? fam.nameOf(forUser) : null
  const usablePlaces = (places.data ?? []).filter((p) => !forOther || p.shared)

  const whenDate = when ? fromLocalInput(when) : null

  const save = useMutation({
    mutationFn: () => {
      const body: Record<string, unknown> =
        kind === 'time'
          ? { text: text.trim(), dueAt: whenDate!.toISOString(), rrule: presetToRrule(preset, whenDate) }
          : { text: text.trim(), placeId, placeTrigger: trigger }
      if (editing) {
        if (kind === 'time') body.placeId = null
        return api.patch<Reminder>(`/reminders/${editing.id}`, body)
      }
      if (body.rrule === null) delete body.rrule
      if (forOther) body.forUserId = forUser
      return api.post<Reminder>('/reminders', body)
    },
    onSuccess: (r) => {
      invalidateEntity(qc, 'reminder')
      onClose()
      if (editing) toast({ title: 'Recordatorio guardado', description: r.text })
      else
        toast({
          title: forName ? `Recordatorio para ${forName}` : 'Recordatorio creado',
          description: r.text,
          tone: 'success',
          actions: [{ label: 'Deshacer', onClick: () => undoLatest(qc, 'reminder', r.id, 'created').catch((e) => toastError(e, 'No se pudo deshacer')) }],
        })
    },
    onError: (e) => setError(e instanceof Error ? e.message : 'No se pudo guardar'),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!text.trim()) return setError(forOther ? `Escribe qué le recuerdo a ${forName ?? 'esa persona'}.` : 'Escribe qué te recuerdo.')
    if (kind === 'time' && (!whenDate || Number.isNaN(whenDate.getTime()))) return setError('Elige una fecha y hora.')
    if (kind === 'time' && preset === 'none' && whenDate!.getTime() < Date.now() - 60_000) return setError('Esa hora ya pasó.')
    if (kind === 'place' && !placeId) return setError('Elige un lugar.')
    if (kind === 'place' && forOther && !usablePlaces.some((p) => p.id === placeId)) return setError('Ese lugar es personal: compártelo con la familia o elige otro.')
    setError(null)
    save.mutate()
  }

  const quick = [
    { label: 'En 1 hora', date: addHours(new Date(), 1) },
    { label: 'Esta tarde', date: set(new Date(), { hours: 18, minutes: 0, seconds: 0 }) },
    { label: 'Mañana 9:00', date: set(addDays(new Date(), 1), { hours: 9, minutes: 0, seconds: 0 }) },
  ].filter((q) => q.date.getTime() > Date.now())

  return (
    <Dialog
      open={!!reminder}
      onClose={onClose}
      title={editing ? 'Editar recordatorio' : 'Nuevo recordatorio'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="primary" type="submit" form="rem-form" loading={save.isPending}>
            Guardar
          </Button>
        </>
      }
    >
      <form id="rem-form" onSubmit={submit} className="grid gap-4">
        {!editing && fam.hasOthers && (
          <Field label="Para" hint={forOther ? `Suena en el teléfono de ${forName} y le llega un aviso.` : undefined}>
            {(id, d) => (
              <Select id={id} value={forOther ? forUser : ''} onChange={(e) => setForUser(e.target.value)} aria-describedby={d}>
                <option value="">Yo</option>
                {fam.others.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        <Field label={forOther ? `¿Qué le recuerdo a ${forName ?? 'esa persona'}?` : '¿Qué te recuerdo?'}>
          {(id) => <Input id={id} value={text} onChange={(e) => setText(e.target.value)} autoFocus placeholder="Llamar a la EPS para la cita" />}
        </Field>
        <Segmented
          label="Tipo de aviso"
          value={kind}
          onChange={setKind}
          options={[
            { value: 'time', label: 'Fecha y hora' },
            { value: 'place', label: 'Al llegar o salir de un lugar' },
          ]}
          className="w-fit max-w-full"
        />
        {kind === 'time' ? (
          <>
            <Field label="Cuándo">
              {(id) => <Input id={id} type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} className="font-mono" />}
            </Field>
            {quick.length > 0 && (
              <div className="-mt-1 flex flex-wrap gap-1.5">
                {quick.map((q) => (
                  <Chip key={q.label} onClick={() => setWhen(toLocalInput(q.date))}>
                    {q.label}
                  </Chip>
                ))}
              </div>
            )}
            <Field label="Repetir">
              {(id) => (
                <Select id={id} value={preset} onChange={(e) => setPreset(e.target.value as RecurrencePreset)}>
                  {(['none', 'daily', 'weekdays', 'weekly', 'monthly'] as RecurrencePreset[]).map((p) => (
                    <option key={p} value={p}>
                      {presetLabel(p, whenDate)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          </>
        ) : usablePlaces.length === 0 ? (
          <p className="rounded-control border border-dashed border-line px-3 py-4 text-base text-muted">
            {forOther
              ? `No hay lugares familiares. Comparte uno con la familia en la sección Lugares para recordarle algo a ${forName} al llegar o salir.`
              : 'Aún no tienes lugares guardados. Agrega uno en la sección Lugares de esta página.'}
          </p>
        ) : (
          <>
            <Field label="Lugar">
              {(id) => (
                <Select id={id} value={placeId} onChange={(e) => setPlaceId(e.target.value)}>
                  <option value="">Elige un lugar</option>
                  {usablePlaces.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Segmented
              label="Momento"
              value={trigger}
              onChange={setTrigger}
              options={[
                { value: 'arrive', label: 'Al llegar' },
                { value: 'leave', label: 'Al salir' },
              ]}
              className="w-fit"
            />
          </>
        )}
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
      </form>
    </Dialog>
  )
}
