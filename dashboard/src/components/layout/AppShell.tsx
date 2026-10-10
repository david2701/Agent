import {
  BellSimple,
  CaretLineLeft,
  CaretLineRight,
  Checks,
  DotsThreeOutline,
  MagnifyingGlass,
  Monitor,
  Moon,
  SignOut,
  Sun,
} from '@phosphor-icons/react'
import { createContext, lazy, Suspense, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { KairoMark } from '@/components/KairoMark'
import { NoticesFeed } from '@/components/NoticesFeed'
import { RecordingPill } from '@/components/RecordingPill'
import { Button, IconButton } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/feedback'
import { Menu } from '@/components/ui/menu'
import { Kbd } from '@/components/ui/misc'
import { Toaster } from '@/components/ui/toast'
import { setClientLocation } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { cn } from '@/lib/cn'
import { useFamilyInfo } from '@/lib/family'
import { t } from '@/lib/i18n'
import { useMarkNoticesRead } from '@/lib/mutations'
import { useNotices } from '@/lib/queries'
import { useRealtime, useRealtimeState } from '@/lib/realtime'
import { useTheme, type ThemeMode } from '@/lib/theme'
import { ALL_NAV, MOBILE_PRIMARY, NAV_GROUPS, SETTINGS_ITEM, type NavItem } from './nav'

interface ShellContextValue {
  openPalette: (prefill?: string) => void
  openNotices: () => void
}

const ShellContext = createContext<ShellContextValue>({ openPalette: () => undefined, openNotices: () => undefined })
export const useShell = () => useContext(ShellContext)

const CommandPalette = lazy(() => import('./CommandPalette').then((m) => ({ default: m.CommandPalette })))

const SIDEBAR_KEY = 'kairo-sidebar'

function initialCollapsed() {
  try {
    const v = localStorage.getItem(SIDEBAR_KEY)
    if (v === 'collapsed') return true
    if (v === 'expanded') return false
  } catch {
    /* no storage */
  }
  return window.matchMedia('(max-width: 1023px)').matches
}

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)
export const MOD_KEY = isMac ? '⌘' : 'Ctrl'

export function AppShell() {
  const navigate = useNavigate()
  const [collapsed, setCollapsed] = useState(initialCollapsed)
  const [palette, setPalette] = useState<{ open: boolean; prefill: string }>({ open: false, prefill: '' })
  const [moreOpen, setMoreOpen] = useState(false)
  const [noticesOpen, setNoticesOpen] = useState(false)
  const [paletteLoaded, setPaletteLoaded] = useState(false)
  const location = useLocation()

  const goHome = useCallback(() => navigate('/today'), [navigate])
  const goTo = useCallback((to: string) => navigate(to), [navigate])
  useRealtime(true, goHome, goTo)

  // Approximate location for weather and places, only if it was already granted.
  useEffect(() => {
    if (!navigator.permissions || !navigator.geolocation) return
    navigator.permissions
      .query({ name: 'geolocation' })
      .then((p) => {
        if (p.state === 'granted') {
          navigator.geolocation.getCurrentPosition(
            (pos) => setClientLocation(pos.coords.latitude, pos.coords.longitude),
            () => undefined,
            { maximumAge: 10 * 60_000, timeout: 8000 },
          )
        }
      })
      .catch(() => undefined)
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPalette((p) => ({ open: !p.open, prefill: '' }))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    setMoreOpen(false)
    setNoticesOpen(false)
  }, [location.pathname])

  const toggleCollapsed = () => {
    setCollapsed((c) => {
      try {
        localStorage.setItem(SIDEBAR_KEY, c ? 'expanded' : 'collapsed')
      } catch {
        /* no storage */
      }
      return !c
    })
  }

  useEffect(() => {
    if (palette.open) setPaletteLoaded(true)
  }, [palette.open])

  const shell = useMemo<ShellContextValue>(
    () => ({ openPalette: (prefill = '') => setPalette({ open: true, prefill }), openNotices: () => setNoticesOpen(true) }),
    [],
  )

  return (
    <ShellContext.Provider value={shell}>
      <a
        href="#main"
        className="sr-only z-50 rounded-control bg-surface px-3 py-2 text-base focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        {t('app.skipToContent')}
      </a>
      <div className="flex min-h-[100dvh]">
        <Sidebar collapsed={collapsed} onToggle={toggleCollapsed} onAsk={() => shell.openPalette()} onNotices={shell.openNotices} />
        <div className="flex min-w-0 flex-1 flex-col">
          <MobileTopBar onAsk={() => shell.openPalette()} onNotices={shell.openNotices} />
          <main id="main" className="min-w-0 flex-1 pb-[calc(68px+env(safe-area-inset-bottom))] md:pb-0">
            <Suspense fallback={<PageFallback />}>
              <Outlet />
            </Suspense>
          </main>
        </div>
      </div>
      <BottomNav onMore={() => setMoreOpen(true)} />
      <MoreSheet open={moreOpen} onClose={() => setMoreOpen(false)} />
      <NoticesDialog open={noticesOpen} onClose={() => setNoticesOpen(false)} />
      {(paletteLoaded || palette.open) && (
        <Suspense fallback={null}>
          <CommandPalette
            open={palette.open}
            prefill={palette.prefill}
            onClose={() => setPalette((p) => ({ ...p, open: false }))}
          />
        </Suspense>
      )}
      <RecordingPill />
      <Toaster />
    </ShellContext.Provider>
  )
}

function PageFallback() {
  return (
    <div className="mx-auto grid max-w-[1400px] gap-6 px-4 py-6 md:px-8 md:py-8" aria-busy="true" aria-label={t('common.loading')}>
      <Skeleton className="h-8 w-48" />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <Skeleton className="h-64 rounded-card" />
        <Skeleton className="h-64 rounded-card" />
      </div>
    </div>
  )
}

function NavEntry({ item, collapsed, onNavigate }: { item: NavItem; collapsed: boolean; onNavigate?: () => void }) {
  const Icon = item.icon
  return (
    <NavLink
      to={item.to}
      onClick={onNavigate}
      title={collapsed ? t(item.labelKey) : undefined}
      className={({ isActive }) =>
        cn(
          'group relative flex h-9 items-center gap-3 rounded-control text-base transition-colors duration-200',
          collapsed ? 'justify-center px-0' : 'px-2.5',
          isActive ? 'bg-surface-2 font-medium text-fg' : 'text-muted hover:bg-surface-2/60 hover:text-fg',
        )
      }
    >
      {({ isActive }) => (
        <>
          <Icon className={cn('size-[18px] shrink-0', isActive ? 'text-accent-strong' : 'text-faint group-hover:text-muted')} />
          <span className={cn(collapsed && 'sr-only')}>{t(item.labelKey)}</span>
        </>
      )}
    </NavLink>
  )
}

/** Family notices indicator: a bell with the unread count; opens Updates. */
function NoticesBell({ onOpen, size = 'md' }: { onOpen: () => void; size?: 'sm' | 'md' }) {
  const notices = useNotices()
  const fam = useFamilyInfo()
  const unread = notices.data?.unread ?? 0
  // With nobody else in the household and no notices, the bell would just be noise.
  if (!fam.hasOthers && unread === 0) return null
  return (
    <IconButton
      label={unread > 0 ? t('app.updatesFamilyUnread', { count: unread }) : t('app.updatesFamily')}
      size={size}
      onClick={onOpen}
      className="relative"
      data-testid="notices-bell"
    >
      <BellSimple className={size === 'sm' ? 'size-4' : 'size-5'} />
      {unread > 0 && (
        <span
          aria-hidden
          className={cn(
            'absolute -right-px flex h-[15px] min-w-[15px] items-center justify-center rounded-full bg-accent px-[3px] font-mono text-[9.5px] leading-none font-semibold text-on-accent ring-2 ring-bg tnum',
            size === 'sm' ? 'top-0.5' : 'top-[3px]',
          )}
        >
          {unread > 9 ? '9+' : unread}
        </span>
      )}
    </IconButton>
  )
}

function NoticesDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  const notices = useNotices()
  const markAll = useMarkNoticesRead()
  const unread = notices.data?.unread ?? 0
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('app.updates')}
      description={notices.data ? (unread > 0 ? t('app.unread', { count: unread }) : t('app.caughtUp')) : undefined}
      footer={
        <>
          {unread > 0 && (
            <Button variant="ghost" className="mr-auto" icon={<Checks className="size-4" />} onClick={() => markAll.mutate(undefined)}>
              {t('app.markAllRead')}
            </Button>
          )}
          <Button
            onClick={() => {
              onClose()
              navigate('/family#updates')
            }}
          >
            {t('app.openFamily')}
          </Button>
        </>
      }
    >
      <NoticesFeed
        notices={notices.data?.notices}
        loading={notices.isLoading}
        error={notices.error as Error | null}
        onRetry={() => void notices.refetch()}
        limit={20}
        onNavigate={onClose}
      />
    </Dialog>
  )
}

function Sidebar({ collapsed, onToggle, onAsk, onNotices }: { collapsed: boolean; onToggle: () => void; onAsk: () => void; onNotices: () => void }) {
  const live = useRealtimeState()
  return (
    <aside
      className={cn(
        'sticky top-0 hidden h-[100dvh] shrink-0 flex-col border-r border-line bg-bg md:flex',
        collapsed ? 'w-[68px]' : 'w-[244px]',
      )}
      aria-label={t('app.mainNav')}
    >
      <div className={cn('flex h-16 items-center', collapsed ? 'justify-center' : 'justify-between pr-2 pl-4')}>
        <NavLink to="/today" className="flex items-center gap-2.5 rounded-control" aria-label={t('app.goToday')}>
          <KairoMark className="size-7" />
          {!collapsed && <span className="text-md font-semibold tracking-tight">Kairo</span>}
        </NavLink>
        {!collapsed && (
          <div className="flex items-center gap-0.5">
            <NoticesBell onOpen={onNotices} size="sm" />
            <IconButton label={t('app.collapseSidebar')} size="sm" onClick={onToggle}>
              <CaretLineLeft className="size-4" />
            </IconButton>
          </div>
        )}
      </div>

      <div className={cn('px-3 pb-2', collapsed && 'flex flex-col items-center gap-1 px-0')}>
        {collapsed ? (
          <>
            <IconButton label={t('app.expandSidebar')} size="sm" onClick={onToggle}>
              <CaretLineRight className="size-4" />
            </IconButton>
            <IconButton label={`${t('app.askOrSearch')} (${MOD_KEY} K)`} onClick={onAsk}>
              <MagnifyingGlass className="size-[18px]" />
            </IconButton>
            <NoticesBell onOpen={onNotices} />
          </>
        ) : (
          <button
            type="button"
            onClick={onAsk}
            className="flex h-9 w-full items-center gap-2 rounded-control border border-line bg-surface px-2 text-base text-muted transition-colors hover:border-[color-mix(in_srgb,var(--text-faint)_45%,var(--border))] hover:text-fg active:scale-[0.99]"
          >
            <MagnifyingGlass className="size-4 text-faint" />
            <span className="min-w-0 flex-1 truncate text-left text-sm">{t('app.askOrSearch')}</span>
            <Kbd className="shrink-0 px-1">{MOD_KEY} K</Kbd>
          </button>
        )}
      </div>

      <nav className={cn('flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto pt-2 pb-3', collapsed ? 'px-2.5' : 'px-3')}>
        {NAV_GROUPS.map((group, i) => (
          <div key={i} className="grid gap-0.5">
            {group.map((item) => (
              <NavEntry key={item.to} item={item} collapsed={collapsed} />
            ))}
          </div>
        ))}
      </nav>

      <div className={cn('grid grid-cols-1 gap-1 border-t border-line py-3', collapsed ? 'px-2.5' : 'px-3')}>
        {live === 'offline' && !collapsed && (
          <p className="px-2.5 pb-1 text-xs text-faint" role="status">
            {t('app.reconnecting')}
          </p>
        )}
        <NavEntry item={SETTINGS_ITEM} collapsed={collapsed} />
        <UserRow collapsed={collapsed} />
      </div>
    </aside>
  )
}

const themeOptions = (): { mode: ThemeMode; label: string; icon: ReactNode }[] => [
  { mode: 'light', label: t('app.themeLight'), icon: <Sun className="size-4" /> },
  { mode: 'dark', label: t('app.themeDark'), icon: <Moon className="size-4" /> },
  { mode: 'system', label: t('app.themeSystem'), icon: <Monitor className="size-4" /> },
]

function ThemeIcon() {
  const { mode, resolved } = useTheme()
  if (mode === 'system') return <Monitor className="size-[18px]" />
  return resolved === 'dark' ? <Moon className="size-[18px]" /> : <Sun className="size-[18px]" />
}

function UserRow({ collapsed }: { collapsed: boolean }) {
  const { user, logout } = useAuth()
  const { setMode } = useTheme()
  const name = user?.displayName || t('app.you')
  const themeItems = themeOptions().map((o) => ({ label: o.label, icon: o.icon, onSelect: () => setMode(o.mode) }))
  return (
    <div className={cn('flex min-w-0 items-center gap-0.5', collapsed ? 'flex-col' : 'pl-1')}>
      {!collapsed && (
        <div className="flex min-w-0 flex-1 items-center gap-2.5 py-1">
          <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-surface-2 text-sm font-medium text-fg" aria-hidden>
            {name.charAt(0).toUpperCase()}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium text-fg">{name}</span>
            <span className="block truncate text-xs text-faint">{user?.email}</span>
          </span>
        </div>
      )}
      <Menu
        align={collapsed ? 'start' : 'end'}
        items={themeItems}
        trigger={(p) => (
          <IconButton label={t('app.changeTheme')} size="sm" {...p}>
            <ThemeIcon />
          </IconButton>
        )}
      />
      <IconButton label={t('app.signOut')} size="sm" tone="danger" onClick={() => void logout()}>
        <SignOut className="size-[18px]" />
      </IconButton>
    </div>
  )
}

function MobileTopBar({ onAsk, onNotices }: { onAsk: () => void; onNotices: () => void }) {
  return (
    <div className="sticky top-0 z-20 flex h-14 items-center justify-between border-b border-line bg-bg px-4 md:hidden">
      <NavLink to="/today" className="flex items-center gap-2" aria-label={t('app.goToday')}>
        <KairoMark className="size-6" />
        <span className="text-md font-semibold tracking-tight">Kairo</span>
      </NavLink>
      <div className="flex items-center gap-1">
        <NoticesBell onOpen={onNotices} />
        <IconButton label={t('app.askOrSearch')} onClick={onAsk}>
          <MagnifyingGlass className="size-5" />
        </IconButton>
      </div>
    </div>
  )
}

function BottomNav({ onMore }: { onMore: () => void }) {
  const location = useLocation()
  const items = MOBILE_PRIMARY.map((to) => ALL_NAV.find((n) => n.to === to)!)
  const inPrimary = items.some((i) => location.pathname.startsWith(i.to))
  return (
    <nav
      aria-label={t('app.nav')}
      className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface pb-[env(safe-area-inset-bottom)] md:hidden"
    >
      <div className="grid h-16 grid-cols-5">
        {items.map((item) => {
          const Icon = item.icon
          const label = t(item.labelKey)
          return (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  'flex flex-col items-center justify-center gap-1 text-xs transition-colors active:scale-95',
                  isActive ? 'font-medium text-fg' : 'text-faint',
                )
              }
            >
              {({ isActive }) => (
                <>
                  <Icon className={cn('size-[22px]', isActive && 'text-accent-strong')} weight={isActive ? 'fill' : 'regular'} />
                  {label}
                </>
              )}
            </NavLink>
          )
        })}
        <button
          type="button"
          onClick={onMore}
          className={cn('flex flex-col items-center justify-center gap-1 text-xs active:scale-95', inPrimary ? 'text-faint' : 'font-medium text-fg')}
        >
          <DotsThreeOutline className={cn('size-[22px]', !inPrimary && 'text-accent-strong')} weight={inPrimary ? 'regular' : 'fill'} />
          {t('app.more')}
        </button>
      </div>
    </nav>
  )
}

function MoreSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { mode, setMode } = useTheme()
  const { logout } = useAuth()
  const rest = ALL_NAV.filter((n) => !MOBILE_PRIMARY.includes(n.to))
  return (
    <Dialog open={open} onClose={onClose} title={t('app.moreSections')} size="sm">
      <div className="grid grid-cols-2 gap-2">
        {rest.map((item) => {
          const Icon = item.icon
          return (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  'flex h-12 items-center gap-3 rounded-control border px-3 text-base transition-colors active:scale-[0.98]',
                  isActive ? 'border-line bg-surface-2 font-medium' : 'border-line bg-surface',
                )
              }
            >
              <Icon className="size-5 text-muted" />
              {t(item.labelKey)}
            </NavLink>
          )
        })}
      </div>
      <div className="mt-5 grid gap-2">
        <p className="text-sm font-medium text-muted">{t('app.theme')}</p>
        <div className="grid grid-cols-3 gap-2">
          {themeOptions().map((o) => (
            <button
              key={o.mode}
              type="button"
              onClick={() => setMode(o.mode)}
              aria-pressed={mode === o.mode}
              className={cn(
                'flex h-10 items-center justify-center gap-2 rounded-control border text-sm transition-colors',
                mode === o.mode ? 'border-accent bg-[color-mix(in_srgb,var(--accent)_12%,var(--surface))] text-fg' : 'border-line text-muted',
              )}
            >
              {o.icon}
              {o.mode === 'light' ? t('app.themeLightShort') : o.mode === 'dark' ? t('app.themeDarkShort') : t('app.themeSystemShort')}
            </button>
          ))}
        </div>
      </div>
      <button
        type="button"
        onClick={() => void logout()}
        className="mt-5 flex h-10 w-full items-center justify-center gap-2 rounded-control border border-line text-base text-danger active:scale-[0.98]"
      >
        <SignOut className="size-4" />
        {t('app.signOut')}
      </button>
    </Dialog>
  )
}
