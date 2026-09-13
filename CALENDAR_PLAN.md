# Calendar and events — implementation plan

Two decisions taken up front:

- **Events are a new first-class block, with recurrence.** Not a calendar view over `due:` dates —
  a thing that has a start and an end, no done state, and lives in the page file like every other
  block. YANTRA owns these: create, edit, delete, repeat.
- **The device calendar is read in, never written to.** Google/Samsung events are drawn alongside
  yours as a backdrop. YANTRA never creates, edits or deletes anything in the provider.

**The second decision is what keeps this safe**, and it is worth being explicit about how much it
removes rather than treating it as a limitation. There is exactly one writer for every piece of
data: YANTRA owns what is in the repo, the provider owns what is in the provider, and neither
reaches into the other. Nothing below has to answer "both sides changed, now what" — because one
side never changes.

An earlier draft of this plan specified two-way sync. What that cost, and what dropping it saves, is
in §5.

---

## 1. What is already here

Worth stating precisely, because most of an event is already in the format under another name.

| | |
|---|---|
| A task line | `- [ ] Write the deck ^a1b2 due:2026-09-12 deadline:2026-09-15 !High #work @sam` |
| Time model | `DueValue.AllDay(LocalDate)` or `DueValue.At(Instant)`, wrapped in `DueSpec(value, reminderMin)` |
| Reminders | `ReminderScheduler.schedule(nodeId, atMillis)` on AlarmManager, driven off `DueSpec.reminderMin` |
| Date picker | `DueSheet` already draws a month grid — `CALENDAR_WIDTH = 48.dp * 7 + 12.dp * 2` |
| Line markers taken | `- [ ]`/`- [x]`/`- [~]` task, `- ` bullet, `# ` heading, `1. ` numbered, `![[ink:id]]`, `![[img:…]]` |
| Room | version 11, one migration, `exportSchema = true` |
| Calendar provider | **Nothing.** No `READ_CALENDAR`, no `CalendarContract` anywhere. `WRITE_CALENDAR` is never going in |

So: reminders, a date model and a month grid exist. What does not exist is a *span*, a *repeat*, and
anything outside the repo.

## 2. The three things that need care

### 2.1 An instant is the wrong type for a recurring time

`DueValue.At` holds an `Instant` — an absolute point on the timeline. That is correct for "remind me
at this moment" and **wrong for a repeating event**. A standup at 09:00 every weekday is 09:00 local
time: across a DST boundary the instant moves by an hour and the wall clock does not. Storing
instants and expanding a rule from them gives you a meeting that silently shifts to 08:00 for half
the year.

Events therefore need **local date-time plus a zone**, not an instant:

```kotlin
data class EventTime(
    val start: LocalDateTime,
    val end: LocalDateTime,          // or a Duration; see §3
    val zone: ZoneId?,               // null = floating, i.e. "09:00 wherever you are"
    val allDay: Boolean,
)
```

A floating zone is a real case (a birthday, a personal reminder) and distinct from a zoned one (a
meeting with people in another country). Both are needed; `CalendarContract` models the same
distinction with `EVENT_TIMEZONE` and its all-day convention.

### 2.2 Recurrence exceptions are where the merges happen

Expanding an RRULE is the easy half. The hard half is that occurrences get **edited and deleted
individually** — this week's standup moved to 09:30, next Monday's is cancelled — and those edits
have to survive a git merge between two devices that each edited a different occurrence.

The obvious storage is a growing list on the series line:

```
@ 09:00-09:15 Standup ^s1 rrule:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR except:2026-10-28,2026-11-04
```

**Do not do this.** Every exception rewrites one line, so two devices cancelling two different
occurrences produce a conflict on that line, and per-file last-writer-wins throws one of them away.
The workspace format's whole advantage over the ink sidecar is that it *has* line structure for git
to merge (`ARCHITECTURE.md`: "a stroke set has no line structure for git to merge") — spending that
advantage on the one field guaranteed to be edited from two places at once would be a poor trade.

Overrides and cancellations are **their own lines** instead:

```
@ 09:00-09:15 Standup ^s1 rrule:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR
@ 09:30-09:45 Standup ^s1x1 series:s1@2026-10-28
@ cancelled ^s1x2 series:s1@2026-11-04
```

Two devices cancelling two different days now add two different lines, and git takes both.

### 2.3 Two calendars in one view, with one writer each

Device events and YANTRA events appear in the same list and must be told apart at a glance, ordered
together, and refreshed on different schedules — the provider changes underneath you, the repo
changes when you or a sync says so. That is a real amount of view work, but it is not *risk*: a
device event drawn wrongly is a wrong pixel, not a lost appointment.

One consequence worth taking early: **only YANTRA's own events need recurrence expanded by us.**
`CalendarContract.Instances` already expands rules, exceptions and cancellations in the provider and
hands back concrete occurrences for a time range. So §4's expander runs over repo events only, and
the device side asks a query. This is a large saving and it is only available because we never write
— an expander good enough to *round-trip* provider recurrence would be a different piece of work.

## 3. The event block

### Marker

```
@ <when> <title> ^<id> [tokens…]
```

`@ ` at line start. Rejected alternatives and why:

- **`* `** — renders as a bullet in any markdown viewer, which is prettier, and collides with a
  bullet somebody types by hand. This format already refused to let a block's *kind* hinge on
  something an editor might touch ("a checkbox is a checkbox whether or not it says anything"), and
  a bullet turning into a meeting because of a leading asterisk is the same fault.
- **`- [@]`** — puts an event in the checkbox family, and an event has no done state. It happens; it
  is not finished.
- **`![[event:id]]`** — matches ink and images, but those are references to a sidecar. An event's
  content is the line.

`@ ` is unambiguous, greppable (`^@ `), and renders as plain text rather than as the wrong thing.

### When

| Form | Means |
|---|---|
| `2026-09-11` | all-day, that day |
| `2026-09-11/2026-09-13` | all-day, the 11th to the 13th **inclusive** |
| `2026-09-11T14:00` | a moment |
| `2026-09-11T14:00/PT1H` | start plus ISO-8601 duration |
| `2026-09-11T14:00/2026-09-11T15:30` | explicit end |
| `2026-09-11T14:00[Europe/Dublin]/PT1H` | zoned; absent means floating |

**A bare `09:00-09:15` was dropped during Phase 0.** An earlier draft allowed it, with the date
coming from "the page's own day" — but a page has no day, so on a standalone event that form has no
date at all and the rule could not be stated without inventing one. Every when-slot carries a full
date, which also makes a file of events sort and grep by time without a parser.

Duration rather than end-time is preferred on render: a 1-hour meeting that moves stays 1 hour, and
a diff shows one changed field instead of two. A hand-written explicit end survives anyway —
`rawStillDescribes` re-parses the line, gets the same event, and writes the bytes already there.

All-day spans are **inclusive in the text and exclusive in the model**, because the inclusive
reading is what somebody writing "the 11th to the 13th" means and the exclusive one is what
arithmetic wants. `PageCodec` is the seam and the tests pin both sides.

### Tokens

Reused from the task line wherever the meaning is identical, so there is one thing to learn:
`^id`, `#label`, `@attendee`, `!priority`. New: `rrule:`, `series:`, `loc:`, `cancelled`, and
`remind:<min>` — a standalone token rather than the `due:` line's `+r<min>` suffix, which only made
sense appended to a value.

### Model

```kotlin
data class EventRef(
    val id: String,
    val title: String,
    val time: EventTime,
    val rrule: String? = null,           // RFC 5545 subset, see §4
    val series: SeriesRef? = null,       // this line overrides one occurrence of another event
    val cancelled: Boolean = false,
    val location: String? = null,
    val reminderMin: Int? = null,
    val labels: List<String> = emptyList(),
    val attendees: List<String> = emptyList(),
    override val indent: Int = 0,
    override val raw: String? = null,
) : Block

data class SeriesRef(val id: String, val originalStart: LocalDateTime)
```

`raw` matters as much here as anywhere: an event written by a newer build with a token this one does
not know must round-trip untouched, exactly as `TaskRef` keeps unrecognised trailing words in its
title.

### Guards

- Round-trip: parse → render → parse is a fixed point, for every form in the table above.
- An unknown token survives a round trip.
- A `@ ` line inside a fenced code block is not an event (the parser needs to know about fences here
  if it does not already).
- A bullet starting with `*` is still a bullet.

## 4. Recurrence

**A subset of RFC 5545, stated explicitly**, because "we support RRULE" is a promise nobody keeps:
`FREQ` (DAILY/WEEKLY/MONTHLY/YEARLY), `INTERVAL`, `BYDAY`, `BYMONTHDAY`, `COUNT`, `UNTIL`. Anything
else is stored verbatim, expanded as best it can be, and never rewritten — a rule this build cannot
fully model is still the user's data.

- Expansion is **lazy and windowed**: the calendar view asks for a date range and gets the
  occurrences in it. Never expand an unbounded rule eagerly.
- Expansion happens in **local time**, then converts to instants for display and alarms — see §2.1.
- Occurrences are indexed into Room over a rolling horizon (say ±1 year) so the view and smart lists
  can query them like anything else. The index is disposable and rebuilt from files, as always.
- An override line replaces the occurrence whose original start it names; a `cancelled` line removes
  it.

Guards: a weekly 09:00 event stays 09:00 across a DST boundary in both directions; `COUNT` and
`UNTIL` stop it; an override moves exactly one occurrence and no other; two overrides on different
dates both apply.

## 5. Reading the device calendar

Read-only, and the boundary is absolute: YANTRA issues no insert, no update, no delete against
`CalendarContract`, and requests `READ_CALENDAR` only. `WRITE_CALENDAR` is never in the manifest, so
the strongest guarantee here is one the OS enforces rather than one this code promises.

### What that removes

Worth listing, because it is most of the original plan:

| Problem two-way sync had | Why it is gone |
|---|---|
| Identity mapping YANTRA id ↔ provider id, device-local, in Room | Nothing to map back. A provider event is read, drawn and forgotten |
| Distinguishing "deleted from the provider" from "never created there" | Both are simply "not in this query's results" |
| Echo suppression, so our own writes are not read back as remote edits | There are no writes to echo |
| A conflict rule for "both sides changed since last sync" | One writer per side. The question cannot arise |
| Mapping our override lines onto `ORIGINAL_ID` / `ORIGINAL_INSTANCE_TIME` | Only needed in the write direction |
| A journal of provider mutations, to describe a bad sync afterwards | No mutations |
| Two-way recurrence translation | `Instances` expands for us — see §2.3 |

### What is left

1. **Permission.** `READ_CALENDAR` is a dangerous permission: a runtime request, a clear reason
   shown before asking, and a calendar view that works perfectly well without it — the overlay is
   an addition to your own events, never a precondition for them.
2. **Calendar picker.** Which of the account's calendars to draw, stored **device-locally** — a
   phone and a tablet signed into different accounts have different answers, and a choice in the
   repo would make one device's calendars appear on the other as ids that mean nothing.
3. **Query.** `CalendarContract.Instances.query(cr, projection, beginMs, endMs)` for the visible
   range, filtered to the chosen calendars. It returns occurrences, so recurrence and its exceptions
   arrive already resolved.
4. **Refresh.** A `ContentObserver` on `CalendarContract.CONTENT_URI` re-queries the visible range.
   Harmless now: it can only cause a redraw.
5. **Drawing them as not-ours.** Distinct enough that nobody tries to edit one and wonders why it
   will not save. Tapping one offers to open it in the calendar app that owns it.

### Why this is not the image mistake

`ARCHITECTURE.md` §A criticises image blocks for holding a device-local `content://` URI that does
not sync, and it is right to. This is deliberately the same shape and is **not** the same mistake,
because the difference is ownership. An image you picked is your content and belongs in the repo. A
Google Calendar event is not yours to own — it lives in an account, is already mirrored to each of
your devices by its own sync adapter, and copying it into the repo would make a second stale copy of
something that is not YANTRA's to keep. Drawn as a backdrop, it disappears cleanly when the account
does, which is correct.

Anything you want to *own*, you make as a YANTRA event.

### What it came to

All five parts, and two things the plan had not said:

- **An all-day event is read in UTC; a timed one in the reader's zone.** The provider stores all-day
  as UTC midnight to UTC midnight — a date wearing an instant's clothes — so resolving it locally
  puts a birthday at nine in the morning in Tokyo and at seven the *evening before* in New York.
  Half the world would see it on the wrong day, and the half that would not is the half a developer
  in Europe happens to test in. `DeviceEventBucketTest` checks every all-day case in three zones,
  one of them west of UTC.
- **`DayItem.Device` is its own kind, not an event with a flag.** It has no node, no file and no id
  of ours, and every gesture that changes a block asks for a node id. Giving it a synthetic one that
  looked like the others would be inviting exactly the write the design forbids, so `BlockChip`
  refuses the drag outright rather than relying on a handler to decline. Its `nodeId` is prefixed
  `device:` so anything that mistakes it fails loudly instead of writing somewhere strange.

Drawn fainter than your own and in its own calendar's colour, unmapped — that colour is the other
app's identity and recognising it is the point. A tap hands the occurrence back to the app that owns
it, with the instance's time range, so it opens on the occurrence you were looking at rather than on
the series' first.

The picker lives in **Settings** rather than the calendar screen, because it is a device-local
preference and that is what the screen is for. The reason is shown before the request: a dangerous
permission asked without explanation is one people refuse permanently on behalf of a feature they
never saw.

## 6. The calendar view

- **A month grid and a day list.** Not the month/week/day trio an earlier draft promised: a week
  view is a different layout rather than the same one re-parameterised, and it can be added on top
  of the same view model when there is a reason to. Month plus day answers "what is on the
  eleventh", which is the question.
- **`DueSheet` could not be reused after all.** It delegates to Material3's `DatePicker`, and a
  picker cannot show what is *on* a day. What is shared is the metric — 48dp a cell, seven across —
  so the two read as the same calendar even though the code is separate.
- Draws events **and** `due:` tasks in one list, sorted together. The point of putting events in the
  same format is that one surface shows both, and interleaving two lists in the view is how they end
  up sorted differently.
- A day with anything on it gets up to three dots. The day list runs all-day first, then by clock,
  then tasks with no time of their own — "today" is weaker than "today at three".
- A repeating event is marked, because only its first occurrence is drawn until expansion lands. An
  unmarked one would look like a one-off somebody mistyped.
- The whole content column is width-capped and centred. A seven-column grid stretched across a
  tablet gives cells the size of playing cards, and capping only the grid left the header and the
  day list against the left margin, reading as two unrelated screens.
- Tapping an item opens its page. Tapping an **empty** slot to create an event is Phase 3.
- Device-calendar events, once §5 lands, draw in the same list marked as not-ours and not editable.

## 7. Order of work

Each phase is useful on its own, and each one's guards go in with it.

| Phase | What | Why here |
|---|---|---|
| **0** ✅ | `EventRef`, `EventTime`, the `@ ` grammar, codec round-trip tests | Freeze the format before anything reads it. `GIT_WORKSPACES_PLAN.md` §2 is emphatic about this and it was right |
| **1** ✅ | Indexing events into Room, a bump to version 12 | The view and smart lists query the index, not the files |
| **2** ✅ | The calendar view — month grid and a day list, over events and `due:` tasks | First point the feature is visible |
| **3** ✅ | Create/edit/delete an event, reminders via the existing scheduler | Reminders are already built; events just feed them |
| **4** | Recurrence: the RRULE subset, windowed expansion, override and cancellation lines | Needs 0–3 stable underneath it |
| **5** ✅ | Device calendar: `READ_CALENDAR`, calendar picker, `Instances` query, overlay in the view | Independent of 0–4; could be built alongside them by someone else |

## 8. What the index holds

`event`, one row per event node, keyed to it and cascading on delete so a removed page cannot leave
its events behind.

**[start_local] and [zone] are the truth; [start_utc]/[end_utc] are a convenience** — derived by
resolving the local time in the event's zone, or in *this device's* zone when it is floating. A
floating event therefore indexes to different instants on a phone in Dublin and a tablet in Tokyo,
which is correct rather than a bug: floating means local, and the index is rebuilt per device from
files that both agree on.

`EventDao.inRange` is a **candidate** query, not an answer. Three clauses: the ordinary overlap; a
moment (`start == end`), whose empty span the overlap test would hide; and every recurring event
regardless of its own span, because the row holds only the first occurrence and a weekly standup
begun in September must come back when November is asked for. Deciding which occurrences actually
land in the window is expansion's job — Phase 4 — not SQL's.

An event with an explicit `^id` keeps it, exactly as a task does, and that is load-bearing: an
override names its series by id, and a derived id is a line number that changes the moment anything
is inserted above. A series renumbered by an unrelated edit would come apart.

## 10. Blocking, and the day and week views

"Blocking" here is the sense TickTick and Notion Calendar use, not exclusivity: a task with a start
and a length is drawn on a timeline beside an event, and **things overlap freely**. Nothing reserves
anything — the job is to show the clash, not to prevent it.

- **A task's `due:` can carry a length**, spelled as the same ISO interval the event when-slot uses:
  `due:2026-09-11T14:00:00Z/PT1H+r15`. Two spellings for one idea is one more than anybody should
  have to learn. A length on an all-day task is refused rather than stored.
- **`property_value` gains `v_duration_min`** rather than another meaning piled onto `v_number`,
  which already carries the reminder offset on that same row.
- **Overlaps share the width**, decided per connected run rather than per pair — a block can need
  its own column because of something it does not itself touch, and a pairwise decision gives a
  layout that jumps as you scroll past the middle item. `TimelineLayout` is pure and tested.
- **An all-day bar** takes what a ruler cannot honestly draw: all-day things, undated tasks, and
  anything crossing midnight. The same split TickTick makes.
- **Three days on a phone, seven on a tablet.** A seven-column week on a phone gives each day about
  fifty pixels — a coloured sliver with no room for a word. Seven snaps to the Monday of its week
  because a week has edges; three does not, because it is a window you push along and anchoring it
  would throw the selected day to the far side of the screen. The segment names what it shows, so a
  phone says "3 days" rather than lying with "Week".
- Deciding that removed the need for a second layout mode: an earlier version cascaded overlapping
  blocks once columns got too narrow to hold a word, and two staggered blocks read as one smeared
  block with their labels run together.

### The look

The first version was crude and drawn as such — worth recording so it is not rebuilt that way.

- The hour grid was **twenty-four bordered boxes**. A border draws a *rectangle*, so every hour got
  a line down both sides as well as across it, and the day read as a spreadsheet. It is one canvas
  of hairlines now, with a fainter half-hour rule.
- Blocks had a **1dp outline on top of the outlined grid** — two competing rectangles. They have a
  soft fill and a 3dp spine down the left instead, which is how a calendar says "this one is mine"
  without drawing a second box.
- The **mode switcher** was three loose 22dp letters, flush against each other, at half the 48dp
  minimum. It is one segmented pill in the page header's actions — down in the bar it left the
  heading forty pixels and "Thu 10 Sept" came out as "T…".
- The **heading** now says what is on screen: a month, a range, or a day. In the day view there had
  been nothing at all naming the day.

## 11. Sittings: a task on the calendar, and focus on top of it

The question this answers: *what does it mean to put a task on the calendar?* The answer taken here
is the one that makes the feature worth having — **it means "I am going to work on this then"**, it
can happen more than once, and it is where a focus session runs.

### Why `due:` with a length is not enough

§10 gave a task's `due:` an optional length, which draws it as one block. That covers the simple
case and stops there: a task you mean to sit down to twice has **two** intentions and one due date,
and there is nowhere to put the second. Stretching `due:` to a list of spans would also make the
deadline and the plan the same field, so moving a working session would move the deadline.

### A sitting is its own line

```
- [ ] Write the deck ^t1 due:2026-09-12
@ 2026-09-11T14:00/PT2H ^s1 for:t1
@ 2026-09-12T09:00/PT1H ^s2 for:t1
```

A `for:<taskId>` token on an event line means *this block is time set aside for that task*. It reuses
the event grammar entirely — a sitting **is** an event, with a referent.

Consequences, each of them a reason for the shape:

- **No title.** A sitting with a title would store the task's name twice, and the format's own rule
  is that nothing is stored in two places. It draws with the task's title, read through `for:`.
- **Two devices can plan different sittings** and git takes both, because they are separate lines —
  the same argument as §2.2's overrides.
- **Ticking the task off does not delete them.** They are a record of what you meant to do, and a
  week of them is the honest answer to "where did that go".
- **The task's own `due:` stops drawing as a block** once it has sittings, and draws as a deadline
  marker in the all-day bar instead. Otherwise the plan appears twice.

### Focus, planned and actual

`FocusSessionEntity` already carries `nodeId`, `startedAt`, `endedAt` and `actualSecs`, and is already
indexed per workspace. **The "what I actually did" half of this exists today and nothing draws it.**

So the calendar gets two layers over the same hours:

| Layer | Where it comes from | What it says |
|---|---|---|
| **Planned** | `for:` sittings, and events | what you meant to do |
| **Actual** | `focus_session` rows | what you did |

Drawn together: the sitting as a block, the actual session as a solid inset bar within (or beside)
it. A sitting you never started is an outline with nothing in it; one you overran shows the bar
running past the block's foot. That comparison is the whole point, and it is the thing no
general-purpose calendar can do because it does not know what you were working on.

Starting focus from a sitting is then the obvious gesture: the block already names the task, so the
play button on it starts the clock the app already has, through the existing `TimingRequest` so that
"something else is already running" is asked the same way it is asked everywhere else.

**Actual sessions are drawn, never edited.** A log of what happened is not a thing to drag around,
and the focus log is append-only for that reason.

### Open

1. Does an actual session with **no** sitting draw at all? (Proposal: yes, faintly — you did the
   work whether or not you planned it, and a calendar that only shows the plan flatters you.)
2. Does starting focus **create** a sitting retroactively? (Proposal: no. The actual layer already
   records it, and inventing a plan after the fact is how a planner starts lying to you.)
3. Should a sitting carry its own reminder, or inherit the task's?

## 12. Moving things about

None of this exists yet, and its absence is what makes the timeline feel like a picture rather than
a plan.

| Gesture | What it does | Notes |
|---|---|---|
| **Drag a block** | Moves it in time — and between days in the multi-day view | Snap to 15 minutes. The write is `editEvent`/`editTask`, already there |
| **Drag its lower edge** | Changes its length | Needs a handle big enough to hit without moving the block |
| **Drag on empty ruler** | Creates a block over the dragged range | The tap-to-create from §7 phase 3 is the degenerate case of this |
| **Drag a task in** | From the day list, or from a list screen, onto the ruler | This is the "blocking" gesture people mean; it writes a `for:` sitting |
| **Long-press a block** | Delete / start focus / open the task | |

Two things to get right, both of which are about the file rather than the finger:

- **Write on drop, not during the drag.** A drag is dozens of frames and each write is a whole-file
  rewrite plus a reindex; only the final position is a decision.
- **Snap, and say so.** Fifteen minutes, with the time shown while dragging. A block that lands at
  14:07 because that is where a thumb was is a block nobody chose.

## 13. Getting a task onto the calendar

Two ways in, both kept, because they answer different moments. One is "I have a list and a day to
fill"; the other is "I have a gap and I want to know what fits in it".

### A. The split: a day and a rail beside it

Three quarters timeline, one quarter tasks. The rail is what the calendar page is missing today —
a task with no date is invisible on a calendar, which is precisely the task most in need of one.

**The rail is a set of buckets you page between**, not a search:

| Bucket | What it holds | Why it earns a place |
|---|---|---|
| **Today** | due today | what you already said you would do |
| **Soon** | a deadline inside the next few days | what is about to become today's problem |
| **Undated** | no due, no deadline | the backlog. The one a calendar normally cannot see at all |
| **Everything else** | has dates, but none of the above | scheduled further out, or overdue and not yet faced |

Pick one up, drop it on an hour, and that writes **one sitting**. No sheet, no naming step: a
sitting has no name by design — it draws with the task's title — so the objection in §12 to creating
blocks silently does not apply to it. What you dropped is what you meant.

The rail is also the answer to a constraint §12 glossed over: you cannot drag onto a day that is not
on screen. The rail sits beside **one** day, and that day is the one you are filling.

### B. Full screen: mark the time, then say what it is for

Timeline edge to edge. Drag out a region, and the app asks what goes in it — the same bucketed list,
as a sheet over the marked range. Pick a task and the sitting exists.

This is the inverse gesture and it suits the opposite mood: not "where does this task go" but "I have
two free hours on Thursday afternoon — what should be in them?" It also works one-handed, and it is
the only one of the two that fits a phone in portrait without the rail squeezing the day.

### When the time comes

The bar at the bottom of the screen already holds **everything on the go, newest first**, and
`TasksRepository.setInProgress` is explicit that the middle state is deliberately not limited to one:
*"Having several things on the go is the ordinary shape of a day."* A sitting starting is exactly
that shape, so this feature is mostly a matter of pointing existing parts at each other.

1. **The sitting's start puts the task in the bar**, ready, with its play button — not running.
2. **The bar orders by sitting, not by recency.** A task whose sitting is happening *now* comes
   first. Newest-first is a reasonable default with nothing better to go on; a sitting is something
   better to go on.
3. **Press play** and focus starts, through the existing `TimingRequest` so that "something else is
   already running" is asked the way it is asked everywhere else. **This** is the moment the task
   becomes `- [~]`, because that is the moment you actually picked it up.

**The one thing not to do is flip `- [~]` at the sitting's start**, and the research is clear about
why. The Reclaim objection does not apply — nothing here records time you did not spend, because
play is still yours to press. But the Teams objection does: a status set by a clock and cleared by
nothing accumulates. A sitting that passes while you are in a meeting would leave a task marked
picked-up that you never touched, in a file that syncs to your other devices and commits to git.

Microsoft's presence has exactly this complaint against it — "In a meeting" for three hours whatever
you are doing — and Adobe Workfront refuses to let its "Work On It" button set a status at all,
because people press it when a task *arrives* rather than when they start, which skews the record.
If a deliberate button press is judged too weak a signal to write a status against, a clock tick is
weaker.

So: **the bar carries the readiness, the file carries the fact.** You get the thing you actually
asked for — the task waiting there with a play button when its time comes — without a synced file
claiming you did something you have not done yet. If a sitting passes untouched, the only trace is
§11's empty outline on the timeline, which is a record of the plan rather than an accusation.

### What a drag turned out to need

Three passes, and the last two were only findable by watching a real finger:

1. **A drop is structural.** A plain edit defers its reindex by 200ms, which is right for typing and
   wrong for a calendar — every block it draws comes from the index, so the block sprang back to
   where it was and arrived at its new hour a beat later.
2. **The block stays where you left it until the file agrees.** Making the write immediate removed
   the wait but not the shape of the problem: the moment a release hands the block back to the data,
   any delay at all is a flicker. `DayTimeline` holds the committed geometry until the index says the
   same thing, or a second and a half passes — a write that never arrives must not freeze a block in
   a position the file does not have.
3. **Both boundaries move.** A block has two edges and a person stretching an hour has no reason to
   prefer one: pulling the top back is the same thought as pushing the foot out. The grabbable end is
   capped at a third of the block's height so the middle third is always somewhere to take hold of
   the whole thing — two fixed 18dp ends swallow a half-hour block entirely.

And one shape worth keeping: **move and stretch are one callback**, `onSpan`, because they are one
fact about a block said three ways. They were two, and the two drifted — a recording showed the
resize landing a beat later than the move, and the only reason was that each had its own copy of the
same write.

## 14. Build order

Written before starting, because most of the cost in a feature like this is discovering an
integration point halfway through. Each step is separately verifiable, and the risky ones are named.

| # | Step | Verified by |
|---|---|---|
| 1 | ✅ `for:` on the event line — `EventRef.forTaskId`, parse and render | JVM codec round-trip |
| 2 | ✅ `event.for_node_id`, migration 13→14, mapper writes it | Migration replay on device |
| 3 | ✅ A sitting resolves its title from the task it points at | JVM, through the DAO projection |
| 4 | ✅ Sittings draw as sittings and open on their own terms | `EventSheet`, `BlockChip` |
| 5 | ✅ The rail: four buckets, pure, then the UI beside the day | `RailBucketTest` |
| 6 | ✅ Putting a task on the day — from the rail, and from a marked range | `TaskRailTest` |
| 7 | ✅ A sitting's start puts the task in the bar, ready and first | `RunningStackTest` |
| 8 | ✅ Play starts focus and writes `- [~]` | already wired; `App.kt` marks on session start |

### What the build actually taught

Three things the plan had not decided, decided by meeting them:

- **A sitting lives on its task's own page**, not the Inbox, written directly under the task. Two
  devices reading the file see the plan beside the thing it is a plan for, and a page deleted takes
  its sittings with it rather than leaving them pointing at nothing.
- **A sitting carries `remind:0`.** It is not an appointment you travel to; the notification *is* the
  moment, and it arrives as the task appears on the bar with its play button. No second alarm kind
  was needed — `ReminderManager` already arms events, and `ReminderReceiver` now resolves a sitting's
  words and its deep link through the task it is for.
- **One row, one target.** The rail's rows carried a chevron for opening the task. In a rail a
  quarter of a phone wide, a 22dp button beside a 37dp title is not two targets — it is one target
  with a trap in it, and the UI test caught a tap aimed at the row landing on the chevron. Tap arms
  the whole row; a long press opens the task.

### The integration points that will bite

- ✅ **A sitting has no title of its own, and `EventWithTitle` joins the wrong node.** That projection
  takes `node.title` for the *event's* node, which for a sitting is empty by design. It needs a
  second `LEFT JOIN` through `for_node_id`, and the calendar has to prefer that title. Getting this
  wrong shows up as a day full of blocks labelled "Event".
- ✅ **The bar is shared.** Readiness is a third source combined in `RunningTask.stack`, never a
  second meaning stuffed into `inProgress`. The pure function is where the ordering is tested.
- ✅ **Drag between two scrollables is the fragile part.** Tap-to-arm shipped first and the drag
  followed, carried in **root coordinates** — the two panes are siblings with no shared ancestor
  either can see, so the rail row publishes where the finger is, `DayTimeline` publishes where its
  hour lane is, and `DayWithRail` does the arithmetic between them. Held near an edge the day scrolls
  under the drag, or you could only ever drop on the five hours already on screen.

  Two things bit, both predicted by the note above and neither visible without the test:

  - **The drop handler was stale.** It lives inside a `pointerInput` keyed on the row, so it is the
    lambda from the *first* composition for the whole life of the gesture — and a minute computed
    during composition and captured in it is the minute as it was before the finger moved, which is
    to say null, for every drag. The minute is read through the state holders now, not captured.
  - **The chevron bisected a narrow row.** At a quarter of a phone the 28dp button's left edge lands
    one point from the centre, so the tap that should lift a task opened it. The row measures itself
    and does without below 120dp: one target, and it is the one the rail exists for.
- ✅ **`EventSheet` must not offer a sitting a title field.** It has no title; it borrows one. Opening a
  sitting should offer time, length, reminder, *Open the task*, and *Remove from calendar* — never a
  bare "Delete", which reads as deleting the task.
- **Deleting the task must take future sittings and leave past ones.** The cascade already deletes
  an event row with its node; a sitting's *referent* going away is a different question, and an
  orphaned `for:` must degrade to an ordinary event rather than vanish or crash.

### Deliberately not in this pass

Recurrence on sittings, dragging a sitting between days in the multi-day view, and the actual-focus
layer from §11. The last is close — `focus_session` already has the times — but drawing it before
you can make a sitting would be drawing the answer to a question nobody can ask yet.

## 15. Open questions

1. ~~**Whose event is it?**~~ **Settled: the Inbox.** It is where this app already puts a thing
   captured with no home — the same answer quick-add gives — rather than a `calendar/` area the
   format has no notion of. An event made from a page belongs to that page; one made from a month
   belongs nowhere in particular, and "nowhere in particular" already had a name here.
2. **Do events archive?** Tasks archive on a threshold after completion. An event is never completed;
   a year-old one is just old. Left alone for now, but a workspace of standups grows forever.
3. **Attendees are `@name` strings**, the same as `assignee`, and carry no email. Nothing is sent to
   anybody: an attendee here is a note about who is involved, the same as an assignee on a task.
   This is not a scheduling feature and should not look like one.
4. **Timezone display**: does a zoned event show its own zone or the reader's? (Proposal: the
   reader's, with the original noted when they differ.)

## 16. Colour

A block can wear a colour, `col:Teal` on the line. Three decisions, and each of them was already
made somewhere else in this app:

**A name in the file, a value in the index.** The line says a word; the word becomes an ink at render
time through `LabelPalette.display`. Writing the hex would freeze whichever theme was on when it was
picked, and a light-mode colour on a dark ground is the one that goes muddy. An unrecognised word is
**kept, not dropped** — a file written by a newer build must not lose somebody's choice the first
time an older one opens the page — and it **inherits rather than painting nothing**, so an unknown
colour looks ordinary instead of invisible.

**The palette is the label palette.** Not a wheel. Those five were already chosen to sit on this
paper, to stay clear of the 24°–71° arc the colour law reserves for priority and effort, and to hold
one lightness across every hue so no swatch out-shouts another. A second set of calendar colours
would be a second thing to learn and a second chance to collide with the accent.

**Inheritance follows the hue the app already has.** No colour on the line means the workspace's, and
the workspace's is `LabelPalette.defaultFor(name)` — the same hue the smart lists and the widget
already use to say which repository a task came from. Following it rather than inventing a per-
workspace preference is what keeps one workspace one colour everywhere you meet it. And, exactly as
those do, it applies **only when more than one repository is open**: with a single one it
distinguishes nothing, and tinting every block in the app a colour nobody chose is noise.

A coloured block replaces the spine and tints the wash; it does not flood the fill. A day of solid
colour blocks is a chart, and the words stop being the thing you read.

## 17. Zoom

An hour is sixty points tall and always has been, which makes a day two and a half screens. That is
right for a morning with three things in it and wrong for a day you want to see the shape of, and
wrong again for a half-hour block you are trying to put a handle on.

**Pinch, and the hour under your fingers stays put.** Zooming is only useful if it keeps your place:
scale the scroll offset around the focal point, or the day leaps and you have to find Tuesday
afternoon again. Clamped to a range with a reason at each end — small enough that a whole working
day fits on a phone, large enough that a fifteen-minute block is a real target.

**It is a view preference, so it is device-local and it persists.** Nothing about how tall you like
your hours belongs in a repository, and re-pinching on every visit would be worse than no zoom.

The mechanical part is that `HOUR_HEIGHT` is a constant read in nineteen places. It becomes a
composition local rather than a parameter threaded through six composables: every reader is already
in a composable, and a local cannot be passed inconsistently by one caller that forgot.

## 18. An event you can write on

**An event owns a page, exactly as a task does.** This is not a new mechanism — it is the one the
app is built on. A task is a line on its parent's page and its contents are a separate document
named by its id; an event is already a node with an id and a type, so `pages/<eventId>.md` needs no
format change, no migration and no new concept. `ensurePage` already creates the file lazily, the
moment there is something to put in it, so an event you never write on costs nothing.

What it buys is the thing that is missing: an agenda, what was decided, who said what, a link to the
deck. Today that has to go in the title or on some other page, and the second one is worse — notes
about a meeting that live somewhere other than the meeting are notes you will not find again.

### Three cases, three answers

| What you tapped | What opening it means |
|---|---|
| **Your own event** | Its page. Notes about the meeting, on the meeting |
| **A sitting** | The **task's** page, not the sitting's. A sitting is a piece of time, not a subject — its notes are the task's notes, and two hours on Thursday is not a thing you have anything to say about |
| **Somebody else's** (§5) | It has no node and no file, so it cannot have a page of its own. It gets a **note attached to it** instead — see §19 |

**A tap on somebody else's event opens a sheet, not another application.** It used to hand the
occurrence straight to the calendar that owns it, which is one of the two right answers and a poor
way to offer it: a single tap that throws you into another app is not a choice, and it left no way to
act on the thing from inside this one. You would tap Tuesday's meeting meaning to write a note about
it and find yourself in Google Calendar. The sheet is read-only and says so — nothing on it can
change their event, and that is not a restriction this code imposes but the absence of a permission
it will never hold.

### What has to change

1. **An event line on a page has to look like one.** It renders through `TextualBlockRow` today, so a
   page shows the event's *title* and nothing else — no time, no marker — and a sitting, which has no
   title by design, renders as an **empty row**. That is why the Inbox looks empty while holding two
   events. An event row shows its when, reads as an event, and carries the chevron that opens it.
2. **The page has to say when it is.** A document with a title and no date is a note that used to be
   a meeting. The event's when belongs in its header, the way a task's chips sit in its.
3. **A way in from the calendar.** The block's sheet gains *Open notes*, beside the row a sitting
   already has for opening its task.

### What does not change

The line stays the record. A page is what the chevron opens, not where the event moves to — the
`@ ` line keeps the time, the id and every token, and `PageDoc.title` remains authoritative only for
a parentless page, so an event's name stays on its line where the format already puts it. Deleting
the event takes its page with it, because `removeBlock` already walks the subtree.

## 19. Notes on somebody else's meeting

The first attempt at this **copied** the event: a YANTRA event with the same words and hours, which
then had a page. It is wrong twice over, and both are the kind of wrong you only see in use.

- **The day shows two blocks for one meeting.** Theirs and your copy, at the same hour, for ever.
- **The copy drifts.** The meeting moves to four o'clock in the calendar that owns it; your copy sits
  at two, and now your own calendar is lying to you about when you are expected somewhere.

The shape every app in this category actually uses is a **link, not a copy**. Notion Calendar
attaches a Notion doc to the existing Google event and the event stays the organiser's. Granola
relates meetings by the calendar's own recurring-event id and never duplicates one. So:

### A note names the meeting it is about

`@ 2026-09-16T14:00/PT1H Design review ^n1 ext:abc123@google.com`

`ext:` carries the identity **the sync source gave the event** — `UID_2445`, the iCalendar UID the
organiser's system generated, falling back to `_SYNC_ID`, the id the account assigned it. Both are
the same string wherever the event reaches, which is what makes one safe to write into a repository
that syncs. Deliberately **not** the provider's `_ID` or `EVENT_ID`: those are row numbers this
device made up, different on your other phone and gone after a reinstall, so a file carrying one
would be claiming a relationship it cannot honour anywhere else.

One occurrence of a repeating meeting adds the start — `ext:<uid>@2026-09-16T09:00` — the same
`id@start` grammar `series:` already uses. A note about this Monday's standup must not become a note
about every Monday.

**The UID is usually an address.** `abc123@google.com` is the ordinary Google Calendar shape, so the
occurrence split is on the **last** `@`, and a tail that does not parse as a date is part of the
identity. Splitting on the first would take `google.com` for a start time and quietly destroy the
identity of every event in the account.

### One meeting, one block

When the meeting is on screen, the note's own line **stands aside** and the meeting is drawn
carrying the note's id — so there is nothing to duplicate and nothing to drift. The times drawn are
always theirs, because theirs are the real ones.

The title and times on the note's line are a **cache, not a claim**: they are what makes the file
readable by a person, and what still draws when the calendar permission is taken away or the meeting
is deleted. Notes you wrote can never become unreachable.

### What is left open

An event the provider gives **neither** a UID nor a sync id for cannot be annotated at all, and the
sheet does not offer to. It is rare — a local-only calendar with no account behind it — and the
honest answer is to say nothing rather than to attach a note to a row number that will not exist
next week.

## 20. Turning their meeting into a task

Some meetings are appointments and some are work. "Design review, 14:00" is an hour you will spend
on the design review, and the thing you want from it afterwards is not a note — it is the same
machinery every other piece of your work gets: a checkbox, a list, a focus session, a place in
Today.

### It composes with `for:` rather than needing anything new

A sitting is already "this time is for that task" (§11). A note is already "this line is about their
meeting" (§19). They are two tokens on one line, and the line that carries both says exactly the
right thing:

```
- [ ] Design review ^t1
@ 2026-09-16T14:00/PT1H ^n1 for:t1 ext:abc123@google.com
```

*Their meeting is the time set aside for my task.* Nothing in the format has to be invented, and
everything already built comes along: the task is a real task with a page, a list and a checkbox;
the bar says **IT IS TIME** when the meeting starts; play runs a focus session and writes `- [~]`;
and the day still draws **one block, at their hours**.

### The finding that makes it work — and that is a bug today

**Every mechanism keyed on our own row's times is wrong for a line whose times are a cache.**
`EventDao.openSittings` reads `e.start_utc`; `observeEventReminders` computes the alarm from
`e.start_utc - reminder_min`. For an `ext:` line those columns are what the meeting said *when the
note was written*. Move the meeting in Google Calendar and:

- a reminder on a note fires at the old hour — **this is already true, today, without any of this**;
- a task attached to it would light the bar at the old hour too.

The fix is one rule, and it repairs the existing bug as well as enabling this: **when the overlay is
read and a matched meeting's times differ from the line's cache, rewrite the line.** The cache is
then never more than one calendar-read stale, and every existing mechanism is correct without
knowing that a provider exists. It is a write to our own file, which is allowed; it is never a write
to theirs, which is not.

Guard: rewrite only when the times actually differ, or every read of the calendar dirties the repo
and every sync carries an empty diff.

### What the block says

The meeting keeps **its own name**, because a meeting is called what it is called and your Wednesday
afternoon should be recognisable. Where an attached task has been renamed to something else, that
goes on the second line — the one that currently holds the time and the place. Honest in both
directions, and no decision to make.

### The task is due when the meeting is

Settled: it gets a `due:` at the meeting's start, so it reaches Today and the rail's Today shelf like
any other piece of today's work. That writes the schedule in two places — the due date and the
meeting — and the price is paid by the refresh above: move the meeting and the due date moves with
it, in the same pass that corrects the cache.

The alternative was to leave it scheduled only by its sitting, which is one source of truth and
invisible in Today. That would have been the tidier model and the less useful app, and the §11 gap
behind it (a task with a sitting today is not *today's* work anywhere) is still open and worth
closing on its own terms.

## 21. One way to write about a thing on your calendar

There were three, which is two too many. An event owned a page (§18); somebody else's meeting could
carry a note line (§19); and a meeting could have a task attached (§20). Three answers to one
question — *I want to write something about this* — and which one you got depended on whose event it
was and which button you found.

**A task is the app's one noun that carries a document.** It has a page, a checkbox, a list, a focus
timer and a place in Today. Building a second thing with a page beside it was the mistake; the
answer is not another mechanism but the one already there.

So: **anything on your calendar you want to write on becomes a task.**

### Converting is lossless because a task can hold a span

`DueSpec` has carried a `duration` since §10 — that is what lets a task be drawn to scale on the
timeline in the first place. So an event maps onto a task without inventing anything:

| Event | Task |
|---|---|
| title | title |
| `2026-09-16T14:00/PT1H` | `due:2026-09-16T14:00` with a duration of `PT1H` |
| an all-day span | an all-day `due:` |
| `remind:15` | the due's own reminder offset |
| labels, priority, indent | the same |
| `loc:Room4` | the first line of the task's page — a task has nowhere else for it, and a page is exactly the place for a detail about a thing |

**The id is kept**, because `editBlock` maps a block to a block and the node is the same node. Which
means an event that already had notes on it comes out as a task with those notes: the conversion
upgrades what is there rather than replacing it, and nothing anybody wrote goes missing.

The one real loss is **colour**, which a task line cannot carry yet. Worth fixing by letting a task
wear one — the timeline already tints any block it is told to — and worth saying out loud rather
than dropping quietly.

### What this removes

The *offers* go, not the capability: an event can still have a page, so anything already written
stays reachable, but the app stops proposing two ways to reach it.

- The event sheet's **Notes** becomes **Turn into a task**.
- Somebody else's meeting offers **Make it a task** and nothing else alongside opening it where it
  lives. The `ext:` link stays — that is not a notes mechanism, it is what makes the task follow the
  meeting when it moves (§20).
