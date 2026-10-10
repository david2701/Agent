# Familia en Kairo

Un mismo servidor para toda la familia. Cada persona entra con su cuenta y tiene lo suyo (notas, tareas,
recordatorios, memoria, conversaciones, grabaciones); además, lo que se marca como **familiar** lo ven y
editan todos en casa.

## Qué se comparte

| Se puede compartir | Ejemplo |
|---|---|
| **Eventos** | "Agenda una cita compartida con el pediatra para Sofía el jueves a las 10" |
| **Listas** | "Agrega huevos y arepas a nuestra lista del súper" |
| **Tareas** (con responsable) | "Que Valentina compre el regalo de la profesora antes del viernes" |
| **Notas** | "Guarda como nota familiar la clave del wifi de la casa" |
| **Lugares** | "Casa" familiar sirve para recordatorios por ubicación de todos |

Y entre personas:

| Acción | Ejemplo |
|---|---|
| **Recordatorio para otro** | "Recuérdale a Valentina el martes a las 2 llevar el carnet de salud" (suena en su teléfono) |
| **Recordatorio para todos** | "Recuérdanos a todos el domingo a las 8 sacar el reciclaje" |
| **Aviso** | "Avísale a Valentina que llego a las 8" · "Diles a todos que la cena está lista" |

Kairo entiende "compartido", "familiar", "de la familia", "de la casa", "para todos", "nuestro" y
"nuestra". Si no lo dices, lo que creas es personal. Cualquier cosa se puede compartir o volver
personal después ("comparte la lista del viaje con la familia").

## Privacidad

- Lo personal no aparece en la cuenta de nadie más: ni los demás miembros ni el administrador lo ven
  desde la suya.
- **Pero el administrador puede restablecer la contraseña de cualquier miembro** con un código de acceso
  (ver abajo), y quien use ese código entra a esa cuenta y ve todo lo de esa persona. Por eso no es una
  puerta trasera silenciosa: **toda la familia recibe un aviso** cuando se genera un código de acceso
  ("David generó un código de acceso para Valentina") y otro cuando se usa; además, las sesiones del
  miembro se cierran. Si recibes uno de estos avisos y no lo pediste, habla con quien administra la familia.
- Quien administra el servidor (tiene la base de datos y el `.env`) técnicamente puede acceder a todo:
  elige como administrador a alguien de confianza.
- Lo familiar lo pueden editar todos; sólo quien lo creó puede dejar de compartirlo.
- La memoria de Kairo ("recuerda que soy alérgico...") es siempre personal.
- Cada aviso llega sólo a las personas que corresponde, y el push va cifrado de punta a punta.

## Avisos de la familia

Cada miembro recibe un aviso cuando:
- alguien comparte un evento, una lista, una tarea o una nota;
- se mueve o se cancela un evento familiar;
- le asignan una tarea, o completan una tarea familiar que creó;
- otro miembro le programa un recordatorio o le envía un aviso;
- alguien nuevo se une a la familia;
- el administrador genera un código de acceso para alguien, o alguien usa uno.

Los avisos llegan al instante por push (ver [PUSH.md](PUSH.md)) o por la conexión en vivo de la app y el
dashboard; si no, aparecen en la siguiente sincronización del teléfono. También quedan en *Familia > Novedades*.

## Invitar a alguien

1. Quien administra entra al dashboard: *Familia > Invitar a alguien* (o en la app: *Más > Familia*).
2. Kairo muestra un código `XXXX-XXXX` (vale 7 días, un solo uso) y un enlace `https://kairo.tudominio.com/unirse?codigo=...`.
3. La otra persona abre el enlace, o en la app toca *Tengo un código*, y crea su cuenta con su correo y contraseña.

## Si alguien olvida su contraseña

El administrador genera un **código de acceso** para esa persona (*Familia > ⋯ > Generar código de acceso*,
vale 24 horas y un solo uso; generar otro anula el anterior). Con él, la persona define una contraseña nueva
y se cierran sus sesiones anteriores. Al generarlo, toda la familia (también esa persona) recibe un aviso;
cuando se usa, los demás reciben otro. Así nadie puede entrar en silencio a la cuenta de otro.

## Administradores

- Pueden invitar, generar códigos de acceso (con aviso a toda la familia), cambiar roles y quitar a alguien de la familia.
- Siempre queda al menos un administrador.
- Al quitar a alguien se borra su cuenta y sus datos personales; lo que había compartido pasa al
  administrador que lo quitó, para que nadie pierda la lista del súper ni los eventos familiares.

## Google Calendar

Cada persona conecta su propio Google Calendar. Para que los eventos familiares aparezcan también en Google
para todos, crea en Google un calendario "Familia", compártelo con las cuentas de tu familia y elígelo en
*Ajustes > Google Calendar > Calendario para eventos familiares*. Lo personal sigue yendo a tu calendario de siempre.
