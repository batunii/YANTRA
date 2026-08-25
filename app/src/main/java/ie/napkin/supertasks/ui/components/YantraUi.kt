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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraMotion
import androidx.compose.ui.text.font.FontWeight

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
        Text(text, color = y.accentText, fontSize = 13.5.sp, fontWeight = FontWeight.W700)
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
        Text(text, color = y.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.W600)
    }
}

/**
 * The Yantra app mark: the bhupura with the bindu at its centre — the same path the launcher icon,
 * the task glyph and the focus glyph draw, so the identity is one shape everywhere it appears
 * rather than four drawings of an idea.
 *
 * The frame takes [tint] (structure) and the bindu takes [checkTint] (coral, the user's own effort).
 * It replaces a compass-rose with a tick at its centre: that mark belonged to the palette this app
 * no longer has, and the tick is precisely the gesture the bindu was introduced to retire.
 */
@Composable
fun YantraMark(
    modifier: Modifier = Modifier,
    tint: Color = Yantra.colors.checkOutline,
    checkTint: Color = Yantra.colors.accent,
) {
    Canvas(modifier) {
        val s = size.minDimension
        drawPartialPath(bhupuraPath(s), 1f, tint, s * 1.6f / 28f)
        drawCircle(color = checkTint, radius = s * 3.6f / 28f, center = center)
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

