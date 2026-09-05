# Yantra — the design language

A specification, written so that someone who has never seen the app can design a new screen for it
and have it look like it was always there.

Two things to know before anything else. **Every rule here has a reason**, and the reason is given,
because a rule without one gets broken the first time it is inconvenient. And **the code is the
truth** — every value below is quoted from a named file, so anything ambiguous can be settled by
reading it rather than by guessing.

---

## 1. What the app is, in one paragraph

Yantra is a to-do app whose notes are as good as a notes app's. Three pillars: **tasks**, **notes**
(typed, drawn, photographed — all first class), and **focus** (time given to a task, kept as a
ledger). Its files are plain Markdown in a git repository the user owns, which is a promise to them
rather than an implementation detail. The core loop is **capture → triage → do**, and capture is
never allowed to be blocked by anything.

Design consequence: the app is a *document* you work in, not a dashboard you monitor. Nothing
should feel like telemetry.

---

## 2. The two laws

### 2.1 The colour law

> `YantraGlyph.kt`, `YantraColors.kt`

Each hue lives on exactly one layer, and never appears on another:

| Layer | Hue | What it means |
|---|---|---|
| Structure | warm neutral | frames, tracks, grounds, text, rules |
| **Your own effort** | the accent (coral by default) | engagement, sessions, the bindu, the ink strike, **done** |
| **The world asking** | crimson / amber | priority — **task rows only** |
| Rest | grey | the break arc |

Three consequences that look like mistakes if you don't know the law:

- **Completion is coral, never green.** Finishing is your effort, not a system status. Green would
  open a fifth hue layer the law has no room for, and would disagree with the bindu.
- **Priority never enters the focus view.** A crimson enclosure you stare at for twenty-five minutes
  is ambient alarm. Priority survives there as a text label, never on the glyph.
- **The accent is choosable; crimson and amber are not.** The world does not take requests.

The law is why there is no hue engine. An earlier palette derived everything from one user-picked
hue, every role rotating together — which meant effort could be painted in the priority hue and the
layers stopped being readable.

### 2.2 The motion law

> `YantraGlyph.kt`

**Motion is punctuation, never texture.** One-shot transitions only. *Nothing on screen moves at
rest.*

One sanctioned exception: the live session arc, one revolution per session. One sanctioned rotation:
a trikona settling 12° → 0° as it is laid down.

No pulsing, no breathing, no shimmer, no glow. `accentGlow` exists in the palette and is deliberately
just the flat accent — a lit accent is texture.

---

## 3. The mark

> `YantraGlyph.kt` — `bhupuraPath()`

The brand is the **bhupura**: a gated square, drawn from a 28-unit design space and scaled. Four
gates (one per side), four rounded corners.

**It is one path and nothing may redefine it.** The checkbox, the focus glyph, the day seal, the
widgets, the notification icon and the launcher icon all draw *this* path. If a new surface needs the
mark, it scales the same path.

The centre point is the **bindu**. In the focus glyph it is constant at 1.6 units — it is the centre,
not a gauge.

---

## 4. Colour values

### 4.1 Grounds — one warm neutral, OKLCH steps

> `YantraColors.kt`, hue = 80°, chroma < 0.01

Warm rather than blue-grey because the language is a pen on paper: coral ink has to sit on something
that could plausibly hold it. Chroma stays under 0.01 so the ground never competes with the accent.

| Role | Light | Dark | OLED |
|---|---|---|---|
| page | `oklch(0.972 0.006 80)` | `oklch(0.171 0.005 80)` | black |
| surface (card, band) | `oklch(0.995 0.003 80)` | `oklch(0.216 0.007 80)` | `oklch(0.188 0.006 80)` |
| surface-high | `oklch(0.958 0.007 80)` | `oklch(0.257 0.009 80)` | `oklch(0.232 0.008 80)` |
| rail | `oklch(0.935 0.007 80)` | `oklch(0.137 0.005 80)` | `oklch(0.102 0.005 80)` |

Four modes: `SYSTEM`, `LIGHT`, `DARK`, `OLED`.

### 4.2 Text inks — the same neutral

| Role | Light | Dark |
|---|---|---|
| primary | `oklch(0.26 0.012 80)` | `oklch(0.948 0.005 80)` |
| secondary | `oklch(0.44 0.011 80)` | `oklch(0.735 0.008 80)` |
| muted | `oklch(0.545 0.010 80)` | `oklch(0.638 0.009 80)` |
| dim | `oklch(0.655 0.009 80)` | `oklch(0.510 0.010 80)` |

Hairline `= primary @ 8.5%`. Tile border `= primary @ 10.5%`.

### 4.3 The fixed inks

> `YantraGlyph.kt` — `YantraInk`. Dark values are lightened steps of the same hue so hairline strokes
> clear 3:1 non-text contrast.

| | Light | Dark |
|---|---|---|
| coral (default accent) | `#D85A30` | `#E8865F` |
| crimson (high priority) | `#A32D2D` | `#E24B4A` |
| amber (medium priority) | `#BA7517` | `#EF9F27` |
| neutral (frame ink) | `#5F5E5A` | `#B4B2A9` |

### 4.4 The accents — a closed set

> `AccentColor.kt`

The user chooses which ink owns the *effort* layer. Five options, and the closure is the whole point:

| | Light | Dark |
|---|---|---|
| Coral (default) | `#D85A30` | `#E8865F` |
| Jade | `#00A072` | `#3BBE8F` |
| Azure | `#0097B1` | `#00B8D6` |
| Indigo | `#5480EB` | `#7BA1F7` |
| Orchid | `#A864CF` | `#C08BE0` |

Three constraints every option satisfies, and any new one must:

1. **Outside the priority band.** Crimson (H≈25) and amber (H≈71) sit on the same task row. Every
   accent is far outside 20°–75°. This is the rule that cannot bend.
2. **Off the label hues.** Labels occupy 140/190/240/290/335; accents sit in the gaps, 22°–25° clear.
   A teal tag and a teal progress arc would say two different things in one colour.
3. **Coral's weight.** Each is generated at coral's OKLCH lightness and chroma, clamped where sRGB
   can't reach (Jade and Azure clamp). Switching accent changes the app's hue, never how loud it is.

Coral keeps its exact brand values rather than being regenerated — the glyph file is its source of
truth and a rounding difference would be a real bug.

### 4.5 Derived accent surfaces

`accentFill` = accent @ 12% light / 15% dark · `accentBorder` = @ 44% · `accentChipBg` = @ 15%/20% ·
`startedWash` = @ 9%/12% · `dueChipBg` = @ 14%.

`startedWash` is stronger than the 5% touch wash on an active block, because the touch wash is
transient and this one is a claim the user made and can leave standing for days.

### 4.6 Label palette

> `LabelPalette.kt` — OKLCH L=0.600 C=0.104 (light), L=0.730 C=0.125 (dark). Hues 140/190/240/290/335,
> every one clear of the reserved 24°–71° arc.

| | Light | Dark |
|---|---|---|
| Moss | `#5D8F52` | `#7CBB6E` |
| Teal | `#00948E` | `#0AC0B9` |
| Blue | `#3D88B8` | `#54B1EE` |
| Violet | `#8075BA` | `#A799F1` |
| Plum | `#A66799` | `#D889C7` |

A label with no chosen colour gets one **derived from its name**, not assigned in order — so the same
tag is the same colour on every device and after a reinstall, and a handful of new labels come out
different colours instead of all landing on the first swatch.

---

## 5. Type — three voices

> `Theme.kt`

| Voice | Face | Used for |
|---|---|---|
| Display | **Bricolage Grotesque** (500–800) | every big title, tight tracking |
| Text | **Space Grotesk** (400–700) | rows, labels, meta, buttons — the UI body |
| Instrument | **Space Mono** (400/700) | the focus countdown and eyebrows, nothing else |

Scale: page hero 32 · screen title 24 · smart-list/focus title 22 · card title 15.5/W700 · row title
15/W500 (line-height 20) · section label 12/W700 uppercase with extra tracking at the call site.

Mono is an instrument voice. A number that is being *watched* is mono; a number that is merely
reported is not.

---

## 6. Shape and reach

One radius per idea, and they are small:

| | Radius |
|---|---|
| Button (all tones) | 13 dp |
| Chip — small / medium | 8 / 10 dp |
| Card, archived row | 14 dp |
| Quick-add bar, sheet | 18 dp |
| Property chip | 5 dp |

Buttons have three tones — `Solid`, `Soft`, `Quiet` — and one radius. A chip declines focus
(`canFocus = false`): it is a switch, not a stop on the way through a form, and taking focus would
pull the caret out of the field it is configuring.

### The two-line row

> `NodePageScreen.kt` — `TextualBlockRow`

A task row on a list is **exactly two lines, whatever it holds**: the title on one, and one line of
meta beneath it in the instrument voice. Both handoffs specify this — "the two-line grammar" — and
the reason is what happens without it. Meta used to be chips: one rode up onto the title line, two
or three sat in a wrapping cloud below, a long title took a second line of its own. Five tasks could
be five heights, and a list read as a ragged pile.

**Due sits at the end of the title line**, in the voice its chip wears everywhere else: crimson past,
accent today, neutral further out. A day list is scanned for dates, so the date goes where the eye
already is.

**The sub-line is what the task is:** labels as `#name`, each in its own hue; then a deadline; then
the list it came from. It ellipsises from the right, so the list goes first — it is the least urgent
thing on the line and one tap away on the task itself.

**What a row does not carry.** Assignee, session count and the workspace are all real facts, and
none of them changes what you do next in a day list. They were crowding out the tags, which do. The
rule is the one every list app converges on: a row carries what the next decision needs — what the
task is, and when it is due — and the rest lives one tap away.

**The workspace is a hue, not a word.** Written on every row it is the same word five times, taking
the space the tags needed. Grouping by it is the textbook fix and it costs too much here: a view like
Today is ordered by what is most pressing, and cutting it into per-repository runs puts an urgent
task in one below a quiet one in another — the ordering is the point of the view. So the repository
rides on the list name the row already prints, tinted by `LabelPalette.defaultFor(name)`: one piece
of text carrying two facts at the width of one, which is what Reminders does with a list's colour.
Neutral when only one repository is open, because a colour that always means the same thing means
nothing.

Character comes from colour, not from a box drawn round every value. The chips went because five of
them read as five buttons and wrapped into a second row; the hues survived them.

### One margin

> `Panes.kt` — `PAGE_MARGIN`

**22dp down both sides of a phone**, and every list of tasks is that wide. It had drifted to four
values — 14dp on a list and a smart list, 18dp on Home, 20dp on stats and the archive — which is the
same row drawn four widths, and walking between those screens made the content appear to breathe.
The large title keeps the same edge, so a screen's name and its first row line up.

The document on a task's page is the one exception, and deliberately: its text sits inside the drag
gutter that belongs to the blocks, and that gutter *is* the margin there.

### Reach — where a control may sit

> `Chrome.kt` — `PageHeader`

A phone this size cannot be worked one-handed at the top. The thumb covers the bottom two thirds and
the top corner not at all, so the screen has two zones and they are not interchangeable:

| Zone | Holds | Never holds |
|---|---|---|
| Top third | the screen's name, and nothing else | anything you have to hit |
| Bottom third | every control that matters | the thing you are reading |

Three rules follow, and they are the whole of it:

1. **A screen opens with its name set large, and the name is not a control.** It spends the
   unreachable band on the one thing you only ever read. It folds into the bar on scroll — a
   one-shot transition, so nothing is mid-fold at rest.
2. **No *chrome* in the expanded band is tappable** except the back circle, which is a courtesy —
   the real way back is the system gesture, which needs no target at all. The distinction is
   between chrome and content: a page band carries the page's own title, glyph, properties and
   links, and those are the thing you came for, not navigation furniture put where it fits. What
   the rule forbids is a *control* parked up there because there was room — a settings cog in the
   top-right corner is the clearest case, being the one pixel a thumb cannot reach.
3. **Actions go to the bottom** — the block bar, the capture bar, the tab bar, the now player, a
   row's own play key. If a new control has nowhere at the bottom to live, that is a question about
   the control, not about the bottom.

`PageHeader` is this pattern for an ordinary screen. The node page keeps `PageBand` instead, which
is the same shape carrying a document's furniture — an editable title, a task glyph, a breadcrumb,
property pills, a linked row — and folds all of it. Home and the smart list have their own hero
bands for the same reason. Everything else uses `PageHeader`, and a new screen should.

This is why the focus stats page has no chart at the top and a column of play keys down the right:
the diagram is read and the keys are pressed, so the diagram is where the thumb is not.

---

## 7. The task glyph — three states

> `YantraCheckbox.kt`

| State | Drawing | Meaning |
|---|---|---|
| **OPEN** | the bhupura, neutral outline | not started |
| **IN PROGRESS** | accent circle inside the neutral frame | *you said you are on this* |
| **DONE** | bare bindu, plus an ink strike through the row title | finished |

The in-progress fill is **constant**. The only motion is the ring drawing in as the state is entered.
No pulsing, ever.

**Priority draws the enclosure and nothing else** — a crimson or amber frame around the glyph. A done
task loses it: there is no urgency left to report, and the law keeps crimson off completion.

### Interaction mapping

**Tap = complete. Tap a done task = undo.** Marking in progress is a *swipe on the row*, not a
gesture on the glyph. So the glyph has one meaning per press, and the two states you can reach by
touching it are the two the pen actually decides: finished, or not.

### Choreography

| | ms |
|---|---|
| frame un-draw | 280 (40 delay) |
| ring trace | 220 |
| ring collapse | 180 |
| strike | 280 (140 delay) |
| bindu lands (THUD haptic) | 300 |
| reduced motion — whole transition | 200 crossfade, alpha/colour only |
| bulk fast path | 200, when within 800 ms of the last completion |

Haptics are the *feel channel* and survive reduced motion: a LOW_TICK when a hold arms, a THUD when
the bindu lands. With animations off, the haptic carries the whole reward.

---

## 8. The focus glyph — a ledger, drawn

> `YantraFocus.kt`

The strata are a **sequential timeline reading outward from the centre**. A **trikona** (triangle)
opens each day; then one **ring** per session that day.

> Day 1: triangle, ring, ring, ring. Day 2: triangle, ring, ring.

One mark per event, no legend needed.

- **Trikonas alternate orientation per day** — day one points *down* (the Shakti trikona, the
  traditional innermost orientation), day two up, and so on, echoing the interlocking convention.
- **The live session** draws an accent arc at the track (radius 8 — the task's own circle, so the
  list glyph and the focus glyph agree). One revolution per session.
- **Breaks drain a neutral arc.** Rest is the ink receding, not a second colour arriving.
- **When a session closes** its ring travels from the track inward and parks on the stack — bright
  while moving, fading to rest weight. The THUD fires when the ring *parks*: the reward is the
  deposit, not the bell.
- **A day's first session lays its trikona first** (fade-in, 12°→0° settle), then its ring follows.
- **Every arrival redistributes the whole stack**, so any number of days and sessions fits forever.
- **A cancelled session deposits nothing.**
- **The frame is always neutral here.** Priority never enters this view.
- **The bindu is constant.** Exact counts belong to the numeral below the glyph — digits over the
  ring band fight the strata.

It renders as a pure function of the session log. There is nothing extra stored to draw it.

### Two instruments, one history

A **committed** session counts down to a target set in advance — a promise, whose value is that
stopping early is visible. An **open** session counts up until stopped, promising nothing and simply
telling the truth about where the time went. Both write the same record.

Outcomes are `RAN_OUT`, `STOPPED`, `INTERRUPTED`, `LOST`, `DISCARDED`. Interrupted time **counts** —
the ledger measures what you gave, not whether you obeyed yourself. Sessions under 60 s are not kept,
so the history is not clogged with noise.

---

## 9. Chips and row furniture

| Chip | Colour |
|---|---|
| Due | accent text on accent @ 14% |
| Overdue | crimson on crimson @ 13%/16% |
| Priority high / medium | crimson / amber |
| Label | its palette colour, tone-adjusted for chip size |
| Focus count | a timer glyph and a numeral |
| Neutral | primary ink @ 5.5%/7% |

A chip's colour is resolved through one function so that the same label cannot be one colour as a
chip and another anywhere else — this has actually drifted before.

---

## 10. Capture — reading what was typed

> `CaptureParse.kt`, `CaptureHighlight.kt`

Typing `buy milk tomorrow 6pm #home !high ~Groceries` is one line and five decisions. The parse is
**deterministic** — a fixed grammar, no model, no network. It works offline, identically, forever,
and cannot invent a date.

Three rules keep it honest, and the tinting is the whole safety story:

1. **Nothing is consumed silently.** Every match is tinted *in place, as you type*, which is why the
   parser returns positions and not just values. A word that quietly vanished from a task title would
   be far worse than one that was never recognised.
2. **A token is never the whole title.** "Today" is a task called Today, not an empty task due today.
3. **`#label` and `!priority` are the file format's own syntax**, so what you type is what the file
   says.

### The tints

| Token | Ink |
|---|---|
| date, time | `due` (the accent) |
| `!priority` | `overdue` (crimson) |
| `~list` | `accentText` — structure's own ink; a list is *where a task goes*, not something about it |
| `#label` | **the colour it is about to become** |

That last one is the interesting one. If the tag exists it wears its own colour; if it does not, it
wears the one it *will be given* — which is knowable in advance, because the default is derived from
the name. So typing `#home` shows you the chip you are about to make, and typing a tag you already
use shows you that you are adding to it rather than starting something new. A single generic tint
could not say either of those things.

**The text is never rewritten, only coloured** — the transformation is identity-mapped, so the caret
lands exactly where it was put. A transformation that rewrote the string would move the caret under
the user's finger.

### The list mark

`~` names a destination. Matching ignores case *and spacing* — `~worktrips`, `~Work trips` and
`~WORK TRIPS` are one instruction, because the shortcut earns its keep mid-sentence and one-handed.
A name that matches nothing is created. A suggestion strip offers the lists that already match, so a
typo shows you the real list to tap rather than quietly becoming a fourth spelling of it.

Two shapes are deliberately *not* lists: a tilde inside a word, and a tilde before a digit — `~5 mins`
is how people write "about five minutes".

---

## 11. Ink

> `InkScreen.kt`, `InkTheme.kt`

Tools: pen, marker, highlighter, shapes, eraser.

The ink layer follows the **app's** theme, not the phone's. Paper is white on light, near-black on
OLED, `oklch(0.152 0.005 80)` on dark — warm graphite and warm off-white matching the app's paper,
because a hand-drawn stroke is the one place a mismatched ground shows immediately.

Default ink is graphite `#23211C` on light paper and chalk `#F1EEE7` on dark, and the two **swap**
with the theme so a sketch is legible in both. Accent and colour strokes pass through unchanged.

The swatch row leads with **the live accent**, resolved for the current theme, then five colours that
are only colours: blue `#6FA8E4`, green `#4E9478`, amber `#E0A83E`, pink `#C56A94`, purple `#8B6BA8`.

Only the *swatch* follows the accent. A stroke stores the colour it was drawn with, so changing accent
later repaints nothing — a drawing is a drawing, not a themed surface.

---

## 12. Things that will look like bugs and are not

- Completion is the accent, not green.
- A crimson frame never appears in the focus view.
- Emphasis (`**bold**`) renders in notes and headings but **not in task titles** — a title is a name,
  and it appears on surfaces that cannot style anything (widget, notification, archive), where
  emphasis could only be dropped or shown raw. So a title is literally its characters, everywhere.
- Markdown markers stay **visible but dimmed** while editing rather than being hidden, so the rendered
  string is exactly as long as the stored one and the caret cannot drift.
- The task glyph cannot be tapped to reach *in progress*. That is a swipe.
- Nothing animates at rest, including things that would look nice animating at rest.

---

## 13. Asking for more

Anything not covered here is decided by the two laws first and the file second. If a new element
needs a hue, the question is not "what looks good" but **which layer it belongs to** — and if the
answer is "a new one", the answer is no.
