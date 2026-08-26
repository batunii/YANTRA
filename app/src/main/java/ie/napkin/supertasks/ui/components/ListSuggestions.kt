package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraText
import ie.napkin.supertasks.data.capture.CaptureParse

/**
 * The lists a half-typed `~` could mean, offered while there is still time to pick one.
 *
 * A `~` that names a list the workspace does not have now *makes* one, which is what lets someone
 * file into a new list without leaving capture. The risk that comes with it is the obvious one: a
 * typo is a new list, and `~Grocries` sitting next to `~Groceries` is a mess someone has to go and
 * tidy up. This is the answer to that. The lists you already have are shown as you type toward one,
 * so the common case is a tap rather than a spelling test, and a name that matches nothing says so
 * plainly — `New list "Camping"` — before it becomes real.
 *
 * Matching is [CaptureParse.listSuggestions], the same rule the parser resolves with, so the field
 * cannot offer something the line would not actually produce.
 *
 * Nothing is drawn unless a mark is being typed. A row that is merely empty still takes its space,
 * and the capture bar moving up and down as you type would be worse than no help at all.
 */
@Composable
fun ListSuggestions(
    text: String,
    lists: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    val draft = remember(text) { CaptureParse.listDraft(text) } ?: return
    val (span, typed) = draft
    val matches = remember(typed, lists) { CaptureParse.listSuggestions(typed, lists) }

    // A name that is already exactly one of the lists needs no help; offering the thing that is
    // already typed is noise, and the tint has confirmed it landed.
    if (matches.size == 1 && matches.first().equals(typed, ignoreCase = true)) return
    if (matches.isEmpty() && typed.isBlank()) return

    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .horizontalFadingEdge()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (matches.isEmpty()) {
            // Not a button. There is nothing to choose — carrying on typing is already the way to
            // make this list, and a control that only restates what will happen anyway is a second
            // control for one idea.
            NewListPill(typed)
        } else {
            matches.forEach { name ->
                SelectChip(
                    label = name,
                    selected = false,
                    size = ChipSize.Small,
                    onClick = { onPick(text.substring(0, span.first) + CaptureParse.LIST_MARK + name) },
                )
            }
        }
    }
}

/**
 * What is about to be made, stated rather than offered.
 *
 * Deliberately not a button: carrying on typing already makes this list, so a control here would be
 * a second way to do one thing. It is a label, and its whole job is that the name is read once
 * before it exists.
 */
@Composable
private fun NewListPill(name: String) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .background(y.tileWarm2, shape)
            .border(1.dp, y.tileBorder, shape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = y.textSecondary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            "New list \u201C$name\u201D",
            color = y.textSecondary,
            fontFamily = YantraText,
            fontWeight = FontWeight.W600,
            fontSize = 12.sp,
        )
    }
}
