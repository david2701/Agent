import { useMemo } from 'react'
import { useAuth } from './auth'
import { useFamily } from './queries'
import type { Member } from './types'

/** "Valentina Gómez" -> "Valentina" (para frases como "Compartida por Valentina"). */
export function firstName(name: string | null | undefined) {
  const n = (name ?? '').trim()
  return n.split(/\s+/)[0] || n
}

/** Iniciales para el avatar: "Valentina Gómez" -> "VG", "David" -> "D". */
export function initials(name: string | null | undefined) {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0].charAt(0)
  const last = parts.length > 1 ? parts[parts.length - 1].charAt(0) : ''
  return (first + last).toUpperCase()
}

/** Normaliza un código de familia a XXXX-XXXX (acepta minúsculas, espacios, sin guion o el enlace completo). */
export function normalizeCode(raw: string) {
  let v = raw.trim()
  const fromLink = /codigo=([^&\s#]+)/i.exec(v)
  if (fromLink) v = decodeURIComponent(fromLink[1])
  const clean = v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8)
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean
}

export function isCompleteCode(code: string) {
  return /^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code)
}

export const ROLE_LABEL: Record<string, string> = { admin: 'Administrador', member: 'Miembro' }

/**
 * Datos de la familia ya resueltos para la interfaz: quién soy, los demás miembros y nombres por id.
 * Usa una sola consulta en caché (GET /family) compartida por toda la app.
 */
export function useFamilyInfo() {
  const { user } = useAuth()
  const query = useFamily()
  const data = query.data
  const view = useMemo(() => {
    const members: Member[] = data?.members ?? []
    const me = data?.me ?? user?.id ?? null
    const byId = new Map(members.map((m) => [m.id, m]))
    const others = members.filter((m) => m.id !== me)
    return {
      household: data?.household ?? null,
      members,
      others,
      me,
      isAdmin: data?.isAdmin ?? false,
      /** Hay más de una persona en el hogar (si no, compartir no tiene sentido y se oculta). */
      hasOthers: others.length > 0,
      member: (id: string | null | undefined) => (id ? byId.get(id) ?? null : null),
      /** Nombre corto de un miembro, o null si no se conoce. */
      nameOf: (id: string | null | undefined) => {
        if (!id) return null
        const m = byId.get(id)
        return m ? firstName(m.displayName) : null
      },
      /** Lo creé yo (o no tiene dueño conocido). */
      isMine: (ownerId: string | null | undefined) => !ownerId || ownerId === me,
    }
  }, [data, user?.id])
  return { ...view, query }
}

export type FamilyView = ReturnType<typeof useFamilyInfo>
