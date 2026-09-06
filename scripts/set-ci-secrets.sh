#!/usr/bin/env bash
# Give the build server the signing key, so a tag produces a signed release APK.
#
# Without these four secrets .github/workflows/apk.yml falls back to an unsigned debug build. That
# fallback is deliberate — a fork's pull request should still tell the contributor whether their
# change compiles — but it means a missing secret looks like a successful build, so run this once
# and check that the next tag's Release says "release" in its filename rather than "debug".
#
# Values are read from the files already on disk rather than taken as arguments, so nothing lands in
# a shell history. Requires gh, authenticated as someone who can write secrets on this repository.

set -euo pipefail
cd "$(dirname "$0")/.."

command -v gh >/dev/null || {
    echo "gh is not installed." >&2
    echo "Either install it, or set the four secrets by hand at:" >&2
    echo "  https://github.com/batunii/YANTRA/settings/secrets/actions" >&2
    echo "The values are described in RELEASE.md." >&2
    exit 1
}
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated — run: gh auth login" >&2; exit 1; }

KEY=app/yantra-release.jks
PROPS=keystore.properties
for f in "$KEY" "$PROPS"; do
    [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

prop() { sed -n "s/^$1=//p" "$PROPS"; }

gh secret set KEYSTORE_BASE64   --body "$(base64 -w0 "$KEY")"
gh secret set KEYSTORE_PASSWORD --body "$(prop storePassword)"
gh secret set KEY_ALIAS         --body "$(prop keyAlias)"
gh secret set KEY_PASSWORD      --body "$(prop keyPassword)"

echo
echo "Set on $(gh repo view --json nameWithOwner -q .nameWithOwner):"
gh secret list
