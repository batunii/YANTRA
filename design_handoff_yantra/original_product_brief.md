# SuperTasks — Design Brief & Product Spec

*Prepared for a visual/UX design pass. Everything described here is implemented and working;
the ask is to elevate the look, feel, motion, and micro-interactions — not to redesign the
information architecture. Sections marked ⚠ are known weak spots the design should solve.*

---

## 1. What the product is

**SuperTasks** is an offline-first task + notes app for Android (phone & tablet), heavily
inspired by **Superlist**: tasks and documents are the same thing. Any list holds a free mix
of tasks, text, headings, hand-drawn ink sketches, and images. Any task can be opened *as a
page* and gain its own subtasks, notes, and sketches — infinitely nested. On top of that:
typed properties (priority, due date, …), live "smart lists" (saved queries that look like
lists), and a built-in pomodoro/focus timer with history & stats.

**Platform:** Native Android, Jetpack Compose, Material 3 foundation, minSdk 26. Phone-first,
tablet-friendly. Light + dark themes, both first-class.

**Personality wanted:** Superlist's energy — playful but focused, bold typography, confident
accent color, generous whitespace, delightful details — *not* stock Material. Think:
a beautiful paper notebook with superpowers, not an enterprise tool.

---

## 2. Design goals (in priority order)

1. **Page feel.** A list/task page should read like a living document, not a settings screen.
   Blocks are content, chrome is minimal, ink is literal handwriting on the page.
2. **Responsive, never squashed.** ⚠ Current UI has rows that crowd on narrow screens.
   Everything must wrap, scroll, or adapt; tablet layouts should use the width
   (e.g., two-pane: workspace rail + page).
3. **Visible, touchable properties.** Properties are pills directly on the task page
   (Superlist-style) — filled when set, ghost "+ Due" when not. Design their hierarchy,
   color usage, and edit affordances.
4. **Fast capture.** Adding a task/text/ink must feel one-tap-instant everywhere
   (page add-bar, smart-list quick-add, home FAB).
5. **Motion.** Screen transitions, checkbox completion, timer ticks, stroke commits —
   currently mostly default/none. Define a motion language (shared-element-ish page opening,
   satisfying check animation, list reorder physics).

---

## 3. Information architecture

```
Home (workspace)
├── Smart lists (computed views)  → Smart list page
├── Lists                          → Node page
│    └── blocks: task / paragraph / heading / ink / image
│         └── task → opens as its own Node page (same renderer, recursive)
│              └── ink block → Ink editor (paginated canvas)
├── Focus (pomodoro) — reachable from any task; survives navigation
└── Stats (focus history)
```

Navigation today: single stack, no tabs. Back always goes up one page.
An active pomodoro shows as a banner card on Home (tap → Focus screen).

---

## 4. Core concepts (domain glossary)

| Concept | What it means | UI expression |
| --- | --- | --- |
| **Node** | One row in a tree: list, task, paragraph, heading, ink, image, smart_list | A block on a page; lists & smart lists are cards on Home |
| **Open as page** | Any task renders its children with the exact same page UI | Chevron on task rows; recursive |
| **Property** | Global typed attribute: select / text / number / date / checkbox | Pills on the task page; small chips on rows; user can create new ones |
| **Smart list** | A stored filter (JSON→SQL) that *computes* its children | Card on Home with ✨ icon; page shows live query results + quick-add |
| **"Real place" rule** | A task lives in exactly one list; smart lists only *show* it | Quick-add into a smart list physically creates the task in a home list and auto-sets matching properties |
| **Pomodoro session** | Timed focus attached to a specific task; every session persisted | Focus screen (big ring timer), 🍅 badges on rows, Stats screen |
| **Ink** | Vector strokes (androidx.ink), never converted to text | Paginated canvas editor; page-native inline previews |

---

## 5. Screen-by-screen spec

### 5.1 Home (workspace)
- **Content:** "Smart lists" section, "Lists" section; each item = icon tile (accent color
  derived from id hash), title, subtitle (done/total counts for lists; "Computed view" for
  smart lists ⚠ dull copy), overflow menu (rename/delete).
- **Actions:** FAB → menu: New list / New smart list (template picker: Due today,
  High priority, All open tasks). Top-right: stats icon.
- **States:** active-timer banner (title, countdown, progress bar); empty workspace (never
  seen after seeding — design one anyway).
- ⚠ Wants: better card design, count visualization (e.g., tiny progress ring), maybe
  list emoji/icon pickers, tablet grid.

### 5.2 Node page (THE core screen — lists and tasks share it)
- **Header:** back, editable title (headline size, placeholder "Untitled"). If the page is a
  task: round checkbox before the title, Focus (timer) icon in the top bar. Overflow: delete.
- **Property pills row** (task pages only): one pill per property def. Set = tinted pill
  "Name Value" with color dot; unset = ghost "+ Name"; final ghost "+ Property" creates new
  defs. Tap-to-edit inline: select → dropdown w/ colored options; date → date picker
  (set) / change–clear menu; checkbox → toggles; text/number → small dialog.
- **Blocks (LazyColumn):**
  - *Task row:* round checkbox, inline-editable title, children-count badge, open-as-page
    chevron, ⋮ menu (Properties, Focus 🍅, Move up/down, Indent, Outdent, Turn into text,
    Delete). Property chips + 🍅 count wrap beneath.
  - *Paragraph:* plain inline-editable text.
  - *Heading:* bolder, larger inline-editable text.
  - *Ink block:* page-native — strokes render directly on the page background at true scale,
    height = content height; empty state is a quiet "✏️ Tap to sketch" line. Tap → Ink editor.
  - *Image block:* full-width rounded image (Coil), picked via system document picker.
- **Add-bar** (bottom, above keyboard): horizontally scrollable chips — Task, Text, Heading,
  Ink, Image.
- ⚠ Wants: drag-handle reorder (currently menu-driven), swipe actions (complete/delete),
  focus/keyboard flow when adding successive tasks, nicer indent visualization,
  collapse/expand affordance for tasks with children.

### 5.3 Smart list page
- **Header:** ✨ + title; description line auto-generated from the filter
  ("open tasks · Priority = High · new tasks land in Inbox").
- **Body:** live task rows (checkbox, title→opens page, chips, 🍅). Empty state:
  "Nothing matches right now."
- **Quick-add bar:** text field + send; created tasks go to the home list and are auto-tagged
  to match equality filters (e.g., Priority=High) — communicate this magic clearly. ⚠
- ⚠ Wants: a real filter-editor UI (model already supports and/or/not, all operators,
  scoped roots, sorts — creation currently limited to 3 templates).

### 5.4 Ink editor (paginated canvas — Samsung Notes-like)
- **Canvas:** continuous vertical document rendered as A4-proportioned pages; hairline
  separators + page numbers; auto-extends (always one blank page below content).
  Paper: white in light / near-black in dark. Ink colors theme-swap (black↔white ink);
  accent pens stay fixed.
- **Input:** one finger or stylus draws (low-latency wet layer); two fingers scroll.
  Once a stylus is seen: pen draws, single finger scrolls. Hint text states the mode.
- **Toolbar:** color swatches (theme ink + coral/blue/green/amber), sizes S/M/L,
  brush family chips (Pen/Marker/Highlighter), page ▲▼ + "Page 1 / 2" indicator,
  undo (last stroke), clear.
- ⚠ Wants: proper tool UI (Samsung-style pen tray?), eraser, lasso later; visual polish for
  page separators; scroll indicator.

### 5.5 Focus (pomodoro)
- **Setup:** task title, duration chips (15/25/50 min), Start.
- **Running:** large ring countdown, state label, Pause/Resume, Finish (counts as done 🍅),
  Drop (abandoned). Finished: "Done! 🍅" + dismiss.
- **History (per task):** sessions with 🍅/◌ mark, timestamp, actual vs planned.
- ⚠ Wants: a real focus aesthetic (full-bleed? ambient?), maybe break timers, notification
  presence (v2 — currently in-app only, timer dies if the OS kills the process).

### 5.6 Stats
- Today (count + time), This week card; last-7-days bar chart; "Most focused tasks" ranked
  list. All computed from persisted sessions.
- ⚠ Wants: proper data-viz treatment, streaks?, per-list breakdown?

---

## 6. Component inventory (design these as a system)

- Round task checkbox (idle / done+check / press animation wanted)
- Property pill (filled w/ color dot · ghost "+ Name" · "+ Property")
- Property chip (compact, read-only, on rows)
- Home tile card (list / smart list variants, accent tinting)
- Active-timer banner
- Add-bar chip
- Block ⋮ menu
- Quick-add field (smart list)
- Ink toolbar (swatches, size chips, family chips, page controls)
- Ink preview (page-native inline strokes)
- Bar chart + stat cards (Stats)
- Dialogs: text-field dialog, confirm-delete, new smart list (template radio), new property
  (name + kind + options), date picker, bottom sheet (all properties of a row task)

---

## 7. Current visual language (baseline tokens — replace freely)

| Token | Light | Dark |
| --- | --- | --- |
| Background (paper) | `#FAF9F7` | `#161513` |
| Surface | `#FFFFFF` | `#201F1D` |
| Surface variant | `#F1EFEA` | `#2A2926` |
| Text primary | `#1C1B1A` | `#F2F0ED` |
| Text muted | `#8A8782` | `#97938C` |
| Outline | `#DDD9D1` | `#3D3B37` |
| **Primary accent (coral)** | `#FF4A1F` | `#FF4A1F` |
| Error | `#C62E2E` | `#FF6B5E` |

- **Accent palette** (list identities, select-option dots, pens):
  coral `#FF4A1F`, amber `#FFB020`, green `#34B27A`, blue `#4A90D9`, purple `#9B59D0`, pink `#E84393`.
- **Type:** system font; headlines Bold with −0.3…−0.5 tracking; titles SemiBold. (A custom
  display face for headlines would land well — Superlist uses a strong grotesque.)
- **Shape:** rounded 6/10/14/20/28dp scale; cards 14dp; pills 8dp; FAB standard.
- **Ink colors:** default pen is "ink" `#1C1B1A`↔`#F2F0ED` (auto-swaps with theme);
  paper white/`#161513`.
- **Icons:** Material Symbols (filled). App icon: coral rounded square + white check.

---

## 8. Motion & interaction (mostly unbuilt — define it)

- Page open (task → its page): currently default nav crossfade. Wants a
  container-transform / shared-element feel.
- Checkbox complete: wants a satisfying tick + subtle strikethrough animation, maybe
  confetti-restraint on completing a list.
- Reorder: wants drag handles with lift/settle physics (currently menu move up/down).
- Timer: ring should animate smoothly; state changes (pause) should feel physical.
- Ink: wet strokes already low-latency; page snaps on the ▲▼ buttons could ease.
- Keyboard: page keeps add-bar above IME (imePadding) — design the composer states.

---

## 9. Accessibility & platform constraints

- Both themes must hold WCAG AA for text; property-pill tints are 12–13% alpha washes over
  surface — verify contrast of colored text/dots.
- Touch targets ≥48dp (several 32dp icon buttons today ⚠).
- Dynamic type: layout must survive large font scales (FlowRows wrap; verify).
- TalkBack: rows need proper semantics (checkbox state, "opens as page").
- Edge-to-edge is enabled; respect gesture insets.
- RTL supported via AutoMirrored icons — keep it.

## 10. Hard constraints (do NOT change these)

- Data model is fixed: node tree + typed property registry + smart-list defs + pomodoro
  sessions + ink strokes (serialized `StrokeInputBatch`). Offline-first, sync-ready
  (UUIDs, LWW timestamps, tombstones). Design must not require schema breaks.
- Smart lists stay "computed views with a real home for writes" — don't redesign them into
  folders.
- Ink stays ink (vector strokes, never OCR'd into text as v1 default).
- Native Compose + Material 3 components as the base; custom drawing is fine.
- A future **canvas mode** (free 2-D placement of blocks; columns exist in the schema) —
  designs can tease it but v1 stays linear.

## 11. Deliverables wanted from the design pass

1. Visual system: color tokens (light+dark), type ramp, elevation/shape rules, icon style.
2. High-fidelity comps: Home, Node page (list + task-as-page variants, with ink block),
   Smart list, Ink editor, Focus (setup/running/done), Stats — phone; plus a tablet
   two-pane concept for Home + Node page.
3. Component sheet for everything in §6 with all states (default/pressed/disabled/empty).
4. Motion spec: page transition, checkbox, drag-reorder, timer ring, add-bar/IME behavior.
5. Empty states & first-run: workspace, empty list, empty smart list, no-stats.
6. App icon refinement + splash.

## 12. Open questions for design

- Home: flat sections vs. grouped/pinnable? Are smart lists visually *distinct enough* from
  lists (they behave very differently)?
- How loud should property pills be on a page with many properties? (truncation rules?)
- Where does "Focus" live long-term — per-task only, or a global tab?
- Ink block on the page: show a page-break marker when a sketch spans pages?
- Completed tasks: hide/collapse behavior on pages and in smart lists?
