package ie.shoonya.yantra.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.shoonya.yantra.App
import kotlinx.coroutines.launch
import ie.shoonya.yantra.data.db.NodeEntity
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.ui.theme.SuperTasksTheme
import ie.shoonya.yantra.ui.theme.Yantra
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
                    listsFlow = remember { container.nodes.allLists().stateIn(container.appScope, SharingStarted.Eagerly, emptyList()) },
                    search = { container.nodes.searchBindable(it) },
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
    listsFlow: kotlinx.coroutines.flow.StateFlow<List<NodeEntity>>,
    search: suspend (String) -> List<NodeEntity>,
    onPick: (NodeEntity) -> Unit,
) {
    val nodes by nodesFlow.collectAsStateWithLifecycle()
    val allLists by listsFlow.collectAsStateWithLifecycle()
    val y = Yantra.colors
    val lists = nodes.filter { it.type == NodeType.LIST }
    val smart = nodes.filter { it.type == NodeType.SMART_LIST }
    var query by remember { mutableStateOf("") }
    val results = rememberBindableResults(query, search)
    // Every list at any nesting, so a search result can say which one it came out of — the browse
    // lists above are top-level only, and a subtask's parent very often is not.
    val listTitles = remember(allLists) {
        allLists.associate { it.id to (it.title?.ifBlank { "Untitled" } ?: "Untitled") }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
            Text("YANTRA", fontSize = 10.5.sp, fontWeight = FontWeight.W700, color = y.accentEyebrow)
            // Not "Choose a list" any more, because it no longer has to be one.
            Text("What should it show?", fontSize = 24.sp, fontWeight = FontWeight.W800, color = y.textPrimary)
        }
        WidgetSearchField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            widgetTargetItems(
                results = results,
                smartLists = smart,
                lists = lists,
                listTitles = listTitles,
                onPick = onPick,
            )
        }
    }
}
