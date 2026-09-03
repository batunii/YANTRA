# Yantra — the names of the parts

A vocabulary, so that "the block bar on a task page" means exactly one thing to both of us and
neither of us has to describe it twice.

This is not a style guide for identifiers. It is the **spoken** vocabulary — what to call a thing in
a bug report, a request, or a commit message. Its one rule:

> **The code name is the name.** If a part is `BlockTypeBar` in the source, we call it the block
> bar, and nothing else. Where the spoken name and the code name have drifted, this file says so
> and picks one.

`ARCHITECTURE.md` says what the app is. `DESIGN.md` says what it looks like and why. This says what
to call it.

---

## 1. The address of anything: Screen → Region → Element

Three levels, and naming all three is almost never necessary — but the order is fixed, so a partial
address is never ambiguous.

```
the block bar on a task page          → screen · element
the property row in the page band     → region · element
the type chips in the block bar        → element · sub-element
```

Say the screen when the same element appears on more than one (the capture bar, the now player, a
chip). Skip it when the element exists in exactly one place (the page band, the block bar).

---

## 2. Words for the model

These are load-bearing. The file format, the database and the UI all use them, and using them
loosely is how a request turns into the wrong change.

| Word | Means | Never means |
|---|---|---|
| **node** | One row in the `node` table. The universal unit — a list, a task, a paragraph, a sketch are all nodes. | Anything on screen. It is a storage word. |
| **page** | A node's own screen, and the `.md` file behind it. Every list and every task has one. | A block. A page *contains* blocks. |
| **block** | A node rendered as a line on a page — the things you type, draw and drop. | A task specifically. A task is one kind of block. |
| **task** | A block with a checkbox. `NodeType.TASK`. | Any to-do-ish thing. A smart list is not a task. |
| **list** | A page that owns tasks. `NodeType.LIST`. Its page shows tasks and nothing else. | A smart list. Say which. |
| **smart list** | A saved query over tasks it does not own. `NodeType.SMART_LIST`. | A list. It owns nothing. |
| **group** | A Home banner that gathers lists and smart lists. Organisational only. | A list. It holds no tasks. |
| **workspace** | One git repository of pages. Personal is a workspace with no remote. | A list, a folder, or an account. |
| **session** | One entry in the focus ledger. | A period of app usage. |

### The two traps

**"Note" means two things, and both are correct.** The pillar (`ARCHITECTURE.md`) is *notes* — any
detail attached to a task, typed or drawn or photographed. The block type is also called Note in
the UI, and it is `NodeType.PARAGRAPH` in the code. So:

- **a note** (pillar sense) — any non-task block. Prose, heading, bullet, sketch, picture.
- **a Note** / **a note block** — specifically a paragraph. What the Note chip makes.

When it matters, say **paragraph** for the block and **notes** for the pillar.

**"The task page" is one of two screens made by one file.** `NodePageScreen` renders both, and
`isTask` is the switch. They behave differently enough to need different names:

- **task page** — a task's own page. A *document*: blocks sit bare, you type into it directly, it
  has a block bar and no capture bar.
- **list page** — a list's own page. A *card of rows*: only tasks show, you capture through the bar
  at the bottom, there is no block bar.

Say **node page** only when you mean the file or genuinely mean both.

---

## 3. Screens

| Say | Route | File |
|---|---|---|
| **splash** | `splash` | `SplashScreen.kt` |
| **home** | `home` | `home/HomeScreen.kt` |
| **task page** / **list page** | `node/{id}` | `node/NodePageScreen.kt` |
| **smart list** | `smart/{id}` | `smart/SmartListScreen.kt` |
| **ink screen** | `ink/{id}` | `ink/InkScreen.kt` |
| **focus screen** | `focus/{id}`, `focus` | `focus/FocusScreen.kt` |
| **stats** | `stats` | `focus/StatsScreen.kt` |
| **settings** | `settings` | `settings/SettingsScreen.kt` |
| **sign-in** | `github` | `sync/SignInScreen.kt` |
| **add workspace** | `workspace/add` | `sync/AddWorkspaceScreen.kt` |
| **archive** | `archive` | `archive/ArchiveScreen.kt` |

"The GitHub screen" is the **sign-in** screen; the route is a legacy name and does not need
following.

---

## 4. The task page, region by region

The busiest screen, and the one worth naming to the element.

```
┌──────────────────────────────────────────┐
│  ‹   Workspace / Inbox      ⏱   ⋮        │  page band — top row
│                                          │
│  ☐  get milk                             │  page band — title row
│  [Due · Aug 26] [+ Assignee] [+ Deadline]│  property row
│  [#home]                                 │  label chips row
│  [→ Call Bob]                            │  linked row
├──────────────────────────────────────────┤
│  hello world                             │  ┐
│  ▨ (sketch)                              │  │ the document
│  Write something…                        │  ┘  (block rows)
│                                          │  page tail
├──────────────────────────────────────────┤
│  [Task][Note][Heading][Bullet]  | [Ink]  │  block bar    ┐ bottom
│                                          │  capture bar  │ cluster
│                                          │  now player   ┘
└──────────────────────────────────────────┘
```

### The page band — `PageBand`

The header. Folds to a single row as you scroll (`collapsed`); the fold is a design decision with
its own note in `DESIGN.md`.

| Say | Is |
|---|---|
| **top row** | Back circle · breadcrumb · timer circle · options — always visible. |
| **breadcrumb** | `Workspace / Inbox`. Root → parent, never the page itself. |
| **back circle**, **timer circle**, **options** | The three `NavCircle`s. "Circle" is the shape's name — see `Chrome.kt`. |
| **title row** | The task glyph plus the big editable title. |
| **page title** | The title itself. Editable in place; renaming writes the file. |
| **property row** (`PropertyRow`) | The pills: Due, Deadline, Assignee, Priority. |
| **pill** (`PropertyPill`) | One property. A **ghost pill** is the dashed `+ Assignee` — a property not set yet. |
| **label chips row** (`LabelChipsRow`) | `#home`, `#grocery`. Chips, not pills — see §6. |
| **linked row** (`LinkedRow`) | What the page's text points at. One chip per `[[link]]`, in order of first mention. |

### The document

The scrolling body of a task page. **Not** "the list" — a list page has a list; a task page has a
document.

| Say | Is |
|---|---|
| **block row** (`BlockRow`) | One block, whatever kind. Dispatches to the three below. |
| **textual block row** (`TextualBlockRow`) | Task, paragraph, heading, bullet, numbered — one composable, they convert freely. `NodeType.TEXTUAL`. |
| **ink block row**, **image block row** | A sketch, a picture. Both draggable, both long-pressable. |
| **block gutter** | The 30dp strip down the left of a draggable block, holding the drag grip. Prose gets a 16dp **prose margin** instead and no grip. |
| **drag grip** | The `⋮⋮` that fades in on the active block. |
| **nest step** | The 20dp one level of indent shifts a line. Indentation is layout only — it never reparents. |
| **write line** (`WriteLine`) | "Write something…" as its own row, on a genuinely empty task page only. |
| **page tail** | The tappable dead space below the last block. Tapping it puts the caret on a new line. |

Note the collision: **"Write something…"** is *also* the placeholder inside an empty paragraph. If
it matters, say **the write line** (the row) or **the paragraph placeholder** (the hint text).

### The bottom cluster

Everything below the document, in one column that owns the keyboard and navigation-bar insets. It
is named because the ownership is the point: **only the cluster may claim `imePadding()`** — two
claimants reserve the keyboard's height twice and squeeze the document to nothing.

| Say | Is | Shows on |
|---|---|---|
| **block bar** (`BlockTypeBar`) | Type chips, then the actions the selected block earns. | task page always; list page only with a block selected |
| **type chips** | Task · Note · Heading · Bullet · Numbered. A *selector*, not an add button — it converts the block the caret is in. | task page |
| **insert chips** | Ink · Image. These two *insert below* the caret rather than converting, because they carry content text cannot hold. | task page |
| **action chips** | Outdent · Indent · Props · Focus · Delete. Appear only once a block is selected. | both |
| **capture bar** (`QuickAddBar`) | The `Add a task…` field and its send key. | list page, smart list, home |
| **now player** (`NowPlayer`) | The on-the-go deck — what you have picked up. Hides while the keyboard is up. | everywhere except a task page |
| **suggestion strip** | The row of candidates above the bar. See §6. | wherever capture happens |

---

## 5. Other screens, briefly

**Home** — **greeting**, **section header**, **home row** (one list or smart list),
**group banner** (a group's header), **create panel** (the cog's sheet), **home tab bar** (the
bottom strip of keys).

**Smart list** — same page band; its body is rows, not a document. **absent workspaces** is the
notice for rules pointing at repos this device has not cloned.

**Focus screen** — **timer setup** (before), **active timer** (during), **done content** (after),
**session row** in the history, **duration chip** for a preset length.

**Ink screen** — **tool tray** (pen · marker · highlighter · shapes · eraser), **swatch row**.

**Stats** — **stat card**, **day bars**.

**Settings** — **setting row**, **accent swatch**, **ink legend row**, **glyph sample**.

**Archive** — **archived row**.

---

## 6. Shared furniture

The vocabulary that travels between screens. Getting these right is most of the benefit.

| Say | Is | Not |
|---|---|---|
| **chip** | A small rounded thing that is either a switch or a fact: label, due, priority, focus count, type. Declines focus. | a button |
| **pill** | A property on a page band, specifically. Radius 5dp, its own row. | a chip |
| **circle** | A round icon button in a header — `NavCircle`. | a chip or a button |
| **key** | A round or square control in a bottom bar — the send key, a transport key, a tab-bar key. | a button |
| **button** (`YantraButton`) | The real thing, with three tones: **solid**, **soft**, **quiet**. | anything above |
| **band** | A screen's rounded header block. Every screen has one. | a bar |
| **bar** | A horizontal strip of controls at the bottom. Block bar, capture bar, tab bar. | a band |
| **strip** | A transient horizontal row of *suggestions*, above a bar. | a bar |
| **sheet** | A modal from the bottom: **due sheet**, **property sheet**, **assignee sheet**, **smart-list builder**. | a dialog |
| **dialog** | A centred modal: **confirm dialog**, **label picker**, **switch-here dialog**. | a sheet |
| **row** | One item in a list-shaped thing. | a block, unless on a task page |
| **card** | A bordered container holding rows. | a band |

### The strips, individually

All appear above the capture bar and answer what is half-typed:

- **list strip** — after `~`, the lists that match. Offers a **new-list chip** for a name that
  matches nothing.
- **people strip** — after `@`, the workspace's collaborators.
- **link strip** — after `[[`, tasks that could be linked. Also `LinkSuggestions` on a task page.

### Glyphs

The drawn marks. `DESIGN.md` §3, §7, §8 is the authority; these are just the names.

| Say | Is |
|---|---|
| **bhupura** | The gated square. The brand mark, one path, redrawn at every size. |
| **bindu** | The centre point. Constant, never a gauge. |
| **task glyph** (`YantraCheckbox`) | The bhupura as a checkbox. Three states: **open**, **in progress**, **done**. |
| **enclosure** | The crimson/amber frame priority draws around a task glyph. Nothing else. |
| **ink strike** | The line struck through a done task's title. |
| **focus glyph** (`YantraFocusGlyph`) | The ledger, drawn. |
| **trikona** | The triangle that opens a day in the focus glyph. |
| **ring** | One session, parked on the **stack**. |
| **strata** | The whole outward-reading timeline of trikonas and rings. |
| **track** | The radius a live session's arc draws at. |

Do not say "checkbox" for the task glyph, or "progress ring" for a session ring. Both invite the
wrong behaviour — a checkbox has two states and this has three, and a progress ring implies a
target where an open session has none.

---

## 7. Naming in the code

What the source already does, written down so it keeps doing it.

**Files** — `<Thing>Screen.kt` for a route destination, `<Thing>ViewModel.kt` beside it. Shared
composables live in `ui/components/` named after the thing (`YantraField.kt`), or after the family
(`Chips.kt`, `Chrome.kt`, `Running.kt`). Data files are named for the concept, not the layer:
`Links.kt`, `CaptureParse.kt`, `StrokeCodec.kt`.

**Composables** — the spoken name in PascalCase. `BlockTypeBar` is the block bar. Where a composable
is private to a screen it may be short (`Wrapper`, `Field`); public ones carry the `Yantra` prefix
only when they are the app's version of a Material primitive (`YantraButton`, `YantraField`,
`YantraCheckbox`).

**State in a screen** — say what it holds, not that it is state: `activeBlockId`, `caretTarget`,
`dragOrder`, `linkDraft`. A boolean is a claim: `isTask`, `collapsed`, `lifted`, `settlingDrag`.

**Flows in a view model** — the noun, plural where it is many: `blocks`, `chips`, `ownLabels`,
`childCounts`. `own*` means "of the page node itself" as against its children.

**Repository methods** — the verb the user did: `captureTask`, `rename`, `setDone`, `splitBlock`,
`becomeBlock`, `pruneTrailingBlanks`. Not `updateNodeTitle`.

**Comments** — this codebase explains *why*, in prose, at the point of the decision, and says what
the wrong version did. Keep doing that; it is how the traps above stay documented.

---

## 8. When a name is missing

Name it after what the user would call it, then check that the code agrees — and if it does not,
rename the code. A part with two names is a part we will eventually mean two different things by,
which is the failure this file exists to prevent.
