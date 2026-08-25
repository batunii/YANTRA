package ie.napkin.supertasks.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.napkin.supertasks.App
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.image.ImageImport
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraText
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.widget.WidgetRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Yantra as somewhere to share *to*.
 *
 * The ordinary way anyone captures a link is the share sheet, mid-thought, from a browser — and
 * until now the app could not receive one at all, which was the largest hole in "capture → triage →
 * do" and among the smallest to close.
 *
 * **The task exists before this screen draws.** Capture is never blocked (L3): there is no form, no
 * destination to choose, nothing to confirm. What appears afterwards is an acknowledgement with one
 * control on it, and dismissing it — or walking away — is never a way to lose the capture, because
 * the capture already happened.
 *
 * Sharing from a browser gives the page title as the subject and the URL as the text, so the task is
 * titled like the page and the link becomes a note on it. A task called
 * "https://example.com/a/b?utm=…" would be technically faithful and useless to read in a list.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        val theme = loadThemeController(this)

        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        @Suppress("DEPRECATION")
        val image: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

        val title = when {
            subject.isNotEmpty() -> subject
            text.isNotEmpty() -> text.lineSequence().first().take(120)
            image != null -> "Picture"
            else -> ""
        }
        if (title.isEmpty() && image == null) {
            finish()
            return
        }

        // Before anything is drawn. Everything below is about telling you where it went.
        val created = MutableStateFlow<String?>(null)
        container.appScope.launch {
            container.seeding.join()
            val id = container.nodes.quickCaptureToInbox(title)
            // The link itself, on the task's page — kept out of the title so a list stays readable.
            if (text.isNotEmpty() && text != title) {
                container.nodes.create(id, NodeType.PARAGRAPH, text)
            }
            if (image != null) {
                ImageImport.downscale(applicationContext, image)?.let { bytes ->
                    container.nodes.addImage(id, bytes)
                }
            }
            created.value = id
            WidgetRefresh.refreshListWidgets(applicationContext)
        }

        setContent {
            SuperTasksTheme(mode = theme.mode, accent = theme.accent) {
                val taskId by created.collectAsStateWithLifecycle()
                val lists by container.nodes.topLevel().collectAsStateWithLifecycle(emptyList())
                var choosing by remember { mutableStateOf(false) }
                var landedIn by remember { mutableStateOf("Inbox") }

                // Gone on its own if untouched. Opening the picker cancels that — someone reading a
                // list of destinations has not finished.
                LaunchedEffect(choosing) {
                    if (!choosing) {
                        delay(SETTLE_MS)
                        finish()
                    }
                }

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Landed(
                        title = title,
                        listName = landedIn,
                        choosing = choosing,
                        lists = lists.filter { it.type == NodeType.LIST },
                        enabled = taskId != null,
                        onChange = { choosing = true },
                        onPick = { list ->
                            val id = taskId
                            if (id != null) {
                                container.appScope.launch {
                                    container.nodes.moveToList(id, list.id)
                                    WidgetRefresh.refreshListWidgets(applicationContext)
                                }
                                landedIn = list.title.orEmpty().ifBlank { "list" }
                            }
                            choosing = false
                        },
                    )
                }
            }
        }
    }

    private companion object {
        /** Long enough to read where it went, short enough not to be in the way. */
        const val SETTLE_MS = 3_200L
    }
}

@Composable
private fun Landed(
    title: String,
    listName: String,
    choosing: Boolean,
    lists: List<NodeEntity>,
    enabled: Boolean,
    onChange: () -> Unit,
    onPick: (NodeEntity) -> Unit,
) {
    val y = Yantra.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(y.band, RoundedCornerShape(18.dp))
            .padding(16.dp)
            .navigationBarsPadding(),
    ) {
        if (choosing) {
            SectionLabel("Move to")
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(lists, key = { it.id }) { list ->
                    Text(
                        list.title.orEmpty().ifBlank { "Untitled list" },
                        color = y.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W600,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(list) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Added to $listName",
                        color = y.textPrimary,
                        fontFamily = YantraText,
                        fontWeight = FontWeight.W700,
                        fontSize = 15.sp,
                    )
                    Text(
                        title,
                        color = y.textMuted,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Change list",
                    color = if (enabled) y.accentText else y.textDim,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.W700,
                    modifier = Modifier
                        .clickable(enabled = enabled, onClick = onChange)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}
