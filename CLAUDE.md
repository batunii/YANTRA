# Yantra — working notes for the next agent

Yantra is a task/calendar app kept as **plain markdown in a git repository**. Two native clients read
and write the same files: **Android (Kotlin/Compose, shipped and final)** and **iOS
(Swift/SwiftUI, in progress)**.

The Android side is **done and frozen**. Treat it as the specification. All remaining work is iOS,
and "parity" here has a specific meaning the user has stated twice: **not feature-level, but
detail-level** — the same wording, the same metrics, the same ordering rules, the same behaviour in
the odd cases. If Android draws a mark, iOS draws that mark, not an SF Symbol that means roughly the
same thing.

Branch: `feature/ios-parity-format`. Android's `main` has been merged in.

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

**Done, with tests and checked on a simulator:** page format (events, `ext:`, page colour, due
durations, `LocalDateTime`/`ISODuration`); calendar (bucketer, timeline layout, month/week/day, event
sheet, EventKit read-only); LabelPalette (Java `String.hashCode` reproduced over UTF-16); the drawn
icon language (48 marks, 28-unit space, 1.6 stroke — `grep -c systemName:` in the app is **0**, keep
it that way); ink (PenKit, shape recognition, snap, undo/redo); the smart list builder; the
now-playing bar; App Store compliance (scanner critical 1 → 0).

**Two pieces worth understanding before you touch them:**

- **`SmartListRule.decode/encode` carries `extras`.** Today's seeded rule is "open AND (due ≤ today OR
  deadline ≤ today)". There is no UI for an OR of two properties, so that branch is carried through
  untouched and re-emitted. Opening the builder on a rule it cannot fully express must edit what it
  can and leave the rest exactly as it was — dropping it would silently rewrite a working smart list
  the first time anyone looked. A test asserts the round trip on the actual seeded rule.
- **`RunningStack.stack` ordering** — timed card first, then whatever is scheduled now, then the rest
  newest-first, **stably** (Swift's `sorted` is not stable; rank carries the original index). The
  selected card is held **by id, not position**, because the list re-sorts when a clock starts and a
  positional index would make the play button act on whatever slid underneath it.

**Outstanding, roughly in dependency order:**

1. **Widgets** — iOS has Focus + List + Live Activity. Missing: Calendar, QuickAdd, Bhupura widgets,
   and widget configuration (Android has `WidgetSettingsActivity`/`WidgetConfigActivity`/
   `WidgetTargetPicker`).
2. **Node page depth** — `PropertySheet` (deadline, assignee, and the props chip on the task page
   block bar, which is deliberately **not** faked today), `AssigneeSheet` + People/roster,
   `InlineText` (inline markdown editing, ~351 lines), links & backlinks, `ListSuggestions` /
   `LinkSuggestions` (~330).
   *Note:* Android's `setValue` only persists the four built-ins (priority, assignee, deadline, due)
   and silently drops custom property kinds. Do not build storage iOS alone has — match Android.
3. **Ink** — viewport pan/zoom is **done**: the document is a stack of A4-proportioned pages,
   `PKCanvasView` is a `UIScrollView` so the pan, the clamp and the pinch-about-a-focal-point are
   already correct, and only the policy is ported (`YantraCore/InkPages` — 0.4×–8×, pages from the
   ink plus one to grow into, the per-cent readout). The fold and its number are drawn outside the
   zoom by `InkPageFurniture`, as `InkCanvas.onDraw` does. **Lasso selection/move is still missing.**
4. **Sync depth** — the joining-device bug is **fixed**; see `YantraCore/…/WorkspaceLink.swift`.
   A device now decides *before* its first merge whether to **adopt** the repository's workspace,
   **push** its own, or **ask**. "Pristine" is decided by the `device:` stamp, which is exact rather
   than a heuristic: `WorkspaceSeeder` writes none and `WorkspaceWriter` stamps one on every write,
   so a page carrying a device is a page a person caused. Proven live
   (`testAJoiningDeviceAdoptsTheRepositoryInsteadOfDoublingIt`). Still to do here: the **ask** case
   has no UI yet — `SyncSettings.connect` returns `.ask` and nothing presents the choice — plus
   multi-device auth, the add-workspace screen, and background sync.

   *Historical note, kept because it explains the design:* before the fix, a second device
   connecting to a populated repository
   **merged its own freshly-seeded starter set in** rather than adopting what was there: two Today,
   two High Priority, two Getting started and two Inbox — and both Inbox pages carried
   `system_key: inbox`, so
   `index.node(systemKey:)` picked one arbitrarily and quick-add could land in either — reported as
   the reassuring-sounding `Synced · 2 conflicts resolved`. The merge was not wrong; it had been
   asked the question too late. Android's `data/sync/WorkspaceLinker.kt` is the reference.
5. **iPad** — `Panes.kt` two-pane shell, pull-to-sync, network pulse, salience.
6. **MeetingDetails** (~400 lines), stats depth, archive worker.
7. **A wording/metrics audit pass** — Android's exact strings and spacing, not approximations.

---

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