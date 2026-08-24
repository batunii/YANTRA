package ie.napkin.supertasks.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Yantra's palette. Material's [androidx.compose.material3.ColorScheme] only has slots for the
 * standard roles; the grounds, accent washes and ink tints that give Yantra its look live here
 * and are reached via [LocalYantra] / `Yantra.colors.*`.
 *
 * The identity is a **hue engine**: the whole theme is derived from a single [hue] the user picks,
 * with lightness and chroma fixed in OKLCH (see [oklch]) so every hue stays balanced. The ground
 * is the *same* hue as the accent, heavily dimmed — a near-neutral graphite with a whisper of
 * colour (a complementary ground would visually vibrate). Three modes: Dark, OLED (true black),
 * and Light.
 */
@Immutable
data class YantraColors(
    val isDark: Boolean,
    val page: Color,
    val band: Color,
    val bandTimer: Color,      // active-timer banner surface
    val tileWarm: Color,
    val tileWarm2: Color,       // secondary add-bar chip bg
    val cardBg: Color,
    val railBg: Color,
    val hairline: Color,
    val tileBorder: Color,
    val accent: Color,          // the single voice
    val accentText: Color,      // accent used as text/label
    val accentGlow: Color,      // focus/timer glow
    val accentEyebrow: Color,   // uppercase eyebrow (FOCUSING)
    val accentFill: Color,      // outlined-button translucent fill
    val accentBorder: Color,    // outlined-button border
    val accentChipBg: Color,    // "High" priority chip bg
    val onAccent: Color,        // ink on solid accent
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDim: Color,
    val checkOutline: Color,
    val due: Color,             // upcoming-due state (uses the accent)
    val dueText: Color,
    val dueChipBg: Color,
    val neutralChipBg: Color,
    // Status voices — the three meanings the accent must not carry (see [yantraColors]).
    val overdue: Color,         // past its date
    val overdueChipBg: Color,
    val warning: Color,         // needs attention soon / mid priority
    val warningChipBg: Color,
    val success: Color,         // completed
    val successChipBg: Color,
    val secondaryButton: Color,
    val listIdentity: List<Color>,
    val inkPaper: Color,
    val inkPageSep: Color,
)

enum class ThemeMode { SYSTEM, DARK, OLED, LIGHT }

/** Collapse SYSTEM to a concrete mode; screens/theme call this before [yantraColors]. */
fun ThemeMode.resolve(systemDark: Boolean): ThemeMode =
    if (this == ThemeMode.SYSTEM) (if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT) else this

/** The hue every new install starts on (indigo). Users can change it. */
const val DEFAULT_HUE = 265f

/**
 * Build the full palette from one [hue] (0..360) and a [mode]. Every value is an OKLCH triplet
 * with fixed lightness/chroma per role — change the hue and the whole theme rotates in balance.
 */
fun yantraColors(hue: Float, mode: ThemeMode): YantraColors {
    // Callers should pass a resolved mode ([ThemeMode.resolve]); collapse defensively anyway.
    @Suppress("NAME_SHADOWING") val mode = if (mode == ThemeMode.SYSTEM) ThemeMode.DARK else mode
    val light = mode == ThemeMode.LIGHT
    val oled = mode == ThemeMode.OLED

    // Grounds — same hue as the accent, very low chroma (a tinted graphite, not grey).
    val page = when (mode) {
        ThemeMode.OLED -> Color.Black
        ThemeMode.LIGHT -> oklch(0.965f, 0.005f, hue)
        else -> oklch(0.169f, 0.006f, hue)
    }
    val surface = when (mode) {
        ThemeMode.OLED -> oklch(0.185f, 0.008f, hue)
        ThemeMode.LIGHT -> Color.White
        else -> oklch(0.214f, 0.008f, hue)
    }
    val surfaceHigh = when (mode) {
        ThemeMode.OLED -> oklch(0.230f, 0.009f, hue)
        ThemeMode.LIGHT -> oklch(0.975f, 0.006f, hue)
        else -> oklch(0.255f, 0.010f, hue)
    }
    val rail = when (mode) {
        ThemeMode.OLED -> oklch(0.100f, 0.006f, hue)
        ThemeMode.LIGHT -> oklch(0.930f, 0.006f, hue)
        else -> oklch(0.135f, 0.006f, hue)
    }

    // Ink — near-white on dark, deep on light, both with a faint hue tint.
    val ink = if (light) oklch(0.28f, 0.030f, hue) else oklch(0.945f, 0.008f, hue)
    val secondary = if (light) oklch(0.42f, 0.020f, hue) else oklch(0.720f, 0.015f, hue)
    val muted = if (light) oklch(0.52f, 0.020f, hue) else oklch(0.620f, 0.018f, hue)
    val dim = if (light) oklch(0.62f, 0.020f, hue) else oklch(0.500f, 0.020f, hue)
    val checkOutline = if (light) oklch(0.70f, 0.020f, hue) else oklch(0.460f, 0.020f, hue)

    // Accent — fixed L/C; darkened on light so it holds contrast on white.
    val accent = if (light) oklch(0.56f, 0.15f, hue) else oklch(0.70f, 0.15f, hue)
    val accentGlow = if (light) oklch(0.62f, 0.15f, hue) else oklch(0.80f, 0.13f, hue)
    val accentEyebrow = if (light) oklch(0.52f, 0.09f, hue) else oklch(0.70f, 0.07f, hue)

    // Status voices sit *outside* the hue engine on purpose. Overdue/warning/done have to mean
    // the same thing at every hue the user can pick, so their hues are pinned (red / amber /
    // green) and only lightness moves with the mode — a rotated "red" that landed on the accent
    // would make urgency indistinguishable from decoration.
    val statusL = if (light) 0.55f else 0.70f
    val overdue = oklch(statusL, 0.17f, 25f)
    val warning = oklch(statusL + 0.05f, 0.14f, 80f)
    val success = oklch(statusL, 0.13f, 155f)

    // Per-node identity: same L/C, hue-rotated — harmonious relatives of the accent, not a rainbow.
    val idL = if (light) 0.58f else 0.70f
    val identity = listOf(0f, 40f, -40f, 95f, -95f, 150f)
        .map { oklch(idL, 0.13f, (hue + it + 360f) % 360f) }

    return YantraColors(
        isDark = !light,
        page = page,
        band = surface,
        bandTimer = surface,
        tileWarm = surfaceHigh,
        tileWarm2 = surface,
        cardBg = surface,
        railBg = rail,
        hairline = ink.copy(alpha = 0.09f),
        tileBorder = ink.copy(alpha = 0.11f),
        accent = accent,
        accentText = accent,
        accentGlow = accentGlow,
        accentEyebrow = accentEyebrow,
        accentFill = accent.copy(alpha = 0.15f),
        accentBorder = accent.copy(alpha = 0.46f),
        accentChipBg = accent.copy(alpha = if (light) 0.18f else 0.22f),
        onAccent = Color.White,
        textPrimary = ink,
        textSecondary = secondary,
        textMuted = muted,
        textDim = dim,
        checkOutline = checkOutline,
        due = accent,
        dueText = if (light) accent else accentGlow,
        dueChipBg = accent.copy(alpha = 0.16f),
        neutralChipBg = ink.copy(alpha = if (light) 0.06f else 0.07f),
        overdue = overdue,
        overdueChipBg = overdue.copy(alpha = if (light) 0.14f else 0.16f),
        warning = warning,
        warningChipBg = warning.copy(alpha = if (light) 0.14f else 0.16f),
        success = success,
        successChipBg = success.copy(alpha = if (light) 0.14f else 0.16f),
        secondaryButton = surfaceHigh,
        listIdentity = identity,
        inkPaper = if (light) Color.White else if (oled) Color.Black else oklch(0.150f, 0.006f, hue),
        inkPageSep = ink.copy(alpha = 0.10f),
    )
}

/** Defaults at the ship hue, for previews and off-composition use. */
val YantraDark = yantraColors(DEFAULT_HUE, ThemeMode.DARK)
val YantraOled = yantraColors(DEFAULT_HUE, ThemeMode.OLED)
val YantraLight = yantraColors(DEFAULT_HUE, ThemeMode.LIGHT)

/** Stable per-node identity palette (id-hash indexed). Non-composable so it works off-tree. */
val AccentPalette: List<Color> = YantraDark.listIdentity

val LocalYantra = staticCompositionLocalOf { YantraDark }
