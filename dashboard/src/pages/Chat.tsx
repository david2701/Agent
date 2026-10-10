import {
  ChatCircleDots,
  ClockCounterClockwise,
  DotsThree,
  MagnifyingGlass,
  NotePencil,
  SpeakerHigh,
  SpeakerSlash,
  Trash,
} from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate, useParams } from 'react-router'
import { Composer, type ComposerHandle } from '@/components/chat/Composer'
import { storedToItems, ThreadItems, turnToItems } from '@/components/chat/Thread'
import { VoiceOrb, type OrbState } from '@/components/VoiceOrb'
import { Button, IconButton } from '@/components/ui/button'
import { ConfirmDialog, Dialog } from '@/components/ui/dialog'
import { ErrorState, Skeleton } from '@/components/ui/feedback'
import { Switch } from '@/components/ui/form'
import { Menu } from '@/components/ui/menu'
import { toast, toastError } from '@/components/ui/toast'
import { newTurn, useAgentRunner, type Turn } from '@/lib/agent'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { cn } from '@/lib/cn'
import { compactWhen } from '@/lib/format'
import { t } from '@/lib/i18n'
import { keys, useConversations, useMessages, useSettings } from '@/lib/queries'
import type { Conversation } from '@/lib/types'
import { useStored } from '@/lib/useStored'
import { stripWakePhrase, useVoice } from '@/lib/voice'

const suggestions = () => [t('chat.suggestion1'), t('chat.suggestion2'), t('chat.suggestion3'), t('chat.suggestion4')]

export default function Chat() {
  const { id } = useParams<{ id?: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const qc = useQueryClient()
  const { user } = useAuth()
  const reduce = useReducedMotion()
  const settings = useSettings()
  const conversations = useConversations()
  const messages = useMessages(id)
  const runner = useAgentRunner()
  const [pending, setPending] = useState<{ convId?: string; base: number } | null>(null)
  const [voiceReply, setVoiceReply] = useStored('kairo-voice-reply', false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState<Conversation | null>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const stick = useRef(true)
  const composer = useRef<ComposerHandle>(null)
  const voiceReplyRef = useRef(voiceReply)
  voiceReplyRef.current = voiceReply
  const idRef = useRef(id)
  idRef.current = id
  const sendVoiceRef = useRef<(blob: Blob, ext: string) => void>(() => undefined)

  const voice = useVoice((blob, ext) => sendVoiceRef.current(blob, ext))

  const current = conversations.data?.find((c) => c.id === id)
  const turnVisible = !!pending && pending.convId === id && !!runner.turn

  const finishTurn = useCallback(
    async (turn: Turn) => {
      if (turn.conversationId) await qc.invalidateQueries({ queryKey: keys.messages(turn.conversationId) })
      if (turn.status === 'done') {
        setPending(null)
        runner.reset()
        const s = settings.data?.settings
        if (voiceReplyRef.current && turn.spoken) void voice.say(turn.spoken, { voice: s?.voice, speed: s?.voiceSpeed })
        else voice.setPhase('off')
      } else {
        voice.setPhase('off')
      }
    },
    [qc, runner, settings.data, voice],
  )

  const begin = useCallback(
    (convId: string | undefined) => {
      const base = convId ? (qc.getQueryData<unknown[]>(keys.messages(convId))?.length ?? 0) : 0
      setPending({ convId, base })
      stick.current = true
      return {
        onStart: (cid: string) => {
          setPending((p) => (p ? { ...p, convId: cid } : p))
          if (!convId && !idRef.current) navigate(`/chat/${cid}`, { replace: true })
        },
        onDone: finishTurn,
      }
    },
    [finishTurn, navigate, qc],
  )

  const sendText = useCallback(
    async (text: string) => {
      if (runner.busy) return
      const convId = idRef.current
      // "Hey Kairo, ..." / "Oye Kairo, ..." typed by habit: the wake phrase is not part of the request.
      const message = stripWakePhrase(text) || text
      await runner.run('/chat', { message, conversationId: convId, channel: 'chat' }, newTurn('chat', message), begin(convId))
    },
    [begin, runner],
  )

  sendVoiceRef.current = (blob: Blob, ext: string) => {
    const convId = idRef.current
    const fd = new FormData()
    fd.append('file', blob, `voice.${ext}`)
    if (convId) fd.append('conversationId', convId)
    fd.append('channel', 'voice')
    void runner.run('/voice/turn', fd, newTurn('voice', null), begin(convId))
  }

  // A message or voice request coming from Today or the palette
  const consumed = useRef(false)
  useEffect(() => {
    const st = location.state as { ask?: string; voice?: boolean } | null
    if (!st || consumed.current) return
    consumed.current = true
    navigate(location.pathname, { replace: true, state: null })
    if (st.ask) void sendText(st.ask)
    else if (st.voice) void voice.start()
  }, [location.state, location.pathname, navigate, sendText, voice])

  useEffect(() => {
    if (voice.error) {
      toast({ title: voice.error, tone: 'danger' })
      voice.setError(null)
    }
  }, [voice.error, voice])

  useEffect(() => {
    stick.current = true
    setHistoryOpen(false)
  }, [id])

  const serverItems = useMemo(() => {
    const all = messages.data ?? []
    return storedToItems(turnVisible && pending ? all.slice(0, pending.base) : all)
  }, [messages.data, turnVisible, pending])
  const liveItems = useMemo(() => (turnVisible && runner.turn ? turnToItems(runner.turn) : []), [turnVisible, runner.turn])

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    const near = el.scrollHeight - el.scrollTop - el.clientHeight < 180
    if (stick.current || near) {
      el.scrollTop = el.scrollHeight
      if (messages.data) stick.current = false
    }
  }, [serverItems, liveItems, messages.data])

  const removeConv = useMutation({
    mutationFn: (c: Conversation) => api.del(`/conversations/${c.id}`).then(() => c),
    onSuccess: (c) => {
      void qc.invalidateQueries({ queryKey: keys.conversations })
      toast({ title: t('chat.deleted'), description: c.title })
      if (c.id === id) navigate('/chat')
      setConfirmDelete(null)
    },
    onError: (e) => toastError(e, t('common.couldNotDelete')),
  })

  const isNew = !id && !turnVisible
  const orbState: OrbState =
    voice.phase === 'listening' ? 'listening' : voice.phase === 'speaking' ? 'speaking' : voice.phase === 'thinking' || runner.busy ? 'thinking' : 'idle'

  const onMic = () => {
    if (voice.phase === 'listening') void voice.finish()
    else if (voice.phase === 'speaking') voice.stopSpeaking()
    else if (!runner.busy) void voice.start()
  }

  const showThinking = turnVisible && runner.turn && runner.busy && !runner.turn.text && runner.turn.tools.every((tool) => tool.status === 'done')

  return (
    <div className="flex h-[calc(100dvh-56px-68px-env(safe-area-inset-bottom))] md:h-[100dvh]">
      {/* Conversation list */}
      <aside className="hidden w-[300px] shrink-0 flex-col border-r border-line lg:flex" aria-label={t('chat.conversations')}>
        <ConversationList
          conversations={conversations.data}
          loading={conversations.isLoading}
          activeId={id}
          onDelete={setConfirmDelete}
        />
      </aside>

      {/* Thread */}
      <section className="flex min-w-0 flex-1 flex-col" aria-label={t('chat.conversation')}>
        <header className="flex h-14 shrink-0 items-center gap-2 border-b border-line px-3 md:h-16 md:px-5">
          <IconButton label={t('chat.history')} className="lg:hidden" onClick={() => setHistoryOpen(true)}>
            <ClockCounterClockwise className="size-5" />
          </IconButton>
          <h1 className="min-w-0 flex-1 truncate text-md font-semibold tracking-tight">
            {id ? current?.title ?? (conversations.isLoading ? '' : t('chat.conversation')) : t('chat.new')}
          </h1>
          <label className="hidden items-center gap-2.5 text-sm text-muted sm:flex">
            {t('chat.voiceReply')}
            <Switch checked={voiceReply} onChange={setVoiceReply} label={t('chat.voiceReply')} />
          </label>
          <IconButton
            label={voiceReply ? t('chat.voiceReplyOff') : t('chat.voiceReply')}
            className="sm:hidden"
            tone={voiceReply ? 'accent' : 'default'}
            onClick={() => setVoiceReply(!voiceReply)}
          >
            {voiceReply ? <SpeakerHigh className="size-5" /> : <SpeakerSlash className="size-5" />}
          </IconButton>
          {id && (
            <Menu
              items={[
                { label: t('chat.new'), icon: <NotePencil className="size-4" />, onSelect: () => navigate('/chat') },
                current && { label: t('chat.delete'), icon: <Trash className="size-4" />, tone: 'danger', onSelect: () => setConfirmDelete(current) },
              ]}
              trigger={(p) => (
                <IconButton label={t('chat.moreOptions')} {...p}>
                  <DotsThree className="size-5" weight="bold" />
                </IconButton>
              )}
            />
          )}
        </header>

        <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto grid w-full max-w-3xl gap-5 px-4 py-6 md:px-6">
            {isNew ? (
              <NewConversation name={user?.displayName} orb={orbState} onPick={(s) => void sendText(s)} getLevel={voice.level} />
            ) : messages.isLoading && !turnVisible ? (
              <ThreadSkeleton />
            ) : messages.isError && !turnVisible ? (
              <ErrorState message={(messages.error as Error).message} onRetry={() => void messages.refetch()} />
            ) : (
              <>
                <ThreadItems items={serverItems} />
                <ThreadItems items={liveItems} animateNew />
                {showThinking && (
                  <div className="flex items-center gap-3 pl-0.5" role="status">
                    <VoiceOrb state="thinking" className="size-6" />
                    <span className="k-shimmer-text text-base">{runner.turn?.status === 'transcribing' ? t('chat.transcribing') : t('palette.thinking')}</span>
                  </div>
                )}
              </>
            )}
          </div>
        </div>

        <div className="shrink-0 px-3 pb-3 md:px-6 md:pb-5">
          <div className="mx-auto w-full max-w-3xl">
            <AnimatePresence initial={false}>
              {voice.phase !== 'off' && !isNew && (
                <motion.div
                  initial={reduce ? { opacity: 0 } : { opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0 }}
                  transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
                >
                  <VoiceBar phase={voice.phase} getLevel={voice.level} onFinish={() => void voice.finish()} onCancel={() => {
                    voice.cancel()
                    if (runner.busy) runner.cancel()
                  }} />
                </motion.div>
              )}
            </AnimatePresence>
            {isNew && voice.phase !== 'off' && (
              <VoiceBar phase={voice.phase} getLevel={voice.level} onFinish={() => void voice.finish()} onCancel={() => voice.cancel()} />
            )}
            <Composer
              ref={composer}
              onSend={(text) => void sendText(text)}
              onMic={onMic}
              micActive={voice.phase === 'listening'}
              busy={runner.busy}
              onStop={() => {
                runner.cancel()
                voice.setPhase('off')
                const turn = runner.turn
                if (turn) runner.setTurn({ ...turn, status: 'error', error: t('chat.stoppedReply') })
              }}
              placeholder={t('composer.placeholder')}
              autoFocus
            />
            <p className="mt-2 hidden text-center text-xs text-faint md:block">
              {t('chat.sendHint')}
            </p>
          </div>
        </div>
      </section>

      <Dialog open={historyOpen} onClose={() => setHistoryOpen(false)} title={t('chat.conversations')} size="sm">
        <div className="-mx-5 -mt-2">
          <ConversationList
            conversations={conversations.data}
            loading={conversations.isLoading}
            activeId={id}
            onDelete={setConfirmDelete}
            embedded
          />
        </div>
      </Dialog>

      <ConfirmDialog
        open={!!confirmDelete}
        onClose={() => setConfirmDelete(null)}
        onConfirm={() => confirmDelete && removeConv.mutate(confirmDelete)}
        loading={removeConv.isPending}
        title={t('chat.deleteTitle')}
        description={t('chat.deleteBody')}
      />
    </div>
  )
}

function VoiceBar({
  phase,
  getLevel,
  onFinish,
  onCancel,
}: {
  phase: 'listening' | 'thinking' | 'speaking' | 'off'
  getLevel: () => number
  onFinish: () => void
  onCancel: () => void
}) {
  const label = phase === 'listening' ? t('orb.listening') : phase === 'thinking' ? t('orb.thinking') : t('orb.speaking')
  const hint =
    phase === 'listening' ? t('chat.hintListening') : phase === 'thinking' ? t('chat.hintThinking') : t('chat.hintSpeaking')
  return (
    <div className="mb-3 flex items-center gap-4 rounded-card border border-line bg-surface px-4 py-3" role="status" aria-live="polite">
      <VoiceOrb state={phase === 'off' ? 'idle' : phase} getLevel={getLevel} className="size-12 shrink-0" />
      <div className="min-w-0 flex-1">
        <p className={cn('text-base font-medium', phase === 'listening' ? 'text-accent-strong' : 'text-fg')}>{label}</p>
        <p className="truncate text-sm text-muted">{hint}</p>
      </div>
      <div className="flex shrink-0 gap-2">
        {phase === 'listening' && (
          <Button size="sm" variant="primary" onClick={onFinish}>
            {t('common.send')}
          </Button>
        )}
        <Button size="sm" variant="ghost" onClick={onCancel}>
          {phase === 'speaking' ? t('composer.stop') : t('common.cancel')}
        </Button>
      </div>
    </div>
  )
}

function NewConversation({ name, orb, onPick, getLevel }: { name?: string; orb: OrbState; onPick: (s: string) => void; getLevel: () => number }) {
  return (
    <div className="flex min-h-[52dvh] flex-col items-center justify-center py-6 text-center">
      <VoiceOrb state={orb} getLevel={getLevel} className="size-24 md:size-28" />
      <h2 className="mt-7 text-lg font-semibold tracking-tight md:text-xl">{name ? t('chat.helpName', { name }) : t('chat.helpYou')}</h2>
      <p className="mt-2 max-w-[46ch] text-base text-muted">{t('chat.intro')}</p>
      <div className="mt-7 grid w-full max-w-xl gap-2 sm:grid-cols-2">
        {suggestions().map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => onPick(s)}
            className="rounded-control border border-line bg-surface px-3.5 py-3 text-left text-base text-muted transition-[color,border-color,transform] hover:border-[color-mix(in_srgb,var(--text-faint)_45%,var(--border))] hover:text-fg active:scale-[0.99]"
          >
            {s}
          </button>
        ))}
      </div>
    </div>
  )
}

function ThreadSkeleton() {
  return (
    <div className="grid gap-6" aria-busy="true" aria-label={t('chat.loadingMessages')}>
      <div className="flex justify-end">
        <Skeleton className="h-10 w-2/5 rounded-[16px]" />
      </div>
      <div className="flex gap-3">
        <Skeleton className="size-7 shrink-0 rounded-full" />
        <div className="grid flex-1 gap-2">
          <Skeleton className="h-3.5 w-11/12" />
          <Skeleton className="h-3.5 w-4/5" />
          <Skeleton className="h-3.5 w-1/2" />
        </div>
      </div>
      <div className="flex justify-end">
        <Skeleton className="h-10 w-1/3 rounded-[16px]" />
      </div>
      <div className="flex gap-3">
        <Skeleton className="size-7 shrink-0 rounded-full" />
        <div className="grid flex-1 gap-2">
          <Skeleton className="h-12 w-full rounded-card" />
          <Skeleton className="h-3.5 w-2/3" />
        </div>
      </div>
    </div>
  )
}

function ConversationList({
  conversations,
  loading,
  activeId,
  onDelete,
  embedded,
}: {
  conversations?: Conversation[]
  loading: boolean
  activeId?: string
  onDelete: (c: Conversation) => void
  embedded?: boolean
}) {
  const [q, setQ] = useState('')
  const navigate = useNavigate()
  const list = (conversations ?? []).filter((c) => !q.trim() || `${c.title} ${c.preview ?? ''}`.toLowerCase().includes(q.trim().toLowerCase()))
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className={cn('grid gap-2 px-3', embedded ? 'pb-3' : 'pt-4 pb-3')}>
        <Button variant="secondary" className="w-full" icon={<NotePencil className="size-4" />} onClick={() => navigate('/chat')}>
          {t('chat.new')}
        </Button>
        <label className="relative block">
          <span className="sr-only">{t('chat.searchConversations')}</span>
          <MagnifyingGlass className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-faint" />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={t('chat.search')}
            className="h-9 w-full rounded-control border border-line bg-surface pr-3 pl-9 text-base outline-none transition-colors focus:border-accent"
          />
        </label>
      </div>
      <div className={cn('min-h-0 flex-1 overflow-y-auto px-2 pb-3', embedded && 'max-h-[60dvh]')}>
        {loading ? (
          <div className="grid gap-3 px-2 pt-2" aria-busy="true">
            {Array.from({ length: 6 }, (_, i) => (
              <div key={i} className="grid gap-1.5">
                <Skeleton className="h-3.5 w-4/5" />
                <Skeleton className="h-3 w-1/2" />
              </div>
            ))}
          </div>
        ) : list.length === 0 ? (
          <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
            <ChatCircleDots className="size-6 text-faint" />
            <p className="text-base text-muted">{q ? t('chat.noMatch') : t('chat.emptyList')}</p>
          </div>
        ) : (
          <ul className="grid gap-0.5">
            {list.map((c) => (
              <li key={c.id} className="group relative">
                <NavLink
                  to={`/chat/${c.id}`}
                  className={({ isActive }) =>
                    cn(
                      'block rounded-control px-3 py-2.5 pr-10 transition-colors',
                      isActive || c.id === activeId ? 'bg-surface-2' : 'hover:bg-surface-2/60',
                    )
                  }
                >
                  <span className="flex items-baseline gap-2">
                    <span className="min-w-0 flex-1 truncate text-base text-fg">{c.title || t('chat.untitled')}</span>
                    <span className="shrink-0 font-mono text-xs text-faint">{compactWhen(c.updatedAt)}</span>
                  </span>
                  {c.preview && !c.preview.startsWith(c.title) && <span className="mt-0.5 block truncate text-sm text-muted">{c.preview}</span>}
                </NavLink>
                <button
                  type="button"
                  aria-label={t('common.deleteNamed', { name: c.title })}
                  onClick={() => onDelete(c)}
                  className="absolute top-2 right-2 flex size-7 items-center justify-center rounded-[8px] text-faint opacity-0 transition-[opacity,color] group-hover:opacity-100 hover:text-danger focus-visible:opacity-100 max-lg:opacity-100"
                >
                  <Trash className="size-4" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
