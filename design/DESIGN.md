# Kairo: lenguaje visual

**Lectura de diseño (Taste §0.B):** asistente personal de productividad (dashboard web + app Android nativa)
para un único dueño que valora herramientas tranquilas y precisas, con un lenguaje *quiet tech* sobrio,
apoyado en Material 3 Expressive en Android y en tokens propios + Tailwind v4 en la web.

Diales (Taste §1): `DESIGN_VARIANCE 4` (producto, no landing) · `MOTION_INTENSITY 5` · `VISUAL_DENSITY 5`.

## Marca
- Nombre: **Kairo** (de *kairós*, "el momento oportuno"). Frase de activación: "Oye Kairo".
- Marca: anillo abierto color hueso + punto ámbar en el hueco = "el momento". Archivos en `design/icon/`.
- El anillo es también el **orbe** de voz: respira en reposo, se deforma con la voz al escuchar, gira al pensar, ondula al hablar.

## Color (un solo acento: ámbar)

| Token | Oscuro | Claro | Uso |
|---|---|---|---|
| `bg` | `#0E1013` | `#F3F4F5` | fondo |
| `surface` | `#15181C` | `#FCFCFD` | paneles |
| `surface-2` | `#1C2025` | `#EEF0F2` | inputs, hover |
| `border` | `#262B31` | `#DFE2E6` | divisores 1px |
| `text` | `#ECEDEE` | `#14161A` | texto principal |
| `text-muted` | `#9BA1A9` | `#5B616A` | secundario (AA) |
| `text-faint` | `#6B717A` | `#858B94` | metadatos no esenciales |
| `accent` | `#F0A23B` | `#E0901F` | acento (relleno) |
| `accent-strong` | `#F5B65E` | `#8F5300` | acento como texto/enlace |
| `on-accent` | `#1A1206` | `#1A1206` | texto sobre ámbar |
| `danger` | `#F0705F` | `#C2412F` | errores, prioridad urgente |
| `success` | `#5BC48A` | `#2E8A57` | completado |

Reglas: neutros fríos (zinc), nunca negro ni blanco puros, sin degradados morados, sin brillos neón.
El ámbar marca la acción principal, el estado "escuchando" y elementos activos. Nada más compite con él.

## Tipografía
- **Geist** (sans, OFL) para todo; **Geist Mono** para horas, fechas y números.
- Escala web: 12 / 13 / 14 (cuerpo) / 16 / 20 / 28 / 40. Títulos `tracking-tight`, peso 600; cuerpo 400.
- Android: mismas familias empaquetadas en `res/font`, mapeadas a la escala tipográfica de Material 3.

## Forma
- Radios: controles 10px, tarjetas/paneles 16px, chips y botón de micrófono en píldora completa. (Regla única en todo el producto.)
- Sombras casi nulas; jerarquía por superficie y bordes de 1px.
- Tarjetas solo cuando la elevación significa algo; listas agrupadas con divisores y espacio.

## Movimiento
- Curva base `cubic-bezier(0.16, 1, 0.3, 1)`, 220-320 ms; resortes (stiffness 120-300, damping 20-26) en Android.
- Animaciones con motivo: entrada de contenido, confirmación de acciones del agente (tarjeta que aparece),
  estado de voz (orbe), arrastrar tareas. Nada en bucle salvo el orbe activo.
- `prefers-reduced-motion` / "Quitar animaciones" de Android: todo a transiciones instantáneas o fundidos.

## Texto
- Español, tono cálido y directo. Sin guiones largos (—), sin emojis, sin frases de relleno.
- Etiquetas de acción de 1-2 palabras: "Guardar", "Hablar", "Deshacer", "Conectar".
