package ie.napkin.supertasks.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * One selectable target in a widget picker. Shared by [WidgetConfigActivity] (choose one while
 * placing) and [WidgetSettingsActivity] (change it afterwards) so the two routes to the same
 * decision cannot drift apart visually.
 *
 * [subtitle] is where a search result says which list it came out of. In the browse lists that is
 * noise — the section header above has already said it — but a result row arrives with no context
 * at all, and two subtasks called "Draft" are otherwise indistinguishable.
 */
@Composable
fun WidgetListRow(
    node: NodeEntity,
    smartList: Boolean,
    onPick: (NodeEntity) -> Unit,
    subtitle: String? = null,
    /**
     * The one the widget is already on.
     *
     * A picker with no selected state is a list of identical buttons that answer a question it
     * never admits to having an answer for. Worse on the settings route than the placement one:
     * there the screen closes and the widget appears, which is its own confirmation, while here
     * the write is silent and the widget is behind the launcher rather than behind this screen —
     * so a tap looked like nothing happening at all.
     */
    selected: Boolean = false,
) {
    val y = Yantra.colors
    // A list tile is structure, not effort, so it is drawn in the frame's neutral — it used to get
    // a hue of its own from a hash of the node id, which was a fifth colour layer with nothing to
    // say. The chosen row is the one exception, and the only thing on the screen wearing effort's
    // colour: that is what makes it findable at a glance rather than something to be read for.
    val accent = if (selected) y.accent else y.checkOutline
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) y.accent.copy(alpha = 0.14f) else y.cardBg,
                RoundedCornerShape(16.dp),
            )
            .clickable { onPick(node) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(accent.copy(alpha = 0.15f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // A task bound to the home screen is a project, and it should not be wearing a
                // list's icon while it sits next to actual lists.
                when {
                    smartList -> Icons.Default.AutoAwesome
                    node.type == NodeType.TASK -> Icons.Default.CheckCircleOutline
                    else -> Icons.AutoMirrored.Filled.List
                },
                contentDescription = null, tint = accent, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.title?.ifBlank { "Untitled" } ?: "Untitled",
                fontSize = 15.5.sp, fontWeight = FontWeight.W700,
                color = if (selected) y.accent else y.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 12.sp, color = y.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = "Showing on this widget",
                tint = y.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
