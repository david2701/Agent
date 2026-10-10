import { Eye, EyeSlash, Ticket } from '@phosphor-icons/react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { KairoMark } from '@/components/KairoMark'
import { Button } from '@/components/ui/button'
import { Field, FormError, Input } from '@/components/ui/form'
import { ApiError } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { isCompleteCode, normalizeCode } from '@/lib/family'

export default function Login() {
  const { login } = useAuth()
  const reduce = useReducedMotion()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [errors, setErrors] = useState<{ email?: string; password?: string; form?: string }>({})
  const [loading, setLoading] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const next: typeof errors = {}
    if (!email.trim()) next.email = 'Escribe tu correo.'
    else if (!/^\S+@\S+\.\S+$/.test(email.trim())) next.email = 'Ese correo no parece válido.'
    if (!password) next.password = 'Escribe tu contraseña.'
    setErrors(next)
    if (Object.keys(next).length) return
    setLoading(true)
    try {
      await login(email, password)
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? err.message
          : 'No pude conectar con el servidor. Revisa tu conexión e intenta de nuevo.'
      setErrors({ form: msg })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-[100dvh] flex-col items-center justify-center px-4 py-10">
      <motion.div
        initial={reduce ? false : { opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
        className="w-full max-w-[380px]"
      >
        <div className="mb-8 flex flex-col items-center text-center">
          <KairoMark className="size-12" />
          <h1 className="mt-5 text-xl font-semibold tracking-tight">Kairo</h1>
          <p className="mt-1.5 text-base text-muted">Entra para continuar con tu asistente.</p>
        </div>

        <form onSubmit={submit} noValidate className="grid gap-4 rounded-card border border-line bg-surface p-5 sm:p-6">
          <Field label="Correo" error={errors.email}>
            {(id, d) => (
              <Input
                id={id}
                type="email"
                autoComplete="username"
                inputMode="email"
                autoFocus
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                aria-invalid={!!errors.email}
                aria-describedby={d}
              />
            )}
          </Field>
          <Field label="Contraseña" error={errors.password}>
            {(id, d) => (
              <div className="relative">
                <Input
                  id={id}
                  type={show ? 'text' : 'password'}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  aria-invalid={!!errors.password}
                  aria-describedby={d}
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
            )}
          </Field>
          {errors.form && <FormError>{errors.form}</FormError>}
          <Button type="submit" variant="primary" size="lg" loading={loading} className="mt-1 w-full">
            Entrar
          </Button>
        </form>

        <InviteCode />

        <p className="mt-6 text-center text-sm text-faint">La sesión queda guardada en este navegador hasta que la cierres.</p>
      </motion.div>
    </div>
  )
}

/** "¿Tienes un código de invitación?": lleva a /unirse con el código escrito o pegado (también acepta el enlace). */
function InviteCode() {
  const navigate = useNavigate()
  const reduce = useReducedMotion()
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const c = normalizeCode(code)
    if (!isCompleteCode(c)) return setError('El código tiene 8 letras o números, como K7QM-4XPA.')
    navigate(`/unirse?codigo=${encodeURIComponent(c)}`)
  }

  return (
    <div className="mt-4">
      {!open ? (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="mx-auto flex items-center gap-2 rounded-control px-2 py-1.5 text-sm text-muted transition-colors hover:text-fg"
        >
          <Ticket className="size-4 text-faint" />
          ¿Tienes un código de invitación?
        </button>
      ) : null}
      <AnimatePresence initial={false}>
        {open && (
          <motion.form
            key="code"
            onSubmit={submit}
            noValidate
            initial={reduce ? { opacity: 0 } : { opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
            className="grid gap-3 rounded-card border border-line bg-surface p-5 sm:p-6"
            aria-label="Unirse con un código"
          >
            <Field label="Código de invitación o de acceso" error={error} hint={error ? undefined : 'Te lo da quien administra tu familia en Kairo.'}>
              {(id, d) => (
                <Input
                  id={id}
                  value={code}
                  onChange={(e) => setCode(e.target.value.includes('codigo=') ? normalizeCode(e.target.value) : e.target.value.toUpperCase())}
                  onBlur={() => code && setCode(normalizeCode(code))}
                  placeholder="XXXX-XXXX"
                  autoFocus
                  autoCapitalize="characters"
                  autoComplete="one-time-code"
                  spellCheck={false}
                  aria-invalid={!!error}
                  aria-describedby={d}
                  className="text-center font-mono tracking-[0.12em]"
                />
              )}
            </Field>
            <div className="flex gap-2">
              <Button variant="ghost" className="flex-1" onClick={() => setOpen(false)}>
                Cancelar
              </Button>
              <Button type="submit" className="flex-1">
                Continuar
              </Button>
            </div>
          </motion.form>
        )}
      </AnimatePresence>
    </div>
  )
}
