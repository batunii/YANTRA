package ie.napkin.supertasks.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.R

/** Reach Yantra's extended palette: `Yantra.colors.accentText`, etc. */
object Yantra {
    val colors: YantraColors
        @Composable @ReadOnlyComposable get() = LocalYantra.current
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun bricolage(w: Int) =
    Font(R.font.bricolage_grotesque, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
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
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
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

val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Wallpaper seed → OKLCH hue for the Yantra engine. Null when the wallpaper is near-neutral
 * (chroma too low for a meaningful hue) — caller falls back to the stored hue. The light
 * scheme's primary is used only as a stable hue carrier; hue is tone-invariant.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun dynamicSeedHue(context: Context): Float? {
    val (chroma, hue) = dynamicLightColorScheme(context).primary.oklchChromaHue()
    return if (chroma < 0.02f) null else hue
}

/**
 * Yantra theme. The whole palette is generated from [hue] + [mode] (see [yantraColors]);
 * with [dynamic] on (API 31+) the hue comes from the Material You wallpaper seed instead,
 * so all ~30 Yantra roles — and the M3 scheme mapped from them — rotate with the wallpaper.
 */
@Composable
fun SuperTasksTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    hue: Float = DEFAULT_HUE,
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val resolved = mode.resolve(isSystemInDarkTheme())
    val context = LocalContext.current
    // Wallpaper-color changes arrive as a configuration change → recompute the seed then.
    val config = LocalConfiguration.current
    val effectiveHue = if (dynamic && dynamicColorAvailable) {
        remember(config) { dynamicSeedHue(context) } ?: hue
    } else hue
    val yantra = remember(resolved, effectiveHue) { yantraColors(effectiveHue, resolved) }
    CompositionLocalProvider(LocalYantra provides yantra) {
        MaterialTheme(
            colorScheme = materialScheme(yantra),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
