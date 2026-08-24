package ie.napkin.supertasks.widget

import android.appwidget.AppWidgetManager
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.napkin.supertasks.App
import ie.napkin.supertasks.data.db.NodeEntity
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.loadThemeController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings for one placed widget, opened from the widget's own overflow button.
 *
 * Separate from [WidgetConfigActivity], which is the launcher's placement hook: that one exists to
 * answer "which list?" once and get out of the way, and the Today widget deliberately has no
 * configure activity at all. This screen is the one you can come back to, so it holds the settings
 * that are worth changing after the fact — how far the widget lets the wallpaper through, and
 * whether it reports what you finished.
 *
 * Every control writes straight through to Glance state and re-renders the widget: a settings
 * screen with an Apply button would be a second thing to get wrong, and the widget is visible
 * behind this one anyway.
 */
class WidgetSettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_WIDGET_ID = "ie.napkin.supertasks.widget.WIDGET_ID"
        const val EXTRA_IS_TODAY = "ie.napkin.supertasks.widget.IS_TODAY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val widgetId = intent?.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val isToday = intent?.getBooleanExtra(EXTRA_IS_TODAY, false) ?: false
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val container = (application as App).container
        val theme = loadThemeController(this)

        // Writes go to Glance state (the live render session only reacts to that) on appScope,
        // so a write survives this activity being finished immediately after a tap.
        fun apply(edit: (MutablePreferences) -> Unit) {
            container.appScope.launch {
                runCatching {
                    val app = applicationContext
                    val gid = GlanceAppWidgetManager(app).getGlanceIdBy(widgetId)
                    updateAppWidgetState(app, gid, edit)
                    // The instance must match the provider, or the widget re-renders as the
                    // other variant: TodayWidget resolves the Today list, YantraListWidget
                    // reads the configured binding.
                    (if (isToday) TodayWidget() else YantraListWidget()).update(app, gid)
                }
            }
        }

        setContent {
            SuperTasksTheme(mode = theme.mode) {
                var opacity by remember { mutableFloatStateOf(ListWidgetDefaults.OPACITY.toFloat()) }
                var showDone by remember { mutableStateOf(ListWidgetDefaults.SHOW_DONE) }
                var loaded by remember { mutableStateOf(false) }

                // Seed the controls from what the widget is actually showing.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    runCatching {
                        val gid = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(widgetId)
                        val prefs = getAppWidgetState(
                            applicationContext, PreferencesGlanceStateDefinition, gid,
                        )
                        opacity = (prefs[ListWidgetKeys.OPACITY] ?: ListWidgetDefaults.OPACITY).toFloat()
                        showDone = prefs[ListWidgetKeys.SHOW_DONE] ?: ListWidgetDefaults.SHOW_DONE
                    }
                    loaded = true
                }

                SettingsScreen(
                    isToday = isToday,
                    enabled = loaded,
                    opacity = opacity,
                    showDone = showDone,
                    onOpacity = { opacity = it },
                    onOpacitySettled = { apply { prefs -> prefs[ListWidgetKeys.OPACITY] = it.toInt() } },
                    onShowDone = {
                        showDone = it
                        apply { prefs -> prefs[ListWidgetKeys.SHOW_DONE] = it }
                    },
                    listsFlow = remember {
                        container.nodes.topLevel()
                            .stateIn(container.appScope, SharingStarted.Eagerly, emptyList())
                    },
                    onPickList = { node ->
                        val smart = node.type == NodeType.SMART_LIST
                        WidgetPrefs.setBinding(this, widgetId, node.id, smart)
                        apply { prefs ->
                            prefs[ListWidgetKeys.NODE_ID] = node.id
                            prefs[ListWidgetKeys.IS_SMART] = smart
                        }
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    isToday: Boolean,
    enabled: Boolean,
    opacity: Float,
    showDone: Boolean,
    onOpacity: (Float) -> Unit,
    onOpacitySettled: (Float) -> Unit,
    onShowDone: (Boolean) -> Unit,
    listsFlow: kotlinx.coroutines.flow.StateFlow<List<NodeEntity>>,
    onPickList: (NodeEntity) -> Unit,
    onClose: () -> Unit,
) {
    val y = Yantra.colors
    val nodes by listsFlow.collectAsStateWithLifecycle()
    val pickable = remember(nodes) {
        nodes.filter { it.type == NodeType.LIST || it.type == NodeType.SMART_LIST }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavCircle(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Close",
                onClick = onClose,
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(14.dp))
            Text("Widget settings", style = MaterialTheme.typography.headlineSmall, color = y.textPrimary)
        }

        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "opacity") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(y.cardBg, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Opacity",
                                style = MaterialTheme.typography.titleMedium,
                                color = y.textPrimary,
                            )
                            // The number is the surface's opacity, so the sentence has to run the
                            // same direction as it: "wallpaper shows through" read backwards
                            // against a rising percentage.
                            Text(
                                "Higher covers more of the wallpaper",
                                fontSize = 12.sp,
                                color = y.textMuted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            "${opacity.toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.W700,
                            color = y.accent,
                        )
                    }
                    Slider(
                        value = opacity,
                        onValueChange = onOpacity,
                        onValueChangeFinished = { onOpacitySettled(opacity) },
                        valueRange = ListWidgetDefaults.MIN_OPACITY.toFloat()..100f,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = y.accent,
                            activeTrackColor = y.accent,
                            inactiveTrackColor = y.tileBorder,
                        ),
                    )
                }
            }

            if (isToday) {
                item(key = "done") {
                    ToggleCard(
                        title = "Show what's done",
                        subtitle = "Today's completed tasks, below the open ones",
                        checked = showDone,
                        enabled = enabled,
                        onChange = onShowDone,
                    )
                }
            }

            if (!isToday && pickable.isNotEmpty()) {
                item(key = "list-label") {
                    SectionLabel("Shows", modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                items(pickable, key = { it.id }) { node ->
                    WidgetListRow(
                        node = node,
                        smartList = node.type == NodeType.SMART_LIST,
                        onPick = onPickList,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(y.cardBg, RoundedCornerShape(18.dp))
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = y.textPrimary)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = y.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = y.onAccent,
                checkedTrackColor = y.accent,
            ),
        )
    }
}
