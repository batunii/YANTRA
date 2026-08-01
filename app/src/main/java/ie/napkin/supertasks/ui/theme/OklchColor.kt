package ie.napkin.supertasks.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Yantra's whole palette is generated from a single hue. Fixing lightness and chroma in
 * **OKLCH** (a perceptually-uniform space) means every hue comes out equally bright and equally
 * vivid — unlike HSL, where a fixed lightness makes yellow blinding and blue murky. This is what
 * lets the user pick any hue and have the theme stay balanced.
 *
 * [oklch] converts an OKLCH triplet to an opaque sRGB [Color] (Björn Ottosson's transform),
 * clamping out-of-gamut results to the sRGB cube.
 *
 * @param l lightness, 0f..1f
 * @param c chroma (≈0f..0.37f); Yantra uses ~0.15 for accents, ~0.006–0.02 for grounds/text
 * @param hueDeg hue angle in degrees, 0f..360f
 */
fun oklch(l: Float, c: Float, hueDeg: Float): Color {
    val h = hueDeg / 180f * PI.toFloat()
    val a = c * cos(h)
    val b = c * sin(h)

    val lp = l + 0.3963377774f * a + 0.2158037573f * b
    val mp = l - 0.1055613458f * a - 0.0638541728f * b
    val sp = l - 0.0894841775f * a - 1.2914855480f * b

    val l3 = lp * lp * lp
    val m3 = mp * mp * mp
    val s3 = sp * sp * sp

    val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
    val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
    val bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3

    return Color(linearToSrgb(r), linearToSrgb(g), linearToSrgb(bl))
}

/** Clamp linear channel into gamut, then apply the sRGB transfer function → 0f..1f. */
private fun linearToSrgb(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
}
