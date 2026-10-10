# Kairo: panel web

Dashboard de Kairo en React 19 + TypeScript (estricto) + Vite + Tailwind CSS v4. Toda la interfaz está en español y
sigue el lenguaje visual de `design/DESIGN.md` (tokens claro/oscuro, Geist y Geist Mono, un solo acento ámbar).

## Desarrollo

```bash
npm install
npm run dev        # http://localhost:5173, con proxy /api (y /api/ws) hacia http://localhost:8080
```

El backend debe estar corriendo en `:8080` (o indica otro con `KAIRO_API=http://localhost:8088 npm run dev`). La sesión usa el refresh token en cookie httpOnly (`/api/auth`) y el
access token solo en memoria; al abrir el panel se llama a `POST /api/auth/refresh`.

## Compilar

```bash
npm run typecheck  # tsc --noEmit
npm run build      # typecheck + vite build -> dist/
npm run preview    # sirve dist/ en :4173 con la misma CSP que Ktor y el proxy /api
```

`dist/` es lo que Ktor sirve como SPA en `/`. No hay scripts en línea ni recursos externos: las fuentes están
empaquetadas y el tema se aplica antes del primer pintado con `public/theme-init.js` (compatible con `script-src 'self'`).

## Estructura

```
src/
  App.tsx              rutas (carga diferida por página), proveedores
  styles/index.css     tokens de color, escala tipográfica, radios, @font-face, prosa markdown
  lib/
    api.ts             fetch con token en memoria, refresh único entre pestañas, lector SSE
    auth.tsx theme.tsx sesión y tema (sistema, claro, oscuro)
    queries.ts         claves e hooks de TanStack Query, invalidación por entidad
    realtime.ts        WebSocket /api/ws con reconexión, avisos de recordatorios y notificaciones
    agent.ts voice.ts  turnos del agente (texto y voz), grabación con corte por silencio
    audio.ts           MediaRecorder, nivel del micrófono, reproducción de /voice/speak
    recordingSession.ts  grabaciones largas por segmentos de 5 min
    undo.ts            deshacer buscando la actividad correspondiente
  components/
    ui/                botones, formularios, diálogo nativo, menú, avisos, estados vacíos y de carga
    layout/            barra lateral, navegación móvil, paleta de comandos (Ctrl/Cmd + K)
    chat/              hilo, tarjetas de acción, compositor
    VoiceOrb.tsx       orbe de voz (anillo + punto ámbar) que reacciona al audio
  pages/               Hoy, Chat, Notas, Tareas, Recordatorios, Calendario, Listas, Memoria,
                       Grabaciones, Actividad, Ajustes, Login, 404
```

## Notas

- Respeta `prefers-reduced-motion`: el orbe queda estático y las transiciones se vuelven instantáneas.
- El panel envía `X-Kairo-Client: web` y no anuncia la capacidad `timer`: los temporizadores que pida el agente se
  muestran como "Temporizador en el teléfono".
