package ie.shoonya.yantra.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import ie.shoonya.yantra.ui.components.CompletionTempo
import ie.shoonya.yantra.ui.components.LocalCompletionTempo
import ie.shoonya.yantra.ui.components.LocalYantraHaptics
import ie.shoonya.yantra.ui.components.YantraHaptics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.R
import androidx.compose.ui.text.ExperimentalTextApi

/** Reach Yantra's extended palette: `Yantra.colors.accentText`, etc. */
object Yantra {
    val colors: YantraColors
        @Composable @ReadOnlyComposable get() = LocalYantra.current
}

@OptIn(ExperimentalTextApi::class)
private fun bricolage(w: Int) =
    Font(R.font.bricolage_grotesque, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
private fun grotesk(w: Int) =
    Font(R.font.space_grotesk, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Display voice: Bricolage Grotesque — characterful contemporary grotesque, all big titles. */
val YantraDisplay = FontFamily(bricolage(500), bricolage(600), bricolage(700), bricolage(800))

/** Text voice: Space Grotesk — the UI/body face (rows, labels, meta, buttons). */
val YantraText = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))

/** Instrument voice: Space Mono — the focus countdown and eyebrows only. */
val YantraMono = FontFamily(
    Font(R.font.space_mono_regular, weight = FontWeight.W400),
    Font(R.font.space_mono_bold, weight = FontWeight.W700),
)

internal fun materialScheme(y: YantraColors) = if (y.isDark) {
    darkColorScheme(
        primary = y.accent,
        onPrimary = y.onAccent,
        primaryContainer = y.bandTimer,
        onPrimaryContainer = y.accentText,
        secondary = y.textSecondary,
        onSecondary = y.page,
        secondaryContainer = y.tileWarm,
        onSecondaryContainer = y.textPrimary,
        background = y.page,
        onBackground = y.textPrimary,
        surface = y.cardBg,
        onSurface = y.textPrimary,
        surfaceVariant = y.tileWarm2,
        onSurfaceVariant = y.textMuted,
        surfaceContainer = y.cardBg,
        surfaceContainerHigh = y.tileWarm,
        surfaceContainerHighest = y.tileWarm,
        outline = y.checkOutline,
        outlineVariant = y.tileBorder,
        error = y.overdue,
        tertiary = y.warning,
    )
} else {
    lightColorScheme(
        primary = y.accent,
        onPrimary = y.onAccent,
        primaryContainer = y.bandTimer,
        onPrimaryContainer = y.accentText,
        secondary = y.textSecondary,
        onSecondary = y.page,
        secondaryContainer = y.tileWarm,
        onSecondaryContainer = y.textPrimary,
        background = y.page,
        onBackground = y.textPrimary,
        surface = y.cardBg,
        onSurface = y.textPrimary,
        surfaceVariant = y.tileWarm2,
        onSurfaceVariant = y.textMuted,
        surfaceContainer = y.cardBg,
        surfaceContainerHigh = y.tileWarm,
        surfaceContainerHighest = y.tileWarm,
        outline = y.checkOutline,
        outlineVariant = y.tileBorder,
        error = y.overdue,
        tertiary = y.warning,
    )
}

// Yantra shape scale: pills 5, chips/buttons 10, icon tiles 12–13, cards/tiles 16–18, FAB 20.
// extraLarge follows M3 Expressive's rounder sheets (bottom-sheet top corners app-wide).
/**
 * The sanctioned radii, named by the surface they belong to.
 *
 * Same finding as [YantraType], and the same answer. The audit counted **130 corner radii in 23
 * distinct values, 90 of them outside AppShapes** — 7, 8 and 9 all doing "a small clipped thing",
 * 13, 15 and 22 existing because one screen invented its own set. A radius is a family resemblance:
 * one that is two off does not read as a distinction, it reads as a mistake.
 *
 * AppShapes was five Material slots and the app needed eight surfaces, so it was gone around. The
 * names here are the surfaces that actually exist, and AppShapes is expressed in terms of them so
 * there is one source rather than two that nearly agree.
 *
 * [card] is 14, not Material's 16, because 14 is what the app already used twenty-four times for a
 * card — more than any other radius in the codebase. The convention was already there; it just had
 * no name.
 */
object YantraRadius {
    /** A capsule: a pill whose radius is half its height, whatever that turns out to be. */
    val pill = 999.dp
    /** The smallest mark that still has corners — a swatch, a dot with a square shoulder. */
    val tiny = 4.dp
    /** A block on the timeline, a row in the rail: small, clipped, many of them at once. */
    val block = 8.dp
    /** A button, a field, a chip. Material's `small`. */
    val control = 10.dp
    /** A secondary surface inside something else — a panel in the ink kit, a tool tray. */
    val panel = 12.dp
    /** A card on the page. The most used radius in the app. */
    val card = 14.dp
    /** A band, a bottom sheet, the header's rounded foot. Material's `large`. */
    val sheet = 18.dp
    /** The largest: a full-height surface that still wants a corner. Material's `extraLarge`. */
    val hero = 28.dp
}

private val AppShapes = Shapes(
    // Expressed in the named scale, so Material's components and the app's own surfaces cannot
    // drift apart. `medium` is the card radius the app actually uses rather than Material's 16.
    extraSmall = RoundedCornerShape(YantraRadius.tiny),
    small = RoundedCornerShape(YantraRadius.control),
    medium = RoundedCornerShape(YantraRadius.card),
    large = RoundedCornerShape(YantraRadius.sheet),
    extraLarge = RoundedCornerShape(YantraRadius.hero),
)

/**
 * Type ramp: Bricolage Grotesque (display) for big titles with tight tracking, Space Grotesk
 * (text) for everything else. Space Mono is set at call sites for the timer/eyebrows only.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontFamily = YantraDisplay, fontWeight = FontWeight.W700, letterSpacing = (-0.6).sp,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = YantraDisplay, fontWeight = FontWeight.W700, letterSpacing = (-0.5).sp,
        ),
        // Page hero title (32sp).
        headlineMedium = TextStyle(
            fontFamily = YantraDisplay, fontWeight = FontWeight.W700, fontSize = 32.sp,
            lineHeight = 38.sp, letterSpacing = (-0.6).sp,
        ),
        // Screen title — Home greeting / Stats / smart-list (24sp).
        headlineSmall = TextStyle(
            fontFamily = YantraDisplay, fontWeight = FontWeight.W700, fontSize = 24.sp,
            lineHeight = 29.sp, letterSpacing = (-0.5).sp,
        ),
        // Smart-list / focus title (22sp).
        titleLarge = TextStyle(
            fontFamily = YantraDisplay, fontWeight = FontWeight.W700, fontSize = 22.sp,
            lineHeight = 27.sp, letterSpacing = (-0.4).sp,
        ),
        // Card title, block heading (15.5sp).
        titleMedium = TextStyle(
            fontFamily = YantraText, fontWeight = FontWeight.W700, fontSize = 15.5.sp,
            lineHeight = 20.sp, letterSpacing = (-0.1).sp,
        ),
        // Section label — uppercase with extra tracking at call sites.
        titleSmall = TextStyle(
            fontFamily = YantraText, fontWeight = FontWeight.W700, fontSize = 12.sp,
            lineHeight = 16.sp, letterSpacing = 1.2.sp,
        ),
        // Row title (15sp / W500).
        bodyLarge = TextStyle(
            fontFamily = YantraText, fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 20.sp,
        ),
        // Paragraph block (14.5sp).
        bodyMedium = TextStyle(
            fontFamily = YantraText, fontWeight = FontWeight.W400, fontSize = 14.5.sp, lineHeight = 22.sp,
        ),
        // Meta / subtitle (12.5sp).
        bodySmall = TextStyle(
            fontFamily = YantraText, fontWeight = FontWeight.W400, fontSize = 12.5.sp, lineHeight = 17.sp,
        ),
        labelLarge = TextStyle(fontFamily = YantraText, fontWeight = FontWeight.W700, fontSize = 13.5.sp),
        labelMedium = TextStyle(
            fontFamily = YantraText, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 15.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = YantraText, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 14.sp,
        ),
    )
}

/**
 * The sanctioned sizes, named by the job they do.
 *
 * **Why a size scale as well as [AppTypography].** The ramp gives a whole style — family, weight,
 * tracking, line height — and that is right where a call site wants all of it. Most do not: a chip
 * sets its own weight and colour and wants only the size, and until now it wrote a number. The
 * audit found **232 of those across 41 files in 17 distinct sizes**, including 11, 11.5, 12 and
 * 12.5 as four separate decisions nobody made.
 *
 * So every size a call site is allowed to use is named here, and each one maps to a role in the
 * ramp. A call site that wants the whole style still takes `MaterialTheme.typography`; one that
 * wants a number takes a name.
 *
 * **Two roles were missing, which is why the drift happened.** A scale that does not cover the app
 * is a scale people go around, and both gaps were real work rather than carelessness:
 *
 *  - [dense] — the timeline's hour numerals, the rail's rows, a count beside a title. The ramp
 *    bottomed out at 11sp, and a day from 07:00 to 23:00 does not fit at 11sp. This is the one
 *    place the app is allowed to go smaller, and it is for data that is read as a column rather
 *    than as a sentence.
 *  - [sheetTitle] — the name at the top of a sheet. It sits between a card title (15.5) and a
 *    screen title (22): a sheet is not a screen, and giving it the screen's size made a half-height
 *    surface shout.
 *
 * An eyebrow is **not** one of the gaps. CALENDAR_UI.md §4 found one at 8.5sp carrying the only
 * piece of state its bar reported — three and a half points under the scale's floor — and the
 * answer there is [section] at 12sp, not a smaller token.
 */
object YantraType {
    /**
     * The wordmark, on the splash and nowhere else.
     *
     * A brand moment rather than a step in the scale: it appears once, for under a second, with no
     * other text beside it to be in proportion to. It is named here anyway, because the alternative
     * is the one number in the app with no home.
     */
    val wordmark = 46.sp
    /** Page hero — `headlineMedium`. */
    val hero = 32.sp
    /** Screen title — `headlineSmall`. */
    val screen = 24.sp
    /** Smart-list and focus title — `titleLarge`. */
    val title = 22.sp
    /** Sheet title. Between a card and a screen; see the note above. */
    val sheetTitle = 19.sp
    /** Card title, block heading — `titleMedium`. */
    val card = 15.5.sp
    /** Row title — `bodyLarge`. */
    val row = 15.sp
    /** Paragraph — `bodyMedium`. */
    val body = 14.5.sp
    /** Button and pill text — `labelLarge`. */
    val label = 13.5.sp
    /** Meta and subtitle — `bodySmall`. */
    val meta = 12.5.sp
    /** Section label, eyebrow, chip — `titleSmall` / `labelMedium`. */
    val section = 12.sp
    /** Caption — `labelSmall`. */
    val caption = 11.sp
    /** Dense data only: timeline hours, rail rows, counts. See the note above. */
    val dense = 10.sp
}

/** Space Mono ramp for the timer countdown and breadcrumb — set at call sites. */
val MonoLarge = TextStyle(
    fontFamily = YantraMono, fontWeight = FontWeight.W700,
    fontSize = 46.sp, letterSpacing = (-1).sp,
)
val MonoBanner = TextStyle(
    fontFamily = YantraMono, fontWeight = FontWeight.W700,
    fontSize = 19.sp, letterSpacing = (0).sp,
)
val MonoBreadcrumb = TextStyle(
    fontFamily = YantraMono, fontWeight = FontWeight.W400,
    fontSize = 10.sp, letterSpacing = 1.4.sp,
)

/**
 * Expressive-style motion language (M3 Expressive's MotionScheme is still internal in
 * material3 1.4.0, so the spec values live here): spatial springs with a hint of bounce for
 * things that move/scale, quick tweens for color/alpha. Swap for MotionScheme when it goes public.
 */
object YantraMotion {
    /** Snappy spring for small, frequent gestures (checkbox pop, pressed scale). */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Default spring for layout-level movement (expand/collapse, screen slides). */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 380f)

    /** Color/alpha fades — never bounce. */
    fun <T> effects(): FiniteAnimationSpec<T> = tween(200)
}


/**
 * Everything a Yantra screen draws inside: the OKLCH palette for the chosen mode and accent, the
 * haptics that carry the completion choreography's feel channel, the shared tempo those two agree
 * on, and a Material theme underneath for the components that still want one.
 *
 * The doc that used to sit here described a wallpaper-seed function that was removed — the hue now
 * comes from the stored accent, not from what is behind the launcher. It also carried a
 * `@RequiresApi(S)` that outlived its reason and quietly held the whole app to Android 12 while
 * the manifest advertised Android 8; the manifest now says 31 and the annotation is redundant.
 */
@Composable
fun SuperTasksTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.CORAL,
    content: @Composable () -> Unit,
) {
    val resolved = mode.resolve(isSystemInDarkTheme())
    val yantra = remember(resolved, accent) { yantraColors(resolved, accent) }
    // The completion choreography's shared state. Haptics are the feel channel the motion law
    // leans on — when the user has animations off, the thud is what is left of the reward.
    val context = LocalContext.current
    val haptics = remember(context) { YantraHaptics(context) }
    val tempo = remember { CompletionTempo() }
    CompositionLocalProvider(
        LocalYantra provides yantra,
        LocalYantraHaptics provides haptics,
        LocalCompletionTempo provides tempo,
    ) {
        MaterialTheme(
            colorScheme = materialScheme(yantra),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
