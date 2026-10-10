import type { QueryClient } from '@tanstack/react-query'
import { api, ApiError } from './api'
import { t } from './i18n'
import { invalidateEntity } from './queries'
import type { Activity } from './types'

/**
 * Undoes the latest action on an entity by finding its record in Activity.
 * The server only allows undoing creations and deletions.
 */
export async function undoLatest(
  qc: QueryClient,
  entityType: string,
  entityId: string,
  kind: 'created' | 'deleted',
): Promise<void> {
  // The record is written in the same transaction, but leave some slack in case it shows up late.
  for (let attempt = 0; attempt < 3; attempt++) {
    const items = await api.get<Activity[]>('/activity?limit=30')
    const match = items.find(
      (a) => a.entityType === entityType && a.entityId === entityId && a.kind === kind && a.undoable && !a.undoneAt,
    )
    if (match) {
      await api.post(`/activity/${match.id}/undo`)
      invalidateEntity(qc, entityType)
      return
    }
    await new Promise((r) => setTimeout(r, 350))
  }
  throw new ApiError(404, t('undo.notFound'))
}

export async function undoActivity(qc: QueryClient, a: Activity) {
  await api.post(`/activity/${a.id}/undo`)
  invalidateEntity(qc, a.entityType)
}
