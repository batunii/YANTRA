#!/usr/bin/env bash
# Regenerates conformance/ink/ from the real androidx.ink library on a device or emulator.
#
#   1. Swift writes the iOS-encoded blobs (conformance/ink/ios/*.blob).
#   2. InkInteropFixturesTest runs on the device: it writes Android's golden strokes, and decodes the
#      iOS blobs through StrokeCodec — the call Android's canvas makes, which drops what fails.
#   3. Everything the device wrote is pulled back into conformance/ink/.
#
# Needs one device on adb and JAVA_HOME pointing at a JDK 17+. Test code only: the app is untouched.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
INK=conformance/ink
PKG=ie.shoonya.yantra
OUT=/sdcard/Android/data/$PKG/files/ink-interop

(cd ios/YantraCore && YANTRA_WRITE_INK_FIXTURES=1 swift test --filter InkBlobTests/testWriteIosFixtures >/dev/null)

./gradlew -q :app:installDebug :app:installDebugAndroidTest

args=""
for f in "$INK"/ios/*.blob; do
  name=$(basename "$f" .blob)
  case "$name" in *.android) continue ;; esac
  args+="$name:$(base64 < "$f" | tr -d '\n'),"
done

"$ADB" shell rm -rf "$OUT"
"$ADB" shell am instrument -w -e class "$PKG.InkInteropFixturesTest" -e iosInk "$args" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee /dev/stderr | grep -q "^OK ("

mkdir -p "$INK/android"
tmp=$(mktemp -d)
"$ADB" pull "$OUT" "$tmp" >/dev/null
src="$tmp/ink-interop"
for f in "$src"/*; do
  base=$(basename "$f")
  case "$base" in
    ios-decoded.json) cp "$f" "$INK/ios/android-decoded.json" ;;
    ios-*.android.blob) cp "$f" "$INK/ios/${base#ios-}" ;;
    *) cp "$f" "$INK/android/$base" ;;
  esac
done
rm -rf "$tmp"
echo "conformance/ink regenerated from $("$ADB" shell getprop ro.product.model | tr -d '\r')"
