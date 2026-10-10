import { IconContext } from '@phosphor-icons/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MotionConfig } from 'motion/react'
import { lazy, Suspense, type ReactNode } from 'react'
import { createBrowserRouter, Navigate, useLocation } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { KairoMark } from '@/components/KairoMark'
import { AppShell } from '@/components/layout/AppShell'
import { ApiError } from '@/lib/api'
import { AuthProvider, useAuth } from '@/lib/auth'
import { ThemeProvider } from '@/lib/theme'

const Login = lazy(() => import('@/pages/Login'))
const Unirse = lazy(() => import('@/pages/Join'))
const Familia = lazy(() => import('@/pages/Family'))
const Hoy = lazy(() => import('@/pages/Today'))
const Chat = lazy(() => import('@/pages/Chat'))
const Notas = lazy(() => import('@/pages/Notes'))
const Tareas = lazy(() => import('@/pages/Tasks'))
const Recordatorios = lazy(() => import('@/pages/Reminders'))
const Calendario = lazy(() => import('@/pages/Calendar'))
const Listas = lazy(() => import('@/pages/Lists'))
const Memoria = lazy(() => import('@/pages/Memory'))
const Grabaciones = lazy(() => import('@/pages/Recordings'))
const Actividad = lazy(() => import('@/pages/Activity'))
const Ajustes = lazy(() => import('@/pages/Settings'))
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
    <div className="flex min-h-[100dvh] items-center justify-center" aria-busy="true" aria-label="Cargando Kairo">
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
    return <Navigate to={from && from !== '/login' ? from : '/'} replace />
  }
  return <Suspense fallback={<Splash />}>{children}</Suspense>
}

/** Pública con o sin sesión (unirse con un código funciona aunque haya otra cuenta abierta). */
function Public({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  if (status === 'loading') return <Splash />
  return <Suspense fallback={<Splash />}>{children}</Suspense>
}

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
    path: '/unirse',
    element: (
      <Public>
        <Unirse />
      </Public>
    ),
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Hoy /> },
      { path: 'chat/:id?', element: <Chat /> },
      { path: 'notas/:id?', element: <Notas /> },
      { path: 'tareas', element: <Tareas /> },
      { path: 'recordatorios', element: <Recordatorios /> },
      { path: 'calendario', element: <Calendario /> },
      { path: 'listas/:id?', element: <Listas /> },
      { path: 'memoria', element: <Memoria /> },
      { path: 'grabaciones/:id?', element: <Grabaciones /> },
      { path: 'actividad', element: <Actividad /> },
      { path: 'familia', element: <Familia /> },
      { path: 'ajustes', element: <Ajustes /> },
      { path: '*', element: <NotFound /> },
    ],
  },
])

export function App() {
  return (
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
  )
}
