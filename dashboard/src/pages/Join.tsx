import { Eye, EyeSlash, WarningCircle } from '@phosphor-icons/react'
import { useQuery } from '@tanstack/react-query'
import { motion, useReducedMotion } from 'motion/react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { KairoMark } from '@/components/KairoMark'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/feedback'
import { Field, FormError, Input } from '@/components/ui/form'
import { ApiError, join, previewInvite } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { firstName, isCompleteCode, looksLikeInviteLink, normalizeCode } from '@/lib/family'
import { t } from '@/lib/i18n'
import type { InvitePreview } from '@/lib/types'

const MIN_PASSWORD = 10

/**
 * /join?code=XXXX-XXXX (public). With an invitation it creates the new member's account;
 * with an access code it sets a new password for an existing member.
 * The legacy `?codigo=` parameter is still read (old links redirect here from /unirse).
 */
export default function Join() {
  const [params, setParams] = useSearchParams()
  const rawCode = params.get('code') ?? params.get('codigo') ?? ''
  const code = normalizeCode(rawCode)
  const valid = isCompleteCode(code)

  const preview = useQuery<InvitePreview | null>({
    queryKey: ['invite-preview', code],
    queryFn: ({ signal }) => previewInvite(code, signal),
    enabled: valid,
    staleTime: Infinity,
    retry: false,
  })

  const setCode = (c: string) => setParams(c ? { code: c } : {}, { replace: false })

  let body: ReactNode
  if (!valid) body = <CodeEntry onSubmit={setCode} initial={rawCode ? code : ''} />
  else if (preview.isLoading) body = <PreviewSkeleton />
  else if (preview.isError)
    body = (
      <Card>
        <Heading
          title={t('join.couldNotCheck')}
          subtitle={
            preview.error instanceof ApiError && preview.error.status === 429
              ? t('auth.tooManyTries')
              : preview.error instanceof ApiError
                ? preview.error.message
                : t('auth.cantReachServer')
          }
        />
        <Button className="mt-5 w-full" size="lg" onClick={() => void preview.refetch()}>
          {t('common.retry')}
        </Button>
      </Card>
    )
  else if (!preview.data)
    body = (
      <Card>
          <div className="mb-5 flex items-start gap-3 rounded-control border border-[color-mix(in_srgb,var(--danger)_35%,var(--border))] bg-[color-mix(in_srgb,var(--danger)_8%,transparent)] px-3 py-2.5 text-sm text-danger" role="alert">
            <WarningCircle className="mt-px size-4 shrink-0" />
            <span>
              {t('join.invalidPre')} <span className="font-mono">{code}</span> {t('join.invalidPost')}
            </span>
          </div>
          <CodeEntry bare onSubmit={setCode} initial="" />
      </Card>
    )
  else body = <JoinForm code={code} preview={preview.data} />

  return (
    <div className="flex min-h-[100dvh] flex-col items-center justify-center px-4 py-10">
      <Shell>{body}</Shell>
      <p className="mt-6 text-center text-sm text-faint">
        {t('join.haveAccount')}{' '}
        <Link to="/login" className="rounded-[6px] font-medium text-muted underline-offset-4 hover:text-fg hover:underline">
          {t('auth.signIn')}
        </Link>
      </p>
    </div>
  )
}

function Shell({ children }: { children: ReactNode }) {
  const reduce = useReducedMotion()
  return (
    <motion.div
      initial={reduce ? false : { opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
      className="w-full max-w-[400px]"
    >
      <div className="mb-7 flex justify-center">
        <KairoMark className="size-11" />
      </div>
      {children}
    </motion.div>
  )
}

function Card({ children }: { children: ReactNode }) {
  return <div className="rounded-card border border-line bg-surface p-5 sm:p-6">{children}</div>
}

function Heading({ title, subtitle }: { title: ReactNode; subtitle?: ReactNode }) {
  return (
    <div className="mb-5">
      <h1 className="text-lg font-semibold tracking-tight text-balance">{title}</h1>
      {subtitle && <p className="mt-1.5 text-base text-muted">{subtitle}</p>}
    </div>
  )
}

function PreviewSkeleton() {
  return (
    <Card>
      <div className="grid gap-2" aria-busy="true" aria-label={t('join.checking')}>
        <Skeleton className="h-6 w-3/4" />
        <Skeleton className="h-4 w-full" />
        <div className="mt-4 grid gap-4">
          {Array.from({ length: 3 }, (_, i) => (
            <div key={i} className="grid gap-1.5">
              <Skeleton className="h-3.5 w-20" />
              <Skeleton className="h-10 w-full rounded-control" />
            </div>
          ))}
        </div>
      </div>
    </Card>
  )
}

/** Field to type or paste a code (or the full link). */
function CodeEntry({ onSubmit, initial, bare }: { onSubmit: (code: string) => void; initial: string; bare?: boolean }) {
  const [value, setValue] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const c = normalizeCode(value)
    if (!isCompleteCode(c)) return setError(t('auth.badCode'))
    setError(null)
    onSubmit(c)
  }
  const form = (
    <form onSubmit={submit} noValidate className="grid gap-4">
      <Field label={t('join.code')} error={error} hint={error ? undefined : t('join.codeHint')}>
        {(id, d) => (
          <Input
            id={id}
            value={value}
            onChange={(e) => setValue(looksLikeInviteLink(e.target.value) ? normalizeCode(e.target.value) : e.target.value.toUpperCase())}
            onBlur={() => value && setValue(normalizeCode(value))}
            placeholder="XXXX-XXXX"
            autoComplete="one-time-code"
            autoCapitalize="characters"
            spellCheck={false}
            autoFocus={!bare}
            aria-invalid={!!error}
            aria-describedby={d}
            className="h-11 text-center font-mono text-md tracking-[0.12em]"
          />
        )}
      </Field>
      <Button type="submit" variant="primary" size="lg" className="w-full">
        {t('auth.continue')}
      </Button>
    </form>
  )
  if (bare) return form
  return (
    <Card>
      <Heading title={t('join.title')} subtitle={t('join.subtitle')} />
      {form}
    </Card>
  )
}

function PasswordInput({
  id,
  value,
  onChange,
  invalid,
  describedBy,
  autoComplete,
  autoFocus,
}: {
  id: string
  value: string
  onChange: (v: string) => void
  invalid: boolean
  describedBy?: string
  autoComplete: string
  autoFocus?: boolean
}) {
  const [show, setShow] = useState(false)
  return (
    <div className="relative">
      <Input
        id={id}
        type={show ? 'text' : 'password'}
        autoComplete={autoComplete}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        autoFocus={autoFocus}
        className="pr-11"
      />
      <button
        type="button"
        onClick={() => setShow((s) => !s)}
        aria-label={show ? t('auth.hidePassword') : t('auth.showPassword')}
        className="absolute top-1/2 right-1.5 flex size-8 -translate-y-1/2 items-center justify-center rounded-[8px] text-faint transition-colors hover:bg-surface-2 hover:text-fg"
      >
        {show ? <EyeSlash className="size-4" /> : <Eye className="size-4" />}
      </button>
    </div>
  )
}

type Errors = { name?: string; email?: string; password?: string; confirm?: string; form?: string }

function JoinForm({ code, preview }: { code: string; preview: InvitePreview }) {
  const navigate = useNavigate()
  const { user, status } = useAuth()
  const isInvite = preview.kind === 'invite'
  const [name, setName] = useState(preview.name)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [loading, setLoading] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const next: Errors = {}
    if (isInvite) {
      if (!name.trim()) next.name = t('join.enterName')
      if (!email.trim()) next.email = t('auth.enterEmail')
      else if (!/^\S+@\S+\.\S+$/.test(email.trim())) next.email = t('auth.invalidEmail')
    }
    if (password.length < MIN_PASSWORD) next.password = t('join.minChars', { min: MIN_PASSWORD })
    if (!next.password && confirm !== password) next.confirm = t('join.mismatch')
    setErrors(next)
    if (Object.keys(next).length) return
    setLoading(true)
    try {
      await join(isInvite ? { code, email: email.trim(), password, displayName: name.trim() } : { code, password })
      navigate('/today', { replace: true })
    } catch (err) {
      setErrors({
        form:
          err instanceof ApiError
            ? err.status === 429
              ? t('auth.tooManyTries')
              : err.message
            : t('auth.cantReachServer'),
      })
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card>
      {isInvite ? (
        <Heading
          title={t('join.invitedTo', { household: preview.householdName })}
          subtitle={t('join.inviteSubtitle')}
        />
      ) : (
        <Heading
          title={t('join.newPassword')}
          subtitle={`${preview.name ? `${t('join.hello', { name: firstName(preview.name) })} ` : ''}${t('join.setPassword', { household: preview.householdName })}`}
        />
      )}

      {status === 'authenticated' && user && (
        <p className="mb-4 rounded-control border border-line bg-surface-2 px-3 py-2 text-sm text-muted">
          {t(isInvite ? 'join.sessionOpenNew' : 'join.sessionOpenExisting', { name: firstName(user.displayName) || user.email })}
        </p>
      )}

      <form onSubmit={submit} noValidate className="grid gap-4">
        {isInvite ? (
          <>
            <Field label={t('join.name')} error={errors.name}>
              {(id, d) => (
                <Input id={id} value={name} onChange={(e) => setName(e.target.value)} autoComplete="given-name" maxLength={60} aria-invalid={!!errors.name} aria-describedby={d} autoFocus={!preview.name} />
              )}
            </Field>
            <Field label={t('auth.email')} error={errors.email}>
              {(id, d) => (
                <Input
                  id={id}
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  aria-invalid={!!errors.email}
                  aria-describedby={d}
                  autoFocus={!!preview.name}
                />
              )}
            </Field>
          </>
        ) : (
          preview.email && (
            <div className="grid gap-1.5">
              <span className="text-sm font-medium text-fg">{t('auth.email')}</span>
              <p className="flex h-10 items-center truncate rounded-control border border-line bg-surface-2 px-3 text-base text-muted">{preview.email}</p>
            </div>
          )
        )}
        <Field label={isInvite ? t('join.password') : t('join.passwordNew')} error={errors.password} hint={t('join.passwordHint', { min: MIN_PASSWORD })}>
          {(id, d) => (
            <PasswordInput id={id} value={password} onChange={setPassword} invalid={!!errors.password} describedBy={d} autoComplete="new-password" autoFocus={!isInvite} />
          )}
        </Field>
        <Field label={t('join.confirm')} error={errors.confirm}>
          {(id, d) => <PasswordInput id={id} value={confirm} onChange={setConfirm} invalid={!!errors.confirm} describedBy={d} autoComplete="new-password" />}
        </Field>
        {errors.form && <FormError>{errors.form}</FormError>}
        <Button type="submit" variant="primary" size="lg" loading={loading} className="mt-1 w-full">
          {isInvite ? t('join.joinFamily') : t('join.saveAndSignIn')}
        </Button>
        {!isInvite && <p className="text-center text-sm text-faint">{t('join.otherSessions')}</p>}
      </form>
    </Card>
  )
}
