# Kairo: visual language

**Design reading (Taste §0.B):** a personal productivity assistant (web dashboard, native Android, iOS and Wear OS apps)
for people who value calm, precise tools, with a restrained *quiet tech* language, built on Material 3 Expressive on
Android, SwiftUI with Liquid Glass on iOS, and custom tokens plus Tailwind v4 on the web.

Dials (Taste §1): `DESIGN_VARIANCE 4` (a product, not a landing page) · `MOTION_INTENSITY 5` · `VISUAL_DENSITY 5`.

## Brand
- Name: **Kairo** (from *kairós*, "the opportune moment"). Wake phrase: "Hey Kairo" (English) or "Oye Kairo" (Spanish).
- Mark: an open bone-colored ring with an amber dot in the gap, meaning "the moment". Files are in `design/icon/`.
- The ring is also the voice **orb**: it breathes at rest, deforms with your voice while listening, spins while thinking and ripples while speaking.

## Color (a single accent: amber)

| Token | Dark | Light | Use |
|---|---|---|---|
| `bg` | `#0E1013` | `#F3F4F5` | background |
| `surface` | `#15181C` | `#FCFCFD` | panels |
| `surface-2` | `#1C2025` | `#EEF0F2` | inputs, hover |
| `border` | `#262B31` | `#DFE2E6` | 1px dividers |
| `text` | `#ECEDEE` | `#14161A` | primary text |
| `text-muted` | `#9BA1A9` | `#5B616A` | secondary text (AA) |
| `text-faint` | `#6B717A` | `#858B94` | non-essential metadata |
| `accent` | `#F0A23B` | `#E0901F` | accent (fill) |
| `accent-strong` | `#F5B65E` | `#8F5300` | accent as text/link |
| `on-accent` | `#1A1206` | `#1A1206` | text on amber |
| `danger` | `#F0705F` | `#C2412F` | errors, urgent priority |
| `success` | `#5BC48A` | `#2E8A57` | completed |

Rules: cool neutrals (zinc), never pure black or white, no purple gradients, no neon glow.
Amber marks the primary action, the "listening" state and active elements. Nothing else competes with it.

## Typography
- **Geist** (sans, OFL) for everything; **Geist Mono** for times, dates and numbers.
- Web scale: 12 / 13 / 14 (body) / 16 / 20 / 28 / 40. Headings use `tracking-tight` at weight 600; body is 400.
- Android: the same families bundled in `res/font`, mapped to the Material 3 type scale.

## Shape
- Radii: controls 10px, cards and panels 16px, chips and the microphone button fully rounded (pill). One rule across the whole product.
- Almost no shadows. Hierarchy comes from surfaces and 1px borders.
- Cards only when elevation means something. Grouped lists use dividers and spacing.

## Motion
- Base curve `cubic-bezier(0.16, 1, 0.3, 1)`, 220-320 ms. Springs (stiffness 120-300, damping 20-26) on Android.
- Animation with a purpose: content entering, confirmation of agent actions (a card that appears), voice state (the
  orb) and dragging tasks. Nothing loops except the active orb.
- `prefers-reduced-motion` / Android's "Remove animations": everything becomes instant transitions or fades.

## Copy
- English by default, with Spanish as a fully supported second language. Warm, direct tone, in sentence case. No em dashes (—), no emojis, no filler phrases.
- Action labels of 1-2 words: "Save", "Talk", "Undo", "Connect".
