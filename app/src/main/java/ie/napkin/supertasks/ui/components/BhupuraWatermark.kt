package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * The app's background flare: the bhupura itself, drawn very large and very faint.
 *
 * This is the only decoration in the app, and it is decoration in the strictest sense — it says
 * nothing, so it is drawn in the structure ink at an alpha where it reads as a watermark in paper
 * rather than as a mark on it. Using the brand path means the flare cannot drift from the identity:
 * the same [bhupuraPath] behind a screen, on a task, in the launcher icon and in the notification
 * tray. There is nothing new to keep in sync.
 *
 * It never moves. The motion law allows exactly one continuously animating thing in the app — the
 * live session arc — and a breathing background would be texture competing with the marks that
 * carry meaning.
 *
 * [inner] adds a second, smaller square rotated 45°, for the big empty surfaces (the focus view)
 * where a single outline reads as an accident rather than a device.
 */
@Composable
fun BhupuraWatermark(
    modifier: Modifier = Modifier,
    alpha: Float = 0.055f,
    inner: Boolean = true,
) {
    val y = Yantra.colors
    // Structure ink, not coral. Coral is spoken for: it means the user's own effort, and a giant
    // coral enclosure behind every screen would claim the whole app as one long session.
    val ink = y.checkOutline
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = bhupuraPath(s)
        val dx = (size.width - s) / 2f
        val dy = (size.height - s) / 2f
        translate(dx, dy) {
            drawPath(path, ink.copy(alpha = alpha), style = stroke)
        }
        if (inner) {
            val small = s * 0.58f
            val innerPath = bhupuraPath(small)
            val ix = (size.width - small) / 2f
            val iy = (size.height - small) / 2f
            rotate(45f) {
                translate(ix, iy) {
                    drawPath(innerPath, ink.copy(alpha = alpha * 0.8f), style = stroke)
                }
            }
        }
    }
}
