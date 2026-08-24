package ie.napkin.supertasks.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.napkin.supertasks.App
import kotlinx.coroutines.launch
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // If the user backs out, the widget host must not add the widget.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val container = (application as App).container

        setContent {
            SuperTasksTheme {
                ConfigScreen(
                    nodesFlow = remember { container.nodes.topLevel().stateIn(container.appScope, SharingStarted.Eagerly, emptyList()) },
                    onPick = { node ->
                        val isSmart = node.type == NodeType.SMART_LIST
                        WidgetPrefs.setBinding(this, appWidgetId, node.id, isSmart)
                        // Glance state, not just prefs: the widget's render session is already
                        // live behind the config screen, and only a state change re-composes it.
                        // appScope: the write outlives this activity, which finishes right away.
                        container.appScope.launch {
                            runCatching {
                                val app = applicationContext
                                val gid = GlanceAppWidgetManager(app).getGlanceIdBy(appWidgetId)
                                updateAppWidgetState(app, gid) { prefs ->
                                    prefs[ListWidgetKeys.NODE_ID] = node.id
                                    prefs[ListWidgetKeys.IS_SMART] = isSmart
                                }
                                YantraListWidget().update(app, gid)
                            }
                        }
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfigScreen(
    nodesFlow: kotlinx.coroutines.flow.StateFlow<List<NodeEntity>>,
    onPick: (NodeEntity) -> Unit,
) {
    val nodes by nodesFlow.collectAsStateWithLifecycle()
    val y = Yantra.colors
    val lists = nodes.filter { it.type == NodeType.LIST }
    val smart = nodes.filter { it.type == NodeType.SMART_LIST }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
            Text("YANTRA", fontSize = 10.5.sp, fontWeight = FontWeight.W700, color = y.accentEyebrow)
            Text("Choose a list", fontSize = 24.sp, fontWeight = FontWeight.W800, color = y.textPrimary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (smart.isNotEmpty()) {
                item { SectionLabel("Smart lists", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(smart, key = { it.id }) { WidgetListRow(it, smartList = true, onPick = onPick) }
            }
            item { SectionLabel("Lists", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(lists, key = { it.id }) { WidgetListRow(it, smartList = false, onPick = onPick) }
        }
    }
}
