import { Crosshair, MapPin, Plus, Trash } from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { FamilyIcon } from '@/components/family'
import { Button, IconButton } from '@/components/ui/button'
import { SkeletonRows } from '@/components/ui/feedback'
import { Field, Input, Select, Switch } from '@/components/ui/form'
import { toast, toastError } from '@/components/ui/toast'
import { api, setClientLocation } from '@/lib/api'
import { useFamilyInfo } from '@/lib/family'
import { invalidateEntity, usePlaces } from '@/lib/queries'
import type { Place } from '@/lib/types'
import { undoLatest } from '@/lib/undo'

/** Lugares guardados para recordatorios por ubicación (nombre + coordenadas). */
export function PlacesManager() {
  const qc = useQueryClient()
  const places = usePlaces()
  const fam = useFamilyInfo()
  const [adding, setAdding] = useState(false)

  const share = useMutation({
    mutationFn: ({ p, shared }: { p: Place; shared: boolean }) => api.patch<Place>(`/places/${p.id}`, { shared }),
    onSuccess: (p) => {
      invalidateEntity(qc, 'place')
      toast({
        title: p.shared ? 'Lugar compartido con la familia' : 'El lugar vuelve a ser personal',
        description: p.shared ? `${p.name}: sirve para recordatorios de cualquiera en la familia.` : p.name,
      })
    },
    onError: (e) => toastError(e, 'No se pudo cambiar el lugar'),
  })

  const remove = useMutation({
    mutationFn: (p: Place) => api.del(`/places/${p.id}`).then(() => p),
    onSuccess: (p) => {
      invalidateEntity(qc, 'place')
      toast({
        title: 'Lugar eliminado',
        description: p.name,
        actions: [{ label: 'Deshacer', onClick: () => undoLatest(qc, 'place', p.id, 'deleted').catch((e) => toastError(e, 'No se pudo deshacer')) }],
      })
    },
    onError: (e) => toastError(e, 'No se pudo eliminar el lugar'),
  })

  const list = places.data ?? []

  return (
    <div>
      {places.isLoading ? (
        <SkeletonRows rows={2} />
      ) : list.length === 0 && !adding ? (
        <p className="text-base text-muted">Guarda lugares como Casa u Oficina para que Kairo te avise al llegar o al salir.</p>
      ) : (
        <>
          {list.length > 0 && fam.hasOthers && (
            <p className="mb-1 text-sm text-muted">Comparte un lugar con la familia para que también sirva en sus recordatorios.</p>
          )}
          <ul className="divide-y divide-line">
            {list.map((p) => {
              const mine = fam.isMine(p.ownerId)
              const pending = share.isPending && share.variables?.p.id === p.id
              return (
                <li key={p.id} className="flex items-center gap-3 py-2.5">
                  <MapPin className="size-[18px] shrink-0 text-faint" />
                  <div className="min-w-0 flex-1">
                    <p className="flex items-center gap-1.5 text-base text-fg">
                      <span className="truncate">{p.name}</span>
                      {p.shared && <FamilyIcon label="Familiar" />}
                    </p>
                    <p className="truncate font-mono text-xs text-faint">
                      {p.latitude.toFixed(4)}, {p.longitude.toFixed(4)} · {p.radiusM} m
                    </p>
                    {p.shared && !mine && <p className="truncate text-xs text-muted">Compartido por {fam.nameOf(p.ownerId) ?? 'otra persona'}</p>}
                  </div>
                  {mine && (fam.hasOthers || p.shared) && (
                    <Switch
                      checked={pending && share.variables ? share.variables.shared : p.shared}
                      disabled={pending}
                      onChange={(shared) => share.mutate({ p, shared })}
                      label={p.shared ? `Dejar de compartir ${p.name}` : `Compartir ${p.name} con la familia`}
                    />
                  )}
                  <IconButton label={`Eliminar ${p.name}`} size="sm" tone="danger" onClick={() => remove.mutate(p)}>
                    <Trash className="size-4" />
                  </IconButton>
                </li>
              )
            })}
          </ul>
        </>
      )}
      {adding ? (
        <PlaceForm onDone={() => setAdding(false)} />
      ) : (
        <Button size="sm" className="mt-3" icon={<Plus className="size-4" />} onClick={() => setAdding(true)}>
          Agregar lugar
        </Button>
      )}
    </div>
  )
}

function PlaceForm({ onDone }: { onDone: () => void }) {
  const qc = useQueryClient()
  const fam = useFamilyInfo()
  const [shared, setShared] = useState(false)
  const [name, setName] = useState('')
  const [lat, setLat] = useState('')
  const [lon, setLon] = useState('')
  const [radius, setRadius] = useState('150')
  const [error, setError] = useState<string | null>(null)
  const [locating, setLocating] = useState(false)

  const save = useMutation({
    mutationFn: () =>
      api.post<Place>('/places', {
        name: name.trim(),
        latitude: Number(lat),
        longitude: Number(lon),
        radiusM: Number(radius),
        ...(shared ? { shared: true } : {}),
      }),
    onSuccess: (p) => {
      invalidateEntity(qc, 'place')
      toast({ title: 'Lugar guardado', description: p.name, tone: 'success' })
      onDone()
    },
    onError: (e) => setError(e instanceof Error ? e.message : 'No se pudo guardar'),
  })

  const locate = () => {
    if (!navigator.geolocation) return setError('Este navegador no comparte la ubicación.')
    setLocating(true)
    setError(null)
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setLat(pos.coords.latitude.toFixed(6))
        setLon(pos.coords.longitude.toFixed(6))
        setClientLocation(pos.coords.latitude, pos.coords.longitude)
        setLocating(false)
      },
      (err) => {
        setLocating(false)
        setError(err.code === err.PERMISSION_DENIED ? 'Permite el acceso a la ubicación para usarla.' : 'No pude obtener tu ubicación.')
      },
      { enableHighAccuracy: true, timeout: 12000 },
    )
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const la = Number(lat)
    const lo = Number(lon)
    if (!name.trim()) return setError('Ponle un nombre al lugar.')
    if (!lat || !lon || Number.isNaN(la) || Number.isNaN(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180)
      return setError('Las coordenadas no son válidas. Usa tu ubicación o escríbelas como 6.2447 y -75.5906.')
    setError(null)
    save.mutate()
  }

  return (
    <form onSubmit={submit} className="mt-3 grid gap-3 rounded-control border border-line bg-bg p-3">
      <Field label="Nombre">
        {(id) => <Input id={id} value={name} onChange={(e) => setName(e.target.value)} placeholder="Casa" autoFocus />}
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Latitud">
          {(id) => <Input id={id} inputMode="decimal" value={lat} onChange={(e) => setLat(e.target.value)} className="font-mono" />}
        </Field>
        <Field label="Longitud">
          {(id) => <Input id={id} inputMode="decimal" value={lon} onChange={(e) => setLon(e.target.value)} className="font-mono" />}
        </Field>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Radio" className="w-36">
          {(id) => (
            <Select id={id} value={radius} onChange={(e) => setRadius(e.target.value)}>
              <option value="100">100 m</option>
              <option value="150">150 m</option>
              <option value="300">300 m</option>
              <option value="500">500 m</option>
              <option value="1000">1 km</option>
            </Select>
          )}
        </Field>
        <Button onClick={locate} loading={locating} icon={<Crosshair className="size-4" />}>
          Usar mi ubicación
        </Button>
      </div>
      {fam.hasOthers && (
        <label className="flex items-center gap-2.5 text-sm text-muted">
          <Switch checked={shared} onChange={setShared} label="Compartir con la familia" />
          Compartir con la familia
        </label>
      )}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onDone}>
          Cancelar
        </Button>
        <Button type="submit" variant="primary" loading={save.isPending}>
          Guardar lugar
        </Button>
      </div>
    </form>
  )
}
