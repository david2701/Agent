Spanish version of the docs. The English docs in `../` are the source of truth and may be newer.

# Conectar Google Calendar

Kairo tiene su propio calendario desde el primer día. Cuando conectes Google Calendar,
la sincronización es en los dos sentidos:

- Los eventos que crees en Kairo (por voz, app o dashboard) se suben a tu calendario de Google.
- Los cambios en Google (desde el móvil, Gmail, etc.) llegan a Kairo cada 5 minutos o con "Sincronizar ahora".
- Ventana sincronizada: 30 días hacia atrás y 180 hacia adelante.

## 1. Crear las credenciales en Google Cloud (una sola vez, ~10 minutos)

1. Entra a <https://console.cloud.google.com/> y crea un proyecto (por ejemplo "Kairo").
2. **APIs y servicios > Biblioteca**: busca **Google Calendar API** y pulsa **Habilitar**.
3. **APIs y servicios > Pantalla de consentimiento de OAuth** (Google Auth Platform):
   - Tipo de usuario: **Externo**.
   - Nombre de la app: Kairo. Correo de asistencia: el tuyo.
   - Permisos (scopes): agrega
     `https://www.googleapis.com/auth/calendar.events` y
     `https://www.googleapis.com/auth/calendar.calendarlist.readonly`.
4. **Importante: publica la app** (Estado de publicación > **Publicar app / En producción**).
   Mientras esté en modo *Prueba*, Google invalida el permiso cada **7 días** y tendrías
   que reconectar. Para uso personal no necesitas verificación de Google: al conectar
   verás un aviso de "Google no verificó esta app"; pulsa *Configuración avanzada > Ir a Kairo*.
5. **APIs y servicios > Credenciales > Crear credenciales > ID de cliente de OAuth**:
   - Tipo: **Aplicación web**.
   - URI de redireccionamiento autorizado:
     `https://kairo.tudominio.com/api/integrations/google/callback`
     (el dashboard muestra la URI exacta en Ajustes > Google Calendar).
6. Copia el **ID de cliente** y el **Secreto de cliente**.

## 2. Configurar el servidor

En tu `.env`:

```env
GOOGLE_CLIENT_ID=1234567890-xxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-xxxx
KAIRO_PUBLIC_URL=https://kairo.tudominio.com
```

Reinicia: `docker compose up -d`.

## 3. Conectar

Dashboard > **Ajustes > Google Calendar > Conectar**. Acepta los permisos y vuelve a Kairo.
Puedes elegir qué calendario usar (por defecto, el principal) y desconectar cuando quieras.

La conexión se hace desde el dashboard y termina en **el mismo navegador** donde pulsaste *Conectar*
(en 10 minutos como mucho): Kairo deja una cookie temporal en ese navegador y el regreso desde Google sólo
vale con ella. Si abres el enlace de Google en otro navegador o dispositivo verás "Estado OAuth inválido";
vuelve a pulsar *Conectar*. Las apps de Android e iPhone usan la conexión que hagas desde el dashboard.

## Seguridad

- El refresh token de Google se guarda **cifrado** (AES-256-GCM con `KAIRO_ENCRYPTION_KEY`).
- Kairo solo pide acceso a eventos y a la lista de calendarios; no lee tu correo ni tus contactos.
- El regreso de Google (`state`) va firmado y atado a una cookie HttpOnly del navegador que inició la
  conexión, y sirve una sola vez: nadie puede conectar su Google a tu cuenta (ni el tuyo a la suya)
  mandándote un enlace.
- Al desconectar, Kairo revoca el token en Google y lo borra.

## Detalles

- Los eventos que vienen de Google se marcan con un ícono de sincronización.
- Si editas un evento en Kairo y en Google a la vez, gana el cambio local pendiente
  (se envía en la siguiente sincronización).
- Los eventos recurrentes creados en Kairo se suben con su regla (RRULE); los recurrentes de
  Google llegan como ocurrencias individuales dentro de la ventana.
