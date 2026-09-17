#!/usr/bin/env python3
"""
What the UI does that the design system says it should not.

Run from the repo root:  python3 scripts/ui_audit.py

This is the *sweep* half of the consistency work. It reads the source and reports where a screen
has gone its own way instead of using the system — the type ramp in Theme.kt, AppShapes, the three
icon sizes in YantraIcons, the palette in YantraColors. It reports; it does not fix, and it does not
fail a build. Lint comes after, once the sweep has shown which rules are worth enforcing.

Every check names the rule it is checking and the file that owns it, so a finding can be argued with
rather than just obeyed. A number here is not automatically a fault: a one-off is a judgement call,
and thirty-seven of the same one-off is a system nobody is using.
"""
import re, sys, collections, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
UI = ROOT / "app/src/main/java/ie/shoonya/yantra"

# Files that define the system are allowed to contain raw values — that is what defining it means.
THEME = {"Theme.kt", "YantraColors.kt", "AccentColor.kt", "OklchColor.kt", "LabelPalette.kt",
         "YantraIcons.kt", "LauncherIcon.kt", "YantraGlyph.kt"}
# Surfaces that legitimately draw rather than compose: the app mark, the timeline, ink, previews.
DRAWS = {"SplashScreen.kt", "TimelineView.kt", "InkCanvas.kt", "InkScreen.kt", "PenKit.kt",
         "YantraGlyph.kt", "YantraCheckbox.kt", "YantraFocus.kt", "Running.kt", "YantraIcons.kt",
         "Common.kt",
         # The app's own identity mark, and the bitmap the notification and widgets need. Neither
         # is a functional icon: one is the brand and the other is not Compose at all.
         "YantraUi.kt", "SessionMark.kt"}

SHAPES = {5, 10, 16, 18, 28}          # AppShapes, Theme.kt
ICON_SIZES = {"Small", "Medium", "Large"}   # YantraIcons §2

def strip_comments(s):
    """Blanks comments while keeping every byte's position, so line numbers stay true."""
    out, i, n = [], 0, len(s)
    while i < n:
        if s.startswith("//", i):
            j = s.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif s.startswith("/*", i):
            j = s.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(c if c == "\n" else " " for c in s[i:j])); i = j
        elif s[i] == '"':
            out.append(s[i]); i += 1
        else:
            out.append(s[i]); i += 1
    return "".join(out)

def kt():
    for p in sorted(UI.rglob("*.kt")):
        yield p, strip_comments(p.read_text(encoding="utf-8", errors="replace"))

def rel(p): return str(p.relative_to(ROOT))

findings = []
def note(rule, owner, rows, why):
    findings.append((rule, owner, rows, why))

# ---- type -------------------------------------------------------------------------------------
rows = collections.Counter(); sites = []
for p, s in kt():
    if p.name in THEME: continue
    for m in re.finditer(r"fontSize = ([0-9.]+)\.sp", s):
        rows[m.group(1)] += 1
        sites.append((rel(p), s[:m.start()].count("\n") + 1, m.group(1)))
note("Raw font sizes instead of the type ramp", "Theme.kt AppTypography",
     [f"{n}sp × {c}" for n, c in rows.most_common()],
     f"{sum(rows.values())} call sites across {len(set(x[0] for x in sites))} files. "
     "The ramp already names every one of these roles; a literal here is a size nobody can "
     "change centrally, and the half-point neighbours (11.5, 12, 12.5) are drift rather than "
     "decisions.")

# ---- shape ------------------------------------------------------------------------------------
rows = collections.Counter()
for p, s in kt():
    if p.name in THEME: continue
    for m in re.finditer(r"RoundedCornerShape\(([0-9.]+)\.dp", s):
        rows[m.group(1)] += 1
off = {n: c for n, c in rows.items() if float(n) not in SHAPES}
note("Corner radii outside AppShapes", "Theme.kt AppShapes {5, 10, 16, 18, 28}",
     [f"{n}dp × {c}" for n, c in sorted(off.items(), key=lambda kv: -kv[1])],
     f"{sum(off.values())} of {sum(rows.values())} radii are not in the set. "
     "A radius is a family resemblance; one that is two off reads as a mistake rather than a "
     "distinction.")

# ---- icon sizes -------------------------------------------------------------------------------
bad = []
for p, s in kt():
    for m in re.finditer(r"YantraIcon\([^)]*?size = ([^,)\n]+)", s, re.S):
        v = m.group(1).strip()
        if not any(v.endswith(k) for k in ICON_SIZES):
            bad.append((rel(p), s[:m.start()].count("\n") + 1, v))
note("Icon sizes outside the three", "YantraIcons Small/Medium/Large (ICONS.md §2)",
     [f"{f}:{l} → {v}" for f, l, v in bad] or ["none"],
     "A mark is one drawing at one of three sizes. Scaling it anywhere else is how nine sizes "
     "came about the first time.")

# ---- colour -----------------------------------------------------------------------------------
lits = []
for p, s in kt():
    if p.name in THEME: continue
    for m in re.finditer(r"Color\(0x[0-9A-Fa-f]{6,8}\)", s):
        lits.append((rel(p), s[:m.start()].count("\n") + 1, m.group(0)))
note("Colour literals outside the palette", "YantraColors.kt / LabelPalette.kt",
     [f"{f}:{l} {v}" for f, l, v in lits] or ["none"],
     "A literal is a colour chosen against one theme. Light and dark are not the same number, so "
     "a hex here is right in one and wrong in the other.")

# ---- how many palettes -------------------------------------------------------------------------
owners = []
for p, s2 in kt():
    if re.search(r"Color\(0x[0-9A-Fa-f]{6,8}\)", s2) and re.search(r"\bobject \w+|\bdata class \w+Colors", s2):
        owners.append(rel(p))
note("Places that define a colour", "there should be one",
     owners or ["none"],
     "Each of these defines light and dark twins correctly, which is why none of them shows up as "
     "a literal. The finding is that there is more than one: a hue added to one palette is absent "
     "from the others, and nothing says which a new mark should ask.")

# ---- glyphs -----------------------------------------------------------------------------------
draws = []
for p, s in kt():
    if p.name in DRAWS: continue
    for m in re.finditer(r"\bCanvas\(", s):
        draws.append((rel(p), s[:m.start()].count("\n") + 1))
note("Screens drawing their own glyphs", "YantraIcons is the only source (ICONS.md §1)",
     [f"{f}:{l}" for f, l in draws] or ["none"],
     "§1 counted six of these as half the original problem. A mark drawn locally cannot be "
     "restyled, resized or retinted with the rest.")

# ---- borrowed icons ---------------------------------------------------------------------------
mats = []
for p, s in kt():
    for m in re.finditer(r"\bIcons\.(Default|AutoMirrored|Filled)\.", s):
        mats.append((rel(p), s[:m.start()].count("\n") + 1))
note("Material icons", "material-icons-extended is off the classpath",
     [f"{f}:{l}" for f, l in mats] or ["none"],
     "Filled Material against a hairline glyph is the inversion ICONS.md §1 exists to stop.")

# ---- page margin ------------------------------------------------------------------------------
rows = collections.Counter()
for p, s in kt():
    if p.name in THEME: continue
    for m in re.finditer(r"padding\((?:horizontal|start) = ([0-9.]+)\.dp", s):
        rows[m.group(1)] += 1
note("Horizontal insets", "PAGE_MARGIN = 22.dp (Panes.kt)",
     [f"{n}dp × {c}" for n, c in rows.most_common(8)],
     "Not all of these are page margins — a chip has its own inset — but a screen-level one that "
     "is not PAGE_MARGIN is drift.")

# ---- dialogs ----------------------------------------------------------------------------------
kinds = collections.Counter()
for p, s in kt():
    kinds["AlertDialog"] += len(re.findall(r"\bAlertDialog\(", s))
    kinds["ModalBottomSheet"] += len(re.findall(r"\bModalBottomSheet\(", s))
    kinds["Dialog"] += len(re.findall(r"(?<!Alert)(?<!BasicAlert)\bDialog\(", s))
note("Ways of asking a question", "no owner — this is the finding",
     [f"{k} × {v}" for k, v in kinds.most_common() if v],
     "Three shapes for one job. Which one a question gets is currently a matter of who wrote it.")

# ---- report -----------------------------------------------------------------------------------
out = ["# UI audit", "",
       "Generated by `scripts/ui_audit.py`. It reports; it does not fix, and it does not fail the",
       "build. A count is the signal: one of something is a judgement call, thirty-seven is a",
       "system nobody is using.", ""]
for rule, owner, rows, why in findings:
    out += [f"## {rule}", "", f"**Rule owner:** {owner}", "", why, ""]
    if rows and rows != ["none"]:
        out += ["```"] + [f"  {r}" for r in rows[:40]] + (
            [f"  … and {len(rows) - 40} more"] if len(rows) > 40 else []) + ["```", ""]
    else:
        out += ["Clean.", ""]
(ROOT / "UI_AUDIT.md").write_text("\n".join(out), encoding="utf-8")

for rule, _, rows, _ in findings:
    clean = rows == ["none"] or not rows
    print(f"{'  ok  ' if clean else ' FIND '} {rule}: {'clean' if clean else len(rows)}")
print("\nwritten: UI_AUDIT.md")
