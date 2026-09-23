# App Store submission — compliance state

Audit run 21 September 2026 against the App Store Review Guidelines, using the
`app-store-compliance` scanner plus the checks it cannot see.

**Scanner verdict: `critical=0 high=1 medium=2`.** The critical blocker is fixed. What remains is
one accepted risk and two tasks that can only be done in App Store Connect.

## Fixed in this repository

| Finding | Guideline | What was done |
| --- | --- | --- |
| `APPLE-PRIVACY-MANIFEST-MISSING` (critical) | 5.1.1 / privacy manifests | `PrivacyInfo.xcprivacy` added to the app, the widgets and the share extension. Declares no tracking, no collected data types, and the one required-reason API actually used: `UserDefaults` with reason `CA92.1` (app group). |
| `APPLE-EXPORT-COMPLIANCE-MISSING` (high) | Export compliance | `ITSAppUsesNonExemptEncryption: false` in `ios/project.yml`. The only encryption is HTTPS to GitHub, which is exempt. Left unset, every upload stalls in *Missing Compliance* and never reaches review. |
| `APPLE-5.1.1-MISSING-PRIVACY-POLICY` (high) | 5.1.1 | `docs/PRIVACY.md` written, and linked from **Settings › Privacy** inside the app. The same URL goes in App Store Connect. |
| `APPLE-PRIVACY-NUTRITION-LABELS` (high) | 5.1.1 | The manifest declares the truth — nothing collected, nothing tracked. The matching answers for App Store Connect are below. |
| `APPLE-ACCESSIBILITY-DYNAMICTYPE` (medium) | Accessibility / EAA | Every face now scales: `Face.display/text/mono` use `Font.custom(_:size:relativeTo:)`, and the 25 SF Symbol call sites use a `.icon(_:_:)` modifier backed by `@ScaledMetric`. Widgets are deliberately excluded — WidgetKit gives a widget a fixed box, and type that grows inside one truncates rather than helps. |

## Accepted risk, now hardened

**`BOTH-UNSAFE-DEEPLINK` (high) — `yantra://` without Universal Links.**

A custom scheme is not owned: any app on the device can open one of these links. So the rule is that
the entire reachable surface is *navigation into the person's own data* — nothing that writes,
deletes, signs out or spends a token.

What was tightened:

- `RootView.onOpenURL` switches on a closed set of hosts (`open`, `focus`, `calendar`, `quickadd`)
  and falls through to nothing.
- `open/<id>` now goes through `openNode(_:)`, which **refuses an id that is not in the workspace**.
  It used to push a route for a node that does not exist, which is a blank screen with a back button
  on it. The same helper backs the `-route` scaffolding, so the two entry points cannot drift.
- `calendar/<date>` with an unparseable date opens the calendar rather than doing anything odd.
- `DeepLinkUITests` pins all three.

Universal Links would still need a domain serving `apple-app-site-association`. **If a domain is
available, do it** — but the residual risk is now only that another app can bring Yantra to a screen
of your own data, which is what the app icon does too.

## Scanner findings that are not real

Re-running the guard after the test work raised two more. Both were checked against the source
before being dismissed, and neither is a change worth making.

**`APPLE-ODR-DEPRECATED-27` (high) — "On-Demand Resources in use".** False positive. The rule greps
for `NSBundleResourceRequest|OnDemandResources|on-demand-resource`, and the only matches in the tree
are Apple's own precompiled module caches under `ios/YantraCore/.build/` — SDK headers, not this
app. There are zero matches in `Yantra/`, `Shared/`, `Widgets/`, `Share/` or the generated
`project.pbxproj`. Verify with:

```sh
grep -rliE "NSBundleResourceRequest|OnDemandResources" ios/Yantra ios/Shared ios/Widgets ios/Share
```

**`APPLE-ACCESSIBILITY-DYNAMICTYPE` (medium) — "hardcoded system font size".** What is left is
deliberate. Every icon in the shipped app UI goes through `.icon(_:_:)`, which scales with
`@ScaledMetric`; the remaining `Font.system(size:)` calls are that modifier's own implementation,
the debug `ConformanceView`, and the widgets. Widgets are excluded on purpose: WidgetKit gives a
widget a fixed box, and type that grows inside one truncates rather than helps.

(One real miss was found doing this: a Home row icon sized `isSmart ? 19 : 17` had escaped the
earlier sweep, because that pass matched literal sizes only. It scales now.)

## Still to do in App Store Connect — cannot be done from the repository

1. **Privacy nutrition labels.** Answer **"Data Not Collected"** for every category. This matches
   the manifest and the runtime: no server, no analytics, no SDKs. Mismatched labels are the single
   largest cause of rejection on both stores, and here the honest answer is also the simplest one.
2. **2026 age rating questionnaire.** Answer the updated 13+/16+/18+ questions. Yantra has no user
   generated content shared between people, no ads, no in-app purchase, no web view onto the open
   internet — it should land at 4+.
3. **Privacy policy URL.** Must be reachable and must stay reachable. Currently
   `https://github.com/batunii/YANTRA/blob/main/docs/PRIVACY.md` (also in `AppLinks.privacyPolicy`;
   change both together). A policy URL that 404s is a rejection whatever the app does.
4. **Notes for review.** There is no account to demo — sync is optional and the app is fully usable
   without signing in. Say exactly that, or a reviewer will look for credentials and reject for
   not being given any. A template is in `templates/REVIEW-NOTES-TEMPLATE.md` of the compliance
   repo. Suggested text:

   > Yantra needs no account. Everything works on first launch — the app creates a local workspace
   > with sample content. GitHub sign-in under Settings › GitHub is entirely optional and only
   > syncs the user's own files to a repository they own; please do not feel you need to sign in to
   > review the app. Calendar access, under Settings › Your calendars, is optional and read-only.

5. **Screenshots** must show the app in use — Home with lists, the task page, the calendar, a focus
   session. Not a splash or an empty state.

## Things that were checked and are already right

- **Account deletion (5.1.1(v))** — no account is created with us, so there is nothing to delete.
  Signing out in **Settings › GitHub** discards the token from the device, and disconnecting the
  repository is a separate, reversible action. Deleting the app removes everything local.
- **Permission usage strings** — `NSCalendarsUsageDescription` and
  `NSCalendarsFullAccessUsageDescription` are set and say what the app actually does with the data
  ("only ever reads them"). A string that does not explain the use is a common 5.1.1 rejection.
- **Calendar access is read-only and optional**, off until somebody turns it on, and it asks at the
  moment the switch is flipped rather than at launch.
- **No third-party SDKs**, so there are no signed third-party manifests to chase.
- **No ads, no tracking, no IDFA**, so no App Tracking Transparency prompt is required — and adding
  one without tracking would itself be a rejection.

## Re-running the audit

```
bash ~/.claude/skills/app-store-compliance/agent-os/hooks/app-store-compliance-guard.sh ios
```

Once a listing exists, also run the metadata layer, which is where a large share of rejections
actually live:

```
bash scripts/pull-metadata.sh apple
python3 scripts/metadata-audit.py ./metadata
```

## Running the tests

Three suites, all on the iOS side.

```sh
# 1. The cross-platform contract. Kotlin writes conformance/, Swift reads it.
./gradlew :app:testDebugUnitTest --tests '*ConformanceFixturesTest'

# 2. The Swift core: format, calendar, timeline, writer, capture, widget model.
cd ios/YantraCore && swift test

# 3. The app itself, driven on a simulator.
cd ios
xcodebuild -project Yantra.xcodeproj -scheme Yantra \
  -destination 'name=iPhone 17 Pro' test
xcodebuild -project Yantra.xcodeproj -scheme Yantra \
  -destination 'name=iPad Pro 13-inch (M5)' test
```

Two things about that third one.

**Grant the runner the calendar, not the app.** `MeetingUITests` writes a real event into the
simulator's calendar database from the test process — the runner is in the simulator, so its
EventKit is the app's EventKit — and without permission it calls `XCTSkipUnless`, which is a green
run that tested nothing. It skipped on every run here for some time before anyone noticed. The
grant goes to the runner's bundle id:

```sh
xcrun simctl boot <device>            # the device must be booted to be granted
xcrun simctl privacy <device> grant calendar ie.shoonya.yantra.uitests
xcrun simctl privacy <device> grant calendar ie.shoonya.yantra   # for -uitest-calendars
```

**Never run two destinations at once.** Two `xcodebuild test` invocations against one simulator
produce failures that move around between runs — three unrelated screens in one case — and if both
redirect to the same log with `>` the two runs interleave and the failure cannot even be read back.
Run the phone, then the iPad.

A fourth, optional, runs against the real GitHub. It needs a token and a repository it may write
to, and is skipped without them:

```sh
cd ios
YANTRA_LIVE_TOKEN=$(gh auth token) swift test --package-path YantraCore --filter LiveRepoTests
YANTRA_LIVE_REPO=owner/name YANTRA_LIVE_TOKEN=$(gh auth token) \
  swift test --package-path YantraCore --filter LiveSyncTests
```

`LiveRepoTests` covers the two endpoints a fake cannot: listing the repositories a sign-in can see,
and `POST /user/repos`. It never creates anything — the create test asks for a name that is already
taken **on the token's own account** and expects the refusal. That last clause is load-bearing: an
earlier version borrowed the name of any repository the token could push to, which can be an
organisation's, and the name being free on the personal account meant the call it expected to be
refused made a repository instead.

The UI tests reset and re-seed the workspace on every launch (`-uitest-reset -uitest`), so a run
never depends on what the run before it left behind. The fixture is `UITestFixture` rather than the
welcome content — otherwise every test that named a row would be a test of the welcome copy — and it
uses stable ids (`fixture-groceries`) so a test can be pointed at a page with
`-route open:<id>` instead of tapping its way there.

Run both destinations. The iPad is not a bigger iPhone here: the task rail is a column rather than a
sheet, and the month is two panes rather than one. Two of the bugs these tests caught only existed
on one of them.

### What the UI suite covers

30 tests: Home and lists, ticking a task through to the file and back, the calendar in all three
modes, creating an event, the task rail on both shapes of window, settings including the
device-calendar switch and the privacy policy, focus and its stats, the archive round trip,
sign-in before anybody has signed in, the ink canvas opening, the `yantra://` scheme, and a smoke
test over every route.

Not covered: drawing *into* the ink canvas (PencilKit has no useful accessibility surface), the
share extension (it needs a second host app to invoke it), and sync against a live GitHub — the
sign-in screen is exercised, the network path is not.

## Guideline 5.1.1(v) — account deletion

Yantra creates no account. There is nothing to sign up for, no server, and no record of you
anywhere: the app signs in **with** GitHub and stores one user token in the Keychain so it can push
to a repository you already own.

So the two things 5.1.1(v) asks for are both in the app, on the GitHub screen:

- **Sign out** deletes the token from this device. It is the only thing Yantra stores about you.
- **Revoke access on GitHub** opens the authorisation itself and withdraws it for every device.
  This is the real "delete", because GitHub holds the permission — an in-app row that claimed to
  delete an account would be claiming to delete something Yantra does not have.

Your workspace files are yours and stay where they are, on the device and in your own repository.
Neither action touches them, which the screen says in as many words.
