package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * The one full-width action.
 *
 * [primary] is the accent fill Home already uses for "Create"; the quiet variant is the raised
 * surface. Only one primary per screen — the accent means "this is the thing you came here to do",
 * and two of them on a page means neither does.
 *
 * [busy] replaces the label rather than sitting beside it, and disables the button while it shows.
 * Every caller of this is a network round trip, and a button that still looks pressable during one
 * gets pressed twice — which for "create a repository" means two repositories.
 */
@Composable
fun YantraButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(13.dp)
    val live = enabled && !busy
    val ink = when {
        !live -> y.textDim
        primary -> y.onAccent
        else -> y.textPrimary
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(
                when {
                    !live && primary -> y.accent.copy(alpha = 0.35f)
                    primary -> y.accent
                    else -> y.secondaryButton
                },
                shape,
            )
            .then(if (primary) Modifier else Modifier.border(1.dp, y.tileBorder, shape))
            .clickable(enabled = live, onClick = onClick)
            .padding(vertical = 13.dp),
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
