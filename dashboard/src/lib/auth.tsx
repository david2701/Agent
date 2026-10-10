import { useQueryClient } from '@tanstack/react-query'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as client from './api'
import type { User } from './types'

type Status = 'loading' | 'authenticated' | 'anonymous'

interface AuthContextValue {
  status: Status
  user: User | null
  deviceId: string | null
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  setUser: (u: User) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient()
  const [status, setStatus] = useState<Status>('loading')
  const [user, setUserState] = useState<User | null>(null)
  const [deviceId, setDeviceId] = useState<string | null>(null)

  useEffect(() => {
    const off = client.onSessionChange((session) => {
      if (session) {
        setUserState(session.user)
        setDeviceId(session.deviceId)
        setStatus('authenticated')
      } else {
        setUserState(null)
        setDeviceId(null)
        setStatus('anonymous')
        qc.clear()
      }
    })
    // Restaura la sesión con la cookie httpOnly.
    void client.refreshSession()
    return off
  }, [qc])

  const login = useCallback(async (email: string, password: string) => {
    await client.login(email.trim(), password)
  }, [])

  const logout = useCallback(async () => {
    await client.logout()
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, deviceId, login, logout, setUser: setUserState }),
    [status, user, deviceId, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth fuera de AuthProvider')
  return ctx
}
