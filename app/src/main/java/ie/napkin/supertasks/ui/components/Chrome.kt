package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * The app's one nav/action button: a small translucent circle. Round rather than a rounded
 * square so header chrome reads as chrome — the squares competed with the cards and chips,
 * which are the things that actually hold content.
 */
@Composable
fun NavCircle(
    icon: ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    size: Dp = 38.dp,
    iconSize: Dp = 19.dp,
    modifier: Modifier = Modifier,
) {
    val y = Yantra.colors
    Box(
        modifier
            .size(size)
            .background(
                if (accent) y.accent.copy(alpha = 0.12f) else y.textPrimary.copy(alpha = 0.05f),
                CircleShape,
            )
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (accent) y.accentGlow else y.textSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** [NavCircle]'s surface with arbitrary content — for glyphs that aren't an [ImageVector]. */
@Composable
fun NavCircleSurface(
    onClick: (() -> Unit)? = null,
    accent: Boolean = false,
    size: Dp = 38.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val y = Yantra.colors
    Box(
        modifier
            .size(size)
            .background(
                if (accent) y.accent.copy(alpha = 0.12f) else y.textPrimary.copy(alpha = 0.05f),
                CircleShape,
            )
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Fades the trailing edge of a horizontally-scrolling row to transparent. A row that simply
 * gets cut off at the screen edge looks broken; one that dissolves reads as "there's more,
 * scroll me". Needs an offscreen layer so [BlendMode.DstIn] has an alpha channel to punch.
 */
fun Modifier.horizontalFadingEdge(width: Dp = 28.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = size.width - fade,
                endX = size.width,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * A void with nothing in it reads as a bug; a void with one quiet mark in it reads as done.
 * The mark is the compass-star from the logo, at the opacity of a watermark.
 */
@Composable
fun ComposedEmpty(
    line: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val y = Yantra.colors
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = y.accent.copy(alpha = 0.35f),
            modifier = Modifier.size(30.dp),
        )
        Text(line, fontSize = 13.sp, fontWeight = FontWeight.W500, color = y.textMuted)
        if (action != null && onAction != null) {
            Row(
                Modifier
                    .background(y.accentFill, RoundedCornerShape(99.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, fontSize = 12.sp, fontWeight = FontWeight.W700, color = y.accent)
            }
        }
    }
}

/**
 * Hairline separator sized to sit *inside* a grouped card — inset so it never touches the
 * card's rounded edge, which is what makes stacked rows read as one surface rather than a
 * stack of slabs.
 */
@Composable
fun GroupDivider(inset: Dp = 16.dp) {
    val y = Yantra.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(y.hairline),
    )
}
