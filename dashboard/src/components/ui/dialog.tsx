import { X } from '@phosphor-icons/react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { cn } from '@/lib/cn'
import { IconButton } from './button'

interface DialogProps {
  open: boolean
  onClose: () => void
  title: string
  description?: ReactNode
  children: ReactNode
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg'
  /** Oculta el encabezado visible (el título sigue para lectores de pantalla). */
  bare?: boolean
  className?: string
}

const widths = { sm: 'sm:w-[min(100%-2rem,26rem)]', md: 'sm:w-[min(100%-2rem,34rem)]', lg: 'sm:w-[min(100%-2rem,46rem)]' }

/**
 * Diálogo modal sobre <dialog> nativo: foco atrapado, Esc y fondo inerte del navegador.
 * En pantallas pequeñas se presenta como hoja inferior.
 */
export function Dialog({ open, onClose, title, description, children, footer, size = 'md', bare, className }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const [mounted, setMounted] = useState(open)
  const downOnBackdrop = useRef(false)

  useEffect(() => {
    if (open) setMounted(true)
  }, [open])

  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (open && !d.open) {
      d.showModal()
    } else if (!open && d.open) {
      d.close()
      const t = setTimeout(() => setMounted(false), 300)
      return () => clearTimeout(t)
    }
  }, [open, mounted])

  if (!mounted) return null

  return createPortal(
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onMouseDown={(e) => {
        downOnBackdrop.current = e.target === ref.current
      }}
      onClick={(e) => {
        if (downOnBackdrop.current && e.target === ref.current) onClose()
      }}
      className={cn(
        'k-dialog fixed m-0 mt-auto max-h-[92dvh] w-full max-w-none overflow-hidden rounded-t-card border border-line bg-surface p-0 text-fg shadow-pop',
        'sm:m-auto sm:max-h-[calc(100dvh-3rem)] sm:rounded-card',
        widths[size],
        className,
      )}
    >
      <div className="flex max-h-[inherit] flex-col">
        <div className={cn('flex items-start justify-between gap-4 px-5 pt-4', bare && 'sr-only')}>
          <div className="min-w-0 pt-1">
            <h2 id={titleId} className="text-md font-semibold tracking-tight">
              {title}
            </h2>
            {description && <div className="mt-1 text-sm text-muted">{description}</div>}
          </div>
          <IconButton label="Cerrar" size="sm" onClick={onClose} className="-mr-1.5">
            <X className="size-4" />
          </IconButton>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 pt-4 pb-5">{children}</div>
        {footer && (
          <div className="flex flex-wrap items-center justify-end gap-2 border-t border-line bg-surface px-5 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
            {footer}
          </div>
        )}
      </div>
    </dialog>,
    document.body,
  )
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmLabel = 'Eliminar',
  loading,
}: {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: string
  description?: ReactNode
  confirmLabel?: string
  loading?: boolean
}) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      size="sm"
      footer={
        <>
          <button
            type="button"
            className="h-9 rounded-control px-3.5 text-base font-medium text-muted transition-colors hover:bg-surface-2 hover:text-fg active:scale-[0.98]"
            onClick={onClose}
          >
            Cancelar
          </button>
          <button
            type="button"
            disabled={loading}
            className="h-9 rounded-control bg-danger px-3.5 text-base font-medium text-bg transition-[filter,transform] hover:brightness-110 active:scale-[0.98] disabled:opacity-60"
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </>
      }
    >
      {description && <p className="text-base text-muted">{description}</p>}
    </Dialog>
  )
}
