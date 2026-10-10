import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { VoiceOrb } from '@/components/VoiceOrb'
import { duration } from '@/lib/format'
import { t } from '@/lib/i18n'
import { sessionElapsed, sessionLevel, useRecordingSession } from '@/lib/recordingSession'

/** Floating indicator while recording and on another page. */
export function RecordingPill() {
  const session = useRecordingSession()
  const location = useLocation()
  const [elapsed, setElapsed] = useState(0)
  useEffect(() => {
    if (session.status !== 'recording') return
    const timer = setInterval(() => setElapsed(sessionElapsed()), 1000)
    return () => clearInterval(timer)
  }, [session.status])
  if (session.status !== 'recording' || location.pathname.startsWith('/recordings')) return null
  return (
    <Link
      to="/recordings"
      className="fixed bottom-[calc(80px+env(safe-area-inset-bottom))] left-1/2 z-40 flex -translate-x-1/2 items-center gap-2 rounded-full border border-line bg-surface py-1.5 pr-3.5 pl-2 text-sm shadow-pop md:bottom-5"
    >
      <VoiceOrb state="listening" getLevel={sessionLevel} className="size-6" />
      <span className="font-medium text-accent-strong">{t('recording.pill')}</span>
      <span className="font-mono text-fg tnum">{duration(elapsed)}</span>
    </Link>
  )
}
