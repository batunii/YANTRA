#!/bin/bash
# Runs Android's own codec — the Kotlin in app/, compiled as-is — to write the page and JSON
# fixtures the Swift suite checks against. No Android SDK needed: PageCodec, PageDoc, Links and
# Filter depend only on java.time and kotlinx.serialization, so the Kotlin compiler and those two
# runtime jars out of the Gradle cache are enough.
#
#   conformance/tools/oracle.sh fixtures          # rewrite pages/lines.json and meta/json.json
#   conformance/tools/oracle.sh roundtrip IN OUT  # re-encode every IN/*.md into OUT/ with Kotlin
#
# Versions follow gradle/libs.versions.toml (Kotlin 2.2.21, kotlinx.serialization 1.8.1).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/app/src/main/java/ie/shoonya/yantra/data"
C=~/.gradle/caches/modules-2/files-2.1
jar() { find "$C/$1" -name "*.jar" ! -name "*sources*" | head -1; }
COMPILER="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.2.21):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.2.21):$(jar org.jetbrains.kotlin/kotlin-reflect/2.2.10):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.21):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0):$(jar org.jetbrains/annotations/13.0)"
PLUGIN="$(jar org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.2.21)"
RUNTIME="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.2.21):$(jar org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.8.1):$(jar org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.8.1)"
OUT="${TMPDIR:-/tmp}/yantra-oracle"
mkdir -p "$OUT"
java -Xmx2g -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn \
  -no-stdlib -no-reflect -cp "$RUNTIME" -Xplugin="$PLUGIN" -d "$OUT" \
  "$SRC/format/PageDoc.kt" "$SRC/format/PageCodec.kt" "$SRC/format/Links.kt" "$SRC/filter/Filter.kt" \
  "$HERE/Oracle.kt" 2>&1 | grep -v "^WARNING" >&2 || true
run() { java -cp "$OUT:$RUNTIME" oracle.OracleKt "$@"; }
case "${1:-fixtures}" in
  fixtures)
    python3 "$HERE/make-sources.py"
    python3 "$HERE/make-pages.py"
    run lines "$HERE/lines-sources.json" "$REPO/conformance/pages/lines.json"
    mkdir -p "$REPO/conformance/meta"
    run json "$REPO/conformance/meta/json.json"
    run roundtrip "$REPO/conformance/pages/roundtrip/in" "$REPO/conformance/pages/roundtrip/out"
    ;;
  roundtrip) run roundtrip "$2" "$3" ;;
  *) run "$@" ;;
esac
