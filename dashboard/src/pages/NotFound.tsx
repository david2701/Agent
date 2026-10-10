import { ArrowLeft } from '@phosphor-icons/react'
import { KairoMark } from '@/components/KairoMark'
import { ButtonLink } from '@/components/ui/button'
import { t } from '@/lib/i18n'

export default function NotFound() {
  return (
    <div className="flex min-h-[70dvh] flex-col items-center justify-center px-6 text-center">
      <KairoMark className="size-12 opacity-90" />
      <p className="mt-6 font-mono text-sm text-faint">404</p>
      <h1 className="mt-1 text-xl font-semibold tracking-tight">{t('notFound.title')}</h1>
      <p className="mt-2 max-w-[42ch] text-base text-muted">{t('notFound.body')}</p>
      <ButtonLink to="/today" variant="secondary" className="mt-6" icon={<ArrowLeft className="size-4" />}>
        {t('notFound.back')}
      </ButtonLink>
    </div>
  )
}
