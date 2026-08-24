package ie.napkin.supertasks.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The bhupura's gate, as a surface shape.
 *
 * The brand mark is a square whose every side is broken by a rectangular gate. Drawn whole and
 * faint behind a screen it was only ever decoration — a picture of the app laid under the app. The
 * shape is more useful than the picture: a header band and a list card can *be* gated instead of
 * sitting on top of something that is, so the identity is carried by the surfaces you actually
 * touch rather than by a watermark competing with them.
 *
 * Proportions come from [bhupuraPath]'s 28-unit design space: the gate spans units 11–17 (6 of 28,
 * so a little over a fifth) and protrudes 2 units. Those ratios only read correctly on a square. On
 * a band the width of a phone a proportional gate would be enormous, so [gateWidth] and [gateDepth]
 * are absolute — the gate keeps its *character* (a wide, shallow, flat-bottomed tab with square
 * shoulders) rather than its arithmetic.
 *
 * The gate is drawn INSIDE the component's bounds: the body stops [gateDepth] short of the bottom
 * and the tab reaches down to fill it. Callers therefore need that much extra bottom padding, or
 * content will sit in the tab.
 */
class GatedSurface(
    private val topCorner: Dp = 0.dp,
    private val bottomCorner: Dp = 20.dp,
    private val gateWidth: Dp = 84.dp,
    private val gateDepth: Dp = 10.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = with(density) {
        val depth = gateDepth.toPx().coerceIn(0f, size.height / 3f)
        val body = size.height - depth
        val top = topCorner.toPx().coerceIn(0f, minOf(size.width / 2f, body / 2f))
        val bottom = bottomCorner.toPx().coerceIn(0f, minOf(size.width / 2f, body / 2f))
        // Leave room for both bottom corners; a gate wider than the edge it sits on would invert
        // the shoulders and draw a bow-tie.
        val gate = gateWidth.toPx().coerceIn(0f, (size.width - 2f * bottom).coerceAtLeast(0f))
        val cx = size.width / 2f

        Outline.Generic(
            Path().apply {
                moveTo(top, 0f)
                lineTo(size.width - top, 0f)
                if (top > 0f) quadraticBezierTo(size.width, 0f, size.width, top)
                lineTo(size.width, body - bottom)
                if (bottom > 0f) quadraticBezierTo(size.width, body, size.width - bottom, body)
                if (gate > 0f && depth > 0f) {
                    lineTo(cx + gate / 2f, body)
                    lineTo(cx + gate / 2f, size.height)
                    lineTo(cx - gate / 2f, size.height)
                    lineTo(cx - gate / 2f, body)
                }
                lineTo(bottom, body)
                if (bottom > 0f) quadraticBezierTo(0f, body, 0f, body - bottom)
                lineTo(0f, top)
                if (top > 0f) quadraticBezierTo(0f, 0f, top, 0f)
                close()
            }
        )
    }
}
