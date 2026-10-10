import { t } from './i18n'
import type { AgentEvent, AuthResponse, InvitePreview } from './types'

/**
 * Kairo HTTP client.
 * - The access token lives in memory only.
 * - The refresh token is an httpOnly cookie (path /api/auth): renewed with POST /api/auth/refresh.
 * - A 401 triggers a single shared refresh and retries once; if that fails, the session is closed.
 */

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

type SessionListener = (session: AuthResponse | null) => void

let accessToken: string | null = null
let refreshTimer: ReturnType<typeof setTimeout> | null = null
let refreshPromise: Promise<AuthResponse | null> | null = null
let clientLocation: string | null = null
const listeners = new Set<SessionListener>()

const BASE = '/api'

export function getAccessToken() {
  return accessToken
}

export function setClientLocation(lat: number, lon: number) {
  clientLocation = `${lat.toFixed(5)},${lon.toFixed(5)}`
}

export function onSessionChange(fn: SessionListener) {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

function emit(session: AuthResponse | null) {
  listeners.forEach((fn) => fn(session))
}

function applySession(session: AuthResponse | null) {
  accessToken = session?.accessToken ?? null
  if (refreshTimer) clearTimeout(refreshTimer)
  refreshTimer = null
  if (session) {
    // Renew two minutes before it expires.
    const ms = Math.max(30, session.expiresIn - 120) * 1000
    refreshTimer = setTimeout(() => {
      void refreshSession()
    }, ms)
  }
  emit(session)
}

function baseHeaders(extra?: HeadersInit): Headers {
  const h = new Headers(extra)
  h.set('X-Kairo-Client', 'web')
  if (accessToken) h.set('Authorization', `Bearer ${accessToken}`)
  if (clientLocation && !h.has('X-Kairo-Location')) h.set('X-Kairo-Location', clientLocation)
  return h
}

async function readError(res: Response): Promise<ApiError> {
  let message = ''
  try {
    const data = (await res.json()) as { error?: string }
    message = data?.error ?? ''
  } catch {
    /* no body */
  }
  if (!message) {
    if (res.status === 401) message = t('error.sessionExpired')
    else if (res.status === 403) message = t('error.forbidden')
    else if (res.status === 404) message = t('error.notFound')
    else if (res.status === 429) message = t('error.tooMany')
    else if (res.status >= 500) message = t('error.server')
    else message = t('error.generic')
  }
  return new ApiError(res.status, message)
}

async function doRefresh(): Promise<AuthResponse | null> {
  const run = async (): Promise<AuthResponse | null> => {
    try {
      const res = await fetch(`${BASE}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-Kairo-Client': 'web' },
        body: '{}',
      })
      if (!res.ok) return null
      return (await res.json()) as AuthResponse
    } catch {
      return null
    }
  }
  // Serialize refresh token rotation across tabs: two simultaneous refreshes
  // with the same cookie make the server invalidate the session.
  if (typeof navigator !== 'undefined' && 'locks' in navigator && navigator.locks) {
    return navigator.locks.request('kairo-refresh', run)
  }
  return run()
}

/** Renews the session (a single request in flight at a time). */
export function refreshSession(): Promise<AuthResponse | null> {
  if (!refreshPromise) {
    refreshPromise = doRefresh()
      .then((session) => {
        applySession(session)
        return session
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

export async function login(email: string, password: string): Promise<AuthResponse> {
  const res = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-Kairo-Client': 'web' },
    body: JSON.stringify({
      email,
      password,
      deviceName: describeBrowser(),
      platform: 'web',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    }),
  })
  if (!res.ok) {
    const err = await readError(res)
    // A 401 on login always means wrong credentials.
    if (res.status === 401) throw new ApiError(401, t('error.badCredentials'))
    throw err
  }
  const session = (await res.json()) as AuthResponse
  applySession(session)
  return session
}

/** What a family code opens (public, no session). null if it is invalid or has expired. */
export async function previewInvite(code: string, signal?: AbortSignal): Promise<InvitePreview | null> {
  const res = await fetch(`${BASE}/auth/invite/${encodeURIComponent(code)}`, {
    headers: { 'X-Kairo-Client': 'web' },
    signal,
  })
  if (res.status === 404) return null
  if (!res.ok) throw await readError(res)
  return (await res.json()) as InvitePreview
}

/**
 * Uses a family code: an invitation creates the account; an access code sets a new password.
 * Like login: the refresh token arrives in the httpOnly cookie and the session stays open in this browser.
 */
export async function join(body: { code: string; password: string; email?: string; displayName?: string }): Promise<AuthResponse> {
  const res = await fetch(`${BASE}/auth/join`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-Kairo-Client': 'web' },
    body: JSON.stringify({
      ...body,
      deviceName: describeBrowser(),
      platform: 'web',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    }),
  })
  if (!res.ok) throw await readError(res)
  const session = (await res.json()) as AuthResponse
  applySession(session)
  return session
}

export async function logout() {
  try {
    await fetch(`${BASE}/auth/logout`, { method: 'POST', credentials: 'include', headers: baseHeaders() })
  } catch {
    /* offline: close locally anyway */
  }
  applySession(null)
}

export function endSessionLocally() {
  applySession(null)
}

/** Authenticated fetch with a retry after refresh. Returns the raw Response. */
export async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const exec = () =>
    fetch(`${BASE}${path}`, {
      ...init,
      credentials: 'same-origin',
      headers: baseHeaders(init.headers),
    })
  let res = await exec()
  if (res.status === 401 && !path.startsWith('/auth/')) {
    const session = await refreshSession()
    if (!session) throw new ApiError(401, t('error.sessionExpired'))
    res = await exec()
    if (res.status === 401) {
      applySession(null)
      throw new ApiError(401, t('error.sessionExpired'))
    }
  }
  return res
}

type Body = Record<string, unknown> | unknown[] | FormData | undefined

async function request<T>(method: string, path: string, body?: Body, signal?: AbortSignal): Promise<T> {
  const init: RequestInit = { method, signal }
  if (body instanceof FormData) {
    init.body = body
  } else if (body !== undefined) {
    init.body = JSON.stringify(body)
    init.headers = { 'Content-Type': 'application/json' }
  }
  const res = await authFetch(path, init)
  if (!res.ok) throw await readError(res)
  if (res.status === 204) return undefined as T
  const ct = res.headers.get('content-type') ?? ''
  if (ct.includes('application/json')) return (await res.json()) as T
  return (await res.text()) as unknown as T
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>('GET', path, undefined, signal),
  post: <T>(path: string, body?: Body) => request<T>('POST', path, body ?? {}),
  patch: <T>(path: string, body: Body) => request<T>('PATCH', path, body),
  del: <T = { ok: boolean }>(path: string) => request<T>('DELETE', path),
  /** Binary response (audio, export). */
  blob: async (path: string, init: RequestInit = {}) => {
    const res = await authFetch(path, init)
    if (!res.ok) throw await readError(res)
    return res.blob()
  },
}

export function qs(params: Record<string, string | number | boolean | null | undefined>) {
  const u = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') u.set(k, String(v))
  }
  const s = u.toString()
  return s ? `?${s}` : ''
}

/**
 * Reads an SSE stream from a POST (`data: {json}` per event).
 * Resolves when the server closes the stream.
 */
export async function streamAgent(
  path: string,
  body: Record<string, unknown> | FormData,
  onEvent: (ev: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const init: RequestInit = { method: 'POST', signal, headers: { Accept: 'text/event-stream' } }
  if (body instanceof FormData) {
    init.body = body
  } else {
    init.body = JSON.stringify(body)
    init.headers = { Accept: 'text/event-stream', 'Content-Type': 'application/json' }
  }
  const res = await authFetch(path, init)
  if (!res.ok) throw await readError(res)
  if (!res.body) throw new ApiError(0, t('error.cantReadResponse'))

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const flush = (chunk: string) => {
    let data = ''
    for (const raw of chunk.split('\n')) {
      const line = raw.replace(/\r$/, '')
      if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, '')
    }
    if (!data) return
    try {
      onEvent(JSON.parse(data) as AgentEvent)
    } catch {
      /* corrupt event: ignored */
    }
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let idx = buffer.indexOf('\n\n')
    while (idx >= 0) {
      flush(buffer.slice(0, idx))
      buffer = buffer.slice(idx + 2)
      idx = buffer.indexOf('\n\n')
    }
  }
  if (buffer.trim()) flush(buffer)
}

function describeBrowser(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua)
    ? 'Edge'
    : /Firefox\//.test(ua)
      ? 'Firefox'
      : /Chrome\//.test(ua)
        ? 'Chrome'
        : /Safari\//.test(ua)
          ? 'Safari'
          : t('browser.fallback')
  const os = /Android/.test(ua)
    ? 'Android'
    : /iPhone|iPad/.test(ua)
      ? 'iOS'
      : /Mac OS X/.test(ua)
        ? 'macOS'
        : /Windows/.test(ua)
          ? 'Windows'
          : /Linux/.test(ua)
            ? 'Linux'
            : ''
  return os ? t('browser.onOs', { browser, os }) : browser
}
