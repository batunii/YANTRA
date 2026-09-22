# iOS parity — what is done, what is left

Android is the specification and is frozen. This tracks the iOS port against it at **behaviour**
level, not file level. Last updated 2026-09-22, branch `feature/ios-parity-format`.

Parity here means what the user has asked for twice: **the same wording, metrics, ordering rules and
odd-case behaviour** — not "a screen that does roughly the same job".

---

## 1. Done this session

### Sync — three bugs no offline test could see (`43f482c`)

The two platforms do **not** share a transport: Android uses JGit, iOS uses GitHub's Git Data REST
API. Android's sync being proven said nothing about iOS. `SyncEngineTests`' fake remote answers in
the shape the caller expects, so wrong assumptions stayed self-consistent and invisible.

| Bug | Effect | Fix |
|---|---|---|
| Empty repository → 409 on **every** Git Data call, blob writes included | The first sync into a fresh repo — the one everybody does — failed | 409 reads as "no branch"; a first push bootstraps through the Contents API using a file being pushed anyway, not an invented `.gitkeep` |
| URLSession served cached reads (GitHub ETags everything) | A ref read after a push reported the *previous* tip — indistinguishable from another device moving the branch, so the engine rebased onto the past and retried until it gave up | `.reloadIgnoringLocalAndRemoteCacheData` |
| A refused token refresh was indistinguishable from being offline (both `nil`) | A dead credential was returned and presented forever while the screen still claimed to be signed in | Ported Android's three outcomes: `renewed` / `needsSignIn` / `couldNotAsk`. Offline changes nothing — a tunnel must not sign anybody out |

**Second-device duplication** also fixed. A joining device merged its own freshly-seeded starter set
instead of adopting what was there: two Inboxes, two Todays, **both carrying `system_key: inbox`**,
so `node(systemKey:)` picked one arbitrarily. `WorkspaceLink` now decides before the first merge —
adopt / push / ask. "Pristine" is the `device:` stamp, which is exact rather than a heuristic: the
seeder writes none, the writer stamps one on every write.

Verified live against a real repo, and on two simulators (iPhone wrote, iPad pulled).

### Features built (`43f482c`)

- **Ink kit** — pen slots owning their own colour and width, eraser, lasso, shape key, snap toggle,
  undo/redo. `ShapeRecognizer` snaps a freehand stroke to line/box/oval/triangle (iterative RDP, so
  a long scribble cannot blow the stack).
- **Smart list builder** — was a stub with three hardcoded conditions that *silently rewrote*
  anything it did not understand. Now every property with its kind's operators, label conditions
  with any/all, inline label creation, templates. `extras` carries branches the form has no control
  for (Today is "due OR deadline") through untouched.
- **The player** — one card per started task, at most one counting. Ordering is pure and tested:
  timed first, then scheduled, then newest-first, **stably**. The showing card is held by id, not
  position, or pressing play would act on whatever slid underneath it.

### Controls and feel (`f05b04f`)

| Reported | Cause | Fix |
|---|---|---|
| "so many buttons do not work" | **Two `.sheet` modifiers on one view** (task page, Home). SwiftUI honours one and drops the rest silently | One `.sheet(item:)` over an enum, so a third kind cannot reintroduce it |
| "a lot of delay in each click" | Every write rescheduled all notifications and pinged the widget host *between the tap and the redraw*; `listColor` read a page off disk **per task** during calendar rebuild | Both coalesced to after the last write (flushed on backgrounding); colour read from the index |
| "the sliding on tasks does nothing" | The gesture existed and tracked a distance **nothing drew** | The ring inside the glyph traces under the finger, haptic at the commit point, springs back if released early, yields to vertical drags |
| "numbers don't change live, only when I finish" | `FocusTimer` is a **nested `ObservableObject`** — it published on itself, not on the model every screen observes | Its changes are forwarded to the model |
| Sittings read "Event" | Nothing resolved a sitting's title; its words *are* the task's | Rule lives on `DayItem.title` so no screen can reach past it. Follows main, where Android fixed the same thing |
| The day always opened at 07:00 | — | Today opens an hour before now; any other day still opens at seven. *Any* of the three visible days being today counts |
| Could not place a task on the calendar | `forTaskId:` appeared in **no** app source — iOS could not create a sitting at all | Hold a rail task, carry it over the day, drop: an hour at the quarter hour, with a preview. A **sitting**, not a due date — the rail's tap still sets that |

Also: the bottom dock reached neither the screen edge nor a sane inset (a fixed 22pt stacked on the
home-indicator inset — the dead gap), and the seam ran into the rounded corner. All three fixed.

### Two things tried that were wrong, reverted with the reason left in the code

- `.contentShape` on a Home row. The 36pt figure was the **accessibility** frame, not the hit area;
  a `Button` is already fully tappable, and the shape swallowed the long press.
- One of the app's drawn marks in a `.contextMenu` label. A menu item takes an `Image`; a drawn view
  makes the whole menu fail to build, so the row long-pressed to nothing.

`ListLookUITests` caught both — it had been passing and started failing.

### Tests

| Suite | Count |
|---|---|
| Core (`swift test`) | **182**, 0 failures |
| UI, iPhone | 42 |
| UI, iPad | 42 |
| Live GitHub (auth + sync, opt-in) | 12 |

---

## 2. Publish readiness

`app-store-compliance` scanner: **critical = 0**, high 6, medium 5.

**Guideline 5.1.1(v), account deletion** — the one critical, now resolved. Yantra creates no
account: no sign-up, no server, no record of anyone. It signs in *with* GitHub and keeps one token.
So the screen offers both halves: **Sign out** deletes the token from this device, and **Revoke
access on GitHub** withdraws the authorisation itself for every device. Documented in
`APP_STORE_SUBMISSION.md`.

The remaining HIGH findings were each checked against the source and match **zero times** in iOS
code: `lorem ipsum`, `example.com`, donation links, `localStorage`, `NSBundleResourceRequest`. They
come from elsewhere in the repo or the scanner's own reference material.

Two genuinely open, both deliberate:
- **`yantra://` deep links without Universal Links.** Every reachable target is navigation into the
  person's own data — nothing that writes, deletes or signs out — which is why it was built this way.
- **Free personal team cannot grant App Groups.** On a device build signed with one, widgets and the
  share extension read a different container from the app and show nothing. The Live Activity's
  Pause/Done buttons cannot work for the same reason — they coordinate through the App Group. Both
  work on the simulator and on device with a paid account.

---

## 3. Still missing, by area

From a behaviour-level audit of every Android UI package. Ordered by how much it costs a person
using the two side by side.

### Serious — fixed

- ~~**Groups are never rendered on Home.**~~ **Fixed.** It was worse than the audit found: a grouped
  list is a *child* of its group, so filing three lists into "Work" made all four disappear. Groups
  now draw as a band with a child count, fold (device-local — the format has no `collapsed` and
  inventing one is a change both apps must agree on), and their lists are indented underneath.
  Three UI tests. The fold needed a `contentShape` — the band is a glyph, a word and a count with a
  Spacer between, so a tap in the middle hit nothing. Safe there, unlike on a list row, because
  nothing on the band wants a long press.
- ~~**Delete has no confirmation.**~~ **Fixed.** Names the page in the question, as Android does —
  reading the title back is the one thing that stops the wrong page going.

### Serious — still open

- **Lists and groups are drawn in one rank order** on Android, not lists-then-groups —
  `HomeScreen.kt:329-332`.
- Long-press a group for **Rename / Delete group** — `HomeScreen.kt:926-973`.

### Task page — the largest area (Android 2,903 lines vs iOS 513)

- Long-press to **lift and reorder blocks**, live re-ordering under the finger, drag auto-scroll
  that ramps within a 72dp edge band — `NodePageScreen.kt:726-832, 638-665`.
- **Enter splits a block at the caret**; Enter on an empty bullet leaves the list. **Backspace at the
  start merges back**, refused when the block has children — `1826-1869`.
- **Markdown as you type** ("- ", "## " swallow the marker) — `1820-1825`.
- **Caret hand-off between blocks**, and the caret kept in view with 28dp of air so typing at the
  page bottom is not hidden by the keyboard — `1981-2014`.
- **Inline markdown and links rendered in the field** — bold/italic/code markers dimmed in place,
  `[[name|^id]]` collapsed, broken links muted, tap-a-link-to-open. iOS has only `inlinePlain`
  flattening — `InlineText.kt:119,313`.
- **`[[` link completion strip**, and **"LINKS TO"** chips listing what a page points at —
  `1056-1082, 2800-2834`.
- **Props chip, Focus chip, indent gating** on the type bar — `1097-1104, 2714-2726`.
- **Deadline pill**, and generic select/number/text/checkbox property pills — `PropertyPills.kt:306`.
- **PropertySheet** and **AssigneeSheet** entire (roster, closed-list rule, "can't see this repo",
  Collaborators refresh) — `PropertySheet.kt:50`, `AssigneeSheet.kt:89-266`. No assignee UI at all.
- **"Move to list…"** with a cross-workspace picker — `507-509, 1219-1256`.
- **Event blocks** as `EventBlockRow`, and an event page's **MeetingHeader** (Join, Where, Organiser,
  Guests, Open in the calendar app) — `EventBlockRow.kt:54-145`, `MeetingDetails.kt:66-355`.
- **Row salience** (`planRow`): which of tags / unassigned / props / list / due a row may print,
  decided per view — `2092-2128, 2469-2526`.

### Home

- Long-press a list for **Rename / Icon & colour / Move to group / Delete** — iOS has only
  "How it looks" — `HomeScreen.kt:830, 889-902`.
- **Workspace sections**, one per open repository in its own hue — `182-189, 321-342`.
- **"Next" section** — the next event today, "in 12m"/"now" inside a 30-minute window — `267-280`.
- Greeting tally is **"N open · 2h 20m booked"**; iOS says "N open across M lists" — `760-777`.
- **Capture highlighting** of `#tag ~list @person [[link]]` as you type, plus the suggestions strip —
  `1024-1047`; `ListSuggestions.kt:46-138`.

### Calendar

- **Drag a block to move it, or grab an edge to resize**, after a long press; 15-minute snapping,
  write only on release — `TimelineView.kt:697-760`.
- **Tap-hold selects a block** and shows resize handles — `653-668`.
- **"Armed" rail mode** — arm a task, then tap the timeline to place it — `CalendarScreen.kt:171`.
- After marking a range, a **PickForRange sheet** asks what goes in it; iOS goes straight to the
  event sheet — `441-466`.
- **Swipe to page** the view, claimed past 56dp and one finger only so pinch survives — `264-299`.
- Device events **refuse writing gestures structurally**, drawn at 0.10 alpha — `607, 680-687`.

### App-wide components

- **PullToSync** — pull-to-refresh anywhere, spinner tracking the real pass — `PullToSync.kt:40-110`.
- **Header fold** — every secondary screen's header collapses on scroll — `Chrome.kt:237, 358`.
- **NetworkPulse** — `HomeScreen.kt:785-787`.

### Settings / sign-in

- **Multiple workspaces at all** — the list, per-workspace colour, "Forget <name>?", "Add a
  workspace" — `SettingsScreen.kt:169-282`. iOS shows one hard-coded row.
- **AddWorkspaceScreen** entire — `AddWorkspaceScreen.kt:78-265`.
- **"Use an access token instead"** (paste a fine-grained PAT) — `SignInScreen.kt:710-745`.
- **RepoHasTasksDialog** — "already has tasks", USE THE REPOSITORY / Keep both. This is the `.ask`
  case `WorkspaceLink` returns and nothing presents — `SignInScreen.kt:1120-1157`.

### Smart list screen

- Started tasks pulled to the top — `SmartListScreen.kt:218-221`.
- "X isn't on this device" banner for a workspace the rule names — `292-294`.
- Rule pill shows the rule's **description**, not "N rules" — `175-196`.
- Done section always expanded with "DONE · n"; iOS hides it behind a toggle — `313-321`.

### Focus / stats

- Span toggle (Week / All), three cuts (Tasks / Lists / Workspaces) with drill-in, play from a
  breakdown row, `WeekReviewPanel` — `StatsScreen.kt:393-610`.

---

## 4. Unverified

- The GitHub **device flow's final approved exchange**. Everything up to it is tested live; the
  approval step needs a human at github.com/login/device.
- Whether a real token refresh succeeds with `client_id` alone. GitHub answers
  `incorrect_client_credentials` to a *bogus* refresh token, which both apps map to "this build's
  GitHub app is misconfigured". If a genuine refresh also needs a client secret — which a mobile app
  cannot hold — sync needs re-signing-in every few hours on **both** platforms.
