package ie.shoonya.yantra.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ie.shoonya.yantra.data.capture.CaptureParse
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.theme.YantraType

/**
 * What a half-typed `#` in a row could mean, offered while there is still time to pick one.
 *
 * On the capture bar a `#label` is read off the line when it is submitted, and the parser is the
 * whole story. A row on a page has no submit — it saves as you type — so until now `#hi` typed
 * into a task's title was simply the title, and once upon a time it was worse than that: the file
 * format read it back as a label per keystroke (see the note on `PageCodec.parseTask`). This is
 * the row's equivalent of the capture bar's grammar: the label becomes real when you *finish* it,
 * by tapping a chip here or by typing the space after it, and the word leaves the title as the
 * label arrives on the task. Nothing is consumed silently, and nothing is consumed early.
 *
 * Existing labels first, matched by [CaptureParse.labelSuggestions] so what is offered and what a
 * space would make cannot disagree, then one chip to make the word a new label — the same bargain
 * `~` strikes for lists, and for the same reason a typo should have to be tapped into existence
 * rather than arrive on its own. Drawn the same way as [LinkSuggestions] and only while a `#` is
 * under the caret, so nothing moves up and down the screen as you write.
 */
@Composable
fun LabelSuggestions(
    /** What has been typed after `#`, or null when no label is being written. */
    draft: String?,
    /** Every label the workspace has, by name. */
    labels: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (draft == null) return
    val y = Yantra.colors
    val offered = remember(draft, labels) { CaptureParse.labelSuggestions(draft, labels) }
    val exact = offered.any { it.equals(draft, ignoreCase = true) }

    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .horizontalFadingEdge()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (offered.isEmpty() && draft.isBlank()) {
            Text(
                "Type a label, then space to add it",
                color = y.textDim,
                fontSize = YantraType.caption,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        offered.forEach { name ->
            SelectChip(
                label = name,
                selected = false,
                size = ChipSize.Small,
                mark = YantraMark.Label,
                onClick = { onPick(name) },
            )
        }
        if (draft.isNotBlank() && !exact) {
            SelectChip(
                label = "New label “$draft”",
                selected = false,
                size = ChipSize.Small,
                mark = YantraMark.Label,
                onClick = { onPick(draft) },
            )
        }
    }
}
