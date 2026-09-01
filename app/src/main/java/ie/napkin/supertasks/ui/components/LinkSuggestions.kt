package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * What a half-typed `[[` could mean, offered while there is still time to pick one.
 *
 * A link needs an id and nobody types a UUID, so this strip is not a convenience — it is the only
 * way a link gets made. Tasks only — see `NodeDao.searchLinkTargets` for why a list is addressable
 * and still not worth offering. That is the same bargain [ListSuggestions] strikes for `~`, and it is drawn
 * the same way for the same reason: a horizontal run of chips above the keyboard, present only
 * while a name is being typed, so nothing moves up and down the screen as you write.
 *
 * Nothing is drawn when there is no draft. A row that is merely empty still takes its space, and
 * the bar shifting every time a bracket is typed would be worse than no help at all.
 */
@Composable
fun LinkSuggestions(
    /** What has been typed after `[[`, or null when no link is being written. */
    draft: String?,
    results: List<NodeEntity>,
    onPick: (NodeEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (draft == null) return
    val y = Yantra.colors

    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .horizontalFadingEdge()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (results.isEmpty()) {
            // Said plainly rather than offering to make something. A `~` that names no list creates
            // one, because a list is a container and making one is cheap; a link that names nothing
            // has nowhere to point, and inventing a task to satisfy a half-typed reference would be
            // the app writing something nobody asked for.
            Text(
                if (draft.isBlank()) "Type to find a task or list to link"
                else "Nothing called “$draft” to link to",
                color = y.textDim,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            results.forEach { node ->
                SelectChip(
                    label = node.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                    selected = false,
                    size = ChipSize.Small,
                    icon = Icons.Default.CheckCircleOutline,
                    onClick = { onPick(node) },
                )
            }
        }
    }
}
