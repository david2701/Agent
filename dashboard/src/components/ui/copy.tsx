import { Check, Copy } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import { cn } from '@/lib/cn'
import { Button } from './button'
import { toast } from './toast'

/** Copia al portapapeles y recuerda unos segundos que se copió (para cambiar la etiqueta del botón). */
export function useCopy(ms = 1800) {
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )
  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      if (timer.current) clearTimeout(timer.current)
      timer.current = setTimeout(() => setCopied(false), ms)
      return true
    } catch {
      toast({ title: 'No se pudo copiar', description: 'Selecciona el texto y cópialo a mano.', tone: 'danger' })
      return false
    }
  }
  return { copied, copy }
}

export function CopyButton({
  value,
  label = 'Copiar',
  copiedLabel = 'Copiado',
  variant = 'quiet',
  size = 'sm',
  className,
}: {
  value: string
  label?: string
  copiedLabel?: string
  variant?: 'quiet' | 'secondary' | 'primary'
  size?: 'sm' | 'md'
  className?: string
}) {
  const { copied, copy } = useCopy()
  return (
    <Button
      size={size}
      variant={variant}
      className={className}
      icon={copied ? <Check className="size-4" /> : <Copy className="size-4" />}
      onClick={() => void copy(value)}
      aria-live="polite"
    >
      {copied ? copiedLabel : label}
    </Button>
  )
}

/** Valor de una línea (URL, enlace) con botón de copiar. */
export function CopyField({
  value,
  label = 'Copiar',
  copiedLabel = 'Copiada',
  className,
}: {
  value: string
  label?: string
  copiedLabel?: string
  className?: string
}) {
  return (
    <div className={cn('flex items-center gap-2 rounded-control border border-line bg-bg py-1 pr-1 pl-3', className)}>
      <code className="min-w-0 flex-1 truncate font-mono text-sm text-fg" title={value}>
        {value}
      </code>
      <CopyButton value={value} label={label} copiedLabel={copiedLabel} />
    </div>
  )
}
