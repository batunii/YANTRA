# Conformance fixtures

The contract between the Android app and the iOS app, as files.

Everything a workspace repository holds is written by one platform and read by the other, so the
two implementations have to agree on **bytes**, not just meaning. These golden files pin that:
each is an input and the exact output the Android code produces for it.

| Folder | What it pins | Written by | Checked by |
| --- | --- | --- | --- |
| `rank/` | `Rank.between` vectors, and a built chain | `ConformanceFixturesTest.rank` | both |
| `ink/` | a `YNK1` stroke, an empty one, and a whole sidecar | `…strokeEnvelope` | both |
| `focus/` | the session clock table | `…sessionClock` | both |
| `sync/` | manifest field-by-field merge cases, both device orders | `…manifestMerge` | both |

**Android** — `app/src/test/java/ie/shoonya/yantra/ConformanceFixturesTest.kt` checks the Kotlin
against these on every test run. To regenerate after a deliberate change:

    YANTRA_WRITE_FIXTURES=1 ./gradlew :app:testDebugUnitTest --tests '*ConformanceFixturesTest'

**iOS** — `ios/YantraCore/Tests/YantraCoreTests/ConformanceTests.swift` reads the same files:

    cd ios/YantraCore && swift test

A fixture changes only by deciding to change it. If either side fails here, the two apps would
disagree in a shared repository, and that is the bug — not the test.
