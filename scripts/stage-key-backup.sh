#!/usr/bin/env bash
# Package the release signing key into one encrypted archive you can put anywhere.
#
# app/yantra-release.jks is the app's permanent identity and this laptop is currently the only place
# it exists. Play matches uploads by signature and Android refuses a sideloaded update signed by a
# different key, so if this disk dies Yantra can never be updated under this identity again.
#
# The output is encrypted with a passphrase you choose, which is what makes it safe to put in
# ordinary cloud storage, email it to yourself, or attach it to a password-manager entry. Store the
# passphrase somewhere other than beside the archive — a passphrase kept next to the thing it
# protects is decoration.
#
# This script deliberately does not upload anything. Where the key lives is your decision.

set -euo pipefail
cd "$(dirname "$0")/.."

KEY=app/yantra-release.jks
PROPS=keystore.properties
OUT="${1:-$HOME/yantra-signing-backup-$(date +%Y%m%d).tar.gz.gpg}"

for f in "$KEY" "$PROPS"; do
    [ -f "$f" ] || { echo "missing: $f — nothing to back up" >&2; exit 1; }
done

# The fingerprint travels with the key so a future you can tell, without a Play Console open,
# whether a recovered .jks is the one the published app was signed with.
FINGERPRINT=$(keytool -list -v -keystore "$KEY" -alias yantra \
                  -storepass "$(sed -n 's/^storePassword=//p' "$PROPS")" 2>/dev/null \
              | sed -n 's/^\s*SHA256: //p')

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/yantra-signing"
cp "$KEY" "$PROPS" "$STAGE/yantra-signing/"

cat > "$STAGE/yantra-signing/README.txt" <<EOF
Yantra release signing key — backed up $(date -u +%Y-%m-%dT%H:%M:%SZ)

yantra-release.jks     goes back in app/
keystore.properties    goes back in the repository root

Both are gitignored on purpose. Neither belongs in the repository.

  alias        yantra
  algorithm    RSA 4096, SHA384withRSA
  valid until  2053
  SHA-256      $FINGERPRINT

If this archive is all that survives, that is enough: restore both files to the paths above and
./gradlew :app:assembleRelease produces a signed build that Play and every installed copy of the
app will accept as an update.
EOF

tar -czf - -C "$STAGE" yantra-signing | gpg --symmetric --cipher-algo AES256 -o "$OUT"
chmod 600 "$OUT"

echo
echo "Wrote $OUT"
echo "  $(du -h "$OUT" | cut -f1), encrypted with the passphrase you just entered."
echo
echo "This is still on the laptop the key is already on, which protects you from deleting the file"
echo "and from nothing else. Move it somewhere that is not this disk — then verify you can open it:"
echo
echo "  gpg -d $OUT | tar -tz"
