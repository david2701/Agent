import type { AgentEvent, AuthResponse, InvitePreview } from './types'

/**
 * Cliente HTTP de Kairo.
 * - El access token vive solo en memoria.
 * - El refresh token es una cookie httpOnly (path /api/auth): se renueva con POST /api/auth/refresh.
 * - Un 401 dispara un único refresh compartido y reintenta una vez; si falla, se cierra la sesión.
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
    // Renueva dos minutos antes de que expire.
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
    /* sin cuerpo */
  }
  if (!message) {
    if (res.status === 401) message = 'Tu sesión expiró. Vuelve a entrar.'
    else if (res.status === 403) message = 'No tienes permiso para esto.'
    else if (res.status === 404) message = 'No lo encontré.'
    else if (res.status === 429) message = 'Demasiados intentos. Espera un momento.'
    else if (res.status >= 500) message = 'El servidor tuvo un problema. Intenta de nuevo.'
    else message = 'No se pudo completar la solicitud.'
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
  // Serializa la rotación del refresh token entre pestañas: dos refresh simultáneos
  // con la misma cookie hacen que el servidor invalide la sesión.
  if (typeof navigator !== 'undefined' && 'locks' in navigator && navigator.locks) {
    return navigator.locks.request('kairo-refresh', run)
  }
  return run()
}

/** Renueva la sesión (una sola petición en vuelo a la vez). */
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
    if (res.status === 401) throw new ApiError(401, err.message.includes('ontraseña') ? err.message : 'Correo o contraseña incorrectos.')
    throw err
  }
  const session = (await res.json()) as AuthResponse
  applySession(session)
  return session
}

/** Qué abre un código de familia (público, sin sesión). null si no vale o ya venció. */
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
 * Usa un código de familia: con una invitación crea la cuenta; con un código de acceso define una contraseña nueva.
 * Igual que login: el refresh token llega en la cookie httpOnly y la sesión queda abierta en este navegador.
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
    /* sin red: igual cerramos localmente */
  }
  applySession(null)
}

export function endSessionLocally() {
  applySession(null)
}

/** fetch autenticado con reintento tras refresh. Devuelve la Response cruda. */
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
    if (!session) throw new ApiError(401, 'Tu sesión expiró. Vuelve a entrar.')
    res = await exec()
    if (res.status === 401) {
      applySession(null)
      throw new ApiError(401, 'Tu sesión expiró. Vuelve a entrar.')
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
  /** Respuesta binaria (audio, exportación). */
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
 * Lee un stream SSE de un POST (`data: {json}` por evento).
 * Resuelve cuando el servidor cierra el stream.
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
  if (!res.body) throw new ApiError(0, 'El navegador no pudo leer la respuesta.')

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
      /* evento corrupto: se ignora */
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
          : 'Navegador'
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
  return os ? `${browser} en ${os}` : browser
}
