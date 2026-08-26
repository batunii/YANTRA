package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraColors

/**
 * One task, one card.
 *
 * The rows used to be a single surface divided by hairlines, and the card that held them carried
 * the bhupura's gate. Both are gone: a task is a thing you pick up, complete, start, drag — it is
 * its own object, and a run of rows welded into one slab argued the opposite. Separating them by a
 * few dp costs a little density and says the true thing.
 *
 * The mark stays on the header band, where there is one of it. Repeating it down every row would
 * have made a signature into wallpaper.
 *
 * A *task's* page is deliberately not this. That one is a document, so its blocks sit bare on the
 * page like paragraphs, which is what they are.
 */
@Composable
fun ListGroupRow(
    modifier: Modifier = Modifier,
    /** Lit in the accent while you are on this task — see [YantraColors.startedWash]. */
    started: Boolean = false,
    content: @Composable () -> Unit,
) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(y.cardBg, shape)
            // A second layer rather than a blended colour: the wash has to sit *over* the card so
            // it reads the same on the card as it does on a bare block, where there is no card
            // underneath it to blend with.
            .then(if (started) Modifier.background(y.startedWash, shape) else Modifier),
    ) {
        content()
    }
}

/**
 * Capture for a list: type and send. Shared by lists and smart lists so adding a task is the same
 * gesture in both — a smart list stamps its own rules onto whatever you add, which is the only
 * difference and it is invisible from here.
 */
@Composable
fun QuickAddBar(
    modifier: Modifier = Modifier,
    placeholder: String = "Add a task…",
    /** The workspace's labels, so a typed `#tag` is tinted the colour it is about to become. */
    labels: List<ie.napkin.supertasks.data.db.LabelEntity> = emptyList(),
    /** The workspace's lists, so `~` can name one — and offer the ones that match while you type. */
    lists: List<String> = emptyList(),
    onAdd: (String) -> Unit,
) {
    val y = Yantra.colors
    var text by remember { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    // The strip belongs above the bar: growing the bar itself would move the send button
    // under the thumb that was reaching for it.
    Column(modifier.fillMaxWidth().background(y.page)) {
        ListSuggestions(text = text, lists = lists, onPick = { text = it })
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(y.cardBg, RoundedCornerShape(18.dp))
                    .border(1.dp, y.tileBorder, RoundedCornerShape(18.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = y.textPrimary),
                    cursorBrush = SolidColor(y.accent),
                    // Typing "buy milk tomorrow #home" tints what it understood as you write it, so a
                    // word that is about to leave the title says so first.
                    visualTransformation = rememberCaptureHighlight(labels, lists),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(placeholder, color = y.textDim, fontSize = 14.sp)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .background(y.accentFill, RoundedCornerShape(12.dp))
                        .border(1.dp, y.accentBorder, RoundedCornerShape(12.dp))
                        .clickable(onClick = send),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Add task",
                        tint = y.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
