import { CaretDown, Check, Minus } from '@phosphor-icons/react'
import { motion, useReducedMotion } from 'motion/react'
import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'
import { cn } from '@/lib/cn'
import { t } from '@/lib/i18n'

export const controlClass =
  'w-full rounded-control border border-line bg-surface px-3 text-base text-fg ' +
  'transition-[border-color,background-color,box-shadow] duration-200 ease-kairo ' +
  'hover:border-[color-mix(in_srgb,var(--text-faint)_55%,var(--border))] ' +
  'focus:border-accent focus:outline-none focus:ring-3 focus:ring-[color-mix(in_srgb,var(--accent)_22%,transparent)] ' +
  'disabled:opacity-60 aria-[invalid=true]:border-danger'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className, ...rest },
  ref,
) {
  return <input ref={ref} className={cn(controlClass, 'h-10', className)} {...rest} />
})

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea(
  { className, ...rest },
  ref,
) {
  return <textarea ref={ref} className={cn(controlClass, 'min-h-24 py-2.5 leading-relaxed', className)} {...rest} />
})

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select(
  { className, children, ...rest },
  ref,
) {
  return (
    <div className="relative">
      <select ref={ref} className={cn(controlClass, 'h-10 appearance-none pr-9', className)} {...rest}>
        {children}
      </select>
      <CaretDown aria-hidden className="pointer-events-none absolute top-1/2 right-3 size-3.5 -translate-y-1/2 text-faint" />
    </div>
  )
})

interface FieldProps {
  label: string
  children: (id: string, describedBy: string | undefined) => ReactNode
  hint?: ReactNode
  error?: string | null
  className?: string
  optional?: boolean
}

/** Label above, hint and error below (Taste §4.6). */
export function Field({ label, children, hint, error, className, optional }: FieldProps) {
  const id = useId()
  const hintId = hint ? `${id}-hint` : undefined
  const errId = error ? `${id}-err` : undefined
  const describedBy = [hintId, errId].filter(Boolean).join(' ') || undefined
  return (
    <div className={cn('grid content-start gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-medium text-fg">
        {label}
        {optional && <span className="ml-1 font-normal text-faint">{t('common.optional')}</span>}
      </label>
      {children(id, describedBy)}
      {hint && !error && (
        <p id={hintId} className="text-sm text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={errId} role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  )
}

export function Switch({
  checked,
  onChange,
  label,
  disabled,
  id,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
  disabled?: boolean
  id?: string
}) {
  const reduce = useReducedMotion()
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        'relative inline-flex h-6 w-10 shrink-0 items-center rounded-full border transition-colors duration-200 ease-kairo disabled:opacity-50',
        checked ? 'border-accent bg-accent' : 'border-line bg-surface-2',
      )}
    >
      <motion.span
        className={cn('block size-4.5 rounded-full shadow-sm', checked ? 'bg-on-accent' : 'bg-faint')}
        animate={{ x: checked ? 19 : 3 }}
        transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 500, damping: 34 }}
      />
    </button>
  )
}

export function Checkbox({
  checked,
  onChange,
  label,
  indeterminate,
  disabled,
  size = 'md',
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
  indeterminate?: boolean
  disabled?: boolean
  size?: 'sm' | 'md'
}) {
  const reduce = useReducedMotion()
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={indeterminate ? 'mixed' : checked}
      aria-label={label}
      disabled={disabled}
      onClick={(e) => {
        e.stopPropagation()
        onChange(!checked)
      }}
      className={cn(
        'group relative inline-flex shrink-0 items-center justify-center rounded-[6px] border transition-[background-color,border-color,transform] duration-200 ease-kairo active:scale-90 disabled:opacity-50',
        size === 'sm' ? 'size-4' : 'size-[18px]',
        checked ? 'border-success bg-success text-bg' : 'border-[color-mix(in_srgb,var(--text-faint)_70%,var(--border))] bg-surface hover:border-muted',
      )}
    >
      {/* Enlarged touch target */}
      <span className="absolute -inset-2" aria-hidden />
      {checked && (
        <motion.span
          initial={reduce ? false : { scale: 0.4, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={{ type: 'spring', stiffness: 600, damping: 28 }}
          className="flex"
        >
          {indeterminate ? <Minus weight="bold" className="size-3" /> : <Check weight="bold" className="size-3" />}
        </motion.span>
      )}
    </button>
  )
}

export function FormError({ children }: { children: ReactNode }) {
  if (!children) return null
  return (
    <p role="alert" className="rounded-control border border-[color-mix(in_srgb,var(--danger)_35%,var(--border))] bg-[color-mix(in_srgb,var(--danger)_8%,transparent)] px-3 py-2 text-sm text-danger">
      {children}
    </p>
  )
}
