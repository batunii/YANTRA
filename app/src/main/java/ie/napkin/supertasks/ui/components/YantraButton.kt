package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraText

/**
 * How loudly a button speaks. Three, because the app already spoke in three.
 *
 * [Solid] is the one thing this screen is for — filled with the accent, at most one per screen.
 * [Soft] is an affirmative that is not the only way out: accent-tinted, bordered, the voice most of
 * the app already uses. [Quiet] is the alternative beside it, carrying no accent at all.
 */
enum class ButtonTone { Solid, Soft, Quiet }

/**
 * The one button.
 *
 * There were six. Home filled a box with the accent at 13dp; the focus screen used an accent tint at
 * 16dp in one place and 12dp in another; the ink tray used the same tint at 10dp; two screens each
 * kept a private `SecondaryButton` and `GhostButton`; and this file — added a fortnight after
 * [SelectChip] cured exactly this disease for chips — arrived as the sixth rather than reusing any
 * of them. Same control, four corner radii, three font weights.
 *
 * What varies now is only [tone] and whether an icon is present. The caller owns the width, because
 * that genuinely differs: full width at the foot of a sheet, `weight(1f)` beside a sibling, wrapped
 * around its label in a tray.
 *
 * [busy] replaces the label rather than sitting beside it, and disables the button while it shows.
 * Every network caller of this is a round trip, and a button that still looks pressable during one
 * gets pressed twice — which for "create a repository" means two repositories.
 */
@Composable
fun YantraButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Solid,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(RADIUS)
    val live = enabled && !busy

    val ink = when {
        !live -> y.textDim
        tone == ButtonTone.Solid -> y.onAccent
        tone == ButtonTone.Soft -> y.accentText
        else -> y.textSecondary
    }
    val fill = when {
        tone == ButtonTone.Solid && !live -> y.accent.copy(alpha = 0.35f)
        tone == ButtonTone.Solid -> y.accent
        tone == ButtonTone.Soft -> y.accentFill
        else -> y.secondaryButton
    }
    val edge = when (tone) {
        ButtonTone.Solid -> null                 // a filled shape needs no outline
        ButtonTone.Soft -> y.accentBorder
        ButtonTone.Quiet -> y.tileBorder
    }

    Row(
        modifier
            .background(fill, shape)
            .then(if (edge != null) Modifier.border(1.dp, edge, shape) else Modifier)
            .clickable(enabled = live, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(17.dp), color = ink, strokeWidth = 2.dp)
        } else {
            if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(17.dp))
            Text(
                label,
                color = ink,
                fontFamily = YantraText,
                fontWeight = FontWeight.W700,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * One radius for every button in the app.
 *
 * Sits between the 12dp of a field and the 14dp of a card, which is the order these things should
 * read in. The previous four values were not decisions, they were four afternoons.
 */
private val RADIUS = 13.dp
