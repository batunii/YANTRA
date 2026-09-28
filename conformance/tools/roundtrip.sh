#!/bin/bash
# The differential round trip over a real workspace clone, both halves:
#
#   1. RoundTripRunnerTests (Swift) decodes and re-encodes every page, archive line, focus-log line,
#      the manifest and the registries, reports every byte that changed, and writes what it made.
#   2. The Kotlin oracle does the same with 17d0beb's own PageCodec and FilterJson.
#   3. The two outputs are diffed. Empty means iOS writes what Android writes for this repository —
#      including for files that are not canonical to begin with (hand-edited, or written by an older
#      build), where "round-trips" is the wrong question and "rewrites them the same way" the right one.
#
#   conformance/tools/roundtrip.sh ~/clones/yantra-tasks
#
# The clone is only ever read.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WS="$(cd "$1" && pwd)"
OUT="${TMPDIR:-/tmp}/yantra-roundtrip-$$"
mkdir -p "$OUT/swift" "$OUT/kotlin"
(cd "$REPO/ios/YantraCore" && YANTRA_ROUNDTRIP_DIR="$WS" YANTRA_ROUNDTRIP_OUT="$OUT/swift" \
  swift test --filter RoundTripRunnerTests/testEveryFileInTheCloneRoundTripsByteForByte 2>&1 \
  | sed -n '/files round-trip/,/^Test Suite/p' | sed '/^Test Suite/d' | awk '!seen[$0]++')
bash "$HERE/oracle.sh" workspace "$WS" "$OUT/kotlin" 2>/dev/null
# The oracle does not do focus logs or .gitignore (neither is re-encoded by Android); compare the rest.
rm -rf "$OUT/swift/focus" "$OUT/swift/pomodoro" "$OUT/swift/.gitignore"
find "$OUT/swift/archive" -maxdepth 1 -name '*.md' -delete 2>/dev/null
echo "== Swift vs Kotlin re-encoding of $WS"
if diff -r "$OUT/kotlin" "$OUT/swift"; then
  echo "identical: $(find "$OUT/swift" -type f | wc -l | tr -d ' ') files"
fi
