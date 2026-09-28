# Conformance fixtures

The contract between the Android app and the iOS app, as files.

Everything a workspace repository holds is written by one platform and read by the other, so the
two implementations have to agree on **bytes**, not just meaning. These golden files pin that:
each is an input and the exact output **Android at 17d0beb** produces for it. Android does not
change for iOS; nothing here is written by a modified Android.

| Folder | What it pins | Written by | Checked by |
| --- | --- | --- | --- |
| `rank/` | `Rank.between` vectors, and a built chain | an earlier Kotlin writer (unchanged at 17d0beb) | iOS |
| `focus/` | the session clock table | an earlier Kotlin writer (unchanged at 17d0beb) | iOS |
| `capture/` | the quick-capture grammar | an earlier Kotlin writer (unchanged at 17d0beb) | iOS |
| `labels/` | the label palette and list icons | an earlier Kotlin writer (unchanged at 17d0beb) | iOS |
| `pages/lines.json` | 204 lines through `decodeBlock`: kind, rebuilt line, raw passthrough | `tools/oracle.sh` | iOS |
| `pages/roundtrip/` | 26 whole pages, `in/` and what `encode(decode(in))` makes of them in `out/` | `tools/oracle.sh` | iOS |
| `meta/json.json` | manifest, labels, properties, seeded smart lists, every filter shape, JVM doubles | `tools/oracle.sh` | iOS |
| `ink/android/` | strokes the real androidx.ink wrote, beside the points it reads back | `scripts/ink-interop.sh` | iOS |
| `ink/ios/` | strokes the iOS encoder wrote, what Android read from them, and its re-encoding | `scripts/ink-interop.sh` | both |

**Pages and meta** are written by 17d0beb's own Kotlin, compiled straight out of `app/` with the
Kotlin compiler in the Gradle cache — no Android SDK, no emulator:

    conformance/tools/oracle.sh fixtures

`tools/make-sources.py` and `tools/make-pages.py` hold the inputs. To check iOS against a real
workspace clone (Swift round trip, Kotlin round trip, and a diff of the two):

    conformance/tools/roundtrip.sh ~/clones/yantra-tasks

**Ink** needs the native androidx.ink library, so it is made on a device or emulator by the
test-only `InkInteropFixturesTest`:

    scripts/ink-interop.sh        # one device on adb, JAVA_HOME at JDK 17+

**iOS** reads all of it in `ios/YantraCore/Tests/YantraCoreTests/` (`ConformanceTests`,
`InkBlobTests`, the format tests):

    cd ios/YantraCore && swift test

A fixture changes only by deciding to change it. If iOS fails here, the two apps would disagree
in a shared repository, and that is the bug — not the test.
