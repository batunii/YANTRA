package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * The one selectable chip.
 *
 * There used to be seven of these — a type picker on Home and another on a task page, three in the
 * rule builder, a theme picker in Settings, a tool chip in the ink tray. Same control every time,
 * and no two agreed: four corner radii, four unselected grounds (one of them a hardcoded hex that
 * ignored the theme entirely), five type sizes, and two different ideas of what "selected" looks
 * like. A user cannot learn a control that is redrawn on every screen, so there is now one.
 *
 * What varies is only what genuinely differs between the sites: how big it is, whether it carries
 * an icon, and how it is laid out (the modifier). The selected voice — coral fill, coral border,
 * coral text — is fixed, because "this one is chosen" has to mean the same thing everywhere.
 */
enum class ChipSize { Small, Medium }

@Composable
fun SelectChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: ChipSize = ChipSize.Medium,
    icon: ImageVector? = null,
    /**
     * True when the caller stretches the chip to a share of a row (a segmented control) rather
     * than letting it size to its label. Padding sized for a free-standing chip leaves too little
     * room inside a quarter-width one — it is what wrapped "System" onto two lines in Settings.
     */
    stretch: Boolean = false,
    onClick: () -> Unit,
) {
    val y = Yantra.colors
    val small = size == ChipSize.Small
    val shape = RoundedCornerShape(if (small) 8.dp else 10.dp)
    Row(
        modifier
            // A chip is a switch, not a stop on the way through a form: taking focus here would
            // pull the caret out of whatever field the chip is configuring.
            .focusProperties { canFocus = false }
            .background(if (selected) y.accentFill else y.tileWarm2, shape)
            .border(1.dp, if (selected) y.accentBorder else y.tileBorder, shape)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (stretch) 6.dp else if (small) 9.dp else 14.dp,
                vertical = if (stretch) 12.dp else if (small) 5.dp else 9.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) y.accentText else y.textSecondary,
                modifier = Modifier.size(if (small) 12.dp else 15.dp),
            )
        }
        Text(
            label,
            fontSize = if (small) 11.sp else 13.sp,
            fontWeight = FontWeight.W600,
            color = if (selected) y.accentText else y.textSecondary,
            maxLines = 1,
        )
    }
}
