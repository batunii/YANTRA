# Calendar and events — implementation plan

Two decisions taken up front, both at the ambitious end:

- **Events are a new first-class block, with recurrence.** Not a calendar view over `due:` dates —
  a thing that has a start and an end, no done state, and lives in the page file like every other
  block.
- **Two-way sync with the device calendar.** YANTRA events appear in Google/Samsung and changes come
  back.

The second is where this can lose data, and §5 says so at length rather than discovering it later.

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
| Calendar provider | **Nothing.** No `READ_CALENDAR`, no `WRITE_CALENDAR`, no `CalendarContract` anywhere |

So: reminders, a date model and a month grid exist. What does not exist is a *span*, a *repeat*, and
anything outside the repo.

## 2. The three things that make this hard

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

### 2.3 Two-way sync means the same event exists twice

See §5. It is the bulk of the risk and about half the work.

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
| `09:00-09:15` | today's date on the page's own day, or the series' date; times are local |
| `2026-09-11T14:00/PT1H` | start plus ISO-8601 duration |
| `2026-09-11T14:00/2026-09-11T15:30` | explicit end |
| `2026-09-11` | all-day |
| `2026-09-11/2026-09-13` | all-day, spanning |
| `…T14:00[Europe/Dublin]` | zoned; absent means floating |

Duration rather than end-time is preferred on render: a 1-hour meeting that moves stays 1 hour, and
a diff shows one changed field instead of two.

### Tokens

Reused from the task line wherever the meaning is identical, so there is one thing to learn:
`^id`, `#label`, `@assignee` (attendees), `!priority`. New: `rrule:`, `series:`, `loc:`, `+r<min>`
for a reminder (already the `due:` spelling).

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

## 5. Two-way sync with the device calendar

**This is the part that can lose data, and it is worth being blunt about why.** Everything else here
is additive — a new block type, a new view. This one makes the same event exist in two systems that
can both edit and both delete it, and the repo's conflict policy (per-file last-writer-wins on
`modifiedAt`) has no opinion about a change that arrived from outside the repo entirely.

### What has to be solved

| Problem | Why it is not obvious |
|---|---|
| **Identity** | A provider event id is local to a device and account; it means nothing in the repo. The mapping YANTRA id ↔ `Events._ID`/`_SYNC_ID` is **device-local** and belongs in Room, not in a file that syncs |
| **Deletion vs never-created** | "Not in the provider" must be distinguishable from "deleted from the provider", or every sync resurrects what you just deleted. Needs a last-synced snapshot per event, device-local |
| **Echo suppression** | Writing to the provider fires a `ContentObserver`; reading that back as a remote edit is an infinite loop that rewrites the repo. Every write records its own revision so the observer can ignore it |
| **Recurrence mapping** | `CalendarContract` models exceptions as separate rows with `ORIGINAL_ID` and `ORIGINAL_INSTANCE_TIME`, and cancellations as `STATUS_CANCELED`. Mapping our override lines to that and back is most of the sync work |
| **Conflict** | Both sides changed since the last sync. Needs a stated rule, and "last writer wins" needs a clock both sides agree on — the provider's `DIRTY` flag is the closest thing |
| **Which calendar** | The user picks a writable calendar; `CALENDAR_ACCESS_LEVEL` must be checked, and the choice is device-local |
| **Permissions** | `READ_CALENDAR` and `WRITE_CALENDAR` are both dangerous permissions, needing a runtime request and a working degraded mode when refused |

### Sequencing, and a recommendation

**Read-only first, as a shipped checkpoint, before any write is enabled.** Not as a lesser version
of the feature — as the thing that proves the identity mapping, the recurrence translation and the
observer plumbing are right while the failure mode is still "a wrong event is drawn on a screen"
rather than "an event is gone from your calendar and your repo".

Writes go on behind a switch that is off by default, and the first release with writes enabled
should keep a local journal of every provider mutation it makes, so a bad sync can be described
after the fact.

## 6. The calendar view

The smallest part, and deliberately last in the plan even though it is the visible one.

- Month, week and day. Month reuses `DueSheet`'s grid geometry rather than growing a second one.
- Draws occurrences **and** `due:` tasks — the point of putting events in the same format is that
  one surface shows both.
- A day with anything on it gets a dot; the day list shows events by time, then undated tasks.
- Tapping an empty slot creates an event there; tapping one opens it.
- Device-calendar events, once §5 lands, draw in the same list marked as not-ours.

## 7. Order of work

Each phase is useful on its own, and each one's guards go in with it.

| Phase | What | Why here |
|---|---|---|
| **0** | `EventRef`, `EventTime`, the `@ ` grammar, codec round-trip tests | Freeze the format before anything reads it. `GIT_WORKSPACES_PLAN.md` §2 is emphatic about this and it was right |
| **1** | Indexing events into Room, a bump to version 12 | The view and smart lists query the index, not the files |
| **2** | The calendar view — month/week/day over events and `due:` tasks | First point the feature is visible |
| **3** | Create/edit/delete an event, reminders via the existing scheduler | Reminders are already built; events just feed them |
| **4** | Recurrence: the RRULE subset, windowed expansion, override and cancellation lines | Needs 0–3 stable underneath it |
| **5** | Device calendar, **read-only**: permission, calendar picker, overlay in the view | Proves the mapping while nothing can be lost |
| **6** | Device calendar, **two-way**: writes behind a switch, mutation journal, conflict rule | Last, deliberately |

## 8. Open questions

1. **Whose event is it?** An event on a page belongs to that page. Does a calendar-created event with
   no obvious home go to Inbox, to a dated page, or to a dedicated `calendar/` area? The format has
   no answer and the view needs one before Phase 3.
2. **Do events archive?** Tasks archive on a threshold after completion. An event is never completed;
   a year-old one is just old. Left alone for now, but a workspace of standups grows forever.
3. **Attendees are `@name` strings**, the same as `assignee`, and carry no email — which is what
   `CalendarContract` wants for a real attendee. Two-way sync can round-trip a name; it cannot
   invite anybody. Worth being explicit that this is not a scheduling feature.
4. **Timezone display**: does a zoned event show its own zone or the reader's? (Proposal: the
   reader's, with the original noted when they differ.)
