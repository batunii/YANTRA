package ie.napkin.supertasks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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

private fun materialScheme(y: YantraColors) = if (y.isDark) {
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
        error = Color(0xFFFF5A6A),
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
        error = Color(0xFFC4472E),
    )
}

// Yantra shape scale: pills 5, chips/buttons 10, icon tiles 12–13, cards/tiles 16–18, FAB 20.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
 * Yantra theme. The whole palette is generated from [hue] + [mode] (see [yantraColors]).
 * [mode] defaults to the OS light/dark setting so previews and un-wired callers still work;
 * the app passes the user's stored mode + hue.
 */
@Composable
fun SuperTasksTheme(
    mode: ThemeMode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT,
    hue: Float = DEFAULT_HUE,
    content: @Composable () -> Unit,
) {
    val yantra = remember(mode, hue) { yantraColors(hue, mode) }
    CompositionLocalProvider(LocalYantra provides yantra) {
        MaterialTheme(
            colorScheme = materialScheme(yantra),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
