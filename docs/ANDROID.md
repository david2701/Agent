# App Android de Kairo

Nativa en Kotlin 2.4 con Jetpack Compose y **Material 3 Expressive**. Funciona en teléfonos
normales, plegables (Galaxy Z Fold/Flip, Pixel Fold) y tablets, y trae una app para **Wear OS**.

## Instalar

1. Descarga el APK desde los artefactos de la CI (*Actions > CI > kairo-apk*) o compílalo:
   ```bash
   cd android
   ./gradlew :app:assembleRelease      # teléfono: app/build/outputs/apk/release/app-release.apk
   ./gradlew :wear:assembleRelease     # reloj:    wear/build/outputs/apk/release/wear-release.apk
   ```
   Requiere JDK 21 y el Android SDK (plataforma 37). Sin keystore propio, la versión release se firma con
   la clave de depuración: sirve para probar, pero instala la familia con tu propia firma (ver abajo).

### Firma de release y tu servidor

Antes de instalar Kairo en los teléfonos de la familia, firma con tu propia clave. Android sólo acepta
actualizaciones firmadas con la misma clave: si cambias de clave después, hay que desinstalar y volver a
instalar (y se pierde la sesión). Guarda el archivo y las contraseñas en un lugar seguro, sin ellos no podrás
publicar actualizaciones.

```bash
keytool -genkeypair -v -keystore kairo-release.jks -alias kairo -keyalg RSA -keysize 4096 -validity 10000
```

Crea `android/keystore.properties` (está en `.gitignore`, nunca se sube):

```properties
storeFile=/ruta/a/kairo-release.jks
storePassword=tu-contraseña
keyAlias=kairo
keyPassword=tu-contraseña
```

o usa las variables `KAIRO_KEYSTORE_FILE`, `KAIRO_KEYSTORE_PASSWORD`, `KAIRO_KEY_ALIAS` y `KAIRO_KEY_PASSWORD`.
El teléfono y el reloj usan la misma clave. Para que la CI publique los APK ya firmados, agrega en GitHub
(*Settings > Secrets and variables > Actions*) los secretos `ANDROID_KEYSTORE_BASE64`
(`base64 -w0 kairo-release.jks`) y `ANDROID_KEYSTORE_PASSWORD`.

**Tu servidor**: el reloj se vincula contra la dirección con la que se compiló y el teléfono la trae escrita al
iniciar sesión. Compila con `KAIRO_SERVER_URL=https://kairo.tudominio.com ./gradlew assembleRelease` (o
`kairo.serverUrl=` en `android/gradle.properties`, o la variable del repositorio `KAIRO_SERVER_URL` para la CI).
2. Instala en el teléfono (`adb install app-release.apk` o abriendo el archivo).
3. Abre Kairo, escribe la URL de tu servidor (`https://kairo.tudominio.com`), tu correo y contraseña.
   Si te invitaron a una familia (o te dieron un código de acceso), toca **Tengo un código**.

## Primeros pasos recomendados (Ajustes)

| Ajuste | Para qué |
|---|---|
| **Oye Kairo** | Escucha continua de la frase de activación. Descarga una vez un modelo de voz offline (40 MB). |
| **Asistente del sistema** | Elige Kairo como *Asistente digital predeterminado*: mantén pulsado inicio/encendido para hablarle. |
| **Batería sin restricciones** | Samsung y otros cierran apps en segundo plano. Necesario para la escucha continua. |
| **Alarmas exactas** | Los recordatorios suenan a la hora exacta incluso sin internet. |
| **Ubicación "todo el tiempo"** | Opcional, para avisos al llegar o salir de lugares guardados. |
| **Vincular reloj** | Genera un código de 6 dígitos para iniciar sesión en Wear OS. |
| **Avisos al instante, sin Firebase** | Conecta Kairo con la app ntfy (UnifiedPush) para recibir avisos con la app cerrada. |
| **Familia** | Miembros, invitaciones y avisos del hogar (también en *Más > Familia*). |

Otros accesos: **tile** de ajustes rápidos "Kairo", **widget** con tu día y botón de hablar,
**atajo** al mantener pulsado el ícono, y **Compartir > Guardar en Kairo** desde cualquier app
(textos, enlaces o fotos: Kairo lee el texto de la imagen y crea notas, recordatorios o eventos).

## Cómo funciona "Oye Kairo"

- Un servicio en primer plano (tipo micrófono) escucha con **Vosk** dentro del teléfono.
  El audio **no sale del dispositivo** hasta detectar "Oye Kairo".
- Al detectarla suena un tono, Kairo graba tu frase y detecta cuándo terminas de hablar.
- La frase va a tu servidor: Whisper (Nan) la transcribe, el agente actúa y Kokoro (Nan) responde con voz.
- Si Kairo te pregunta algo, sigue escuchando sin que repitas la frase (desactivable).

Reglas de Android que debes conocer:
- La notificación "Kairo está atento" y el punto verde del micrófono son obligatorios.
- Desde Android 14, tras reiniciar el teléfono la escucha no puede arrancar sola:
  Kairo muestra una notificación; tócala (o abre la app) y vuelve a quedar atento.
- La detección de voz por hardware del sistema (la de "Ok Google") está reservada a apps del sistema; por eso Kairo usa su propio detector.

## Familia

Un hogar con varios perfiles. Cada quien tiene lo suyo y comparte lo que marca como **familiar**:
eventos, listas, tareas, notas y lugares. Lo personal es privado, también para quien administra.

- **Unirse**: en el login, *Tengo un código* revisa el código (`XXXX-XXXX`, da igual mayúsculas, espacios o guion)
  y muestra "Te invitaron a Familia ...": nombre (ya escrito), correo y contraseña de al menos 10 caracteres.
  Con un **código de acceso** (contraseña olvidada) pide solo la contraseña nueva y cierra las otras sesiones.
- **Pantalla Familia** (*Más* o *Ajustes*): nombre del hogar, miembros con su rol y "Tú", invitaciones pendientes,
  "Avisar a la familia" (mensaje que llega como notificación) y **Novedades** con "Marcar todo leído".
  Quien administra invita (nombre y rol), ve el código en grande con **Compartir invitación** (hoja de compartir de
  Android con el enlace y el código), revoca invitaciones, genera códigos de acceso, cambia roles y quita miembros
  (se borra su cuenta y lo personal; lo que había compartido pasa a quien lo quita).
- **En cada pantalla**: Notas (filtro Todas, Mías, Familiares e insignia "Familiar" o "de Valentina"), Tareas
  (Familiar, **Responsable** con iniciales en la tarjeta y el tablero, filtro "Asignadas a mí"), Listas, Calendario
  ("Evento familiar" al crear o editar y "agendado por"), Recordatorios (**Para**: tú u otro miembro; "Para X" o "De X")
  y Lugares (en Recordatorios). Solo quien creó algo puede dejar de compartirlo.
- **Hoy** muestra una tarjeta "Familia" con los últimos avisos sin leer (no aparece si estás solo; a quien
  administra le propone invitar).
- La familia se guarda en caché para mostrar nombres al instante y se recarga al abrir la app, al iniciar sesión
  y cuando el servidor avisa un cambio (`entity.changed: family`).

## Avisos sin Firebase

Kairo no usa Firebase ni servicios de Google para notificar. Hay tres niveles y conviven: cada aviso tiene un id
y el teléfono no lo muestra dos veces (sin importar si llegó por push, WebSocket, alarma local o sincronización).

| Nivel | Cuándo llega | Qué hace falta |
|---|---|---|
| **1. Push propio (UnifiedPush)** | Al instante, con Kairo cerrado y sin gastar batería extra | La app **ntfy** (F-Droid o Play Store) apuntando a tu ntfy (perfil `push` del `docker-compose.yml`) |
| **2. Conexión directa o "Oye Kairo"** | Al instante mientras el servicio corre | Activar *Conexión directa con tu servidor* (o la escucha); usa algo más de batería |
| **3. Alarmas locales y sincronización** | Recordatorios, avisos de eventos y resumen a su hora aunque no haya internet; el resto en la siguiente sincronización (15 min) | Nada |

1. **Push propio**: en *Ajustes > Avisos al instante, sin Firebase* toca **Configurar**; Kairo usa el distribuidor
   instalado (ntfy) y le pide un endpoint con la clave VAPID de tu servidor. El endpoint y las claves de cifrado
   (`p256dh`, `auth`) se registran en `POST /api/push/register`. El servidor cifra cada aviso para este teléfono
   (RFC 8291) y el conector lo descifra; el contenido es el mismo `{type, data}` del WebSocket y pasa por el mismo
   manejador. **Enviar prueba** dice cuántos dispositivos aceptaron el aviso. Al abrir la app y al iniciar sesión
   se renueva el registro; al cerrar sesión se borra. Detalles del servidor en [PUSH.md](PUSH.md).
2. **Conexión directa**: servicio en primer plano ligero (tipo `specialUse`) que solo mantiene abierto el WebSocket,
   con la notificación fija "Kairo conectado". Nunca arranca solo: lo activas tú y vuelve tras reiniciar.
   El servicio de "Oye Kairo" también mantiene esta conexión, igual que la app abierta.
3. **Alarmas locales exactas** para recordatorios (también los recurrentes y los que otro miembro te programó,
   con "De Valentina") y avisos de eventos, más la **sincronización** con WorkManager: `/sync/device` trae además
   los avisos de la familia sin leer de las últimas 48 h, y el teléfono muestra los que aún no vio.

Canales de notificación: Recordatorios, Familia (mensajes, tareas asignadas y cambios en lo compartido), Resumen del
día, Conexión directa, Escucha continua, Grabaciones y Temporizadores. Tocar un aviso de la familia abre la pantalla
relacionada (o Familia) y lo marca como leído.

Los recordatorios por lugar usan las alertas de proximidad del sistema (sin Google Play Services).
Aparte, el servidor puede publicar en un tema de **ntfy** (`NTFY_URL`, `NTFY_TOPIC`) como canal adicional.

## Plegables

| Postura | Diseño |
|---|---|
| Teléfono o Fold cerrado | Barra inferior; listas y detalle en pantallas separadas. |
| Fold abierto (vertical) | Riel lateral; Hoy en dos columnas; Notas, Chat y Listas con lista y detalle lado a lado; Familia con miembros a la izquierda y avisos a la derecha. |
| Fold abierto (horizontal) / tablet | Tareas como tablero de 3 columnas; calendario mensual con agenda. |
| Modo mesa (semiabierto) | El orbe y la respuesta arriba del pliegue; teclado y micrófono abajo. |

Al doblar o desdoblar no se pierde nada: el estado vive en ViewModels.

## Capturas

Se generan sin emulador con Roborazzi contra un backend local:

```bash
cd android
./gradlew :app:recordRoborazziDebug     # salida en docs/screenshots/android
```

Usa el backend de `KAIRO_TEST_SERVER` (por defecto `http://localhost:8088`) con un solo inicio de sesión por
ejecución. Las horas se muestran en `America/Bogota` (cámbialo con `KAIRO_TEST_TZ`). Las capturas de familia
crean una invitación temporal y la revocan al terminar.

## Privacidad y seguridad en el teléfono

- La sesión (refresh token) se guarda cifrada con una clave del **Android Keystore**.
- HTTPS obligatorio (HTTP solo para pruebas con el emulador).
- Sin respaldos automáticos en la nube de los datos de la app.
- Las grabaciones de reuniones se suben por partes y se borran del teléfono al subirse.

## Wear OS

App independiente (funciona con Wi-Fi o LTE del reloj). Vincúlala con el código de 6 dígitos de
*Ajustes > Vincular reloj* en el teléfono. Toca el botón para hablar: Kairo responde en texto y,
si el reloj tiene altavoz, con voz. Los avisos del teléfono llegan al reloj como notificaciones.
