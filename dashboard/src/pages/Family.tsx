import {
  Checks,
  DotsThree,
  House,
  Key,
  LockSimple,
  PaperPlaneTilt,
  PencilSimple,
  ShieldCheck,
  ShieldSlash,
  Ticket,
  UserMinus,
  UserPlus,
} from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import { Avatar } from '@/components/family'
import { NoticesFeed } from '@/components/NoticesFeed'
import { Button, IconButton } from '@/components/ui/button'
import { CopyButton, CopyField } from '@/components/ui/copy'
import { ConfirmDialog, Dialog } from '@/components/ui/dialog'
import { ErrorState, Skeleton, SkeletonRows } from '@/components/ui/feedback'
import { Field, FormError, Input, Textarea } from '@/components/ui/form'
import { Menu } from '@/components/ui/menu'
import { Chip, PageHeader, Panel, SectionHeader, Segmented, Tag } from '@/components/ui/misc'
import { toast, toastError } from '@/components/ui/toast'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { firstName, roleLabel, useFamilyInfo, type FamilyView } from '@/lib/family'
import { dayMonthLong, longDate, shortDate, time, toDate } from '@/lib/format'
import { intlLocale, t, tn } from '@/lib/i18n'
import { useMarkNoticesRead } from '@/lib/mutations'
import { keys, useInvites, useNotices } from '@/lib/queries'
import type { FamilyInfo, FamilyRole, Household, Invitation, InviteCreated, Member } from '@/lib/types'

const MESSAGE_MAX = 500

/** "Expires Saturday, October 17 at 9:00 AM" */
function expiresLabel(iso: string) {
  return t('family.expires', { date: longDate(iso), time: time(iso) })
}

export default function FamilyPage() {
  const fam = useFamilyInfo()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const [inviteOpen, setInviteOpen] = useState(false)

  // ?invite=1 opens the dialog (from Today or the command palette); #updates and #notice scroll to the section.
  useEffect(() => {
    if (!params.get('invite') || !fam.query.data) return
    if (fam.isAdmin) setInviteOpen(true)
    params.delete('invite')
    setParams(params, { replace: true })
  }, [params, setParams, fam.query.data, fam.isAdmin])

  useEffect(() => {
    if (location.hash && fam.query.data) requestAnimationFrame(() => document.getElementById(location.hash.slice(1))?.scrollIntoView({ block: 'start' }))
  }, [location.hash, fam.query.data])

  if (fam.query.isLoading) return <FamilySkeleton />
  if (fam.query.isError || !fam.household)
    return (
      <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
        <PageHeader title={t('nav.family')} />
        <Panel className="mt-7">
          <ErrorState message={(fam.query.error as Error | null)?.message ?? t('family.err.load')} onRetry={() => void fam.query.refetch()} />
        </Panel>
      </div>
    )

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9">
      <HouseholdHeader fam={fam} household={fam.household} onInvite={() => setInviteOpen(true)} />

      <div className="mt-7 grid items-start gap-5 lg:grid-cols-[minmax(0,1.45fr)_minmax(0,1fr)] lg:gap-6">
        <div className="grid gap-5 lg:gap-6">
          <MembersPanel fam={fam} onInvite={() => setInviteOpen(true)} />
          <NoticesPanel />
        </div>
        <div className="grid gap-5 lg:gap-6">
          <MessagePanel fam={fam} />
          {fam.isAdmin ? <InvitesPanel fam={fam} /> : <OnlyAdminsPanel fam={fam} />}
          <HowItWorksPanel />
        </div>
      </div>

      {fam.isAdmin && <InviteDialog open={inviteOpen} onClose={() => setInviteOpen(false)} householdName={fam.household.name} />}
    </div>
  )
}

function FamilySkeleton() {
  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 md:px-8 md:py-9" aria-busy="true" aria-label={t('today.loadingFamily')}>
      <div className="grid gap-2">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-4 w-80 max-w-full" />
      </div>
      <div className="mt-7 grid items-start gap-5 lg:grid-cols-[minmax(0,1.45fr)_minmax(0,1fr)] lg:gap-6">
        <Panel className="p-5">
          <Skeleton className="h-5 w-28" />
          <SkeletonRows rows={3} className="mt-5" />
        </Panel>
        <Panel className="p-5">
          <Skeleton className="h-5 w-32" />
          <Skeleton className="mt-4 h-24 w-full rounded-control" />
        </Panel>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- Header */

function HouseholdHeader({ fam, household, onInvite }: { fam: FamilyView; household: Household; onInvite: () => void }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState<string | null>(null)
  const rename = useMutation({
    mutationFn: (name: string) => api.patch<Household>('/family', { name }),
    onSuccess: (h) => {
      const prev = qc.getQueryData<FamilyInfo>(keys.family)
      if (prev) qc.setQueryData<FamilyInfo>(keys.family, { ...prev, household: h })
      setEditing(null)
      toast({ title: t('family.nameSaved'), description: h.name })
    },
    onError: (e) => toastError(e, t('family.err.rename')),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const name = editing?.trim()
    if (!name) return
    if (name === household.name) return setEditing(null)
    rename.mutate(name)
  }

  const title =
    editing !== null ? (
      <form onSubmit={submit} className="flex flex-wrap items-center gap-2">
        <Input
          value={editing}
          onChange={(e) => setEditing(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') setEditing(null)
          }}
          maxLength={60}
          autoFocus
          aria-label={t('family.nameLabel')}
          className="h-10 w-[min(100%,22rem)] text-md font-semibold"
        />
        <Button type="submit" variant="primary" loading={rename.isPending} disabled={!editing.trim()}>
          {t('common.save')}
        </Button>
        <Button variant="ghost" onClick={() => setEditing(null)}>
          {t('common.cancel')}
        </Button>
      </form>
    ) : (
      <span className="flex items-center gap-1.5">
        <span className="min-w-0 break-words">{household.name}</span>
        {fam.isAdmin && (
          <IconButton label={t('family.renameHousehold')} size="sm" onClick={() => setEditing(household.name)}>
            <PencilSimple className="size-4" />
          </IconButton>
        )}
      </span>
    )

  return (
    <PageHeader
      title={title}
      description={tn('family.membersLine', fam.members.length)}
      actions={
        fam.isAdmin && editing === null ? (
          <Button variant="primary" icon={<UserPlus className="size-4" />} onClick={onInvite}>
            {t('family.invite')}
          </Button>
        ) : undefined
      }
    />
  )
}

/* ---------------------------------------------------------------- Members */

function MembersPanel({ fam, onInvite }: { fam: FamilyView; onInvite: () => void }) {
  const qc = useQueryClient()
  const [removing, setRemoving] = useState<Member | null>(null)
  const [access, setAccess] = useState<{ member: Member; result: InviteCreated } | null>(null)

  const setRole = useMutation({
    mutationFn: ({ m, role }: { m: Member; role: FamilyRole }) => api.patch<Member>(`/family/members/${m.id}`, { role }),
    onSuccess: (m) => {
      void qc.invalidateQueries({ queryKey: keys.family })
      const name = firstName(m.displayName)
      toast({ title: m.role === 'admin' ? t('family.nowAdmin', { name }) : t('family.noLongerAdmin', { name }) })
    },
    onError: (e) => toastError(e, t('family.err.role')),
  })

  const accessCode = useMutation({
    mutationFn: (m: Member) => api.post<InviteCreated>(`/family/members/${m.id}/access-code`).then((r) => ({ member: m, result: r })),
    onSuccess: (r) => {
      setAccess(r)
      void qc.invalidateQueries({ queryKey: keys.invites })
    },
    onError: (e) => toastError(e, t('family.err.code')),
  })

  const remove = useMutation({
    mutationFn: (m: Member) => api.del(`/family/members/${m.id}`).then(() => m),
    onSuccess: (m) => {
      setRemoving(null)
      void qc.invalidateQueries({ queryKey: keys.family })
      toast({ title: t('family.removed', { name: firstName(m.displayName) }), description: t('family.removedBody') })
    },
    onError: (e) => toastError(e, t('family.err.remove')),
  })

  const members = [...fam.members].sort((a, b) => Number(b.id === fam.me) - Number(a.id === fam.me) || a.joinedAt.localeCompare(b.joinedAt))

  return (
    <Panel className="p-5" aria-labelledby="members-title">
      <SectionHeader id="members-title" title={t('family.members')} count={fam.members.length} />
      <ul className="mt-2 divide-y divide-line">
        {members.map((m) => {
          const me = m.id === fam.me
          const role = <Tag tone="neutral">{roleLabel(m.role)}</Tag>
          return (
            <li key={m.id} className="flex items-center gap-3 py-3">
              <Avatar name={m.displayName} size="lg" />
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span className="min-w-0 truncate text-base font-medium text-fg">{m.displayName}</span>
                  {me && <span className="text-sm font-medium text-accent-strong">{t('app.you')}</span>}
                  <span className="sm:hidden">{role}</span>
                </p>
                <p className="truncate text-sm text-muted">{m.email}</p>
                <p className="text-xs text-faint">{t('family.joined', { date: dayMonthLong(toDate(m.joinedAt)) })}</p>
              </div>
              <span className="max-sm:hidden">{role}</span>
              {fam.isAdmin && !me ? (
                <Menu
                  items={[
                    { label: t('family.genCode'), icon: <Key className="size-4" />, onSelect: () => accessCode.mutate(m), disabled: accessCode.isPending },
                    m.role === 'admin'
                      ? { label: t('family.removeAdmin'), icon: <ShieldSlash className="size-4" />, onSelect: () => setRole.mutate({ m, role: 'member' }) }
                      : { label: t('family.makeAdmin'), icon: <ShieldCheck className="size-4" />, onSelect: () => setRole.mutate({ m, role: 'admin' }) },
                    { label: t('family.removeFromFamily'), icon: <UserMinus className="size-4" />, tone: 'danger', onSelect: () => setRemoving(m) },
                  ]}
                  trigger={(p) => (
                    <IconButton label={t('family.optionsFor', { name: m.displayName })} {...p}>
                      <DotsThree className="size-5" weight="bold" />
                    </IconButton>
                  )}
                />
              ) : fam.isAdmin ? (
                <span className="size-9 shrink-0" aria-hidden />
              ) : null}
            </li>
          )
        })}
      </ul>

      {!fam.hasOthers && fam.isAdmin && (
        <div className="mt-2 flex flex-wrap items-center justify-between gap-3 rounded-control border border-dashed border-line px-4 py-3.5">
          <p className="min-w-0 flex-1 basis-56 text-base text-muted">{t('family.aloneHint')}</p>
          <Button size="sm" icon={<UserPlus className="size-4" />} onClick={onInvite}>
            {t('family.inviteShort')}
          </Button>
        </div>
      )}

      <ConfirmDialog
        open={!!removing}
        onClose={() => setRemoving(null)}
        onConfirm={() => removing && remove.mutate(removing)}
        loading={remove.isPending}
        title={t('family.removeTitle', { name: removing ? firstName(removing.displayName) : t('family.thisMember') })}
        description={t('family.removeBody')}
        confirmLabel={t('family.removeFromFamily')}
      />

      <Dialog
        open={!!access}
        onClose={() => setAccess(null)}
        title={access ? t('family.accessCodeFor', { name: firstName(access.member.displayName) }) : t('family.accessCode')}
        description={t('family.accessCodeHint')}
        footer={
          <Button variant="primary" onClick={() => setAccess(null)}>
            {t('common.done')}
          </Button>
        }
      >
        {access && (
          <CodeResult
            result={access.result}
            note={t('family.accessCodeNote', { name: firstName(access.member.displayName) })}
          />
        )}
      </Dialog>
    </Panel>
  )
}

/** Large code in Geist Mono, link and expiry, with copy buttons. */
function CodeResult({ result, note }: { result: InviteCreated; note: string }) {
  return (
    <div className="grid gap-4">
      <div className="rounded-card border border-line bg-bg px-4 py-5 text-center">
        <p className="text-sm text-muted">{t('family.code')}</p>
        <p className="mt-2 font-mono text-xl leading-none font-medium tracking-[0.08em] text-fg select-all tnum sm:text-2xl" data-testid="family-code">
          {result.code}
        </p>
        <p className="mt-3 text-sm text-muted">{expiresLabel(result.invitation.expiresAt)}</p>
        <CopyButton value={result.code} label={t('family.copyCode')} className="mt-4" />
      </div>
      <div className="grid gap-1.5">
        <p className="text-sm font-medium text-fg">{t('family.link')}</p>
        <CopyField value={result.link} label={t('family.copyLink')} copiedLabel={t('common.copied')} />
      </div>
      <p className="text-sm text-muted">{note}</p>
    </div>
  )
}

/* ---------------------------------------------------------------- Invite */

function InviteDialog({ open, onClose, householdName }: { open: boolean; onClose: () => void; householdName: string }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [role, setRole] = useState<FamilyRole>('member')
  const [result, setResult] = useState<InviteCreated | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setName('')
    setRole('member')
    setResult(null)
    setError(null)
  }, [open])

  const create = useMutation({
    mutationFn: () => api.post<InviteCreated>('/family/invites', { name: name.trim(), role }),
    onSuccess: (r) => {
      setResult(r)
      void qc.invalidateQueries({ queryKey: keys.invites })
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('family.err.createInvite')),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    create.mutate()
  }

  const invitedName = result?.invitation.name ? firstName(result.invitation.name) : null

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={result ? (invitedName ? t('family.inviteFor', { name: invitedName }) : t('family.inviteReady')) : t('family.invite')}
      description={result ? t('family.shareAnyWay') : t('family.willJoin', { household: householdName })}
      footer={
        result ? (
          <>
            <Button
              variant="ghost"
              onClick={() => {
                setResult(null)
                setName('')
              }}
            >
              {t('family.inviteAnother')}
            </Button>
            <Button variant="primary" onClick={onClose}>
              {t('common.done')}
            </Button>
          </>
        ) : (
          <>
            <Button variant="ghost" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button variant="primary" type="submit" form="invite-form" loading={create.isPending}>
              {t('family.createInvite')}
            </Button>
          </>
        )
      }
    >
      {result ? (
        <CodeResult result={result} note={t('family.inviteNote')} />
      ) : (
        <form id="invite-form" onSubmit={submit} className="grid gap-4">
          <Field label={t('common.name')} optional hint={t('family.inviteNameHint')}>
            {(id, d) => (
              <Input id={id} value={name} onChange={(e) => setName(e.target.value)} maxLength={60} placeholder={t('family.inviteNamePlaceholder')} autoFocus aria-describedby={d} />
            )}
          </Field>
          <div className="grid gap-1.5">
            <span className="text-sm font-medium">{t('family.role')}</span>
            <Segmented
              label={t('family.role')}
              value={role}
              onChange={setRole}
              options={[
                { value: 'member', label: t('role.member') },
                { value: 'admin', label: t('role.admin') },
              ]}
              className="w-fit"
            />
            <p className="text-sm text-muted">
              {role === 'member' ? t('family.roleMemberHint') : t('family.roleAdminHint')}
            </p>
          </div>
          {error && <FormError>{error}</FormError>}
        </form>
      )}
    </Dialog>
  )
}

function InvitesPanel({ fam }: { fam: FamilyView }) {
  const qc = useQueryClient()
  const invites = useInvites(true)
  const pending = useMemo(() => {
    const now = Date.now()
    return (invites.data ?? [])
      .filter((i) => !i.usedAt && new Date(i.expiresAt).getTime() > now)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  }, [invites.data])

  const revoke = useMutation({
    mutationFn: (i: Invitation) => api.del(`/family/invites/${i.id}`).then(() => i),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.invites })
      toast({ title: t('family.inviteRevoked'), description: t('family.inviteRevokedBody') })
    },
    onError: (e) => toastError(e, t('family.err.revoke')),
  })

  const label = (i: Invitation) => {
    if (i.kind === 'reset') return t('family.accessCodeOf', { name: fam.nameOf(i.targetUserId) ?? t('family.aMember') })
    return i.name ? t('family.inviteFor', { name: i.name }) : t('family.unnamedInvite')
  }

  return (
    <Panel className="p-5" aria-labelledby="invites-title">
      <SectionHeader id="invites-title" title={t('family.pendingInvites')} count={invites.data ? pending.length : undefined} />
      <p className="mt-1 text-sm text-muted">{t('family.pendingHint')}</p>
      {invites.isLoading ? (
        <SkeletonRows rows={2} className="mt-4" />
      ) : invites.isError ? (
        <ErrorState message={(invites.error as Error).message} onRetry={() => void invites.refetch()} className="py-6" />
      ) : pending.length === 0 ? (
        <p className="mt-3 text-base text-muted">{t('family.noPending')}</p>
      ) : (
        <ul className="mt-2 divide-y divide-line">
          {pending.map((i) => {
            const Icon = i.kind === 'reset' ? Key : Ticket
            return (
              <li key={i.id} className="flex items-center gap-3 py-2.5">
                <Icon className="size-[18px] shrink-0 text-faint" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-base text-fg">{label(i)}</p>
                  <p className="text-sm text-muted">
                    {i.kind === 'invite' && <>{roleLabel(i.role)}. </>}
                    {t('family.expiresOn')} {shortDate(i.expiresAt)}, <span className="font-mono">{time(i.expiresAt)}</span>
                  </p>
                </div>
                <Button
                  size="sm"
                  variant="ghost"
                  loading={revoke.isPending && revoke.variables?.id === i.id}
                  onClick={() => revoke.mutate(i)}
                >
                  {t('family.revoke')}
                </Button>
              </li>
            )
          })}
        </ul>
      )}
    </Panel>
  )
}

function OnlyAdminsPanel({ fam }: { fam: FamilyView }) {
  const admins = fam.members.filter((m) => m.role === 'admin').map((m) => firstName(m.displayName))
  return (
    <Panel className="p-5" aria-labelledby="invites-title">
      <SectionHeader id="invites-title" title={t('family.invites')} />
      <p className="mt-2 text-base text-muted">
        {t('family.onlyAdmins')}
        {admins.length > 0 && <> {t('family.askAdmins', { admins: new Intl.ListFormat(intlLocale(), { type: 'disjunction' }).format(admins) })}</>}
      </p>
    </Panel>
  )
}

function HowItWorksPanel() {
  return (
    <Panel className="p-5" aria-labelledby="how-title">
      <SectionHeader id="how-title" title={t('family.whoSees')} />
      <dl className="mt-3 grid gap-3">
        <div className="flex gap-3">
          <LockSimple className="mt-0.5 size-[18px] shrink-0 text-faint" aria-hidden />
          <div>
            <dt className="text-base font-medium text-fg">{t('family.personal')}</dt>
            <dd className="text-sm text-muted">{t('family.personalHint')}</dd>
          </div>
        </div>
        <div className="flex gap-3">
          <House className="mt-0.5 size-[18px] shrink-0 text-faint" aria-hidden />
          <div>
            <dt className="text-base font-medium text-fg">{t('family.tag')}</dt>
            <dd className="text-sm text-muted">{t('family.sharedHint')}</dd>
          </div>
        </div>
      </dl>
    </Panel>
  )
}

/* ---------------------------------------------------------------- Notice */

function MessagePanel({ fam }: { fam: FamilyView }) {
  const [text, setText] = useState('')
  const [to, setTo] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const others = fam.others

  const send = useMutation({
    mutationFn: () => api.post<{ sent: number }>('/family/message', to.length ? { text: text.trim(), to } : { text: text.trim() }),
    onSuccess: (r) => {
      setText('')
      setTo([])
      toast({ title: t('family.noticeSent'), description: tn('family.sentTo', r.sent), tone: 'success' })
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('family.err.send')),
  })

  const toggle = (id: string) => {
    const next = to.includes(id) ? to.filter((x) => x !== id) : [...to, id]
    // Picking everyone one by one is the same as "Everyone".
    setTo(next.length === others.length ? [] : next)
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!text.trim()) return setError(t('family.err.writeNotice'))
    setError(null)
    send.mutate()
  }

  return (
    <Panel className="scroll-mt-6 p-5" id="notice" aria-labelledby="msg-title">
      <SectionHeader id="msg-title" title={t('family.sendNotice')} />
      {!fam.hasOthers ? (
        <p className="mt-2 text-base text-muted">{t('family.noticeEmpty')}</p>
      ) : (
        <form onSubmit={submit} className="mt-3 grid gap-3">
          <Textarea
            value={text}
            onChange={(e) => setText(e.target.value.slice(0, MESSAGE_MAX))}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault()
                submit(e)
              }
            }}
            rows={3}
            placeholder={t('family.noticePlaceholder')}
            aria-label={t('family.noticeLabel')}
            aria-invalid={!!error && !text.trim()}
            className="min-h-20"
          />
          <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label={t('family.to')}>
            <span className="mr-1 text-sm text-muted">{t('family.to')}</span>
            <Chip selected={to.length === 0} onClick={() => setTo([])}>
              {t('family.everyone')}
            </Chip>
            {others.map((m) => (
              <Chip key={m.id} selected={to.includes(m.id)} onClick={() => toggle(m.id)}>
                {firstName(m.displayName)}
              </Chip>
            ))}
          </div>
          {error && (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          )}
          <div className="flex items-center justify-between gap-3">
            <span className={cn('font-mono text-xs tnum', text.length > MESSAGE_MAX - 40 ? 'text-muted' : 'text-faint')}>
              {text.length}/{MESSAGE_MAX}
            </span>
            <Button type="submit" variant={text.trim() ? 'primary' : 'secondary'} disabled={!text.trim()} loading={send.isPending} icon={<PaperPlaneTilt className="size-4" />}>
              {t('common.send')}
            </Button>
          </div>
        </form>
      )}
    </Panel>
  )
}

/* ---------------------------------------------------------------- Updates */

const FEED_PAGE = 8

function NoticesPanel() {
  const notices = useNotices()
  const markAll = useMarkNoticesRead()
  const [expanded, setExpanded] = useState(false)
  const unread = notices.data?.unread ?? 0
  const total = notices.data?.notices.length ?? 0
  return (
    <Panel className="scroll-mt-6 p-5" id="updates" aria-labelledby="news-title">
      <SectionHeader
        id="news-title"
        title={
          <>
            {t('app.updates')}
            {unread > 0 && (
              <span className="font-mono text-sm font-normal text-faint tnum">
                {unread}
                <span className="max-sm:sr-only"> {t('family.unreadSuffix')}</span>
              </span>
            )}
          </>
        }
        action={
          unread > 0 ? (
            <Button size="sm" variant="ghost" icon={<Checks className="size-4" />} onClick={() => markAll.mutate(undefined)}>
              <span className="max-sm:hidden">{t('app.markAllRead')}</span>
              <span className="sm:hidden">{t('family.allRead')}</span>
            </Button>
          ) : undefined
        }
      />
      <div className="mt-2">
        <NoticesFeed
          notices={notices.data?.notices}
          loading={notices.isLoading}
          error={notices.error as Error | null}
          onRetry={() => void notices.refetch()}
          limit={expanded ? undefined : FEED_PAGE}
        />
        {total > FEED_PAGE && (
          <button type="button" onClick={() => setExpanded((e) => !e)} className="mt-1 rounded-[6px] py-1.5 text-sm text-muted hover:text-fg">
            {expanded ? t('family.showLess') : t('common.seeMore', { count: total - FEED_PAGE })}
          </button>
        )}
      </div>
    </Panel>
  )
}
