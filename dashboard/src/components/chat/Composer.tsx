import { ArrowUp, Microphone, Stop } from '@phosphor-icons/react'
import { forwardRef, useImperativeHandle, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { cn } from '@/lib/cn'

export interface ComposerHandle {
  focus: () => void
  setValue: (v: string) => void
}

interface Props {
  onSend: (text: string) => void
  onMic?: () => void
  micActive?: boolean
  busy?: boolean
  onStop?: () => void
  placeholder?: string
  autoFocus?: boolean
  size?: 'md' | 'lg'
  className?: string
  label?: string
}

/** Caja de mensaje: Enter envía, Shift+Enter hace salto de línea. */
export const Composer = forwardRef<ComposerHandle, Props>(function Composer(
  { onSend, onMic, micActive, busy, onStop, placeholder = 'Escribe a Kairo', autoFocus, size = 'md', className, label = 'Mensaje para Kairo' },
  ref,
) {
  const [value, setValue] = useState('')
  const ta = useRef<HTMLTextAreaElement>(null)

  useImperativeHandle(ref, () => ({ focus: () => ta.current?.focus(), setValue }), [])

  useLayoutEffect(() => {
    const el = ta.current
    if (!el) return
    el.style.height = '0px'
    el.style.height = `${Math.min(el.scrollHeight, 220)}px`
  }, [value])

  const submit = () => {
    const text = value.trim()
    if (!text || busy) return
    onSend(text)
    setValue('')
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  const lg = size === 'lg'
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
      className={cn(
        'flex items-end gap-2 rounded-card border border-line bg-surface p-2 transition-[border-color,box-shadow] duration-200',
        'focus-within:border-[color-mix(in_srgb,var(--accent)_60%,var(--border))] focus-within:ring-3 focus-within:ring-[color-mix(in_srgb,var(--accent)_14%,transparent)]',
        className,
      )}
    >
      <textarea
        ref={ta}
        rows={1}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        aria-label={label}
        autoFocus={autoFocus}
        className={cn(
          'max-h-[220px] min-w-0 flex-1 resize-none bg-transparent px-2 text-fg outline-none placeholder:text-faint',
          lg ? 'py-2.5 text-md' : 'py-2 text-base',
        )}
      />
      {onMic && (
        <button
          type="button"
          onClick={onMic}
          aria-label={micActive ? 'Terminar de hablar' : 'Hablar con Kairo'}
          aria-pressed={micActive}
          title={micActive ? 'Terminar de hablar' : 'Hablar'}
          className={cn(
            'flex shrink-0 items-center justify-center rounded-full transition-[background-color,color,transform] duration-200 active:scale-95',
            lg ? 'size-11' : 'size-9',
            micActive ? 'bg-accent text-on-accent' : 'bg-surface-2 text-fg hover:bg-line',
          )}
        >
          <Microphone className={lg ? 'size-5' : 'size-[18px]'} weight={micActive ? 'fill' : 'regular'} />
        </button>
      )}
      {busy && onStop ? (
        <button
          type="button"
          onClick={onStop}
          aria-label="Detener respuesta"
          title="Detener"
          className={cn(
            'flex shrink-0 items-center justify-center rounded-full bg-fg text-bg transition-transform active:scale-95',
            lg ? 'size-11' : 'size-9',
          )}
        >
          <Stop className="size-4" weight="fill" />
        </button>
      ) : (
        <button
          type="submit"
          disabled={!value.trim() || busy}
          aria-label="Enviar"
          title="Enviar (Enter)"
          className={cn(
            'flex shrink-0 items-center justify-center rounded-full transition-[background-color,opacity,transform] duration-200 active:scale-95 disabled:opacity-35',
            lg ? 'size-11' : 'size-9',
            value.trim() ? 'bg-accent text-on-accent' : 'bg-surface-2 text-muted',
          )}
        >
          <ArrowUp className={lg ? 'size-5' : 'size-[18px]'} />
        </button>
      )}
    </form>
  )
})
