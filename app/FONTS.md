# The fonts, and why they are smaller than the ones you downloaded

Each file here is a subset of the upstream release, cut to what this app draws. Replacing one means
re-cutting it — dropping a full upstream .ttf in will work and will quietly add back a few hundred
kilobytes to every install.

## What was cut

**Character coverage.** Basic Latin, Latin-1 Supplement, Latin Extended-A, General Punctuation,
currency symbols, arrows, and the few maths signs the UI uses. That is 349 codepoints where
Bricolage shipped 527 and Space Grotesk 735 — the rest was Cyrillic, Greek and Vietnamese, which
this app has no strings in. A task title typed in a script outside that range still renders: Android
falls back to a system font per character, as it does for the emoji in the seeded workspace.

**Bricolage's dead axes.** It ships three — `opsz` 12–96, `wght` 200–800, `wdth` 75–100 — and the
only one this app ever varies is weight. Android does not apply optical sizing automatically the way
CSS does, so `opsz` has always been sitting at its default of 96 and `wdth` at 100; pinning them
there changes nothing on screen and removes two thirds of a `gvar` table that was the single largest
thing in `res/`. 408 KB to 116 KB.

## Re-cutting after an upstream update

    pip install fonttools brotli
    UNI="U+0020-007E,U+00A0-00FF,U+0100-017F,U+2000-206F,U+20A0-20BF,U+2190-2193,U+2212,U+2022,U+2026,U+00D7"

    # Bricolage: pin the axes nothing varies, then subset.
    fonttools varLib.instancer -o bric.ttf upstream/bricolage_grotesque.ttf opsz=96 wdth=100
    pyftsubset bric.ttf --unicodes="$UNI" --layout-features='*' \
        --output-file=bricolage_grotesque.ttf

    # The rest have no dead axes; subset only.
    for f in space_grotesk space_mono_regular space_mono_bold; do
        pyftsubset upstream/$f.ttf --unicodes="$UNI" --layout-features='*' --output-file=$f.ttf
    done

`--layout-features='*'` is not optional: dropping it would take the kerning and the ligatures with
it, and the display face is the one place in this app where that would show.
