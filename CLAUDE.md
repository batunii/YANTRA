# Yantra — working notes for the next agent

Yantra is a task/calendar app kept as **plain markdown in a git repository**. Two native clients read
and write the same files: **Android (Kotlin/Compose, shipped and final)** and **iOS
(Swift/SwiftUI, in progress)**.

The Android side is **done and frozen**. Treat it as the specification. All remaining work is iOS,
and "parity" here has a specific meaning the user has stated twice: **not feature-level, but
detail-level** — the same wording, the same metrics, the same ordering rules, the same behaviour in
the odd cases. If Android draws a mark, iOS draws that mark, not an SF Symbol that means roughly the
same thing.

Branch: `feature/ios-parity-format`. **Android is pinned at `17d0beb`** ("Next sits under the views,
and wears a clock"): `app/` and `web/` are byte-identical to that commit, and the only Android file
this branch adds is test code (`app/src/androidTest/.../InkInteropFixturesTest.kt`).

**The spec is the iOS brief** in `ios.zip` beside the repo (`PROMPT.md` + four appendices extracted
from the Android code). Where the brief and an appendix disagree the brief wins; where either
disagrees with the Kotlin at 17d0beb, the Kotlin wins. It says: don't build ownership (`by:`,
`on:`, a ninth focus-log field) — but read and preserve those bytes when newer Android writes them.

---

## 1. The shape of the repository

```
app/src/main/java/ie/shoonya/yantra/   Android. The reference. Do not change it without being asked.
  data/format/       PageCodec's counterpart — the markdown line format
  data/sync/         JGit-based sync, GitHubDeviceAuth, TokenRenewal, WorkspaceLinker
  domain/            RunningTask, FocusTimer
  ui/                Compose screens, one package per area

ios/
  YantraCore/        A SwiftPM package. Pure logic, no UI, no UIKit. All of it is unit-tested.
  Yantra/Sources/    The SwiftUI app.
  Shared/            Code the app, widgets and share extension all need (AppGroup, Keychain, Mark).
  Widgets/           WidgetKit extension + Live Activity.
  Share/             Share extension.
  UITests/           XCUITest target.
  project.yml        **The truth.** Yantra.xcodeproj is generated from it — never edit the project.

conformance/         Golden fixtures. Kotlin writes them, Swift reads them. This is the contract.
docs/                APP_STORE_SUBMISSION.md (how to run everything), PRIVACY.md
```

### The format is the contract

Both apps must produce **byte-identical** files. The mechanism is `conformance/`: Kotlin writes
fixtures with `YANTRA_WRITE_FIXTURES=1`, Swift reads them in `ConformanceTests.swift`. When you
change anything about the on-disk format, you change it on both sides and you regenerate fixtures.
**Format first, UI second** — this has held all session and should keep holding.

---

## 2. Building and testing

```bash
cd ios

# Core logic — fast, run this constantly
(cd YantraCore && swift test)

# The app. project.yml is the source; regenerate whenever you ADD A FILE.
xcodegen generate
xcodebuild -project Yantra.xcodeproj -scheme Yantra \
  -destination 'id=<SIM-UDID>' -derivedDataPath build build

# UI tests
xcodebuild ... build-for-testing
xcodebuild ... test-without-building -skip-testing:YantraUITests/SyncLiveTests
```

**Gotchas that have cost real time this session:**

- **A new file does not exist until `xcodegen generate` runs.** `project.yml` lists directories, so a
  new `.swift` compiles only after regenerating. A test that "doesn't exist" (`Executed 0 tests`) is
  almost always this.
- **Never run two `xcodebuild` test destinations at once.** It kills CoreSimulator with
  `Mach error -308 — server died`. Recover with
  `xcrun simctl shutdown all; killall -9 com.apple.CoreSimulator.CoreSimulatorService`. Run
  destinations sequentially.
- **SourceKit lies about new YantraCore files** ("cannot find type in scope"). `swift build` is
  authoritative; ignore the editor.
- Commit messages with backticks trigger zsh command substitution — use `git commit -F <file>`.
- Don't commit `ios/build*` (5,734 derived-data files got committed once). It is in `.gitignore`.

### Driving the simulator

The app takes launch arguments, which is how screens are reached without tapping:

- `-route <value>` — `home`, `focus`, `focus:<id>`, `start:<id>:<secs>`, `settings`, `marks`,
  `calendar`, `calendar:<id>`, `github`, `archive`, `ink:<id>`, `stats`, `open:<id>`,
  `rules:<id>` (opens a smart list with its builder up). Parsed in one place: `LaunchRoute` in
  `YantraApp.swift`.
- `-uitest-reset` wipes the App Group workspace and defaults. `-uitest` seeds `UITestFixture` with
  **stable ids** (`fixture-groceries`, `fixture-today`, `fixture-started`, …).
- `-uitest-connect <owner/name> <token> [deviceName]` signs a simulator in without the device flow.
  **`#if DEBUG` only**, deliberately: installing a credential from a launch argument must not be
  possible in a shipped binary, and no runtime guard is strong enough — the code simply is not there.

A screenshot is not proof a thing is reachable:

```bash
xcrun simctl launch <UDID> ie.shoonya.yantra -uitest-reset -uitest -route home
xcrun simctl io <UDID> screenshot /tmp/x.png
```

---

## 3. Traps that produced real bugs in this codebase

**`.accessibilityIdentifier` on a bare stack creates nothing.** SwiftUI only makes an accessibility
element where one is declared. The now-playing bar was on screen, visibly correct in a screenshot,
and completely absent from the accessibility tree — invisible to VoiceOver *and* to tests. Fix:
`.accessibilityElement(children: .contain)` on the container. If a UI test cannot find something you
can see, this is the first thing to check.

**Selection signalled by colour alone is invisible.** `SelectChip` said "chosen" only with the
accent colour. Added `.accessibilityAddTraits(selected ? [.isSelected] : [])`.

Both were fixed **in the app, not worked around in the test** — that is the expectation here.

### Three more, all at the SwiftUI/UIKit seam

These cost most of a day between them, and all three look like the test being wrong.

**A SwiftUI `.gesture` on a view that contains a `UIViewRepresentable` never fires.** The day
column's tap-to-place was `.gesture(SpatialTapGesture())` on the `ZStack` that holds
`RangeMarkSurface`. The touch lands on a real `UIView`, and the recogniser SwiftUI would have used
for the ancestor does not get it back — not once, not ever. Nothing in the log, no warning, and the
banner disappeared for an unrelated reason so even the test looked half-right. Fix: put the tap on
the same recogniser chain as the rest, in the representable. **If a representable is in the stack,
the gesture belongs in it.**

**SwiftUI accessibility modifiers on a representable are applied once and never refreshed.**
`.accessibilityValue("Page \(page) of \(pageCount)")` on `PencilCanvas` went on saying "Page 1 of
2" for the life of a drawing that had grown to four — the app was computing the right answer and
reporting it, and the accessibility tree kept the first one. Fix: set `accessibilityIdentifier`,
`accessibilityLabel` and `accessibilityValue` **on the UIView**, in `makeUIView`/the coordinator.

**SwiftUI state written from `updateUIView` is discarded.** "Fit" set the zoom back to 1 and the
readout went on saying 267%, because the assignment that would have hidden it happened during a
view update, which is the one moment SwiftUI ignores. Fix: `DispatchQueue.main.async` around the
callback — next turn of the loop, not this one.

### And one that is not SwiftUI's fault

**A coordinate picked as a fraction of the window is a test of what time it is.** The calendar opens
on the current hour, so which hours are on screen moves through the day. `dy: 0.45` was the middle
of the fixture's "Timed thing" at half past nine in the morning: the tap opened that task, the
calendar went away, and the test's "the banner disappeared" assertion passed meaning the opposite of
what it was written to mean. Anchor on real geometry — `PlaceOnCalendarUITests.emptyPointOnTheDay`
asks the timeline where it is and finds a gap between the blocks — and assert you are still on the
screen you think you are on.

### Found on a real phone, invisible on the simulator (2026-09-29)

Each passed its UI tests before it was fixed. **The simulator's synthetic gestures are not a
hand**, so gesture work is confirmed on the device.

- **A pinch never reached PencilKit's scroll view.** A real hand lands one finger a few ms before
  the other; with finger drawing on, PencilKit starts a stroke with the first and the pinch never
  gets the touch. The canvas has its own pinch, run alongside the drawing, which cancels the stroke
  and drives the zoom (`InkCanvas`'s rule). The simulator puts both fingers down at once.
- **A SwiftUI drag on list rows made the list hard to scroll**, even as `simultaneousGesture`. Row
  swipes are a UIKit pan (`UIGestureRecognizerRepresentable`) that refuses to *begin* unless the
  finger is plainly going right. Refusing to begin is the part that matters.
- **The whole app redrew every second.** `FocusTimer` re-assigned `@Published state` each tick even
  with no session, and `AppModel` forwarded every change. Only real changes publish now, and only
  start/pause/finish reach `AppModel`; clocks watch the timer via the environment. Measure with an
  `os.Logger` mark in `init` and `log show` on the simulator — a page rebuilt once a second is
  obvious in ten seconds of log.
- **A drawn sketch pushed `Route.node(inkId)`** — an empty task page. `Route.node` of an ink node now
  opens the drawing. **An 8 pt inset on the tick glyph reached the gap before the title**, so taps
  meant to open a task ticked it (the hit area now grows outward, never toward the words).
- To reproduce with **real data**: `devicectl device copy from … --source "Library/Application
  Support/workspaces"` (free signing has no App Group, so the phone keeps workspaces in the app
  container), then copy into the simulator's App Group `workspaces/` and launch without `-uitest`.

---

## 4. Sync: read this before touching it

**The two platforms do not share a transport.** Android uses **JGit** (real git protocol). iOS uses
**GitHub's Git Data REST API** (`ios/YantraCore/Sources/YantraCore/GitHub.swift`). Android's sync
being proven says *nothing* about iOS. Three bugs existed on iOS that the offline suite could not
see, because `FakeRemote` in `SyncEngineTests` answers in whatever shape the caller expects, so
wrong assumptions stay self-consistent:

1. **Empty repository → 409 on everything.** A repo with no commits answers
   `409 "Git Repository is empty"` to *every* Git Data call, blob creation included. `head()` only
   understood 404, so the first sync into a fresh repo — the one everybody does — failed. Fixed: 409
   reads as "no branch", and a first push bootstraps through the **Contents API** (the one endpoint
   that can start an empty repo), using one of the real files being pushed rather than an invented
   `.gitkeep` that would then live in every workspace forever.
2. **URLSession served cached reads.** GitHub ETags every read; the default cache policy returned
   stored bodies, so a ref read straight after a push reported the *previous* tip —
   indistinguishable from another device moving the branch. The engine rebased onto the past, was
   refused, and retried until "the branch kept moving". Fixed with
   `.reloadIgnoringLocalAndRemoteCacheData`.
3. **A refused token refresh looked identical to being offline.** `refresh` returned `Token?`, nil
   for both, so a *dead* credential was returned and presented forever while the screen still
   claimed to be signed in. GitHub App user tokens lapse after a few hours unless expiry is off for
   the registration. **Android already shipped and fixed this** — see the comment in
   `data/sync/TokenRenewal.kt`. Ported its three outcomes: `renewed` / `needsSignIn` (credential is
   scrap — clear it and say so) / `couldNotAsk` (offline; change nothing, *a tunnel must not sign
   anybody out*), plus `nothingToDo` when there is no refresh token at all.

### Live tests — opt-in, they hit the network

```bash
cd ios/YantraCore
YANTRA_LIVE_AUTH=1 swift test --filter LiveAuthTests           # needs no credential
YANTRA_LIVE_REPO=owner/name YANTRA_LIVE_TOKEN=$(gh auth token) \
  swift test --filter LiveSyncTests
```

They skip cleanly when the environment is unset, so the ordinary suite is unaffected.

`saieeshward/yantra-sync-test` is the throwaway repo. The machine's `gh` token lacks `delete_repo`,
so it must be deleted by hand or after `gh auth refresh -h github.com -s delete_repo`.

### Still unknown about sync

- **The final approved device-flow exchange has never run.** Everything up to it is tested live
  (code issuance, `authorization_pending`, `slow_down` and its new interval, bogus device code,
  refused refresh). The approval step needs a human at github.com/login/device.
- **Whether a real refresh succeeds with `client_id` alone.** GitHub answers
  `incorrect_client_credentials` to a *bogus* refresh token, which both apps map to "this build's
  GitHub app is misconfigured". If a genuine refresh also needs a client secret — which a mobile app
  cannot hold — that message is literally true and sync needs re-signing-in every few hours on both
  platforms. Settling it needs one real approved token. **Worth settling.**

---

## 5. Where the iOS port stands

Audited against the brief on 2026-09-28 (four audits, one per appendix). The brief's milestones are
the order: format → sync → Home/lists/pages → focus → calendar → ink → widgets → settings.

### Done this pass, with tests

- **Ink is Android's format** (`InkBlob`, `InkSidecar`). YNK1 is gone. Proven both ways against the
  real androidx.ink on an emulator: `scripts/ink-interop.sh` regenerates `conformance/ink/`. The
  encoder reproduces androidx.ink **1.0.0**, which differs from google/ink `main` in small float
  details — see the comments in `InkProto.encodeBatch`. Don't "fix" them toward main.
- **The canvas never rewrites a stroke it did not touch** (`InkLedger` in `InkView.swift`). Checked
  on the simulator: an Android sketch with an unreadable v1 stroke, opened and backgrounded, stays
  byte-identical. PencilKit gotchas that each hid a stroke: it keeps a path date at float precision
  (so the identity is whole seconds after 2001), it inverts colours in a dark style (so the canvas is
  always `.light` and Yantra swaps graphite/chalk itself), and it records a tip 3.14× (pen) / 1.47×
  (highlighter) the tool width (`InkBridge.tipPerWidth`; monoline unmeasured).
- **Sync data safety** (branch `ios/sync-data-safety`, merged): `mergePage` ported line for line,
  manifest keeps local, `.gitignore` synced, tree writes re-checked under a `flock` and atomic,
  atomic base snapshot, one pass per workspace (`SyncGate`), one token renewal (`TokenKeeper`),
  "keep both" adds a linked workspace, `CommitScheduler` on Android's timings. The REST transport
  stays — the audit found nothing appendix 03 needs that it cannot do.
- **Format** (branch `ios/format-parity-agentA`, merged): pages, events (`by:` written, not
  read), `pinned`, tolerated `by:`/`on:`/unknown tokens, java.time parsing, kotlinx-exact JSON
  (`KotlinJSON`), manifest defaults, upkeep on open. **17d0beb's own Kotlin writes the page and
  meta fixtures** — `conformance/tools/oracle.sh`, no SDK needed — and
  `conformance/tools/roundtrip.sh <clone>` diffs Swift against Kotlin on a real workspace.
- **Home** is 17d0beb's: views (Inbox, Today, smart lists) → Next (Clock mark) → Pinned →
  one section per repository, lists and groups in one rank order. `HomeDay` holds booked/next.
- **Authorship, read and written** — the user asked for it (overriding the brief's §1.1): rows say
  "by @x" / "on @x" about other people, the page "Added by @x/you" and "On it: @x" (newer
  Android's words), and a task made in a **shared** workspace is stamped `by:<login>` straight
  after its title. Personal never is. `on:` is read, not yet written; no ninth focus-log field.
- Sittings are not list rows, and a sitting saves from the event sheet. Pinch zoom no longer
  compounds. The list widget's "+" files into its own list. Colour tokens match `yantraColors`.
  The network pill is still. No SF Symbols in any target (marks live in `Shared/YantraMarks.swift`).

### Outstanding, in the brief's order

1. **Format, left** — due times inside task lines only to the millisecond; the editor half of
   `LinksTest` (`collapse`) is unported. `saieeshward/yantra-tasks` itself still holds an old
   iOS-written `properties.json` (High = 4294986271, invalid); upkeep only fills what is missing,
   so nothing repairs it.
2. **Sync, still** — the writer's other writes don't take the lock yet; the app syncs on "opened"
   (Android doesn't); reminders are task-only (no event/sitting reminders, no re-plan after sync).
3. **Home, left** — Rename, Move to group… and Delete (Android's three delete bodies) on rows and
   views; group banner long-press; the create sheet's live highlighting and `~`/`@`/`[[` chips.
4. **Lists and pages** — row geometry (card radius 14, glyph 26, gap 6, sub-line order), the
   settled "Done · N" section, drag reorder, document mode (markdown shortcuts, Enter splits,
   Backspace joins, `[[` suggestions in blocks), property pills (Deadline, Priority dropdown, Alarm
   mark not 🔔), capture live highlighting, `[[Name]]` resolved on capture.
5. **Focus** — Stats week/all-time and cuts; Live Activity shows a bar, not the arc.
6. **Calendar** — zoom about the focal point, block move/resize, the "What is this time for?"
   sheet, Length chips in the event sheet, "Open the task" from a sitting.
7. **Ink UI** — lasso bar and scale handle, pen kit slider/recents, shape modes, dock.
8. **Widgets** — widgets ignore the theme (always dark), calendar widget rows/ticks, the Live
   Activity's bar should be the arc. App Groups need a paid account — see memory.
9. **Glyph choreography** (`Bhupura.swift`): the frame un-draw / ring / bindu-THUD sequence.
10. Brief §6 open questions for the owner: Low priority's blue frame, chip radius 4 vs 5, sheet
    radius 28 vs 18. Matching the code (the brief's default) until told otherwise.

## 6. How to work here

- Work one area to green — core tests **and** a simulator screenshot or a UI test — before starting
  the next. Commit per area.
- When porting, **read the Kotlin doc comments**. They record why a decision was made, and several
  are load-bearing (the `TokenRenewal` header; `RunningTask.stack`'s ordering argument; the
  `extras` rationale). Carry the reasoning into the Swift, not just the behaviour.
- When a test fails, work out whether the test or the app is wrong. This session it was the app more
  often than not, and fixing the app was right each time.
- Don't claim something works because a screenshot looks right. Screenshots do not prove
  reachability, and the network half of sync was broken in two ways while every offline test passed.

---

<!-- rtk-instructions v2 -->
# RTK (Rust Token Killer) - Token-Optimized Commands

## Golden Rule

**Always prefix commands with `rtk`**. If RTK has a dedicated filter, it uses it. If not, it passes through unchanged. This means RTK is always safe to use.

**Important**: Even in command chains with `&&`, use `rtk`:
```bash
# ❌ Wrong
git add . && git commit -m "msg" && git push

# ✅ Correct
rtk git add . && rtk git commit -m "msg" && rtk git push
```

## RTK Commands by Workflow

### Build & Compile (80-90% savings)
```bash
rtk cargo build         # Cargo build output
rtk cargo check         # Cargo check output
rtk cargo clippy        # Clippy warnings grouped by file (80%)
rtk tsc                 # TypeScript errors grouped by file/code (83%)
rtk lint                # ESLint/Biome violations grouped (84%)
rtk prettier --check    # Files needing format only (70%)
rtk next build          # Next.js build with route metrics (87%)
```

### Test (60-99% savings)
```bash
rtk cargo test          # Cargo test failures only (90%)
rtk go test             # Go test failures only (90%)
rtk jest                # Jest failures only (99.5%)
rtk vitest              # Vitest failures only (99.5%)
rtk playwright test     # Playwright failures only (94%)
rtk pytest              # Python test failures only (90%)
rtk rake test           # Ruby test failures only (90%)
rtk rspec               # RSpec test failures only (60%)
rtk test <cmd>          # Generic test wrapper - failures only
```

### Git (59-80% savings)
```bash
rtk git status          # Compact status
rtk git log             # Compact log (works with all git flags)
rtk git diff            # Compact diff (80%)
rtk git show            # Compact show (80%)
rtk git add             # Ultra-compact confirmations (59%)
rtk git commit          # Ultra-compact confirmations (59%)
rtk git push            # Ultra-compact confirmations
rtk git pull            # Ultra-compact confirmations
rtk git branch          # Compact branch list
rtk git fetch           # Compact fetch
rtk git stash           # Compact stash
rtk git worktree        # Compact worktree
```

Note: Git passthrough works for ALL subcommands, even those not explicitly listed.

### GitHub (26-87% savings)
```bash
rtk gh pr view <num>    # Compact PR view (87%)
rtk gh pr checks        # Compact PR checks (79%)
rtk gh run list         # Compact workflow runs (82%)
rtk gh issue list       # Compact issue list (80%)
rtk gh api              # Compact API responses (26%)
```

### JavaScript/TypeScript Tooling (70-90% savings)
```bash
rtk pnpm list           # Compact dependency tree (70%)
rtk pnpm outdated       # Compact outdated packages (80%)
rtk pnpm install        # Compact install output (90%)
rtk npm run <script>    # Compact npm script output
rtk npx <cmd>           # Compact npx command output
rtk prisma              # Prisma without ASCII art (88%)
rtk uv run <cmd>        # Compact uv project command output
```

### Files & Search (60-75% savings)
```bash
rtk ls <path>           # Tree format, compact (65%)
rtk read <file>         # Code reading with filtering (60%)
rtk grep <pattern>      # Search grouped by file (75%). Format flags (-c, -l, -L, -o, -Z) run raw.
rtk find <pattern>      # Find grouped by directory (70%)
```

### Analysis & Debug (70-90% savings)
```bash
rtk err <cmd>           # Filter errors only from any command
rtk log <file>          # Deduplicated logs with counts
rtk json <file>         # JSON structure without values
rtk deps                # Dependency overview
rtk env                 # Environment variables compact
rtk summary <cmd>       # Smart summary of command output
rtk diff                # Ultra-compact diffs
```

### Infrastructure (85% savings)
```bash
rtk docker ps           # Compact container list
rtk docker images       # Compact image list
rtk docker logs <c>     # Deduplicated logs
rtk kubectl get         # Compact resource list
rtk kubectl logs        # Deduplicated pod logs
```

### Network (65-70% savings)
```bash
rtk curl <url>          # Compact HTTP responses (70%)
rtk wget <url>          # Compact download output (65%)
```

### Meta Commands
```bash
rtk gain                # View token savings statistics
rtk gain --history      # View command history with savings
rtk discover            # Analyze Claude Code sessions for missed RTK usage
rtk proxy <cmd>         # Run command without filtering (for debugging)
rtk init                # Add RTK instructions to CLAUDE.md
rtk init --global       # Add RTK to ~/.claude/CLAUDE.md
```

## Token Savings Overview

| Category | Commands | Typical Savings |
|----------|----------|-----------------|
| Tests | vitest, playwright, cargo test | 90-99% |
| Build | next, tsc, lint, prettier | 70-87% |
| Git | status, log, diff, add, commit | 59-80% |
| GitHub | gh pr, gh run, gh issue | 26-87% |
| Package Managers | pnpm, npm, npx | 70-90% |
| Files | ls, read, grep, find | 60-75% |
| Infrastructure | docker, kubectl | 85% |
| Network | curl, wget | 65-70% |

Overall average: **60-90% token reduction** on common development operations.
<!-- /rtk-instructions -->