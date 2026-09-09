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

## 6. The calendar view

The smallest part, and deliberately last in the plan even though it is the visible one.

- Month, week and day. Month reuses `DueSheet`'s grid geometry rather than growing a second one.
- Draws occurrences **and** `due:` tasks — the point of putting events in the same format is that
  one surface shows both.
- A day with anything on it gets a dot; the day list shows events by time, then undated tasks.
- Tapping an empty slot creates an event there; tapping one opens it.
- Device-calendar events, once §5 lands, draw in the same list marked as not-ours and not editable.

## 7. Order of work

Each phase is useful on its own, and each one's guards go in with it.

| Phase | What | Why here |
|---|---|---|
| **0** ✅ | `EventRef`, `EventTime`, the `@ ` grammar, codec round-trip tests | Freeze the format before anything reads it. `GIT_WORKSPACES_PLAN.md` §2 is emphatic about this and it was right |
| **1** | Indexing events into Room, a bump to version 12 | The view and smart lists query the index, not the files |
| **2** | The calendar view — month/week/day over events and `due:` tasks | First point the feature is visible |
| **3** | Create/edit/delete an event, reminders via the existing scheduler | Reminders are already built; events just feed them |
| **4** | Recurrence: the RRULE subset, windowed expansion, override and cancellation lines | Needs 0–3 stable underneath it |
| **5** | Device calendar: `READ_CALENDAR`, calendar picker, `Instances` query, overlay in the view | Independent of 0–4; could be built alongside them by someone else |

## 8. Open questions

1. **Whose event is it?** An event on a page belongs to that page. Does a calendar-created event with
   no obvious home go to Inbox, to a dated page, or to a dedicated `calendar/` area? The format has
   no answer and the view needs one before Phase 3.
2. **Do events archive?** Tasks archive on a threshold after completion. An event is never completed;
   a year-old one is just old. Left alone for now, but a workspace of standups grows forever.
3. **Attendees are `@name` strings**, the same as `assignee`, and carry no email. Nothing is sent to
   anybody: an attendee here is a note about who is involved, the same as an assignee on a task.
   This is not a scheduling feature and should not look like one.
4. **Timezone display**: does a zoned event show its own zone or the reader's? (Proposal: the
   reader's, with the original noted when they differ.)
