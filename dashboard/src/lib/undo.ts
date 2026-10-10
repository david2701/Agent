import type { QueryClient } from '@tanstack/react-query'
import { api, ApiError } from './api'
import { invalidateEntity } from './queries'
import type { Activity } from './types'

/**
 * Deshace la acción más reciente sobre una entidad buscando su registro en Actividad.
 * El servidor solo permite deshacer creaciones y borrados.
 */
export async function undoLatest(
  qc: QueryClient,
  entityType: string,
  entityId: string,
  kind: 'created' | 'deleted',
): Promise<void> {
  // El registro se escribe en la misma transacción, pero damos margen por si llega tarde.
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
  throw new ApiError(404, 'No encontré esa acción para deshacerla.')
}

export async function undoActivity(qc: QueryClient, a: Activity) {
  await api.post(`/activity/${a.id}/undo`)
  invalidateEntity(qc, a.entityType)
}
