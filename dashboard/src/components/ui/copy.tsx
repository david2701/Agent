import { Check, Copy } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import { cn } from '@/lib/cn'
import { t } from '@/lib/i18n'
import { Button } from './button'
import { toast } from './toast'

/** Copies to the clipboard and remembers for a few seconds that it did (to swap the button label). */
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
      toast({ title: t('common.couldNotCopy'), description: t('common.couldNotCopyHint'), tone: 'danger' })
      return false
    }
  }
  return { copied, copy }
}

export function CopyButton({
  value,
  label = t('common.copy'),
  copiedLabel = t('common.copied'),
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

/** One-line value (URL, link) with a copy button. */
export function CopyField({
  value,
  label = t('common.copy'),
  copiedLabel = t('common.copiedFem'),
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
