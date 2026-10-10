import { ArrowLeft } from '@phosphor-icons/react'
import { KairoMark } from '@/components/KairoMark'
import { ButtonLink } from '@/components/ui/button'

export default function NotFound() {
  return (
    <div className="flex min-h-[70dvh] flex-col items-center justify-center px-6 text-center">
      <KairoMark className="size-12 opacity-90" />
      <p className="mt-6 font-mono text-sm text-faint">404</p>
      <h1 className="mt-1 text-xl font-semibold tracking-tight">Esta página no existe</h1>
      <p className="mt-2 max-w-[42ch] text-base text-muted">Puede que el enlace esté mal escrito o que lo hayas borrado.</p>
      <ButtonLink to="/" variant="secondary" className="mt-6" icon={<ArrowLeft className="size-4" />}>
        Volver a Hoy
      </ButtonLink>
    </div>
  )
}
