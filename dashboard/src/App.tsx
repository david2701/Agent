import { IconContext } from '@phosphor-icons/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MotionConfig } from 'motion/react'
import { lazy, Suspense, type ReactNode } from 'react'
import { createBrowserRouter, Navigate, useLocation, useParams } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { KairoMark } from '@/components/KairoMark'
import { AppShell } from '@/components/layout/AppShell'
import { ApiError } from '@/lib/api'
import { AuthProvider, useAuth } from '@/lib/auth'
import { I18nProvider, t } from '@/lib/i18n'
import { ThemeProvider } from '@/lib/theme'

const Login = lazy(() => import('@/pages/Login'))
const Join = lazy(() => import('@/pages/Join'))
const Family = lazy(() => import('@/pages/Family'))
const Today = lazy(() => import('@/pages/Today'))
const Chat = lazy(() => import('@/pages/Chat'))
const Notes = lazy(() => import('@/pages/Notes'))
const Tasks = lazy(() => import('@/pages/Tasks'))
const Reminders = lazy(() => import('@/pages/Reminders'))
const Calendar = lazy(() => import('@/pages/Calendar'))
const Lists = lazy(() => import('@/pages/Lists'))
const Memory = lazy(() => import('@/pages/Memory'))
const Recordings = lazy(() => import('@/pages/Recordings'))
const Activity = lazy(() => import('@/pages/Activity'))
const Settings = lazy(() => import('@/pages/Settings'))
const NotFound = lazy(() => import('@/pages/NotFound'))

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 20_000,
      retry: (count, error) => {
        if (error instanceof ApiError && error.status > 0 && error.status < 500) return false
        return count < 2
      },
    },
    mutations: { retry: false },
  },
})

function Splash() {
  return (
    <div className="flex min-h-[100dvh] items-center justify-center" aria-busy="true" aria-label={t('app.loadingKairo')}>
      <KairoMark className="size-10 opacity-80" />
    </div>
  )
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()
  if (status === 'loading') return <Splash />
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return <>{children}</>
}

function PublicOnly({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()
  if (status === 'loading') return <Splash />
  if (status === 'authenticated') {
    const from = (location.state as { from?: string } | null)?.from
    return <Navigate to={from && from !== '/login' ? from : '/today'} replace />
  }
  return <Suspense fallback={<Splash />}>{children}</Suspense>
}

/** Public with or without a session (joining with a code works even when another account is signed in). */
function Public({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  if (status === 'loading') return <Splash />
  return <Suspense fallback={<Splash />}>{children}</Suspense>
}

/* ------------------------------------------------------------ legacy (Spanish) URLs */

/** Query parameters of the old Spanish deep links -> their English names. */
const LEGACY_PARAMS: Record<string, string> = {
  codigo: 'code',
  nueva: 'new',
  nuevo: 'new',
  tarea: 'task',
  proyecto: 'project',
  evento: 'event',
  invitar: 'invite',
  asignadas: 'assigned',
}

const LEGACY_HASHES: Record<string, string> = {
  '#lugares': '#places',
  '#aviso': '#notice',
  '#novedades': '#updates',
  '#datos': '#data',
}

/** Redirects an old Spanish route to its English one, keeping the sub-path, query string (renamed) and hash. */
function LegacyRedirect({ to }: { to: string }) {
  const location = useLocation()
  const splat = useParams()['*']
  const params = new URLSearchParams(location.search)
  const search = new URLSearchParams()
  params.forEach((value, key) => search.append(LEGACY_PARAMS[key] ?? key, value))
  const sub = splat ? `/${splat === 'nueva' ? 'new' : splat}` : ''
  const qs = search.toString()
  return <Navigate to={`${to}${sub}${qs ? `?${qs}` : ''}${LEGACY_HASHES[location.hash] ?? location.hash}`} replace />
}

const LEGACY_ROUTES: [string, string][] = [
  ['hoy', '/today'],
  ['tareas', '/tasks'],
  ['calendario', '/calendar'],
  ['recordatorios', '/reminders'],
  ['notas/*', '/notes'],
  ['notas', '/notes'],
  ['listas/*', '/lists'],
  ['listas', '/lists'],
  ['memoria', '/memory'],
  ['grabaciones/*', '/recordings'],
  ['grabaciones', '/recordings'],
  ['actividad', '/activity'],
  ['familia', '/family'],
  ['ajustes', '/settings'],
  ['unirse', '/join'],
]

const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <PublicOnly>
        <Login />
      </PublicOnly>
    ),
  },
  {
    path: '/join',
    element: (
      <Public>
        <Join />
      </Public>
    ),
  },
  ...LEGACY_ROUTES.map(([path, to]) => ({ path: `/${path}`, element: <LegacyRedirect to={to} /> })),
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/today" replace /> },
      { path: 'today', element: <Today /> },
      { path: 'chat/:id?', element: <Chat /> },
      { path: 'notes/:id?', element: <Notes /> },
      { path: 'tasks', element: <Tasks /> },
      { path: 'reminders', element: <Reminders /> },
      { path: 'calendar', element: <Calendar /> },
      { path: 'lists/:id?', element: <Lists /> },
      { path: 'memory', element: <Memory /> },
      { path: 'recordings/:id?', element: <Recordings /> },
      { path: 'activity', element: <Activity /> },
      { path: 'family', element: <Family /> },
      { path: 'settings', element: <Settings /> },
      { path: '*', element: <NotFound /> },
    ],
  },
])

export function App() {
  return (
    <I18nProvider>
      <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          <MotionConfig reducedMotion="user">
            <IconContext.Provider value={{ weight: 'regular', mirrored: false }}>
              <AuthProvider>
                <RouterProvider router={router} />
              </AuthProvider>
            </IconContext.Provider>
          </MotionConfig>
        </QueryClientProvider>
      </ThemeProvider>
    </I18nProvider>
  )
}
