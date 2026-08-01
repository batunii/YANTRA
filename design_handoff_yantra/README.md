# Handoff: Yantra — task + notes app (visual/UX pass)

## Overview
**Yantra** ("tool" in Hindi; also *Yet Another Notes, Todos & Reminder App*) is an offline-first, Superlist-style task + notes app for Android phone & tablet. Tasks and documents are the same thing: any list holds a free mix of tasks, text, headings, hand-drawn ink, and images; any task can be opened *as a page* and gain its own children (recursive). On top of that: typed properties, live "smart lists" (saved queries), and a built-in pomodoro/focus timer with history & stats.

This bundle is the **visual + UX design pass** — it elevates look, feel, motion, and micro-interactions. It does **not** change the information architecture, data model, or the "smart lists are computed views" behavior (see §10 of `original_product_brief.md` for the hard constraints).

## About the design files
The file `Yantra App.dc.html` is a **design reference created in HTML** — a prototype that shows the intended look and behavior. It is **not** production code to copy directly. Your job is to **recreate these designs in the target codebase** — native **Android, Jetpack Compose, Material 3** (per the brief) — using its established patterns, theming, and components. Treat the HTML as the source of truth for color, type, spacing, layout, and interaction intent; translate it into idiomatic Compose (e.g. `Checkbox`/custom drawing, `LazyColumn`, `FlowRow`, `Scaffold`, `NavHost`), not literal HTML/CSS.

`support.js` is only the runtime that makes the HTML prototype render/interactive in the browser — **ignore it for implementation**. `original_product_brief.md` is the full product spec (IA, domain glossary, per-screen behavior, constraints).

### How to open the reference
Open `Yantra App.dc.html` in a browser. It's a zoom/pan canvas: all screens are laid out as labeled phone/tablet frames. Every checkbox is live (tap to toggle the complete animation).

## Fidelity
**High-fidelity.** Final colors, type scale, spacing, iconography, and interaction intent are all specified. Recreate the UI faithfully using Compose + Material 3, mapping the tokens below to the app's theme. Where the brief marked something as a "want" (drag reorder, filter editor, etc.), the comp shows the target visual; the underlying behavior is yours to wire per the brief.

---

## Design language (the "system")
- **Theme shown:** OLED dark. Page background is pure black `#000000`; content sits directly on it (document feel, minimal chrome). A light theme is *not yet designed* — see "Not included" below.
- **Surfaces are warm, not neutral.** The header/hero band and raised tiles are warm dark browns (a subtle tint of the accent), never gray.
- **Single accent hue.** Terracotta `#E06A43` drives the whole app — checkboxes, primary actions, chips, ink accent, progress, focus ring. It is deliberately *muted* (not neon). Reserve it for signal; don't flood surfaces with it.
- **Buttons are outlined + hue-tinted, never solid/shiny.** Primary actions (add-bar "+ Task", FAB, focus controls, "New list", quick-add send) use a translucent accent fill `rgba(224,106,67,0.13)` + `1px` border `rgba(224,106,67,0.5)` + accent-colored glyph/label. **No drop shadows, no glows** on buttons.
- **Blocks on a page have no divider lines** — spacing (vertical padding) separates them, reinforcing the "living document" feel.
- **Type:** system font (`-apple-system, Roboto, …` in the proto → use the platform default / Roboto on Android). Headlines are heavy (800) with tight negative tracking; section labels are small uppercase with wide tracking.
- **Motion:** checkbox completes with a spring pop (`cubic-bezier(.34,1.56,.64,1)`) + fade/slide-in tick + strikethrough. Focus ring animates smoothly. (Full motion targets in brief §8.)

---

## Design tokens

### Color
| Token | Value | Use |
| --- | --- | --- |
| `page` | `#000000` | App background (OLED) |
| `band` | `#221A16` | Page header/hero band, warm |
| `tileWarm` | `#2A211B` | Raised chips/tiles (older) |
| `tileWarm2` | `#1E1815` | Secondary add-bar chips (current) |
| `cardBg` | `#141210` | Home list/smart-list cards |
| `railBg` | `#0C0A09` | Tablet workspace rail |
| `hairline` | `#1A1512` | Subtle separators (used sparingly) |
| `tileBorder` | `#2E2620` | Secondary chip border |
| **`accent`** | **`#E06A43`** | Primary terracotta — checks, actions, ring |
| `accentText` | `#F0A07C` | Accent as text/label on dark |
| `accentGlow` | `#E88A62` | Header timer-tile icon stroke |
| `accentFill` | `rgba(224,106,67,0.13)` | Outlined-button fill |
| `accentBorder` | `rgba(224,106,67,0.5)` | Outlined-button border |
| `accentChipBg` | `rgba(224,106,67,0.22)` | "High" priority chip bg |
| `onAccent` | `#1C0F09` | Dark ink used on solid accent (icon/app-icon only) |
| `textPrimary` | `#F6F1EB` / `#F1ECE6` | Titles, body |
| `textSecondary` | `#B0A294` | Body/secondary |
| `textMuted` | `#9E9086` | Meta, captions |
| `textDim` | `#8E8175` / `#7E7266` | Completed titles, faint labels |
| `checkOutline` | `#6E5C4E` | Idle checkbox border |
| `due` | `#EFA684` | Due-date dot |
| `dueText` | `#EFC1A2` | Due-chip text |
| `dueChipBg` | `rgba(239,166,132,0.16)` | Due-chip bg |

**List-identity palette** (per-list accent, derived from id hash): coral `#E06A43`, amber `#D79B45`, green `#4E9478`, blue `#5E82A8`, purple `#8B6BA8`, pink `#C56A94`. Each is shown as a 12–16% alpha wash behind the list's icon tile.

**Ink pen colors** (Ink editor): ink white `#F2EDE4` (theme-swaps to dark on light paper), coral `#E06A43`, blue `#6FA8E4`, green `#4E9478`, amber `#E0A83E`.

### Type scale (px in proto → sp on Android)
| Role | Size | Weight | Tracking |
| --- | --- | --- | --- |
| Page title (hero) | 34 | 800 | −1px |
| Tablet page title | 40 | 800 | −1.2px |
| Screen title (Home/Stats) | 22–26 | 800 | −0.5px |
| Splash wordmark | 38 | 800 | −1px |
| Block heading ("This week") | 16 | 800 | −0.2px |
| Row title | 15 | 500 | 0 |
| Card title | 15.5 | 700 | 0 |
| Body / paragraph block | 14.5 | 400 | 0, line-height 1.6 |
| Chip / meta | 11–12.5 | 600 | 0 |
| Section label (uppercase) | 11–12 | 700 | +1 to +1.4px, UPPERCASE |
| Breadcrumb (mono) | 10.5 | 600 | +1.5px, monospace, UPPERCASE |
| Timer countdown (mono) | 50 (running) / 20 (banner) | 700 | −2px, monospace |

### Shape / radius (dp)
Phone frame 44 · tablet frame 34 · header band bottom 20 · cards/tiles 16–18 · icon tiles 12–13 · buttons/chips 10 · property pills / row chips 5 · checkbox 7 · FAB 20 · app-icon 30 (large) / 17 (small) · focus ring & pause button are circles.

### Spacing
Screen horizontal padding 20–26 · row vertical padding 13–14 · card padding 13–16 · gap between cards 8 · gap between add-bar chips 8 · touch targets ≥ 44–48 (icon tiles are 38–40, FAB 60, pause 76).

### Elevation / effects
Frames use a large ambient shadow **in the comp only** (to lift the phone off the canvas) — **do not** replicate as in-app elevation. In-app: **no shadows on buttons or cards**; separation comes from the warm surface tints and 1px borders. Focus/splash use a soft radial accent glow **behind** the ring/logo (`radial-gradient(circle, rgba(224,106,67,0.16–0.20), transparent 66%)`).

---

## Screens / views
All phone frames are 390 wide. Each is labeled on the canvas via `data-screen-label`.

### 1. Splash (first run)
Centered: app mark (see Assets) in an outlined-tint rounded square (88, radius 26), "Yantra" wordmark (38/800), tagline "Yet Another Notes, Todos & Reminder App" (12.5/600, muted). Soft radial accent glow behind. Home-indicator pill at bottom.

### 2. Home (workspace)
- **Header:** "YANTRA" eyebrow (10.5/700 uppercase, accentGlow) above "Workspace" (26/800); stats icon tile top-right (bar-chart, tallest bar accent).
- **Active-timer banner:** warm card `#241812`, 1px `rgba(224,106,67,0.28)` border, radius 16. Left: timer icon in accent wash tile. Middle: "FOCUSING" micro-label + task title. Right: countdown "15:32" (mono, accentText). Below: 5px progress track `rgba(255,255,255,0.08)` with accent fill (62%). Tapping opens Focus.
- **"Smart lists" section:** uppercase label; cards (`cardBg`, radius 16): 44 icon tile with **sparkle** glyph in identity wash, title (15.5/700), subtitle (12.5, muted) — copy is descriptive, e.g. "6 open · updates live", "3 tasks · lands in Inbox" (NOT "Computed view"). Overflow ⋮.
- **"Lists" section:** same card, but icon tile shows the list's emoji/identity, subtitle "12 of 18 done", and a **tiny progress ring** (34px, track `#2A211B`, arc in identity color; full = solid ring, no gap).
- **FAB:** bottom-right, 60px, radius 20, **outlined-tint** (accentFill + accentBorder), accent "+" glyph. Opens menu: New list / New smart list (template picker).
- States to build (brief §5.1): empty workspace, active-timer present/absent.

### 3. Node page — LIST variant (the core screen)
- **Band header (radius 44 44 20 20, `band`):** status bar; row of back tile (38, `rgba(255,255,255,0.06)`) + centered mono breadcrumb "WORKSPACE / LISTS" + overflow ⋮. Then page title "Launch checklist" (34/800). Meta line "12 of 18 done · updated 2h ago" (12.5, muted). **No** checkbox, **no** property pills on a list page.
- **Blocks (LazyColumn), no divider lines:**
  - *Heading block* "This week" (16/800).
  - *Task rows:* round checkbox (22, radius 7, idle border `checkOutline`; done = accent fill + `onAccent` tick, scale 1.08) · inline title (15/500, muted+strikethrough when done) · optional chips row beneath (priority/due) · optional pomodoro count (timer glyph + n) · optional children-count + chevron on the right (opens as page).
  - *Paragraph block:* plain body text (14.5, `textSecondary`, line-height 1.6).
  - *Ink block:* strokes render directly on the page background at true scale (white + accent strokes shown); faint "INK · P.1" mono tag bottom-right; tap → Ink editor. Empty state = quiet "✏️ Tap to sketch".
  - *Image block:* full-width rounded (radius 16) image, ~132 tall, with a small filename chip.
- **Add-bar (bottom):** horizontally scrollable chips. "+ Task" = outlined-tint accent chip (accentFill + accentBorder + accent glyph/text). Text / Heading / Ink / Image = `tileWarm2` chips with `tileBorder`, `#DCD1C5` text. Padding ~9×15.

### 4. Node page — TASK-AS-PAGE variant
Same renderer as the list page, plus:
- **Round checkbox before the title** (24, accent border; toggles the whole task) and a Focus (timer) tile in the header actions.
- **Property pills row** (task pages only): filled pill = tinted bg + color dot + "Name · Value" (e.g. "Priority · High" in accent; "Due · Wed 8" in due tones; "Owner · Sam" neutral). Unset = ghost "+ Name" (dashed border). Final "+ Property" (solid ghost border) creates new defs. Tap-to-edit inline per kind (brief §5.2).
- **"Subtasks" heading** with a pomodoro summary pill ("🍅 2 · 50m focused" — use the timer glyph, not emoji).
- Subtask rows, paragraph note, add-bar as above.

### 5. Smart list page
- **Band header:** sparkle glyph + title "High priority" (32/800). Auto-generated description line: "Open tasks · Priority = High · new tasks land in **Inbox**" (Inbox highlighted).
- **Rows:** task rows with priority chip, a muted "in {List}" **origin** label, optional pomodoro count. Empty state: "Nothing matches right now."
- **Quick-add bar (bottom):** rounded field ("Add a task — auto-tagged High") + outlined-tint send button (arrow). Helper line below explains the magic: "New tasks are created in Inbox and set to Priority = High automatically." (brief §5.3 — communicate the "real place" rule clearly.)

### 6. Ink editor (paginated canvas)
- **Canvas:** continuous vertical document of A4-proportioned pages on near-black `#101010`, 1px `#1C1B1A` separators + faint page numbers; auto-extends (one blank page below content). Sample strokes show ink-white, accent, blue, green.
- **Mode hint:** "Stylus draws · two fingers scroll".
- **Pen tray (bottom, `#161311`):** row 1 = color swatches (selected has ring) + size chips S/M/L (selected = accent tint); row 2 = brush-family chips Pen / Marker / Highlighter (selected = accent tint) + undo + "1/2" page indicator. Brief wants eraser + page ▲▼ too.

### 7. Focus (pomodoro)
- **Setup:** "FOCUS ON" label + task title; "DURATION" with 15 / 25 / 50 chips (selected = accent tint + border); "Start focus" outlined-tint button.
- **Running:** ambient full-bleed black with radial accent glow. "FOCUSING" label + task title; large ring (250px, track `#241812`, accent progress arc, elapsed shown); center countdown "15:32" (mono 50) + "of 25:00". Bottom: outlined-tint circular **Pause** button (76) + "Finish · 🍅" and "Drop" secondary buttons (`#1B1613`).
- **Done:** check-in-circle in accent wash; "Session complete"; "25 min on Finalize budget · +1 focus"; "Start another" (neutral) + "Done" (outlined-tint) buttons.

### 8. Stats
- Two cards: **Today** (count in accentText + "🍅" + "1h 40m focused") and **This week** (count + "9h 35m · +18%").
- **Last 7 days** bar chart: bars `#3A2A20`, today's bar accent; weekday letters beneath (today's letter accent).
- **Most focused** ranked list: rank (mono, #1 accent) + task title + "n 🍅".

### 9. Tablet — two-pane
1120×800, radius 34, split:
- **Left rail (360, `railBg`, 1px right hairline):** "YANTRA" eyebrow + "Workspace" + stats tile; "Smart lists" then "Lists" as condensed rows (36 icon tile, title, count); selected list row highlighted `rgba(224,106,67,0.13)`. Bottom: outlined-tint "New list" button.
- **Right pane:** the Node page (list) — band header (title 40), blocks, add-bar. Uses the width (max content ~560 for text blocks); rows are roomier (padding 14×26).

### 10. Component sheet
Reference states for: checkbox (idle / done), property pill (filled / ghost "+ Due" dashed / "+ Property"), add-bar chips (outlined-tint + secondary), list-identity palette swatches, row chips (High / Due / pomodoro).

---

## Interactions & behavior
- **Checkbox toggle:** on tap, box fills accent + scales to 1.08 (spring `cubic-bezier(.34,1.56,.64,1)`, ~280ms), tick fades+scales in (~150/300ms), title animates to muted + strikethrough (~300ms). Reversible.
- **Open as page:** tapping a task row's chevron opens that task as its own page (same renderer, recursive). Brief §8 wants a container-transform / shared-element transition.
- **Add-bar:** each chip inserts a block of that type at the cursor and focuses it; successive task-adds should keep the add-bar above the IME (imePadding) and keep focus flowing (brief §5.2 want).
- **Smart-list quick-add:** creates the task in a real home list (Inbox) and auto-sets equality-filter properties (e.g. Priority=High); surface this outcome in copy.
- **Focus timer:** ring animates smoothly; Pause/Resume feels physical; Finish counts as a completed pomodoro (🍅), Drop = abandoned; both persist to Stats. Active session shows as the Home banner and survives navigation.
- **Ink:** one finger/stylus draws (low-latency wet layer), two fingers scroll; once a stylus is seen, pen draws & single finger scrolls. Page snaps on ▲▼ should ease.
- **Reorder (want):** drag handles with lift/settle physics; swipe actions for complete/delete.
- **Responsive:** everything wraps/scrolls (FlowRow for pills/chips); must survive large dynamic-type scales; tablet uses the two-pane layout.

## State management
Per the fixed data model (brief §10 — do not break schema): node tree + typed property registry + smart-list defs + pomodoro sessions + ink strokes (serialized `StrokeInputBatch`). Offline-first, sync-ready (UUIDs, LWW timestamps, tombstones). UI state needed: current node/page stack (back = up one), per-row done state, inline property-edit popovers, active focus session (task id, planned vs elapsed, running/paused), ink tool state (color/size/family/page), smart-list live query results.

## Assets
- **App mark (logo):** geometric — a rounded rotated-square ("diamond") frame around a **Y whose fork + stem also reads as a checkmark**. This is drawn as inline SVG in the file (see Splash + App icon block); recreate as a vector drawable. Two-tone options: accent mark on dark, or dark mark on solid-coral tile. Small size drops the diamond frame for legibility.
  - Diamond frame: `rect x=8 y=8 w=26 h=26 rx=4` rotated 45° about center, thin stroke at ~40% opacity.
  - Y/check: `M12.5 20 L19 26.5 L30.5 13.5` (fork/tick) + `M19 26.5 L19 33.5` (stem), round caps/joins.
- **Icons:** Material Symbols (filled) per brief. In-proto SVGs (back chevron, overflow ⋮, sparkle for smart lists, timer/pomodoro glyph, bar-chart stats, plus, send arrow, undo) are stand-ins — use Material Symbols equivalents in-app.
- **List emojis** (🚀 🐞 📚) are placeholder identity glyphs; the real app offers an emoji/icon picker.
- No external image assets; the image-block visual is a placeholder for user-picked photos (Coil).

## Files in this bundle
- `Yantra App.dc.html` — the full design reference (all screens on one canvas). **Primary artifact.**
- `original_product_brief.md` — complete product spec: IA, domain glossary, per-screen behavior, accessibility, and the **hard constraints** (§10). Read this alongside the comp.
- `support.js` — browser runtime for the prototype only; not for implementation.

## Not included (future design passes)
- **Light theme** (brief wants both first-class; only OLED dark is designed here — derive light from the same token roles: paper `#FAF9F7`, surface `#FFFFFF`, text `#1C1B1A`, keep accent `#E06A43`).
- Full dialog set (new property, new smart-list templates, date picker, confirm-delete, row bottom-sheet).
- Empty/first-run states beyond splash; filter-editor UI; drag-reorder & swipe visuals (behavior specified, final visuals TBD).
