package ie.napkin.supertasks.ui.components

/**
 * YantraGlyph — the shared design language. One source of truth.
 *
 * The bhupura path is the brand: the checkbox, the focus glyph, the day
 * seal, widgets, and notification icons all draw THIS path, scaled from
 * the 28-unit design space. Nothing else may redefine it.
 *
 * Color law (each hue lives on exactly one layer):
 *   neutral  = structure (frames, tracks)
 *   coral    = the user's own effort (engagement circle, session arc,
 *              strata marks, bindu, ink strike)
 *   crimson / amber = the world (priority — task-list frames ONLY;
 *              never in the focus view, never on done)
 *   gray     = rest (break arc)
 *
 * Motion law: motion is punctuation, never texture. One-shot transitions
 * only; nothing on screen moves at rest (the sole sanctioned exception is
 * the live session arc, one revolution per session).
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Theme-aware inks. Dark values are lightened steps of the same hue so
 *  hairline strokes clear 3:1 non-text contrast on dark surfaces. */
object YantraInk {
    val coralLight = Color(0xFFD85A30)
    val coralDark = Color(0xFFE8865F)
    val crimsonLight = Color(0xFFA32D2D)   // high priority (task list only)
    val crimsonDark = Color(0xFFE24B4A)
    val amberLight = Color(0xFFBA7517)     // medium priority (task list only)
    val amberDark = Color(0xFFEF9F27)

    fun coral(darkTheme: Boolean) = if (darkTheme) coralDark else coralLight
    fun crimson(darkTheme: Boolean) = if (darkTheme) crimsonDark else crimsonLight
    fun amber(darkTheme: Boolean) = if (darkTheme) amberDark else amberLight
    fun neutral(darkTheme: Boolean) =
        if (darkTheme) Color(0xFFB4B2A9) else Color(0xFF5F5E5A)
}

/** The gated square (bhupura). Pass the target size in px; the path scales
 *  from the 28-unit design space. */
internal fun bhupuraPath(s: Float): Path {
    val u = s / 28f
    return Path().apply {
        moveTo(8 * u, 4 * u)
        lineTo(11 * u, 4 * u); lineTo(11 * u, 2 * u)
        lineTo(17 * u, 2 * u); lineTo(17 * u, 4 * u)
        lineTo(20 * u, 4 * u)
        quadraticTo(24 * u, 4 * u, 24 * u, 8 * u)
        lineTo(24 * u, 11 * u); lineTo(26 * u, 11 * u)
        lineTo(26 * u, 17 * u); lineTo(24 * u, 17 * u)
        lineTo(24 * u, 20 * u)
        quadraticTo(24 * u, 24 * u, 20 * u, 24 * u)
        lineTo(17 * u, 24 * u); lineTo(17 * u, 26 * u)
        lineTo(11 * u, 26 * u); lineTo(11 * u, 24 * u)
        lineTo(8 * u, 24 * u)
        quadraticTo(4 * u, 24 * u, 4 * u, 20 * u)
        lineTo(4 * u, 17 * u); lineTo(2 * u, 17 * u)
        lineTo(2 * u, 11 * u); lineTo(4 * u, 11 * u)
        lineTo(4 * u, 8 * u)
        quadraticTo(4 * u, 4 * u, 8 * u, 4 * u)
        close()
    }
}

/** Draw [progress] (0..1) of a path — the un-draw/draw primitive behind the
 *  completion choreography and the hold windup. */
internal fun DrawScope.drawPartialPath(
    full: Path, progress: Float, color: Color, strokeWidth: Float
) {
    if (progress <= 0f) return
    val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (progress >= 1f) { drawPath(full, color, style = stroke); return }
    val pm = PathMeasure().apply { setPath(full, false) }
    val seg = Path()
    pm.getSegment(0f, pm.length * progress, seg, true)
    drawPath(seg, color, style = stroke)
}
