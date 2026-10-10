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
import { t } from '@/lib/i18n'
import { useReminderActions } from '@/lib/mutations'
import { invalidateEntity, usePlaces, useReminders } from '@/lib/queries'
import { presetLabel, presetToRrule, rruleToPreset, type RecurrencePreset } from '@/lib/rrule'
import type { Reminder } from '@/lib/types'
import { undoLatest } from '@/lib/undo'

export default function RemindersPage() {
  const reminders = useReminders()
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const [editing, setEditing] = useState<Reminder | 'new' | null>(null)
  const [highlight, setHighlight] = useState<string | null>(null)
  const [showAllDone, setShowAllDone] = useState(false)
  const actions = useReminderActions()

  useEffect(() => {
    if (params.get('new')) {
      setEditing('new')
      params.delete('new')
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
    if (location.hash === '#places') document.getElementById('places')?.scrollIntoView({ behavior: 'smooth' })
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
        r.status !== 'done' && r.status !== 'cancelled' && { label: t('common.edit'), icon: <PencilSimple className="size-4" />, onSelect: () => setEditing(r) },
        r.status === 'scheduled' && { label: t('reminders.markDone'), icon: <Check className="size-4" />, onSelect: () => actions.done.mutate(r) },
        (r.status === 'scheduled' || r.status === 'fired') && !r.placeId && { label: t('realtime.snooze10'), icon: <Clock className="size-4" />, onSelect: () => actions.snooze.mutate({ r, minutes: 10 }) },
        r.status === 'fired' && { label: t('reminders.snooze1h'), icon: <Clock className="size-4" />, onSelect: () => actions.snooze.mutate({ r, minutes: 60 }) },
        (r.status === 'scheduled' || r.status === 'fired') && { label: t('reminders.cancel'), icon: <Prohibit className="size-4" />, onSelect: () => actions.cancel.mutate(r) },
        { label: t('common.delete'), icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => actions.remove.mutate(r) },
      ]}
      trigger={(p) => (
        <IconButton label={t('reminders.optionsFor', { text: r.text })} size="sm" {...p}>
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
      title: t('reminders.sectionFired'),
      items: groups.fired,
      extra: (r) => (
        <>
          <Button size="sm" variant="quiet" onClick={() => actions.done.mutate(r)}>
            {t('reminders.done')}
          </Button>
          <Button size="sm" variant="ghost" className="max-sm:hidden" onClick={() => actions.snooze.mutate({ r, minutes: 10 })}>
            {t('realtime.snooze10')}
          </Button>
        </>
      ),
    },
    { id: 'upcoming', title: t('reminders.sectionUpcoming'), items: groups.upcoming, empty: t('reminders.noDated') },
    { id: 'recurring', title: t('reminders.sectionRecurring'), items: groups.recurring },
    { id: 'place', title: t('reminders.sectionPlace'), items: groups.place },
  ]

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <PageHeader
        title={t('nav.reminders')}
        description={t('reminders.subtitle')}
        actions={
          <Button variant="primary" icon={<Plus className="size-4" />} onClick={() => setEditing('new')}>
            {t('reminders.new')}
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
                title={t('reminders.empty')}
                description={t('reminders.emptyHint')}
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
                  <SectionHeader id="sec-done" title={t('reminders.sectionDone')} count={groups.done.length} />
                  <div className="divide-y divide-line">{(showAllDone ? groups.done : groups.done.slice(0, 6)).map((r) => row(r))}</div>
                  {groups.done.length > 6 && !showAllDone && (
                    <button type="button" onClick={() => setShowAllDone(true)} className="py-3 text-sm text-muted hover:text-fg">
                      {t('reminders.showMore', { count: groups.done.length - 6 })}
                    </button>
                  )}
                </Panel>
              )}
            </>
          )}
        </div>

        <Panel className="p-5 lg:sticky lg:top-6" aria-labelledby="places">
          <SectionHeader id="places" title={t('reminders.places')} />
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

  // Only family places work for someone else (they can't see personal ones).
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
      if (editing) toast({ title: t('reminders.saved'), description: r.text })
      else
        toast({
          title: forName ? t('reminders.forName', { name: forName }) : t('reminders.created'),
          description: r.text,
          tone: 'success',
          actions: [{ label: t('common.undo'), onClick: () => undoLatest(qc, 'reminder', r.id, 'created').catch((e) => toastError(e, t('common.couldNotUndo'))) }],
        })
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('places.err.save')),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!text.trim()) return setError(forOther ? t('reminders.err.textFor', { name: forName ?? t('reminders.thatPerson') }) : t('reminders.err.text'))
    if (kind === 'time' && (!whenDate || Number.isNaN(whenDate.getTime()))) return setError(t('reminders.err.date'))
    if (kind === 'time' && preset === 'none' && whenDate!.getTime() < Date.now() - 60_000) return setError(t('reminders.err.past'))
    if (kind === 'place' && !placeId) return setError(t('reminders.err.place'))
    if (kind === 'place' && forOther && !usablePlaces.some((p) => p.id === placeId)) return setError(t('reminders.err.personalPlace'))
    setError(null)
    save.mutate()
  }

  const quick = [
    { label: t('reminders.quickHour'), date: addHours(new Date(), 1) },
    { label: t('reminders.quickEvening'), date: set(new Date(), { hours: 18, minutes: 0, seconds: 0 }) },
    { label: t('reminders.quickTomorrow'), date: set(addDays(new Date(), 1), { hours: 9, minutes: 0, seconds: 0 }) },
  ].filter((q) => q.date.getTime() > Date.now())

  return (
    <Dialog
      open={!!reminder}
      onClose={onClose}
      title={editing ? t('reminders.editTitle') : t('reminders.new')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="rem-form" loading={save.isPending}>
            {t('common.save')}
          </Button>
        </>
      }
    >
      <form id="rem-form" onSubmit={submit} className="grid gap-4">
        {!editing && fam.hasOthers && (
          <Field label={t('reminders.for')} hint={forOther ? t('reminders.forHint', { name: forName ?? '' }) : undefined}>
            {(id, d) => (
              <Select id={id} value={forOther ? forUser : ''} onChange={(e) => setForUser(e.target.value)} aria-describedby={d}>
                <option value="">{t('reminders.me')}</option>
                {fam.others.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        <Field label={forOther ? t('reminders.whatFor', { name: forName ?? t('reminders.thatPerson') }) : t('reminders.what')}>
          {(id) => <Input id={id} value={text} onChange={(e) => setText(e.target.value)} autoFocus placeholder={t('reminders.textPlaceholder')} />}
        </Field>
        <Segmented
          label={t('reminders.kind')}
          value={kind}
          onChange={setKind}
          options={[
            { value: 'time', label: t('reminders.kindTime') },
            { value: 'place', label: t('reminders.kindPlace') },
          ]}
          className="w-fit max-w-full"
        />
        {kind === 'time' ? (
          <>
            <Field label={t('reminders.when')}>
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
            <Field label={t('reminders.repeat')}>
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
              ? t('reminders.noFamilyPlaces', { name: forName ?? '' })
              : t('reminders.noPlaces')}
          </p>
        ) : (
          <>
            <Field label={t('reminders.place')}>
              {(id) => (
                <Select id={id} value={placeId} onChange={(e) => setPlaceId(e.target.value)}>
                  <option value="">{t('reminders.pickPlace')}</option>
                  {usablePlaces.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            <Segmented
              label={t('reminders.moment')}
              value={trigger}
              onChange={setTrigger}
              options={[
                { value: 'arrive', label: t('reminders.onArrive') },
                { value: 'leave', label: t('reminders.onLeave') },
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
