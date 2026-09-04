package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import ie.napkin.supertasks.ui.theme.YantraDisplay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
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
 * The mark is the bhupura, drawn faintly — the same shape as the icon, the task glyph and the focus
 * glyph, so an empty list is stamped with the app rather than decorated with a stock sparkle.
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
        YantraMark(
            Modifier.size(34.dp),
            tint = y.checkOutline.copy(alpha = 0.55f),
            checkTint = y.accent.copy(alpha = 0.45f),
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

/**
 * A hairline under a header band, inset past its rounded corners.
 *
 * The band briefly ended in the bhupura's gate. A mark that appears above every screen is not
 * cosmetic any more — you cannot look away from it — so what is left is this: a fold line, the
 * width of the content and no darker than the dividers, doing nothing but separating two sheets.
 */
@Composable
fun HeaderFold(inset: Dp = 22.dp) {
    val y = Yantra.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(y.hairline),
    )
}

/**
 * The large title a screen opens with, and the compact bar it becomes.
 *
 * ## Why the top of the screen is mostly empty
 *
 * A phone this size cannot be operated one-handed at the top. The thumb reaches the bottom two
 * thirds comfortably and the top corner not at all, which is the whole reason One UI puts a screen's
 * name in a tall band that scrolls away and its controls near the bottom: the part you cannot reach
 * is spent on something you only ever read.
 *
 * So the rule this encodes, and the one to keep when adding a screen:
 *
 *  - **The title is big, and it is not a control.** It occupies the unreachable band on purpose.
 *  - **Nothing in the expanded band is tappable** except the back circle, which is a courtesy —
 *    the real way back is the system gesture, which needs no target at all.
 *  - **Actions live at the bottom**, in the bar or the cluster, where the thumb already is.
 *
 * [collapsed] is the screen's own answer, because only the screen knows what it scrolls: a list
 * hands over its `firstVisibleItemIndex`, a `verticalScroll` its offset. Folding is a one-shot
 * transition either way — the band is never mid-animation at rest, which the motion law requires.
 *
 * The node page keeps its own `PageBand` rather than using this. A page's title is editable, it
 * carries a task glyph, a breadcrumb, property pills and a linked row, and it folds all of them —
 * it is this pattern with a document's worth of extra furniture, not a variant of this component.
 */
@Composable
fun PageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    collapsed: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val y = Yantra.colors
    Column(Modifier.fillMaxWidth().background(y.page)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                NavCircle(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    onClick = onBack,
                    iconSize = 22.dp,
                )
            }
            // Collapsed, the name moves up here so the screen never goes unlabelled. It is the
            // same string at a size that fits a bar, not a second title.
            AnimatedVisibility(
                visible = collapsed,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    title,
                    fontFamily = YantraDisplay,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = (-0.3).sp,
                    color = y.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (!collapsed) Spacer(Modifier.weight(1f))
            actions()
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Text(
                title,
                fontFamily = YantraDisplay,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = (-0.6).sp,
                color = y.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 20.dp),
            )
        }
    }
}
