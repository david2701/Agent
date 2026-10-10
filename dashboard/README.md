# Kairo: web dashboard

The Kairo dashboard is built with React 19, strict TypeScript, Vite and Tailwind CSS v4. It follows the visual
language in `design/DESIGN.md` (light/dark tokens, Geist and Geist Mono, a single amber accent). The interface is
available in English (default) and Spanish: it follows the browser language and can be changed in
*Settings > Profile > Language*.

## Development

```bash
npm install
npm run dev        # http://localhost:5173, proxies /api (and /api/ws) to http://localhost:8080
```

The backend must be running on `:8080` (or point to another one with `KAIRO_API=http://localhost:8088 npm run dev`).
The session keeps the refresh token in an httpOnly cookie (`/api/auth`) and the access token in memory only; when the
dashboard opens it calls `POST /api/auth/refresh`.

## Build

```bash
npm run typecheck  # tsc --noEmit
npm run build      # typecheck + vite build -> dist/
npm run preview    # serves dist/ on :4173 with the same CSP as Ktor and the /api proxy
```

`dist/` is what Ktor serves as the SPA at `/`. There are no inline scripts or external resources: fonts are bundled
and the theme is applied before first paint by `public/theme-init.js` (compatible with `script-src 'self'`).

## Structure

```
src/
  App.tsx              routes (lazy-loaded per page), providers
  styles/index.css     color tokens, type scale, radii, @font-face, markdown prose
  lib/
    api.ts             fetch with in-memory token, single refresh across tabs, SSE reader
    auth.tsx theme.tsx session and theme (system, light, dark)
    i18n/              translations: en.ts is the source of truth, es.ts must have the same keys
    queries.ts         TanStack Query keys and hooks, invalidation per entity
    family.ts          cached household (GET /family): names by id, initials, XXXX-XXXX codes
    realtime.ts        WebSocket /api/ws with reconnection, reminder and family notices, notifications
    agent.ts voice.ts  agent turns (text and voice), recording that stops on silence
    audio.ts           MediaRecorder, microphone level, playback of /voice/speak
    recordingSession.ts  long recordings in 5-minute segments
    undo.ts            undo by finding the matching activity entry
  components/
    ui/                buttons, forms, native dialog, menu, toasts, copy, empty and loading states
    family.tsx         avatar with initials and "family" markers
    NoticesFeed.tsx    family Updates (Family page, header bell and Today card)
    layout/            sidebar, mobile navigation, command palette (Ctrl/Cmd + K)
    chat/              thread, action cards, composer
    VoiceOrb.tsx       voice orb (ring + amber dot) that reacts to audio
  pages/               Today, Chat, Notes, Tasks, Reminders, Calendar, Lists, Family, Memory,
                       Recordings, Activity, Settings, Login, Join (/join?code=, public), 404
```

Routes are English (`/today`, `/tasks`, `/family`, ...). The old Spanish routes (`/hoy`, `/tareas`, `/unirse?codigo=`,
...) redirect to the new ones, so existing links and bookmarks keep working.

## Adding a language

1. Copy `src/lib/i18n/en.ts` to `src/lib/i18n/<code>.ts` and translate the values (the type check fails if a key is missing).
2. Register it in `src/lib/i18n/index.tsx` (the `Lang` type and `DICTS`) and add it to the language selector in `src/pages/Settings.tsx`.

## Family

Several people share one server: personal items are private, and anything marked as family (notes, tasks, lists,
events and places) is visible and editable by everyone. `/family` shows the members, invitations (admins only),
family notices and Updates; the bell in the header opens the same Updates.
Someone who receives a code joins at `/join?code=XXXX-XXXX` (or from "I have an invite code" on the sign-in page):
an invitation creates their account and an access code sets a new password for them.

## Notes

- Respects `prefers-reduced-motion`: the orb stays static and transitions become instant.
- The dashboard sends `X-Kairo-Client: web` and does not announce the `timer` capability: timers requested from the
  agent are shown as "Timer on your phone".
