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

## Accepted risk

**`BOTH-UNSAFE-DEEPLINK` (high) — `yantra://` without Universal Links.**

The scheme is used only by this app's own widgets, notifications and Live Activity to reach a
screen. The handler in `RootView.onOpenURL` switches on a closed set of hosts (`open`, `focus`,
`quickadd`, `calendar`) and falls through to nothing, and the worst a hijacked link can do is
navigate to a node in the person's own data — there is no destructive action and no credential
behind a URL.

Universal Links would need a domain serving `apple-app-site-association`. **If a domain is
available, this should be done**; it is the only finding left that is worth real work. Until then
the risk is that another app could open Yantra on a chosen screen, which is not a data-loss or
credential path.

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
