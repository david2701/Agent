# Avisos sin Firebase

Sí: Kairo tiene su propio sistema de avisos y no depende de Firebase ni de Google Play Services.
Funciona en tres niveles; cuanto más arriba, más inmediato con la app cerrada.

| Nivel | Cómo llega | Qué necesita |
|---|---|---|
| **1. Push propio (UnifiedPush)** | Al instante, con la app cerrada y sin gastar batería extra | Un servidor ntfy (incluido en `docker-compose.yml`) y la app ntfy en cada teléfono |
| **2. Conexión directa** | Al instante mientras la app está abierta, "Oye Kairo" está activo o activas *Conexión directa con tu servidor* | Nada más; usa un poco más de batería |
| **3. Alarmas locales y sincronización** | Recordatorios, avisos de eventos y resumen matinal suenan a su hora aunque no haya internet; el resto llega en la siguiente sincronización (cada 15 min) | Nada |

Los tres conviven: el teléfono reconoce cada aviso por su id y no lo muestra dos veces.

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

## Comprobar que funciona

- Dashboard: *Ajustes > Notificaciones* muestra cuántos teléfonos tienen push registrado.
- App: *Enviar prueba* manda un aviso sólo por push; si llega con la app cerrada, está listo.
- API: `POST /api/push/test` devuelve `{delivered}` (ver [API.md](API.md#push-propio-sin-firebase)).

## ntfy como canal adicional (opcional)

Aparte del push de la app, el servidor puede publicar los avisos en un tema de ntfy para quien prefiera verlos en
la propia app ntfy o en el escritorio: `NTFY_URL`, `NTFY_TOPIC` y `NTFY_TOKEN` en `.env` (tema del propietario), o
un tema por persona en *Ajustes*.
