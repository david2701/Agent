import { useMemo } from 'react'
import { useAuth } from './auth'
import { t, type TKey } from './i18n'
import { useFamily } from './queries'
import type { Member } from './types'

/** "Valentina Gomez" -> "Valentina" (for phrases like "Shared by Valentina"). */
export function firstName(name: string | null | undefined) {
  const n = (name ?? '').trim()
  return n.split(/\s+/)[0] || n
}

/** Initials for the avatar: "Valentina Gomez" -> "VG", "Sam" -> "S". */
export function initials(name: string | null | undefined) {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0].charAt(0)
  const last = parts.length > 1 ? parts[parts.length - 1].charAt(0) : ''
  return (first + last).toUpperCase()
}

/** Normalizes a family code to XXXX-XXXX (accepts lowercase, spaces, no dash, or the whole link: `?code=` or the legacy `?codigo=`). */
export function normalizeCode(raw: string) {
  let v = raw.trim()
  const fromLink = /(?:code|codigo)=([^&\s#]+)/i.exec(v)
  if (fromLink) v = decodeURIComponent(fromLink[1])
  const clean = v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8)
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean
}

export function isCompleteCode(code: string) {
  return /^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code)
}

/** True when the text looks like an invite link or contains a `code=` parameter. */
export function looksLikeInviteLink(raw: string) {
  return /(?:code|codigo)=/i.test(raw)
}

export function roleLabel(role: string) {
  return role === 'admin' || role === 'member' ? t(`role.${role}` as TKey) : role
}

/**
 * Family data already resolved for the UI: who I am, the other members and names by id.
 * Uses a single cached query (GET /family) shared by the whole app.
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
      /** There is more than one person in the household (otherwise sharing makes no sense and is hidden). */
      hasOthers: others.length > 0,
      member: (id: string | null | undefined) => (id ? byId.get(id) ?? null : null),
      /** Short name of a member, or null if unknown. */
      nameOf: (id: string | null | undefined) => {
        if (!id) return null
        const m = byId.get(id)
        return m ? firstName(m.displayName) : null
      },
      /** I created it (or it has no known owner). */
      isMine: (ownerId: string | null | undefined) => !ownerId || ownerId === me,
    }
  }, [data, user?.id])
  return { ...view, query }
}

export type FamilyView = ReturnType<typeof useFamilyInfo>
