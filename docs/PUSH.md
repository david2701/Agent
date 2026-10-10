# Avisos sin Firebase

Sí: Kairo tiene su propio sistema de avisos y no depende de Firebase ni de Google Play Services.
Funciona en tres niveles; cuanto más arriba, más inmediato con la app cerrada.

| Nivel | Cómo llega | Qué necesita |
|---|---|---|
| **1. Push propio (UnifiedPush)** | Al instante, con la app cerrada y sin gastar batería extra | Un servidor ntfy (incluido en `docker-compose.yml`) y la app ntfy en cada teléfono |
| **2. Conexión directa** | Al instante mientras la app está abierta, "Oye Kairo" está activo o activas *Conexión directa con tu servidor* | Nada más; usa un poco más de batería |
| **3. Alarmas locales y sincronización** | Recordatorios, avisos de eventos y resumen matinal suenan a su hora aunque no haya internet; el resto llega en la siguiente sincronización (cada 15 min) | Nada |

Los tres conviven: el teléfono reconoce cada aviso por su id y no lo muestra dos veces.

En **iPhone** no hay UnifiedPush: el nivel 1 es el push de Apple (APNs), también opcional
(ver [Push de Apple (APNs) para iPhone](#push-de-apple-apns-para-iphone)).

## Cómo funciona el push propio

```
Kairo (tu servidor) --Web Push cifrado--> ntfy (tu servidor) --conexión del teléfono--> app ntfy --> Kairo en el teléfono
```

- **UnifiedPush** es un estándar abierto para que las apps reciban push sin Firebase. La app ntfy actúa como
  "distribuidor": mantiene una sola conexión con tu servidor ntfy y entrega los mensajes a Kairo.
- Kairo cifra cada mensaje para el teléfono (RFC 8291, `aes128gcm`) y lo firma con VAPID (RFC 8292). ntfy sólo
  ve bytes cifrados: ni ntfy ni nadie en el camino puede leer tus recordatorios.
- La clave VAPID se genera sola en el primer uso y se guarda cifrada con `KAIRO_ENCRYPTION_KEY`.

## Ponerlo en marcha

1. En el servidor, define en `.env` la URL pública de ntfy y levántalo:
   ```bash
   # .env
   NTFY_PUBLIC_URL=https://ntfy.tudominio.com
   ```
   ```bash
   docker compose --profile push up -d
   ```
2. Agrega a tu Caddyfile el bloque `ntfy.tudominio.com` de [`deploy/Caddyfile.example`](../deploy/Caddyfile.example) y ejecuta `caddy reload`.
3. En cada teléfono de la familia:
   - Instala **ntfy** (F-Droid o Play Store).
   - En ntfy: *Ajustes > Servidor predeterminado* = `https://ntfy.tudominio.com`.
   - En Kairo: *Ajustes > Avisos al instante, sin Firebase > Configurar*, elige ntfy y toca *Enviar prueba*.

No hace falta crear temas ni suscripciones a mano: Kairo y ntfy los gestionan solos.

## Seguridad del servidor ntfy

- Los temas de UnifiedPush son aleatorios e imposibles de adivinar, y el contenido va cifrado.
- La configuración incluida no permite registrarse (`NTFY_ENABLE_SIGNUP=false`), guarda los mensajes 24 horas
  y sólo escucha en `127.0.0.1` (lo publica Caddy con HTTPS).
- Si quieres cerrarlo más, ntfy permite control de acceso (`auth-default-access: deny-all`) dejando escritura
  anónima sólo en los temas `up*`; consulta la documentación de ntfy sobre UnifiedPush.

### Qué endpoints acepta Kairo

El endpoint lo manda el teléfono, así que Kairo lo valida (al registrarlo y antes de cada envío) para que nadie pueda
usarlo para llegar a servicios de tu red:
- Debe ser `https` (`KAIRO_PUSH_ALLOW_HTTP=true` permite `http`, para pruebas).
- El host de `NTFY_PUBLIC_URL` (o de `NTFY_URL`) se acepta siempre, aunque resuelva a una IP de tu red local; es tu
  ntfy. Si ese host es local o está en `NO_PROXY`, Kairo llega a él sin pasar por `HTTPS_PROXY`.
- Cualquier otro host (ntfy.sh, otro distribuidor) tiene que resolver a direcciones públicas. Si usas un distribuidor
  en tu red con otro nombre, pon `KAIRO_PUSH_ALLOW_PRIVATE=true` (acepta redes privadas; nunca loopback ni `169.254.x.x`).
- Un endpoint o token de APNs que ya registró otra cuenta no se reasigna (`400`): cierra sesión en esa cuenta en el
  teléfono (o revoca su dispositivo en *Ajustes > Dispositivos*) antes de entrar con otra.

## Comprobar que funciona

- Dashboard: *Ajustes > Notificaciones* muestra cuántos teléfonos tienen push registrado.
- App: *Enviar prueba* manda un aviso sólo por push; si llega con la app cerrada, está listo.
- API: `POST /api/push/test` devuelve `{delivered}` (ver [API.md](API.md#push-propio-sin-firebase)).

## Push de Apple (APNs) para iPhone

Con la app de iOS cerrada, el único push que llega es el de Apple. Es **opcional**: sin APNs la app funciona con sus
notificaciones locales (recordatorios, avisos de eventos y resumen matinal programados en el propio iPhone, que suenan
aunque no haya internet), la conexión directa mientras está abierta y la actualización en segundo plano. Lo único que
se pierde es que los avisos de la familia lleguen al instante con la app cerrada.

```
Kairo (tu servidor) --HTTP/2 + token de la clave .p8--> APNs (Apple) --> iPhone (y el Apple Watch emparejado)
```

Necesitas una cuenta del Apple Developer Program (la misma con la que firmas la app, ver [IOS.md](IOS.md)).

### 1. Crear la clave `.p8`

1. En [developer.apple.com](https://developer.apple.com/account) entra a *Certificates, Identifiers & Profiles > Keys*
   y toca **+**.
2. Ponle un nombre (por ejemplo "Kairo push"), marca **Apple Push Notifications service (APNs)** y registra la clave.
   Si Apple te pide configurarla, elige el entorno *Sandbox & Production* para usar la misma clave en ambos.
3. Descarga el archivo `AuthKey_XXXXXXXXXX.p8`. **Sólo se puede descargar una vez**: guárdalo en un lugar seguro y
   nunca lo subas al repositorio (`*.p8` y `secrets/` están en `.gitignore`).
4. Anota el **Key ID** (10 caracteres, también va en el nombre del archivo) y tu **Team ID** (*Membership details*).

El App ID de la app debe tener la capacidad *Push Notifications*; con la firma automática de Xcode se activa sola,
porque la app ya trae el permiso `aps-environment`.

### 2. Configurar el servidor

```bash
# .env
APNS_KEY_ID=ABC123DEFG
APNS_TEAM_ID=TEAM123456
APNS_TOPIC=app.kairo.ios                    # KAIRO_BUNDLE_ID de ios/Config/Signing.xcconfig
APNS_PRIVATE_KEY_FILE=/run/secrets/apns.p8
```

Con Docker, monta el archivo en el contenedor (descomenta estas líneas del servicio `kairo` en `docker-compose.yml`):

```yaml
    volumes:
      - ./secrets/AuthKey_ABC123DEFG.p8:/run/secrets/apns.p8:ro
```

Si prefieres no usar un archivo, pon el contenido de la clave en `APNS_PRIVATE_KEY`, en una sola línea y con `\n` en
lugar de cada salto de línea:

```bash
awk 'NF {printf "%s\\n", $0}' AuthKey_ABC123DEFG.p8
```

Reinicia (`docker compose up -d`). El log dice `APNs activo para app.kairo.ios`; si falta un dato o la clave no es
válida, dice `APNs desactivado: ...` y Kairo sigue funcionando sin push de Apple.

### Sandbox y producción

| Cómo instalaste la app | Entorno | Servidor de Apple |
|---|---|---|
| Desde Xcode (compilación de depuración) | `sandbox` | `api.sandbox.push.apple.com` |
| TestFlight o App Store | `production` | `api.push.apple.com` |

La app dice al registrarse en qué entorno está y el servidor usa el de cada token; la misma clave `.p8` sirve para los
dos. Si un token no corresponde a su entorno o a la app, Apple responde `BadDeviceToken` o `DeviceTokenNotForTopic`
y Kairo lo borra; la app lo vuelve a registrar al iniciar sesión.

### Comprobar que funciona

- App: *Ajustes > Avisos* dice si el servidor tiene el push de Apple activo y cuántos dispositivos tienes registrados.
- API: `GET /api/push/apns` -> `{available: true, registeredDevices: 1}` (ver [API.md](API.md#push-de-apple-apns)).
- Cierra la app y toca *Enviar prueba* desde otro dispositivo, o llama a `POST /api/push/test`: `delivered` cuenta
  también los iPhone que aceptaron el aviso.

### Qué conviene saber

- Los recordatorios y avisos de eventos llegan como *urgentes* (`time-sensitive`), con los botones "Hecho" y
  "En 10 min" en los recordatorios. Cada aviso lleva el mismo id que la alarma local de la app, así que no se duplica.
- A diferencia de Web Push, el contenido de APNs no va cifrado de extremo a extremo: viaja por TLS hasta Apple, que
  puede ver el título y el texto del aviso.
- Los tokens se borran al cerrar sesión, al revocar el dispositivo en *Ajustes > Dispositivos* y cuando Apple
  avisa que ya no sirven.

## ntfy como canal adicional (opcional)

Aparte del push de la app, el servidor puede publicar los avisos en un tema de ntfy para quien prefiera verlos en
la propia app ntfy o en el escritorio: `NTFY_URL`, `NTFY_TOPIC` y `NTFY_TOKEN` en `.env` (tema del propietario), o
un tema por persona en *Ajustes*.

`NTFY_TOPIC` y `NTFY_TOKEN` son del propietario (`KAIRO_OWNER_EMAIL`): sólo sus avisos se publican con el token. Los
demás miembros pueden elegir su propio tema (letras, números, `-` y `_`), que Kairo publica **sin** token, y no pueden
usar el tema del propietario. Si tu ntfy exige autenticación para escribir (`auth-default-access: deny-all`), da
permiso de escritura anónima a los temas de los miembros (`ntfy access everyone tema-de-sofia write-only`) o que usen
temas en ntfy.sh.
