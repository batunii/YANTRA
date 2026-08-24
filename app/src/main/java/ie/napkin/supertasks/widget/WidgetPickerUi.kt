package ie.napkin.supertasks.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import ie.napkin.supertasks.ui.components.accentFor
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * One selectable list in a widget picker. Shared by [WidgetConfigActivity] (choose a list while
 * placing) and [WidgetSettingsActivity] (change it afterwards) so the two routes to the same
 * decision cannot drift apart visually.
 */
@Composable
fun WidgetListRow(node: NodeEntity, smartList: Boolean, onPick: (NodeEntity) -> Unit) {
    val y = Yantra.colors
    val accent = accentFor(node.id)
    Row(
        Modifier
            .fillMaxWidth()
            .background(y.cardBg, RoundedCornerShape(16.dp))
            .clickable { onPick(node) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(accent.copy(alpha = 0.15f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (smartList) Icons.Default.AutoAwesome else Icons.AutoMirrored.Filled.List,
                contentDescription = null, tint = accent, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            node.title?.ifBlank { "Untitled" } ?: "Untitled",
            fontSize = 15.5.sp, fontWeight = FontWeight.W700, color = y.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
