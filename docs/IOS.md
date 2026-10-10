# Kairo para iPhone, iPad y Apple Watch

App nativa en SwiftUI (Swift 6, iOS 27 y watchOS 27) que se conecta a tu servidor de Kairo, igual que la
app de Android. Todo el código está en [`ios/`](../ios), y el proyecto de Xcode ya viene generado.

## Qué incluye

- **Hoy**: saludo, clima, el orbe para hablar, lo que sonó, la tarjeta de la familia, agenda, pendientes
  (se completan con un toque), recordatorios del día, listas y notas recientes.
- **Voz**: conversación a pantalla completa con el orbe animado, transcripción, tarjetas de lo que hizo
  Kairo y la respuesta hablada (Whisper y Kokoro en Nan, a través de tu servidor). Si Kairo te pregunta algo,
  sigue escuchando. **"Oye Kairo"** se reconoce dentro del iPhone (reconocimiento de voz en el dispositivo);
  el audio no sale del teléfono hasta que dices la frase.
- **Chat** con historial, respuestas en streaming e imágenes (la cámara o la fototeca van a `/capture`).
- **Tareas** (lista por estado en el iPhone, tablero con arrastrar y soltar en el iPad), **Calendario**
  (mes y agenda por horas), **Recordatorios** (por hora, repetidos o por lugar, también para otro miembro),
  **Listas**, **Notas** (búsqueda por significado y markdown), **Memoria**, **Grabaciones** de reuniones,
  **Actividad** con deshacer, **Familia** y **Ajustes**.
- **Familia**: lo compartido lleva la casita y el nombre de quien lo creó; invitaciones y códigos de acceso,
  avisos a la familia y novedades. Unirse con un código o con el enlace `…/unirse?codigo=XXXX-XXXX`.
- **Widgets**: Hoy (pequeño, mediano, grande, extragrande y pantalla bloqueada), Siguiente evento con cuenta
  regresiva, Lista interactiva (marcas sin abrir la app) y Hablar con Kairo.
- **Live Activities** en la Isla Dinámica y la pantalla bloqueada: la conversación por voz, la grabación de
  una reunión, el próximo evento (cuando falta menos de 45 minutos) y los **temporizadores y alarmas** que
  pides ("pon un temporizador de 10 minutos"), creados con AlarmKit: suenan como los del Reloj, aunque el
  iPhone esté en silencio, y se pausan o detienen desde la Isla Dinámica.
- **Centro de control y pantalla bloqueada**: controles "Hablar con Kairo" y "Mi día".
- **Siri y Atajos**: "Oye Siri, habla con Kairo", "Oye Siri, pregúntale a Kairo" (responde sin abrir la app),
  "Abre Kairo en Tareas". También sirven para el **botón de Acción**.
- **Guardar en Kairo** desde la hoja de compartir de cualquier app: textos, enlaces e imágenes. Kairo decide
  si es una nota, una tarea, un evento o un elemento de una lista, y te muestra lo que hizo.
- **Apple Watch**: hablar con Kairo desde el reloj (graba hasta que te callas y lee la respuesta), Hoy,
  listas para marcar, y complicaciones (hablar, siguiente evento y pendientes). Se vincula solo con el iPhone
  cerca, o con el código de Ajustes > Apple Watch (sirve para relojes con datos móviles).
- **Avisos**: los recordatorios, los avisos de eventos y el resumen de la mañana se programan en el iPhone y
  suenan aunque no haya internet. Los avisos de la familia llegan al instante con la app abierta (WebSocket),
  al actualizar en segundo plano, y con el push de Apple si lo configuras en el servidor (ver más abajo).
- **Recordatorios por lugar** con `CLMonitor` (hasta 20 lugares a la vez), aunque la app esté cerrada.

## iPhone, iPad y iPhone Duo

El diseño se decide por el *size class*, no por el modelo, como recomienda Apple:

| Pantalla | Diseño |
|---|---|
| iPhone, pantalla exterior del iPhone Duo, iPad en Slide Over | Pestañas (Hoy, Chat, Tareas, Calendario, Más) con Liquid Glass y la barra "Pregúntale a Kairo" encima |
| iPad, pantalla interior del iPhone Duo, iPhone grande en horizontal | Barra lateral con todas las secciones y el botón "Hablar con Kairo" (⌘K) |

En pantallas anchas, Hoy, Calendario, Notas, Listas y Chat muestran dos columnas, y Tareas un tablero.
La app admite todas las orientaciones y varias ventanas en el iPad, y no usa `UIScreen.main`, así que se
adapta sola al abrir o cerrar el iPhone Duo. Con un teclado, ⌘K abre la voz y ⌘N crea un elemento.

## Compilar en tu Mac

Necesitas **Xcode 27** (trae el SDK de iOS 27 y watchOS 27).

1. Abre `ios/Config/Signing.xcconfig` y pon:
   - `DEVELOPMENT_TEAM`: tu Team ID (Xcode > Settings > Accounts, o developer.apple.com > Membership).
   - `KAIRO_BUNDLE_ID`: un identificador tuyo, por ejemplo `com.tunombre.kairo`. Los widgets, compartir y el
     reloj cuelgan de él (`.widgets`, `.share`, `.watchkitapp`).
   - `KAIRO_APP_GROUP` y `KAIRO_WATCH_APP_GROUP`: `group.` + tu identificador.
   - `KAIRO_KEYCHAIN_GROUP`: tu identificador + `.shared`.
   - `KAIRO_DEFAULT_SERVER` (opcional): la dirección de tu servidor, para que aparezca escrita al iniciar sesión.
2. Abre `ios/Kairo.xcodeproj`. Xcode descarga y compila el paquete local `KairoKit` (no hay dependencias externas).
3. Elige el esquema **Kairo** y tu iPhone, y pulsa ▶. La primera vez, en el iPhone: Ajustes > General >
   VPN y gestión de dispositivos > confía en tu cuenta, y activa el Modo de desarrollador.
4. La app del reloj se instala con la del iPhone (app Watch > Kairo > Instalar), o eligiendo el esquema
   **KairoWatch** con el reloj conectado.

Con la firma automática, Xcode crea los identificadores, el App Group y los perfiles. Para push, App Groups
(los widgets con tus datos), Live Activities y el reloj necesitas el **Apple Developer Program**. Con una
cuenta gratuita puedes probar la app en tu iPhone durante 7 días si quitas en *Signing & Capabilities* las
capacidades que Xcode marque como no disponibles (por ejemplo, Push Notifications).

Si cambias `ios/project.yml` (archivos nuevos, targets, ajustes), regenera el proyecto:

```bash
brew install xcodegen
cd ios && xcodegen
```

### Estructura

| Carpeta | Contenido |
|---|---|
| `ios/KairoKit` | Paquete Swift (SPM). `KairoCore`: modelos, cliente del API con SSE y WebSocket, fechas en español, lógica de voz y el resumen para widgets (sólo Foundation; sus pruebas corren también en Linux). `KairoUI`: colores, Geist, el orbe y componentes. |
| `ios/App` | La app: estado global, voz, "Oye Kairo", grabaciones, notificaciones, geovallas, AlarmKit, atajos de Siri y todas las pantallas. |
| `ios/Widgets` | Widgets, controles y Live Activities. |
| `ios/Share` | Extensión "Guardar en Kairo". |
| `ios/Watch`, `ios/WatchWidgets` | App y complicaciones del Apple Watch. |
| `ios/Shared` | Código común a la app y sus extensiones (configuración, intents, tipos de las Live Activities). |
| `ios/ci` | Servidor de prueba y capturas automáticas en el simulador. |

## Push de Apple (opcional)

Sin push, Kairo funciona: los recordatorios y eventos se programan en el iPhone, y los avisos de la familia
llegan con la app abierta o al actualizar en segundo plano. Para que lleguen al instante con la app cerrada,
configura APNs en tu servidor (ver [PUSH.md](PUSH.md)) con una clave `.p8` de tu cuenta de desarrollador.
Las compilaciones de Xcode usan el entorno *sandbox*; las de TestFlight y la App Store, *production*. La app
registra su token en `POST /api/push/apns/register` y en Ajustes > Avisos ves si el servidor lo tiene activo.

## Privacidad y seguridad

- La sesión se guarda en el llavero del iPhone. Los widgets y la extensión de compartir tienen **su propia
  sesión** (en un llavero compartido), así nunca se pisan las renovaciones con la app; al cerrar sesión se
  cierran todas.
- Sólo se piden los permisos al usarlos: micrófono al hablar, reconocimiento de voz para "Oye Kairo",
  ubicación para guardar lugares, y alarmas al poner el primer temporizador.
- El reloj tiene su propia sesión, que puedes cerrar desde Ajustes > Dispositivos con sesión.

## Pruebas y CI

- `cd ios/KairoKit && swift test`: pruebas de los modelos (con respuestas reales anonimizadas del servidor),
  fechas, voz, SSE y avisos. Corren en macOS y en Linux.
- El workflow [`ios.yml`](../.github/workflows/ios.yml) compila la app, los widgets, la extensión y el reloj
  con Xcode 27 en cada cambio (unos 3 minutos).
- [`ios-capturas.yml`](../.github/workflows/ios-capturas.yml), que se lanza a mano (*Actions > iOS capturas >
  Run workflow*), toma capturas en el simulador de iPhone (y de iPad si lo marcas) contra un servidor de prueba
  (`ios/ci/mock_server.py`) y las deja en el artefacto *capturas-ios*.

## Limitaciones conocidas

- La app se compiló y se probó en el simulador, pero todavía no en un iPhone, iPad o Apple Watch físicos.
- "Oye Kairo" escucha mientras Kairo está abierto o te está hablando. iOS no permite que una app escuche
  siempre en segundo plano como en Android; para eso están Siri, el botón de Acción y el control
  "Hablar con Kairo".
- Las zonas reservadas de la pantalla del iPhone Duo (`ReservedRegion`, iOS 27.1) todavía no se usan: el
  diseño se adapta por tamaño de ventana y áreas seguras.
