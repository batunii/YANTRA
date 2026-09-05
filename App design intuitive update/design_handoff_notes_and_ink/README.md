# Handoff: Yantra — notes, task pages, and ink

## Overview

Yantra is an Android task app whose second pillar is handwritten notes. This handoff covers the surfaces added in the most recent design batch:

- **07 · A task's page is a page** — the task detail screen rebuilt as a document of indented blocks (matching `PageDoc`), plus the block-insert menu and an ink block opened on the phone.
- **08 · In progress, end to end** — the five signals that mark the one running task, shown on a list screen and on the lock screen.
- **09 · Tablet · the task's page** — two-pane tablet layout with live ink blocks inline in the page flow.
- **10 · Full-screen ink** — the writing surface: pen kit, gesture eraser, lasso-to-task, one continuous scrolling canvas.

Earlier sections (01–06: first run, the day, one task, focus, widgets, tablet ink) are in the same file and unchanged by this batch.

## About the design files

`Yantra App.dc.html` is a **design reference written in HTML** — a prototype showing intended look, layout, and behaviour. It is not production code to port.

The task is to **recreate these designs in Yantra's existing Android environment**: Jetpack Compose, Material 3, the app's own `YantraColors` / `Theme.kt` tokens, and the existing `PageDoc` / `PageCodec` / ink model. Read the HTML for measurements, hierarchy, and copy; implement with the codebase's own composables and patterns. Do not introduce a web view or reimplement the token system.

Source repo: `batunii/YANTRA`, branch `main`. See `github.md` in the project root for the screen → repo-file map.

## Fidelity

**High-fidelity.** Colours, type, spacing, and radii are final and are taken from the repo's own token files. Recreate faithfully. Two caveats:

- Ink strokes in the prototype are hand-authored SVG paths standing in for real stroke data. The real thing renders from `StrokeCodec`.
- Image blocks are unstyled placeholders — no real assets were supplied.

---

## Screens / views

### 07 · Task page (phone) — the page of blocks

**Purpose.** Everything a task holds: its properties, its prose, its subtasks, its ink and images. Opened from any task row.

**The model this follows.** `PageDoc` defines a page as an ordered list of blocks, each with a kind and an indent level. Kinds: prose, heading, bullet, numbered, task, ink, image. The screen must be an outline renderer, not a form with fixed fields.

**Layout.** Single column, 22dp horizontal page margin. App bar 56dp: back chevron 20dp, breadcrumb (`WORKSPACE · LIST`) in Space Mono 10.5px/700/1.4px tracking at `ink2`, overflow dots right.

Below the bar, in flow order:

| Element | Spec |
|---|---|
| Title | Bricolage Grotesque 700, 31px, -0.9px tracking, line-height 1.12, `ink`, `text-wrap: pretty` |
| Action pair | 20px below title. Row, 9px gap. Primary flex 1.35 vs secondary flex 1: 15px/700 label, 15px vertical padding, 15dp radius |
| Property chips | 18px below. Wrapping row, 8px gap. Each: `surfaceHigh` ground, 11dp radius, 9px/12px padding, 14dp icon + 13px/600 label |
| Blocks | Each separated by a 1px `hair` top border with 14–16px padding above |

**Block rendering.**

- **Prose** — 15.5px, line-height 1.6, `ink2`.
- **Heading** — Bricolage 700, 18px, -0.3px tracking, `ink`.
- **Bullet** — 11px gap; marker is a Space Mono `·` at `dim`; text 15px/1.5 `ink2`.
- **Task** — the full row grammar: 24dp glyph, title 15.5px/500 `ink`, a Space Mono 11px/700 sub-line reading `SUBTASK` plus a state word, trailing elapsed time in `accent` when running. Drag-right ladder applies here exactly as in the day list.
- **Ink** — full column width, 118dp tall on phone, `surface` ground, 1px `tile` border, 12dp radius. Caption bottom-left in Space Mono 9px/1.2px tracking `muted` on a `surface` chip, 5dp radius: `INK · N STROKES`.
- **Image** — same frame as ink.
- **Indent** — 20dp padding-left per level.

**Insert affordance.** Last row of the page: 19dp plus icon + `Add a block` at 15px `dim`. Also fires on Enter at the end of a block.

**Nothing else.** No drag handles, no per-block menus, no type badges at rest. The block's own shape is its label.

---

### 07b · Block insert menu

Bottom sheet, `surface`, 22dp top corner radius, 1px `tile` top border, 14px padding.

Header row: a 2×19dp `accent` bar plus context in Space Mono 10px/1.4px `muted` — `NEW BLOCK, AFTER LINE 3`.

Grid, 2 columns, 8px gap. Six tiles: Note, Heading, Bullets, Task, **Ink**, Image. Each `surfaceHigh`, 14dp radius, 15px/14px padding, 19dp icon + 14.5px/600 label, 12px gap. Ink is the one exception — `accentChip` ground, `accent` 700 label — because it is the reason the tablet exists.

Footer, 12px below, Space Mono 10px `dim`: `OR JUST KEEP TYPING FOR A NOTE`.

No submenus. Six kinds is the whole list.

---

### 07c · Ink block opened (phone)

App bar: back chevron, `INK BLOCK · N STROKES` in Space Mono, elapsed time right in `accent` 11px/700.

Canvas fills the rest. Dot grid: `radial-gradient(oklch(0.26 0.012 80 / 0.12) 1px, transparent 1px)`, 24dp pitch.

Tool puck bottom-right, 14dp inset: `surface`, 1px `tile`, 22dp radius, 10px/8px padding, column of 44dp square targets at 15dp radius, 8px gap. Active tool gets an `accent` fill with `onAccent` icon. Order: pen, highlighter, lasso, hairline divider, undo.

Stroke/colour bar bottom-left: `surface`, 1px `tile`, 16dp radius. Four dots at 8/12/17/22dp; active carries a 2px `accent` ring. Then a 1px divider and colour dots at 19dp.

---

### 08 · In progress, end to end

`TaskStatus` already models in-progress. The design's rule: it should be why the app looks different, not a badge you hunt for. **Exactly one task can be running** — the treatment is only legible if a single row wears it. Starting a second offers `SWITCH HERE`, never a second clock.

Five signals, identical on every screen:

1. **Glyph** — the drawn ring (`r=6`, 2.4px `accent` stroke) inside the neutral bhupura frame. The only ring on screen.
2. **Row ground** — `wash` fill, 14dp radius, extended to the full bleed by negative margins equal to the page margin. The only tinted row.
3. **Trailing slot** — elapsed time in Space Mono 11.5px/700 `accent`, replacing the deadline chip. Effort outranks schedule while the clock runs.
4. **Position** — the row leaves its section and pins to the top of whatever list is open, so it can never be scrolled away from.
5. **Now bar** — replaces the capture bar app-wide: `accent` ground, 20dp radius, 15px/18px padding, ring glyph in `onAccent`, title 15px/700 ellipsised, sub-line `RUNNING · MM:SS · TAP TO OPEN` at 78% opacity, trailing chevron. Capture relocates to a 52dp leading key in the tab strip and stays reachable.

**Where it must not go:** no colour on the device frame, no animation ever, no count in the header.

**Lock screen.** Dark ground `#1d1b20`. Clock in Space Mono 66px/700/-3px at 92% white. Session card: 10% white, 22dp radius; ring glyph, `YANTRA · RUNNING`, elapsed in `accent`; title 16.5px/600; three buttons — Stop, Done (both 14% white) and Open (`accent`). A second 6% card summarises the day. The session must be actionable without unlocking.

---

### 09 · Tablet · the task's page

Two panes inside a 1330×812 landscape frame (design size; treat as a large-tablet breakpoint, scale by ratio).

**Left pane, 340dp.** `surface`, 1px `hair` right border. Header: breadcrumb + list name in Bricolage 26px/700. Task rows at 23dp glyph with the two-line grammar. Running row wears the `wash` ground. The now bar sits at the bottom of this pane, 18dp inset.

**Right pane, flex.** Header row 18/28px padding, 1px `hair` bottom: block count in Space Mono, then Stop (`accentFill` + `accentBorder`, elapsed inline) and Done (`surfaceHigh`) at 13dp radius.

**The column stays a column: 720dp of measure, centred.** Prose at 1300dp wide is unreadable — do not stretch to the glass.

Page content is the same block renderer as phone, scaled: title Bricolage 34px/-1.1px; chips gain a dashed-border `+ Label` affordance; ink blocks 250dp tall and **always live** — pen down anywhere inside one draws, palm and finger scroll, no mode to enter. Nested tasks keep the ladder so a page can be worked as a checklist without leaving it.

Puck bottom-right of the canvas pane at 46dp targets: pen, highlighter, lasso, divider, undo, redo. It only appears once the pen is near a block. Hover preview is a 52dp dashed `accentBorder` circle on `accentFill` tracking the pen tip.

Properties are chips under the title — the same chips as phone, one row. No sidebar of fields.

---

### 10 · Full-screen ink

The design conclusions here came from how established handwriting apps behave. The through-line: nobody browses a tool library mid-sentence. People keep two or three pens they trust and switch without looking.

**A pen kit, not a palette.** Bottom-right, 22dp inset: `surface`, 1px `tile`, 26dp radius, 11px/9px padding, 9px gap. Three 56dp slots — FINE, BOLD, MARK — each **drawn as the stroke it makes** (a small swept path at 1.8 / 4.2 / 9px, marker at 40% `teal`), labelled in Space Mono 8px/0.8px `muted`. Active slot: `accentFill` ground, 1.5px `accent` border, `accent` label. Then a 34dp divider and two 48dp tools: lasso and shape-snap.

Hold a slot to change what it holds. **That is the only place width and colour live** — there is no separate palette.

**The eraser is a gesture, not a trip.** Double-tap the pen barrel, or hold its button, and the active slot flips to erase until you lift. This is the single biggest speed difference between apps that feel good and ones that don't; hint in Space Mono 9px `dim`: `DOUBLE-TAP THE PEN / TO ERASE`.

**Chrome dims but never moves.** Pen down → title and kit drop to ~26% / 55% opacity so the kit is exactly where you left it on lift. **Undo and redo never fade**: two 52dp squares at 18dp radius, `surface` + 1px `tile`, bottom-**left**, opposite the writing hand — the two controls reached for mid-stroke.

**One continuous surface. There are no pages.** The canvas scrolls down for as long as you keep writing and grows a screenful ahead of the pen, so the bottom edge is never a wall. Nothing to add, nothing to number, no page chrome. Position is a hairline that appears at the left edge while scrolling and leaves when you stop. A 112dp bottom scrim (`linear-gradient(to top, page, transparent)`) sits **above the ink and below the controls** so strokes fade out while undo and the kit stay at full strength.

**Selection produces something.** Lasso draws a 2px `accent` dashed outline (10/8 dash) around the group. A floating bar appears directly beneath it: `surface`, 1px `tile`, 15dp radius, 6px padding, `0 12px 30px rgba(0,0,0,.14)` shadow. Actions: **To text** (active, `accentFill`), **To task** (bhupura icon), divider, Move, Copy, Tidy, Delete (`crimson`). Every other app stops at Copy — To task is Yantra's own, and it writes a real task block onto this page.

**Input model.** Pen draws, one finger scrolls, two fingers zoom. Pressure and tilt vary the stroke. Palm ignored. There is no mode to be in the wrong one of.

**Left-handed.** Kit and undo pair swap edges — one setting, or follow which edge the palm rests on.

---

## Interactions & behaviour

**The drag ladder (all task rows, every screen).** One horizontal axis carries every decision:

| Gesture | Detent | Result | Haptic |
|---|---|---|---|
| Drag right | 40dp | Start — glyph becomes the drawn ring | light tick |
| Drag right | 120dp | Done — glyph becomes a filled circle | firm thud |
| Drag left | 120dp | Not today — leaves the day, no guilt copy | light tick |

The glyph is drawn *by* the rightward motion — the ring traces as the finger travels, which is how the gesture teaches itself. Tap the row opens the page; tap the glyph does nothing (the ladder owns state).

**Glyph states — exactly three.** Not done: the bhupura frame. In progress: bhupura + drawn ring. Done: a plain filled circle, and the title takes a `line-through` in `accent`.

**Motion.** Nothing animates at rest, anywhere. Movement happens only while a finger or pen is down, and stops when it lifts. No entrance animations, no pulsing timers, no spinners on the ink canvas.

**Ink.** Stroke renders under the pen with no perceptible latency; palm contact never marks. Double-tap barrel toggles erase for the duration of the touch. Lasso is a hold-then-draw on the lasso tool. Shape snap replaces a closed freehand path with its regular form on lift, once, undoably.

**Now bar.** Present app-wide whenever a session runs; tap opens the running task's page. Dismissed only by Stop or Done.

---

## State

- `runningTaskId: Long?` — **at most one.** Everything in section 08 derives from it.
- `sessionStartedAt`, `elapsed` — the ledger's per-task and per-day totals come from `FocusRepository`.
- `PageDoc` blocks: kind, indent, content, plus stroke refs for ink and URIs for images.
- Ink: active slot index (0–2), the three slot configs (width + colour), transient erase flag, lasso selection set, scroll offset, canvas extent.
- Ladder: per-row drag offset and the detent currently crossed.

---

## Design tokens

Taken from `ui/theme/YantraColors.kt` and `data/label/LabelPalette.kt` — use those, not these copies.

**Light** — page `oklch(0.972 0.006 80)` · surface `oklch(0.995 0.003 80)` · surfaceHigh `oklch(0.958 0.007 80)` · ink `oklch(0.26 0.012 80)` · ink2 `oklch(0.44 0.011 80)` · muted `oklch(0.545 0.010 80)` · dim `oklch(0.655 0.009 80)` · hair `ink @ 8.5%` · tile `ink @ 10.5%`

**Dark** — page `oklch(0.171 0.005 80)` · surface `oklch(0.216 0.007 80)` · surfaceHigh `oklch(0.257 0.009 80)` · ink `oklch(0.948 0.005 80)` · ink2 `oklch(0.735 0.008 80)` · device ground `#1d1b20`

**Accent** (user-chosen, five legal values) — `#D85A30` coral (default), `#00A072`, `#0097B1`, `#5480EB`, `#A864CF`. Derived: `accentFill` = accent @ 12%, `accentBorder` = accent @ 44%, `accentChip` = accent @ 15%, `wash` = accent @ 9%.

**Priority** — crimson `#A32D2D` (dark `#E24B4A`), amber `#BA7517` (dark `#EF9F27`). These appear **only** as a task glyph's frame stroke. Never as a fill, never on a chip, never anywhere else.

**Colour law.** The accent owns effort and only effort: sessions, the ring, elapsed time, the now bar, the capture caret. Priority owns the glyph frame. Labels own their own hues from `LabelPalette` and appear only as label text. Nothing else is coloured.

**Type** — Bricolage Grotesque 700 (titles, -0.3 to -1.6px tracking by size) · Space Grotesk 400/500/600/700 (body, rows, chips) · Space Mono 400/700 (metadata, timers, all-caps labels at 1.2–1.7px tracking).

**Radii** — 6 chips-small · 11–14 chips and tiles · 15 buttons · 18–20 bars and pucks · 22 sheets and cards · 26 kit.

**Spacing** — 22dp page margin (phone), 26–28dp (tablet). 13–15dp row vertical padding. 8–10dp gap in chip rows, 9–14dp in control columns.

**Touch targets** — 44dp minimum everywhere; ink tools 46–56dp.

**Shadow** — sparse. `0 6px 18px rgba(0,0,0,.09)` for lifted controls, `0 12px 30px rgba(0,0,0,.13)` for floating pucks and bars, `0 -6px 30px rgba(0,0,0,.10)` for bottom sheets. No shadow on rows or blocks.

---

## Assets

None supplied. Every glyph and icon in the prototype is inline SVG.

The bhupura path is lifted verbatim from `ui/components/YantraGlyph.kt` and must be used as-is:

```
M8 4H11V2H17V4H20Q24 4 24 8V11H26V17H24V20Q24 24 20 24H17V26H11V24H8Q4 24 4 20V17H2V11H4V8Q4 4 8 4Z
```

on a 28×28 viewBox, 1.7px stroke at row size.

Ink strokes are illustrative SVG. Image blocks are placeholders — real assets still needed.

---

## Files

- `Yantra App.dc.html` — the full design. Sections are marked in source as `07 · A TASK'S PAGE IS A PAGE`, `08 · IN PROGRESS, END TO END`, `09 · TABLET · THE TASK'S PAGE`, `10 · FULL-SCREEN INK`. Sections 01–06 precede them.
- `android-frame.jsx` — device bezel used for presentation only. Not part of the design.
- `github.md` — repo association and the screen → repo-file map.

Open the HTML in a browser and read source alongside it; every measurement in this document is inline in the markup.
