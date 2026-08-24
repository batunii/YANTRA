package ie.napkin.supertasks.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraMotion

/**
 * Yantra's signature action affordance: a translucent accent fill + 1px accent border + accent
 * label/glyph. No shadow, no solid fill — used for FAB, add-bar "+ Task", focus controls,
 * "New list", quick-add send.
 */
@Composable
fun AccentPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 9.dp,
) {
    val y = Yantra.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = YantraMotion.fastSpatial(),
        label = "pillPress",
    )
    Row(
        modifier = modifier
            .scale(pressScale)
            .background(y.accentFill, shape)
            .border(1.dp, y.accentBorder, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = y.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = y.accentText, fontSize = 13.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
    }
}

/** Secondary, quiet chip — warm surface + subtle border. Add-bar Text/Heading/Ink/Image. */
@Composable
fun NeutralChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .background(y.tileWarm2, shape)
            .border(1.dp, y.tileBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = y.textSecondary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = y.textSecondary, fontSize = 13.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W600)
    }
}

/**
 * The Yantra app mark: a compass-rose / sunburst — eight rays (four long cardinal points,
 * four short) around a ring, with a checkmark at the centre. Sun (routine) + compass & gear
 * (the instrument) + check (done). Rays and ring take [tint]; the check takes [checkTint].
 */
@Composable
fun YantraMark(
    modifier: Modifier = Modifier,
    tint: Color = Yantra.colors.accent,
    checkTint: Color = tint,
) {
    Canvas(modifier) {
        val s = size.minDimension / 42f  // authored in a 42×42 box, centred at (21,21)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val cx = 21f; val cy = 21f
        val hw = 1.65f; val baseY = 10.5f
        for (i in 0 until 8) {
            val tipY = if (i % 2 == 0) 2f else 5.2f  // long cardinal / short diagonal rays
            rotate(i * 45f, pivot = p(cx, cy)) {
                val ray = Path().apply {
                    moveTo(cx * s, tipY * s)
                    lineTo((cx - hw) * s, baseY * s)
                    lineTo((cx + hw) * s, baseY * s)
                    close()
                }
                drawPath(ray, color = tint)
            }
        }
        drawCircle(color = tint, radius = 10f * s, center = p(cx, cy), style = Stroke(width = 2.4f * s))
        val check = Path().apply {
            moveTo(15f * s, 21.4f * s)
            lineTo(19.6f * s, 26f * s)
            lineTo(28f * s, 15.8f * s)
        }
        drawPath(check, color = checkTint, style = Stroke(width = 2.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Gear / cog — blunt-toothed ring with a hub. The "organize / grouping" motif from the yantra. */
@Composable
fun GearMark(modifier: Modifier = Modifier, tint: Color = Yantra.colors.accent) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val c = Offset(12f * s, 12f * s)
        val ring = 6.2f * s
        drawCircle(color = tint, radius = ring, center = c, style = Stroke(width = 2f * s))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val p1 = Offset(c.x + (ring * kotlin.math.cos(a)).toFloat(), c.y + (ring * kotlin.math.sin(a)).toFloat())
            val p2 = Offset(c.x + (9f * s * kotlin.math.cos(a)).toFloat(), c.y + (9f * s * kotlin.math.sin(a)).toFloat())
            drawLine(tint, p1, p2, strokeWidth = 2.6f * s, cap = StrokeCap.Round)
        }
        drawCircle(color = tint, radius = 1.9f * s, center = c)
    }
}

/**
 * A single four-point compass-star — the same glyph [TaskCheck] pops on completion, scaled up
 * and very faint. Used as a quiet watermark (behind the Focus dial) in place of a literal
 * hexagram: one star reads as this app's own mark, not a mystical diagram borrowed for the
 * occasion.
 */
@Composable
fun SparkleMark(modifier: Modifier = Modifier, tint: Color = Yantra.colors.accent, alpha: Float = 0.1f) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension / 2f
        val inner = outer * 0.4f
        val path = Path().apply {
            for (i in 0 until 8) {
                val rr = if (i % 2 == 0) outer else inner
                val a = Math.toRadians(-90.0 + i * 45.0)
                val px = cx + (rr * kotlin.math.cos(a)).toFloat()
                val py = cy + (rr * kotlin.math.sin(a)).toFloat()
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        drawPath(path, color = tint.copy(alpha = alpha))
    }
}
