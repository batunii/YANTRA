package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/**
 * A list of tasks is one surface with hairline-divided rows, whether the tasks were put there by
 * hand (a list) or gathered by rules (a smart list). Two elevation layers instead of rows floating
 * on the page — and the same shape either way, because to the person reading it they are the same
 * thing: a list.
 *
 * A *task's* page is deliberately not this. That one is a document, so its blocks sit bare on the
 * page like paragraphs, which is what they are.
 */
@Composable
fun ListGroupRow(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val y = Yantra.colors
    // Corners are rounded only where the group actually ends, so a run of rows reads as one card —
    // and the row that ends it closes with the bhupura's gate, the same way a header band does. A
    // card is a surface you touch, so it is where the mark belongs; it used to be a watermark lying
    // behind everything, which is a picture of the app laid under the app.
    val shape = GatedSurface(
        topCorner = if (first) 20.dp else 0.dp,
        bottomCorner = if (last) 20.dp else 0.dp,
        // Narrower and shallower than a band's: a card is the width of the screen minus its
        // margins, and at a band's proportions the tab reads as a bite taken out of the list.
        gateWidth = if (last) 62.dp else 0.dp,
        gateDepth = if (last) 8.dp else 0.dp,
    )
    Column(modifier.fillMaxWidth().background(y.cardBg, shape)) {
        if (!first) GroupDivider()
        content()
        // Room for the tab, so the last row's text never sits inside it.
        if (last) Spacer(Modifier.height(8.dp))
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
    Row(
        modifier
            .fillMaxWidth()
            .background(y.page)
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
