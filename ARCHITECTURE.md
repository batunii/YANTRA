# Yantra — what it is, and the architecture that follows

Written after the app existed, which is the wrong order, and is why this document exists: several
subsystems were built for a reason that was true at the time and are now slightly out of step with
what the app turned out to be. Part 1 says what it is. Part 2 derives the rules. Part 3 audits the
code against them.

---

## 1. What Yantra is

**A todo app whose notes are as good as a notes app's.**

Not a notes app that tracks tasks. The spine is a task list, and the test of the whole thing is a
loop that has to be excellent:

> **capture → triage → do**

Everything else is in service of that, including the notes.

### The three pillars

| Pillar | What it means | Test of success |
|---|---|---|
| **Tasks** | The spine. Lists, Today, priorities, due dates, nesting. | A task is captured in under two seconds from anywhere. |
| **Notes** | Detail attached to a task, in whatever form the thought arrived. | **Attaching detail costs the same as adding the task.** |
| **Focus** | Doing the work, tracked as seriously as planning it. | A session's history survives and is visible where the work is. |

### What "first class notes" actually demands

The user's words: *"however you like to add details — via a pic, a link, a handwritten note in your
tab, anything."* That is a stronger claim than "notes are supported", and it has an architectural
consequence: **prose, headings, bullets, ink, images and links are peers.** None of them may be the
one that is awkward, lossy, or stored somewhere the others are not.

### Scale

**A few hundred *active* tasks feel instant.** The word active is doing work: the total is unbounded
and grows forever, so the architecture owes an answer for how the working set stays small. That
answer is archiving, and it is a concept the app needs rather than a feature it might add.

### Priorities, stated plainly

1. Capture, triage, do — the loop.
2. Notes of any kind, as cheap as a task.
3. Focus and its history.
4. **Sync is a capability, not the product.** Multi-device and shared repos are why the file format
   is what it is, and they are worth building — but nothing in the core loop may pay for them at the
   point of use.

### Compromises accepted, and why

| Compromise | Why it is acceptable |
|---|---|
| Ink merges last-writer-wins | A stroke set has no line structure for git to merge; pretending otherwise corrupts drawings rather than reconciling them. |
| Background sync is best-effort | Android's 15-minute floor is a request, not a promise. Pull-to-sync exists because of it. |
| Room holds every workspace in one database | So a single Today can span personal, project and shared. The cost is that scoping is mandatory. |
| Task ids are positional for unidentified blocks | A re-index during editing renumbers them; the alternative is an id on every prose line. |

---

## 2. The laws

Six invariants. Anything that breaks one of these is incoherent even when it works.

**L1 — Files are the truth, and the truth is complete.**
Everything a task carries lives inside the workspace directory. Not a pointer to it. If it is not in
the repo, it does not survive the device.

**L2 — The index is derived and disposable.**
Room is a cache of what the files say. Deleting it costs nothing. Nothing may exist only there.

**L3 — Capture is never blocked.**
No network, no lock, no rebuild, no confirmation stands between an intent to record something and it
being recorded. Everything expensive happens after, and may be deferred or coalesced.

**L4 — Sync observes; it never participates in a write.**
A write finishes without knowing sync exists. Sync learns afterwards, through a callback, and does
its work on its own schedule.

**L5 — One control per idea.**
A chip, a field, a button means the same thing on every screen. Learned once.

**L6 — The colour law.**
Neutral is structure. The accent is the user's own effort. Crimson and amber are the world asking.
Grey is rest. No hue means two things, and priority is never a preference.

---

## 2a. Focus — a ledger, not a timer

Focus is a pillar, and the current implementation is the pomodoro technique rather than the thing
the app actually needs. The correction is the same inversion as files-as-truth: **the session record
is the durable object, and the timer is one way of writing to it.**

### Two instruments, one history

| Mode | What it is | Why it exists |
|---|---|---|
| **Committed** (countdown) | This task gets exactly forty minutes. | A commitment device. Its value is not measuring — it is making *stopping early* visible to you. |
| **Open** (stopwatch) | Start, work, stop. | An observation device. Constrains nothing, tells the truth about where the time went. |

Time-boxing and time-tracking: prospective and retrospective. Most apps pick one. Both write the
same record.

### Why this is the app's own idea, not a borrowed one

The colour law already names **the accent as the user's own effort**, against crimson and amber for
the world asking. Effort is one of four things the palette treats as fundamental — and the only
place the app measures it is a count of pomodoros. A proper ledger is not a new feature; it finishes
a promise the design already makes in colour.

Effort is also the only honest axis a task has. A due date is a wish and *done* is a claim; time
given is evidence, and it is the feedback that tells you what your estimates are worth.

### Rules

**F1 — Every session that happened is recorded.** Interrupted sessions count. What varies is not
whether time is counted but how it ended: *reached its target · stopped by you · interrupted · lost
to the process dying*. All of them sum.

**F2 — One exception, and it is about accidents, not brevity.** A session under
`FocusOutcome.MIN_KEPT_SECS` that did not reach a target is discarded as a mis-tap. Anything longer is
real, including a deliberate three-minute commitment. A discarded session is still *closed* — written
with a `discarded` outcome and counted nowhere — because declining to write an end line left it open
in the log forever and the timer kept resurrecting it.

**Sixty seconds.** Roughly where an accidental start stops being plausible. (It sat at ten for a
while so the timer could be exercised without waiting out a minute; that is not a value to ship.)

The screen reads the same constant and asks **at the moment of stopping** — "too short to record ·
End anyway / Keep going" — rather than captioning the whole session. A permanent warning is noise for
the great majority of a session that is long enough and nags while you are working; the question only
matters when you are about to act on it. What it must never do is stay silent and let the session
disappear, which is where this started.

**F3 — "Too short to be interesting" is a display rule, never a recording rule.** The history view
may hide or collapse sessions under a few minutes; the totals still include them. Filtering at write
time would bake a display decision into a permanent, append-only record — the same category of
mistake as storing an image as a `content://` URI — and would understate effort in the one direction
nothing on screen would reveal. The threshold stays movable because it costs nothing to move.

**F4 — A report is a task query plus a time window.** Every dimension worth slicing by is already in
the filter language the smart lists compile: labels via `HasLabel`, workspace via the derived
workspace label, task via `node_id`. The only new axis is a window on `started_at`. So a focus report
is `Filter` (which tasks) + window (which sessions) + `SUM`. **No second query language.** Anything
expressible as a smart list can be asked "how much time did this get".

**F5 — Time rolls up the task tree, and is never summed across it.** A subtask's `parent_id` is its
parent task's id, so a parent's total includes its children — a parent reading zero while its
children read hours would look broken, and lists get subtree totals for free. But the same session
belongs to every ancestor, so **any aggregate goes back to the session rows and counts each once**.
A task therefore has two numbers, both meaningful: its own time, and its subtree's.

### What this costs

- `plannedSecs` becomes nullable; absent means open.
- `completed: Boolean` becomes an outcome, per F1.
- Aggregation moves out of Kotlin and into SQL as `SUM(actual_secs)`, grouped and windowed. Today
  `StatsScreen` loads *every session ever* and adds them up in memory, and `completedCounts()`
  returns a **count** — which is only a proxy for time while every session is the same length. The
  moment a stopwatch exists, counting is the wrong unit, and `WHERE completed = 1` discards exactly
  the interrupted sessions F1 says to keep.
- `Pomodoro` becomes `Focus` in the data layer, which the UI has called it for a while. The technique
  is a 25/5 protocol; naming the model after it misdescribes what it now holds.
- The log format is tab-separated and readers skip lines with fewer than seven fields, so a new field
  is invisible to an older app. This evolves without a migration.

---

## 3. The shape

```
  UI (Compose)            screens · components · theme
        │                 reads Room entities, calls repositories
        ▼
  Repositories            NodeRepository, PropertyRepository, LabelRepository,
        │                 SmartListRepository, PomodoroRepository, InkRepository
        │                 ── the only door between UI and data ──
        ▼
  Workspaces  ──►  WorkspaceWriter ──► WorkspaceStore ──► files on disk  ◄── TRUTH
        │                 │                                    │
        │                 └── onChange(Change) ──► CommitScheduler ──► SyncEngine ──► git
        │                        (L4: an observer)
        ▼
  Indexer ──► WorkspaceReconciler ──► Room   ◄── a cache of the above
```

**Read path:** UI observes Room Flows. **Write path:** UI → repository → writer → file → index.
The index is refreshed after the file, never before, so nothing can be in Room that is not on disk.

### Where each idea lives

| Idea | Home | Note |
|---|---|---|
| What a page contains | `data/format` — `PageDoc`, `PageCodec` | The public contract. A user may edit these files by hand. |
| Turning files into rows | `data/workspace` — mapper, reconciler, indexer | Pure function of the working tree. |
| Making a change | `WorkspaceWriter` | One mutex, one place that stamps `modified_at`. |
| Git | `data/sync` | Reached only through a callback. |
| Rules for smart lists | `data/filter` | Compiled to SQL against the index. |

---

## 4. Audit — where the code diverges

Ranked by how much the divergence costs, not by how hard it is to fix. Status appended after each
heading; the detail below every one is the finding as first written, kept so the reasoning survives
the fix.

### A. Images are not in the workspace — breaks **L1**, and the notes promise · **DONE**

An image block stores a `content://` URI as its payload. A persistable permission is taken, so it
survives a reboot *on that device*, and the bytes are never copied anywhere.

The consequences follow directly: the image is not in the git repo, so it does not sync; on a second
device the block is a URI that means nothing; and if the source is deleted or the SD card is
unmounted, the note is gone with no trace of what it was.

Ink, the other handwritten-note pillar, does the opposite and does it correctly — strokes are `.ink`
sidecars inside the workspace, committed with everything else. **So the two first-class note types
have opposite storage philosophies, and the one the user named first is the broken one.**

> Fix: copy the bytes into the workspace on pick (`pages/<id>.img`, or an `assets/` directory),
> reference relatively, and let git carry them. Around a day, plus a decision about large files.

### B. There is no archive — the working set grows forever · **PART DONE**

"A few hundred **active** tasks" implies a boundary between active and finished-long-ago. Nothing in
the app draws one. A completed task stays in its page, is re-read on every index rebuild, and is
re-inserted into Room forever. Deletion is the only removal and it is destructive.

This is the one finding that is not a bug today and is certain to become one: the indexing work done
this session is measured against the *whole* workspace, so a year of use degrades the core loop.

> Done: `archiveFinished(before)` moves a finished task's line to `archive/<pageId>.md` and its page
> to `archive/pages/`, out of the index and still in the repo; `restoreArchived` brings it back whole.
> Its prerequisite turned out to be missing entirely — nothing recorded *when* a task was finished, so
> a `done:` token was added to the line format.
>
> **Not done: the trigger.** No threshold setting, no view of what is archived, no periodic sweep.
> Deliberate — an automatic move with nowhere to see it and no undo is the one version of this that
> could lose someone's work. See §6.

### C. No share target — the cheapest capture path is missing · **DONE**

The stated requirement is that adding a link or a picture is as easy as adding a task. The manifest
has no `ACTION_SEND` filter, so the ordinary way anyone captures a link — share sheet, from the
browser, mid-thought — does not exist. Yantra cannot be shared *to*.

This is the largest gap against the core loop and among the smallest to close.

> Fix: a share-target activity reusing `QuickAddActivity`'s path. Text becomes a task or a note;
> an image becomes an image block, once (A) gives images somewhere to live.

### D. Property definitions are wiped globally — a real bug · **DONE**

`PropertyDao.clearDefs()` is `DELETE FROM property_def` with no workspace filter, while every other
clear in `Indexer.apply` is scoped. Rebuilding one workspace's index empties the property registry
for all of them and refills it from that one workspace. Harmless only because every workspace
currently scaffolds the same built-ins.

> Fix: scope the table, or accept that defs are global and stop rewriting them per workspace. An hour.

### E. Sync got the investment; the core loop got less · **NOTED, NO ACTION**

Not an architectural fault — the boundary is clean, and `L4` holds: `data/sync` is reached only
through `onChange`, and the UI touches it in two setup screens. Worth stating plainly anyway, since
it is the thing this session was mostly spent on and it is, by the product's own priority ordering,
fourth. Recorded so the next weeks go elsewhere.

### F. The UI is shaped by the index — tension with **L2**, but leave it · **DECIDED: LEAVE**

`ui/` imports `data.db` 47 times: screens read Room entities directly. Strictly, a disposable cache
should not also be the app's model. In practice the entities *are* a faithful projection of the file
format, the repository boundary is intact (the UI never imports `data.workspace` or `data.format`),
and interposing a domain model would add a layer of translation for an app this size and buy nothing.

> Recommendation: **do not change.** Recorded so it is a decision rather than an oversight.

### What is already coherent

Worth saying, because most of it is:

- **The repository boundary holds.** No screen reaches past it; every task is created through one path.
- **Focus is stored as a pillar should be** — append-only log lines in the workspace, merge-friendly
  by construction, history that survives, per-task figures on the page where the work is. What is
  wrong with focus is the *model above* that storage, not the storage; see §2a.
- **The write path obeys L1 and L2** end to end, and is now proven by tests that throw the index away
  and rebuild from files.
- **L3 holds after this session**: writes are immediate, indexing is deferred, commits are batched,
  strokes buffer in the session.
- **L5 and L6 hold**: one chip, one field, one button; the accent is a closed set that cannot reach
  the priority band.

---

### G. Focus is measured in pomodoros, not time — see §2a · **DONE, EXCEPT THE RENAME**

Ranked here rather than in §2a because it is a divergence like the others: the ledger counts fixed
units and sums nothing, which is correct only while every session is the same length. Settled in
discussion; not yet built.

---

## 4b. Coherence pass — does each feature feel native?

A different question from §4, which asked whether the code breaks the laws. This one asks whether a
feature looks like it grew here or was fitted afterwards. Both passes find real things; neither finds
the other's.

### Fixed in this pass

**The primary action was built six ways.** Home filled a box with the accent at 13dp; the focus
screen used an accent tint at 16dp in one place and 12dp in another; the ink tray used the same tint
at 10dp; the focus screen and the rule builder each kept a private button; and `YantraButton` — added
long after `SelectChip` cured exactly this for chips — arrived as a sixth rather than reusing any of
them. Now one component, three tones (`Solid`, `Soft`, `Quiet`, matching the three voices already in
use), one radius. Width belongs to the caller, because that genuinely differs.

**The drawing surface did not follow the theme.** `InkCanvas` chose paper, separators and page
numbers from six constants split between itself and `InkTheme`, selected by a boolean — while
`YantraColors.inkPaper` and `inkPageSep` computed the same thing correctly, including a pure-black
branch for OLED, and were **read by nothing**. So the notes surface, on the pillar the product cares
most about after tasks, was the one place the app's own palette did not reach. The canvas now takes
the resolved colours; `darkTheme` survives only where it is genuinely a boolean question — which way
round to remap a stroke.

**The empty state existed and was used once.** `ComposedEmpty` carries the Yantra mark and takes an
action, and appeared on one screen out of five — not Home, which is the first screen anyone sees. A
new user met a blank page.

### Left deliberately

**Ink keeps its own six-colour preset palette in raw hex**, including a copy of coral that will not
move when the accent changes. It is a *drawing* palette rather than a semantic one, so it is not
governed by the colour law — but the duplicated coral is a small lie and should reference the accent.
Worth doing; not urgent.

**Dialogs are Material `AlertDialog`.** Suspected as foreign, checked, and they are not: the M3
scheme is mapped thoroughly enough (`surface`, `surfaceContainer`, `onSurface`, `primary`) that they
inherit Yantra's palette. Shape and typography differ slightly. Not worth the churn.

**Corner radii drift** — eight distinct values in the card-and-button range. The button work removed
four of them. The rest are mostly deliberate, and a token set would be a large diff for a small gain.

### The standard to copy

The widget layer is the most coherent thing in the app. `GlanceWidgetTheme` reads the same accent
prefs the app does, and refuses Material You for a stated reason: *"a wallpaper-tinted bindu would
say 'your effort' in a hue that means nothing."* A feature that reasons about the colour law before
drawing a pixel is one that grew here. That is the bar.

---

## 5. Decisions taken

### Images — the downscale is the truth, the original is a local luxury

The repo carries a downscaled copy: long edge ~2048px, JPEG ~85%, **EXIF location stripped** — a
photo carries GPS, and committing one to a shared workspace publishes where you were. That copy is
what every device has and what the page file points at.

The device that picked the image *also* keeps a reference to the original and prefers it when
drawing. So the picture is sharp on the phone that took it and correct everywhere else, and the repo
stays small.

**The original's URI must never reach the page file.** A `content://` grant means nothing on another
device, so putting it in the file would be exactly the L1 violation this replaces. The file holds the
repo-relative path; the original is device-local state keyed by the block id. The URI the code stores
today is not wrong — it is the local half, written to the wrong place and missing its other half.

### Archive — finished tasks leave on a threshold, and can come back

A task finished longer ago than the threshold moves out of its page into an archive file beside it.
Still in git, still findable, out of the index — which is what holds the working set at "a few
hundred active" while the total grows forever, and what keeps a list like Inbox from carrying every
task it has ever held.

Two requirements attached to it: the threshold is the user's, not a constant; and **archiving is
reversible** — a task can be brought back, because finishing something is not the same as being
finished with it.

### Share target — lands in Inbox, re-filed in one tap

Shared text or a link becomes a task in Inbox immediately, with no screen in the way: capture is
never blocked (L3). The confirmation carries a single control to change the list, so filing is
available without ever being required.

Note that a toast cannot hold a button; this needs a brief bar or sheet that dismisses itself. The
task exists before it appears — the control re-files something already captured, and dismissing it is
never a way to lose the capture.

## 6. Still open

**Archiving is finished.** The threshold lives in the workspace manifest — a property of the repo
rather than the device, because archiving moves files that sync and two devices disagreeing would
mean the shorter setting silently winning. It defaults to **never**, since archiving moves someone's
work and the first launch after an update is the worst moment to do that unasked.

The sweep runs daily via `ArchiveWorker` **and once on launch**, because Android is free to decide a
periodic job can wait until tomorrow and archiving is what keeps the working set at the size the
whole indexing design assumes. For a workspace that has not opted in it costs one manifest read.

The archive screen is the reason the sweep can exist at all: it reads the archive files directly —
archived tasks are deliberately not indexed, which is the point of moving them — groups what it finds
by the list each task came from, and puts any single one back where it was.

**`Pomodoro` is still the name in the data layer** while the UI has said `Focus` for a long time
(§2a). Nineteen files, a table, a directory in the file format and four widget classes — worth doing,
and worth doing when it is the only thing in flight rather than alongside behaviour changes.

**Ink's preset palette** still hardcodes a copy of coral that will not follow the accent (§4b).

## 7. Added since, and not from the audit

Two things landed that no finding asked for, and both are architecture rather than features.

**A task records when it was finished.** The format had `[x]` and no date, so "done for thirty days"
was unanswerable and a task completed this morning was indistinguishable from one completed last
year. Archiving needed it; nothing else had ever asked.

**Typing carries its own meaning.** `buy milk tomorrow 6pm #home !high` is one line and four
decisions where the alternative was a task plus four taps, which is the capture loop the app exists
for. Deterministic — a fixed grammar, no model, no service — so it works offline, identically,
forever, and cannot invent a date.

Three rules keep it honest, and they are the interesting part:

- **Nothing is consumed silently.** Every match is tinted in place as you type, which is why the
  parser returns positions and not just values. A label is tinted the colour it is *about to become*,
  resolved through the same function the chips use so the preview cannot drift from the chip.
- **A token is never the whole title.** "Today" is a task called Today, not an empty task due today.
- **`#label` and `!priority` are the file format's own syntax** for those fields, so what you type is
  what the file says.
- **`> List` says where it goes.** `>` rather than `@`, which the format already spends on the
  assignee — the field shared workspaces were built for. It is the one token that is *not* written to
  the file: a task's list is the page it sits on, so naming one is a routing instruction spent at
  capture, with nowhere on the line to live afterwards. Names are matched against the lists that
  exist, because names contain spaces and a typo must leave the text alone rather than invent a list.

One path — `captureTask` — so the quick-add bar, the create sheet, smart lists and the widget cannot
disagree about what "tomorrow" means. The share target deliberately does *not* parse: what arrives
there was written by a web page, and a headline like "10 things to do today" must not lose its last
word and acquire a due date. Parsing is for what a person typed.
