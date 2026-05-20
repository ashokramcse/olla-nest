# Olla Nest — Brand Guidelines

> **Version:** 1.0 · **Updated:** 2026-05

---

## 1. Name & Usage

| Form | Usage |
|---|---|
| **Olla Nest** | Standard written form — two words, title case |
| **OLLA·NEST** | Logo/display form — all caps, middle dot separator |
| **olla-nest** | File names, URLs, CLI, Docker image tags |
| **ollanest** | Domain, package names (no separator) |

Never write: `OllaNest`, `olla_nest`, `OLLANEST` (without dot), `Olla-Nest`.

---

## 2. Logo Mark

The Olla Nest mark is a **squircle** (`rx=11` on a 40×40 grid) containing three concentric signal arcs and a center dot. It represents a local-first AI signal hub — the idea that intelligence radiates outward from your own infrastructure.

### Construction

```
Outer arc:  M20 8  A12 12 0 1 1 8 20   stroke-width 2.5
Inner arc:  M20 13 A7  7  0 1 1 13 20  stroke-width 2.0
Center dot: cx=20 cy=20 r=2.2
```

All three elements use `stroke-linecap="round"` and share the same stroke color.

### Color variants

| Variant | Background | Strokes |
|---|---|---|
| **Default (Day/Night)** | `var(--ac)` | `var(--ac-text)` |
| **Static / GitHub / Favicon** | `#F5C800` | `#1a1600` |
| **On dark bg (monochrome)** | `#1c1c22` | `#F5C800` |
| **White bg (print)** | `#F5C800` | `#1a1600` |

### Minimum sizes

| Context | Minimum size |
|---|---|
| App topbar | 30 × 30 px |
| Favicon | 32 × 32 px |
| README / docs | 40 × 40 px isolated, 280 px wide full lockup |
| Print | 12 mm |

### Clear space

Maintain a minimum clear space equal to **¼ of the mark's width** on all sides.

---

## 3. Full Logo Lockup

The full lockup combines the mark with wordmark and optional tagline.

```
[mark]  OLLA·NEST
        // AI Workspace
```

- Mark and wordmark are vertically centered
- Wordmark: **Archivo Black**, all caps, letter-spacing 2px, `#F5C800`
- Tagline: **Inconsolata** (or Courier New for static SVG), `// AI Workspace`, muted color (`#888` or `var(--sub-text)`)
- Gap between mark and wordmark: `10–14px`

---

## 4. Color Palette

### Primary

| Token | Hex | Usage |
|---|---|---|
| `--ac` | `#F5C800` (default) | Accent — interactive elements, logo, highlights |
| `--ac-text` | `#1a1600` (dark on yellow) | Text/icons on accent backgrounds |

The accent color is **user-configurable** (10 preset swatches + custom). The default is Signal Yellow `#F5C800`.

### Preset swatches

| Name | Hex |
|---|---|
| Signal Yellow | `#F5C800` |
| Amber | `#F59E0B` |
| Coral | `#F97316` |
| Rose | `#F43F5E` |
| Violet | `#8B5CF6` |
| Sky | `#0EA5E9` |
| Teal | `#14B8A6` |
| Emerald | `#22C55E` |
| Slate | `#64748B` |
| Zinc | `#71717A` |

### Night mode surfaces (zinc hierarchy)

| Token | Hex | Role |
|---|---|---|
| `--bg` | `#0a0a0c` | Page background |
| `--nav-bg` | `#111115` | Sidebar / nav |
| `--bubble` | `#1c1c22` | Cards, panels, message bubbles |
| `--div` | `#27272e` | Hover states, dividers |
| `--border` | `#3f3f46` | Borders, separators |
| `--sub-text` | `#71717a` | Secondary / muted text |
| `--body-text` | `#e4e4e7` | Primary body text |

### Day mode surfaces

| Token | Hex | Role |
|---|---|---|
| `--bg` | `#f8f8fb` | Page background |
| `--nav-bg` | `#f0f0f4` | Sidebar / nav |
| `--bubble` | `#ffffff` | Cards, panels |
| `--border` | `#e2e2e8` | Borders |
| `--body-text` | `#18181b` | Primary body text |

---

## 5. Typography

### Font stack

| Role | Font | Fallback | CSS Token |
|---|---|---|---|
| Brand / display | Archivo Black | 'Arial Black', Arial | `--font-brand` |
| Body / UI | Archivo | system-ui, sans-serif | `--font-body` |
| Monospace / code | Inconsolata | 'Courier New', monospace | `--font-mono` |

Fonts are loaded via Google Fonts:
```html
<link href="https://fonts.googleapis.com/css2?family=Archivo+Black&family=Archivo:wght@400;500;600;700&family=Inconsolata:wght@400;500;600;700&display=swap" rel="stylesheet">
```

### Static SVG / GitHub rendering

When fonts cannot be loaded (GitHub SVG rendering, email, PDF), use system fallbacks:
- **Display:** `'Arial Black', 'Helvetica Neue', Arial, sans-serif`
- **Mono:** `'Courier New', Courier, monospace`

### Scale

| Element | Size | Weight | Font |
|---|---|---|---|
| Logo wordmark | 26px / 900 | Black | Archivo Black |
| Section headings | 20–24px | 700 | Archivo |
| Card headings | 15–16px | 700 | Archivo |
| Body | 14px | 400–500 | Archivo |
| Code | 13px | 400–500 | Inconsolata |
| Eyebrows / labels | 11–13px | 700 | Inconsolata, all-caps |

---

## 6. Iconography

All icons are **Lucide** (`lucide-react` or CDN). Default stroke-width: `1.5`. Use `var(--sub-text)` for decorative icons, `var(--ac)` for active/selected state.

Never use filled icons from other libraries next to Lucide stroke icons.

---

## 7. Components

### Buttons

| Variant | Background | Text | Border |
|---|---|---|---|
| Primary | `var(--ac)` | `var(--ac-text)` | none |
| Secondary | `var(--bubble)` | `var(--body-text)` | `var(--border)` |
| Ghost | transparent | `var(--body-text)` | `var(--border)` |
| Danger | `#ef4444` | white | none |

Border radius: `10px` (standard), `8px` (small), `14px` (large/pill).

### Cards / Panels

- Background: `var(--bubble)`
- Border: `1px solid var(--border)`
- Border radius: `14px` (standard), `20–24px` (login/modal)
- Box shadow (floating): `0 20px 60px -20px rgba(0,0,0,0.12)`

### Code blocks

- Background: `var(--nav-bg)` or `#0d1117` (night)
- Border: `1px solid var(--border)`
- Border radius: `10px`
- Language badge: colored pill, `var(--font-mono)`, 11px

---

## 8. Voice & Tone

| ✅ Do | ❌ Don't |
|---|---|
| Direct, confident | Hype or buzzword-heavy |
| Admin = control | Admin = restriction |
| "Your AI. Your rules." | "Powered by AI!" |
| Technical precision | Vague promises |
| Concise — say it in one line | Paragraph preambles |

### Product copy patterns

- Feature names: title case noun phrases — *Auto Router*, *Project Knowledge*, *Chat Memory*
- Status labels: concise, lowercase — `routing…`, `thinking…`, `approved`, `restricted`
- Error messages: explain the cause + what to do — not just "Error"
- Empty states: explain why + one action — not just "No data"

---

## 9. Do / Don't

### Logo

| ✅ Do | ❌ Don't |
|---|---|
| Use on accent or dark backgrounds | Place on busy photos |
| Use SVG format | Rasterise below 32px |
| Keep mark + wordmark proportions | Stretch or squish |
| Use provided color variants | Recolor arbitrarily |

### Color

| ✅ Do | ❌ Don't |
|---|---|
| Use accent for one primary action per view | Use accent for decorative backgrounds |
| Maintain the zinc surface hierarchy | Mix light surfaces with dark surfaces |
| Use muted text for secondary info | Use full-contrast text everywhere |

### Typography

| ✅ Do | ❌ Don't |
|---|---|
| Use Archivo Black only for display/logo | Set body copy in Archivo Black |
| Use Inconsolata for all code and labels | Use monospace for headings |
| Maintain size hierarchy | Use more than 3 size levels per view |

---

## 10. File References

| File | Purpose |
|---|---|
| `public/logo.svg` | App logo mark (CSS var colors, for use inside the app) |
| `public/favicon.svg` | Browser favicon (hardcoded `#F5C800`) |
| `docs/logo-readme.svg` | README / GitHub display (hardcoded colors, full lockup) |
| `docs/architecture.svg` | Architecture diagram (branded, system fonts) |
| `public/theme.js` | Color token engine — `applyTheme(hex, mode)` |
| `public/styles.css` | Full design system — all component CSS |
