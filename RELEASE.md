# Releasing Yantra

Everything a build needs that is not in the build files. Written down because each of these was
learned once, expensively, and none of it is visible from the source.

## The signing key

`app/yantra-release.jks`, with its passwords in `keystore.properties`. Both are gitignored and
**neither exists anywhere else**.

This is the app's permanent identity. Play matches uploads by signature and Android refuses a
sideloaded update signed by a different key, so losing the `.jks` means Yantra can never be updated
under this identity again — by anyone, ever, including you. Not a lockout that support can undo.

    Alias        yantra
    Algorithm    RSA 4096, SHA384withRSA
    Valid until  2053 (10,000 days from 2026-09-06)
    SHA-256      e1:31:a5:9d:9b:a2:8d:d8:90:97:f9:d8:56:83:28:19
                 76:6d:b2:00:cf:42:7b:e7:06:10:81:b2:d0:fa:0f:8b

Keep a copy somewhere that is not this laptop. `scripts/stage-key-backup.sh` puts both files and
this fingerprint into one encrypted archive you can then put anywhere — a password manager
attachment, a USB stick, ordinary cloud storage. The archive is encrypted, so where it lands
matters much less than that it lands.

## CI

`.github/workflows/apk.yml` builds the release variant and signs it when the repository holds these
four secrets, and falls back to an unsigned debug build when it does not — so a fork's pull request
still tells the contributor whether their change compiles, rather than failing on a secret it was
never going to be given.

    KEYSTORE_BASE64      base64 -w0 app/yantra-release.jks
    KEYSTORE_PASSWORD    storePassword from keystore.properties
    KEY_ALIAS            yantra
    KEY_PASSWORD         keyPassword from keystore.properties

`scripts/set-ci-secrets.sh` sets all four from the files already on disk. It needs `gh` and an
authenticated session; it reads the values rather than taking them as arguments, so they never enter
a shell history.

Pushing a tag matching `v*` publishes a Release with the APK attached. Run artifacts expire and are
only reachable by people who can see the Actions tab, which is why the tag path exists at all.

## Play Console: the specialUse justification

Play requires a written justification for `FOREGROUND_SERVICE_SPECIAL_USE` at submission, in the
Console under *App content → Foreground service permissions*. Paste this:

> Yantra is an offline task manager with a focus timer. When the user starts a focus session, a
> foreground service keeps that session counting and keeps its controls — pause, stop, and mark
> done — available from the notification shade and the lock screen for as long as the session runs.
>
> No existing foreground service type describes a user-started timer. `dataSync` would assert
> network activity the service does not perform: the session is entirely local and touches no
> network. `shortService` is capped at a few minutes, which is shorter than the shortest session the
> app offers. `mediaPlayback` and the remaining types describe work this service does not do.
>
> The service runs only while a session the user explicitly started is running. It is started by
> that user action and stops itself the moment the session ends, is paused past its planned end, or
> is dismissed. It holds no wake lock, performs no network or location access, and does nothing in
> the background beyond keeping the session's own notification current.

The same sentence, compressed, is already in the manifest as
`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, which is the declaration the platform reads. The
Console wants the prose version separately; they should agree, so change both or neither.

## Before tagging

- `./gradlew :app:testDebugUnitTest :app:lintDebug` — lint aborts on error by configuration, so a
  green run means clean.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` must increase for
  every build that leaves this machine; Play rejects a repeat and a sideload silently refuses to
  downgrade.
- **Install the release APK and open it.** Not the debug one. R8 removes code that is only reached
  by reflection, and the failure mode is a crash on launch rather than a build error — this has
  happened once already, to JGit's message bundles. To check it without disturbing an install you
  care about, add `applicationIdSuffix = ".rc"` to the release build type, install alongside,
  drive it, then uninstall `.rc` and revert.
