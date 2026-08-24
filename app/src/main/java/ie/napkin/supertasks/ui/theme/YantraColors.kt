package ie.napkin.supertasks.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ie.napkin.supertasks.ui.components.YantraInk

/**
 * Yantra's palette, governed by the colour law of the design language (see YantraGlyph.kt):
 *
 *     neutral         = structure   (frames, tracks, grounds, text)
 *     coral           = the user's own effort  (engagement, sessions, bindu, ink strike, done)
 *     crimson / amber = the world   (priority — task-list frames only)
 *     gray            = rest        (break arc)
 *
 * Each hue lives on exactly one layer, and that is why this file no longer contains a hue engine.
 * The palette used to be derived from a hue the user picked, every role rotating together. Under
 * the law that cannot hold: coral does not mean "accent", it means *your effort* — a rotatable
 * accent would let effort be rendered in the same hue as priority, and the layers would stop being
 * readable. So the hues are fixed and only lightness moves with the mode.
 *
 * What survives from the old engine is [oklch] and the discipline behind it: the grounds and inks
 * are still fixed-lightness OKLCH steps, now on a single warm neutral, so paper reads as paper and
 * coral is the only warm thing on it with anything to say.
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
    val accent: Color,          // coral: the user's own effort
    val accentText: Color,
    val accentGlow: Color,
    val accentEyebrow: Color,
    val accentFill: Color,
    val accentBorder: Color,
    val accentChipBg: Color,
    val onAccent: Color,        // ink on solid coral
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDim: Color,
    val checkOutline: Color,    // neutral: the bhupura frame's ink
    val due: Color,
    val dueText: Color,
    val dueChipBg: Color,
    val neutralChipBg: Color,
    // The world: pinned crimson/amber. Priority only, and only on a task list.
    val overdue: Color,
    val overdueChipBg: Color,
    val warning: Color,
    val warningChipBg: Color,
    // Completion is the user's own effort, so it is coral — never green. Green would open a fifth
    // hue layer that the law has no room for, and would disagree with the bindu the glyph draws.
    val success: Color,
    val successChipBg: Color,
    val secondaryButton: Color,
    val inkPaper: Color,
    val inkPageSep: Color,
)

enum class ThemeMode { SYSTEM, DARK, OLED, LIGHT }

/** Collapse SYSTEM to a concrete mode; screens/theme call this before [yantraColors]. */
fun ThemeMode.resolve(systemDark: Boolean): ThemeMode =
    if (this == ThemeMode.SYSTEM) (if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT) else this

/**
 * The one warm neutral everything structural is built from. Warm rather than blue-grey because the
 * design language is a pen on paper: coral ink has to sit on something that could plausibly hold it.
 */
private const val PAPER_HUE = 80f

/**
 * Build the palette for a [mode]. No hue parameter: see the note on [YantraColors].
 */
fun yantraColors(mode: ThemeMode): YantraColors {
    @Suppress("NAME_SHADOWING") val mode = if (mode == ThemeMode.SYSTEM) ThemeMode.DARK else mode
    val light = mode == ThemeMode.LIGHT
    val oled = mode == ThemeMode.OLED

    // Grounds — warm paper on light, warm graphite on dark. Chroma stays under 0.01 so the ground
    // never competes with coral; it only keeps it from looking like ink on a screen.
    val page = when (mode) {
        ThemeMode.OLED -> Color.Black
        ThemeMode.LIGHT -> oklch(0.972f, 0.006f, PAPER_HUE)
        else -> oklch(0.171f, 0.005f, PAPER_HUE)
    }
    val surface = when (mode) {
        ThemeMode.OLED -> oklch(0.188f, 0.006f, PAPER_HUE)
        ThemeMode.LIGHT -> oklch(0.995f, 0.003f, PAPER_HUE)
        else -> oklch(0.216f, 0.007f, PAPER_HUE)
    }
    val surfaceHigh = when (mode) {
        ThemeMode.OLED -> oklch(0.232f, 0.008f, PAPER_HUE)
        ThemeMode.LIGHT -> oklch(0.958f, 0.007f, PAPER_HUE)
        else -> oklch(0.257f, 0.009f, PAPER_HUE)
    }
    val rail = when (mode) {
        ThemeMode.OLED -> oklch(0.102f, 0.005f, PAPER_HUE)
        ThemeMode.LIGHT -> oklch(0.935f, 0.007f, PAPER_HUE)
        else -> oklch(0.137f, 0.005f, PAPER_HUE)
    }

    // Inks — the structure layer. These are the same warm neutrals the glyph strokes with, so a
    // bhupura frame and the text beside it are demonstrably the same colour.
    val ink = if (light) oklch(0.26f, 0.012f, PAPER_HUE) else oklch(0.948f, 0.005f, PAPER_HUE)
    val secondary = if (light) oklch(0.44f, 0.011f, PAPER_HUE) else oklch(0.735f, 0.008f, PAPER_HUE)
    val muted = if (light) oklch(0.545f, 0.010f, PAPER_HUE) else oklch(0.638f, 0.009f, PAPER_HUE)
    val dim = if (light) oklch(0.655f, 0.009f, PAPER_HUE) else oklch(0.510f, 0.010f, PAPER_HUE)

    // Coral, crimson and amber come from the glyph's own inks — one definition, so a drawn glyph and
    // a themed surface can never disagree about what coral is.
    val coral = YantraInk.coral(!light)
    val crimson = YantraInk.crimson(!light)
    val amber = YantraInk.amber(!light)
    // The frame's neutral, straight from the design language.
    val frame = YantraInk.neutral(!light)

    return YantraColors(
        isDark = !light,
        page = page,
        band = surface,
        bandTimer = surface,
        tileWarm = surfaceHigh,
        tileWarm2 = surface,
        cardBg = surface,
        railBg = rail,
        hairline = ink.copy(alpha = 0.085f),
        tileBorder = ink.copy(alpha = 0.105f),
        accent = coral,
        accentText = coral,
        // No glow: a lit accent is texture, and the motion law's sibling is that nothing radiates
        // at rest either. This stays coral so old call sites read as plain coral.
        accentGlow = coral,
        accentEyebrow = if (light) coral.copy(alpha = 0.85f) else coral,
        accentFill = coral.copy(alpha = if (light) 0.12f else 0.15f),
        accentBorder = coral.copy(alpha = 0.44f),
        accentChipBg = coral.copy(alpha = if (light) 0.15f else 0.20f),
        onAccent = if (light) Color.White else oklch(0.16f, 0.010f, PAPER_HUE),
        textPrimary = ink,
        textSecondary = secondary,
        textMuted = muted,
        textDim = dim,
        checkOutline = frame,
        due = coral,
        dueText = coral,
        dueChipBg = coral.copy(alpha = 0.14f),
        neutralChipBg = ink.copy(alpha = if (light) 0.055f else 0.07f),
        overdue = crimson,
        overdueChipBg = crimson.copy(alpha = if (light) 0.13f else 0.16f),
        warning = amber,
        warningChipBg = amber.copy(alpha = if (light) 0.13f else 0.16f),
        success = coral,
        successChipBg = coral.copy(alpha = if (light) 0.13f else 0.16f),
        secondaryButton = surfaceHigh,
        inkPaper = if (light) Color.White else if (oled) Color.Black else oklch(0.152f, 0.005f, PAPER_HUE),
        inkPageSep = ink.copy(alpha = 0.095f),
    )
}

/** Defaults for previews and off-composition use. */
val YantraDark = yantraColors(ThemeMode.DARK)
val YantraOled = yantraColors(ThemeMode.OLED)
val YantraLight = yantraColors(ThemeMode.LIGHT)

val LocalYantra = staticCompositionLocalOf { YantraDark }
