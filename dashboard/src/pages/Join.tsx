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
import { firstName, isCompleteCode, normalizeCode } from '@/lib/family'
import type { InvitePreview } from '@/lib/types'

const MIN_PASSWORD = 10

/**
 * /unirse?codigo=XXXX-XXXX (pública). Con una invitación crea la cuenta del nuevo miembro;
 * con un código de acceso define una contraseña nueva para un miembro existente.
 */
export default function Unirse() {
  const [params, setParams] = useSearchParams()
  const code = normalizeCode(params.get('codigo') ?? '')
  const valid = isCompleteCode(code)

  const preview = useQuery<InvitePreview | null>({
    queryKey: ['invite-preview', code],
    queryFn: ({ signal }) => previewInvite(code, signal),
    enabled: valid,
    staleTime: Infinity,
    retry: false,
  })

  const setCode = (c: string) => setParams(c ? { codigo: c } : {}, { replace: false })

  let body: ReactNode
  if (!valid) body = <CodeEntry onSubmit={setCode} initial={params.get('codigo') ? code : ''} />
  else if (preview.isLoading) body = <PreviewSkeleton />
  else if (preview.isError)
    body = (
      <Card>
        <Heading
          title="No pude revisar el código"
          subtitle={
            preview.error instanceof ApiError && preview.error.status === 429
              ? 'Demasiados intentos seguidos. Espera un minuto e inténtalo de nuevo.'
              : preview.error instanceof ApiError
                ? preview.error.message
                : 'No pude conectar con el servidor. Revisa tu conexión.'
          }
        />
        <Button className="mt-5 w-full" size="lg" onClick={() => void preview.refetch()}>
          Reintentar
        </Button>
      </Card>
    )
  else if (!preview.data)
    body = (
      <Card>
          <div className="mb-5 flex items-start gap-3 rounded-control border border-[color-mix(in_srgb,var(--danger)_35%,var(--border))] bg-[color-mix(in_srgb,var(--danger)_8%,transparent)] px-3 py-2.5 text-sm text-danger" role="alert">
            <WarningCircle className="mt-px size-4 shrink-0" />
            <span>
              El código <span className="font-mono">{code}</span> no es válido o ya venció. Pide uno nuevo a quien administra la familia.
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
        ¿Ya tienes cuenta?{' '}
        <Link to="/login" className="rounded-[6px] font-medium text-muted underline-offset-4 hover:text-fg hover:underline">
          Entrar
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
      <div className="grid gap-2" aria-busy="true" aria-label="Revisando el código">
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

/** Campo para escribir o pegar un código (o el enlace completo). */
function CodeEntry({ onSubmit, initial, bare }: { onSubmit: (code: string) => void; initial: string; bare?: boolean }) {
  const [value, setValue] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const c = normalizeCode(value)
    if (!isCompleteCode(c)) return setError('El código tiene 8 letras o números, como K7QM-4XPA.')
    setError(null)
    onSubmit(c)
  }
  const form = (
    <form onSubmit={submit} noValidate className="grid gap-4">
      <Field label="Código" error={error} hint={error ? undefined : 'Te lo dio quien administra tu familia en Kairo.'}>
        {(id, d) => (
          <Input
            id={id}
            value={value}
            onChange={(e) => setValue(e.target.value.includes('codigo=') ? normalizeCode(e.target.value) : e.target.value.toUpperCase())}
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
        Continuar
      </Button>
    </form>
  )
  if (bare) return form
  return (
    <Card>
      <Heading title="Únete a tu familia" subtitle="Escribe el código de invitación o de acceso que te compartieron." />
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
        aria-label={show ? 'Ocultar contraseña' : 'Mostrar contraseña'}
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
      if (!name.trim()) next.name = 'Escribe tu nombre.'
      if (!email.trim()) next.email = 'Escribe tu correo.'
      else if (!/^\S+@\S+\.\S+$/.test(email.trim())) next.email = 'Ese correo no parece válido.'
    }
    if (password.length < MIN_PASSWORD) next.password = `Usa al menos ${MIN_PASSWORD} caracteres.`
    if (!next.password && confirm !== password) next.confirm = 'Las contraseñas no coinciden.'
    setErrors(next)
    if (Object.keys(next).length) return
    setLoading(true)
    try {
      await join(isInvite ? { code, email: email.trim(), password, displayName: name.trim() } : { code, password })
      navigate('/', { replace: true })
    } catch (err) {
      setErrors({
        form:
          err instanceof ApiError
            ? err.status === 429
              ? 'Demasiados intentos seguidos. Espera un minuto e inténtalo de nuevo.'
              : err.message
            : 'No pude conectar con el servidor. Revisa tu conexión e intenta de nuevo.',
      })
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card>
      {isInvite ? (
        <Heading
          title={`Te invitaron a ${preview.householdName}`}
          subtitle="Crea tu cuenta. Verás lo que la familia comparte y tendrás tu propio espacio personal."
        />
      ) : (
        <Heading
          title="Nueva contraseña"
          subtitle={`${preview.name ? `Hola, ${firstName(preview.name)}. ` : ''}Define una contraseña nueva para entrar a ${preview.householdName}.`}
        />
      )}

      {status === 'authenticated' && user && (
        <p className="mb-4 rounded-control border border-line bg-surface-2 px-3 py-2 text-sm text-muted">
          Tienes abierta la sesión de {firstName(user.displayName) || user.email}. Si continúas, este navegador entrará con {isInvite ? 'la cuenta nueva' : 'esa cuenta'}.
        </p>
      )}

      <form onSubmit={submit} noValidate className="grid gap-4">
        {isInvite ? (
          <>
            <Field label="Nombre" error={errors.name}>
              {(id, d) => (
                <Input id={id} value={name} onChange={(e) => setName(e.target.value)} autoComplete="given-name" maxLength={60} aria-invalid={!!errors.name} aria-describedby={d} autoFocus={!preview.name} />
              )}
            </Field>
            <Field label="Correo" error={errors.email}>
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
              <span className="text-sm font-medium text-fg">Correo</span>
              <p className="flex h-10 items-center truncate rounded-control border border-line bg-surface-2 px-3 text-base text-muted">{preview.email}</p>
            </div>
          )
        )}
        <Field label={isInvite ? 'Contraseña' : 'Contraseña nueva'} error={errors.password} hint={`Mínimo ${MIN_PASSWORD} caracteres.`}>
          {(id, d) => (
            <PasswordInput id={id} value={password} onChange={setPassword} invalid={!!errors.password} describedBy={d} autoComplete="new-password" autoFocus={!isInvite} />
          )}
        </Field>
        <Field label="Confirmar contraseña" error={errors.confirm}>
          {(id, d) => <PasswordInput id={id} value={confirm} onChange={setConfirm} invalid={!!errors.confirm} describedBy={d} autoComplete="new-password" />}
        </Field>
        {errors.form && <FormError>{errors.form}</FormError>}
        <Button type="submit" variant="primary" size="lg" loading={loading} className="mt-1 w-full">
          {isInvite ? 'Unirme a la familia' : 'Guardar y entrar'}
        </Button>
        {!isInvite && <p className="text-center text-sm text-faint">Se cerrarán tus otras sesiones abiertas.</p>}
      </form>
    </Card>
  )
}
