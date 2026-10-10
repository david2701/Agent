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
   Requiere JDK 21 y el Android SDK (plataforma 37). La versión release está firmada con la clave
   de depuración para instalación directa; para publicar en Play Store define tu propio keystore.
2. Instala en el teléfono (`adb install app-release.apk` o abriendo el archivo).
3. Abre Kairo, escribe la URL de tu servidor (`https://kairo.tudominio.com`), tu correo y contraseña.

## Primeros pasos recomendados (Ajustes)

| Ajuste | Para qué |
|---|---|
| **Oye Kairo** | Escucha continua de la frase de activación. Descarga una vez un modelo de voz offline (40 MB). |
| **Asistente del sistema** | Elige Kairo como *Asistente digital predeterminado*: mantén pulsado inicio/encendido para hablarle. |
| **Batería sin restricciones** | Samsung y otros cierran apps en segundo plano. Necesario para la escucha continua. |
| **Alarmas exactas** | Los recordatorios suenan a la hora exacta incluso sin internet. |
| **Ubicación "todo el tiempo"** | Opcional, para avisos al llegar o salir de lugares guardados. |
| **Vincular reloj** | Genera un código de 6 dígitos para iniciar sesión en Wear OS. |

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

## Avisos sin Firebase

Kairo no usa Firebase ni servicios de Google para notificar:
1. **Alarmas locales exactas**: el teléfono programa tus recordatorios (y los recurrentes) y suenan sin conexión.
2. **WebSocket** con tu servidor mientras la app está abierta o la escucha activa: avisos al instante y
   todo se actualiza en vivo (si creas algo en el dashboard, aparece en el teléfono).
3. **Sincronización** cada 15 minutos con WorkManager.
4. Opcional: **ntfy** autoalojado (`NTFY_URL`, `NTFY_TOPIC`) como canal adicional.

Los recordatorios por lugar usan las alertas de proximidad del sistema (sin Google Play Services).

## Plegables

| Postura | Diseño |
|---|---|
| Teléfono o Fold cerrado | Barra inferior; listas y detalle en pantallas separadas. |
| Fold abierto (vertical) | Riel lateral; Hoy en dos columnas; Notas, Chat y Listas con lista y detalle lado a lado. |
| Fold abierto (horizontal) / tablet | Tareas como tablero de 3 columnas; calendario mensual con agenda. |
| Modo mesa (semiabierto) | El orbe y la respuesta arriba del pliegue; teclado y micrófono abajo. |

Al doblar o desdoblar no se pierde nada: el estado vive en ViewModels.

## Capturas

Se generan sin emulador con Roborazzi contra un backend local:

```bash
cd android
./gradlew :app:recordRoborazziDebug     # salida en docs/screenshots/android
```

## Privacidad y seguridad en el teléfono

- La sesión (refresh token) se guarda cifrada con una clave del **Android Keystore**.
- HTTPS obligatorio (HTTP solo para pruebas con el emulador).
- Sin respaldos automáticos en la nube de los datos de la app.
- Las grabaciones de reuniones se suben por partes y se borran del teléfono al subirse.

## Wear OS

App independiente (funciona con Wi-Fi o LTE del reloj). Vincúlala con el código de 6 dígitos de
*Ajustes > Vincular reloj* en el teléfono. Toca el botón para hablar: Kairo responde en texto y,
si el reloj tiene altavoz, con voz. Los avisos del teléfono llegan al reloj como notificaciones.
