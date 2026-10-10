# Skills del proyecto

Las skills de diseño de [Taste](https://www.tasteskill.dev/) (MIT) están instaladas aquí con su CLI oficial:

```bash
npx skills add Leonxlnx/taste-skill --skill '*' --agent claude-code -y
```

`skills-lock.json` (en la raíz) fija la versión de cada una. Para restaurarlas en otra máquina:
`npx skills experimental_install`. Para actualizarlas: `npx skills update -p`.

Las que usa Kairo:

| Skill | Para qué |
|---|---|
| `design-taste-frontend` | Reglas base del dashboard: tipografía, color, layout, movimiento y estados completos. |
| `minimalist-ui` | Lenguaje editorial sobrio para las vistas de lectura (notas, resumen del día). |
| `redesign-existing-projects` | Auditoría antes de cambiar una pantalla existente. |
| `full-output-enforcement` | Entregables completos, sin placeholders. |
| `imagegen-frontend-mobile` | Referencias visuales para pantallas de la app (sólo genera imágenes). |

El resto (`gpt-taste`, `high-end-visual-design`, `stitch-design-taste`, `brandkit`, etc.) se instala con el
paquete y queda disponible para trabajos futuros.

Nota: Taste declara fuera de alcance los dashboards densos y las apps nativas. Por eso el dashboard aplica
sus reglas sobre los tokens de `design/DESIGN.md` y la app Android usa Material 3 Expressive como sistema
base, con la misma paleta y tipografía.
