package ie.napkin.supertasks.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.App
import ie.napkin.supertasks.data.db.BuiltIns
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.PropertyDefEntity
import ie.napkin.supertasks.data.repo.SelectOption
import ie.napkin.supertasks.ui.components.selectConfig
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.loadThemeController
import kotlinx.coroutines.launch

/**
 * Instant capture from the quick-add widget: translucent dialog-style activity, keyboard up
 * immediately, one save into the Inbox, gone. Deliberately not MainActivity — no splash, no
 * nav stack, nothing left in recents (manifest: noHistory + excludeFromRecents + translucent).
 *
 * Capture, not a form. The old sheet led with a title ("Add to Inbox") and framed a bordered
 * field with an Add button — three pieces of chrome around one line of typing. Now the line of
 * typing *is* the sheet, the destination is stated as a chip instead of a heading, and the
 * chips let a task arrive already dated and flagged so it doesn't need a second visit in the app.
 */
class QuickAddActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_NODE = "ie.napkin.supertasks.widget.TARGET_NODE"
        const val EXTRA_TARGET_IS_SMART = "ie.napkin.supertasks.widget.TARGET_IS_SMART"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        val theme = loadThemeController(this)
        val targetNode = intent.getStringExtra(EXTRA_TARGET_NODE)
        val targetIsSmart = intent.getBooleanExtra(EXTRA_TARGET_IS_SMART, false)

        fun save(title: String, dueToday: Boolean, priority: String?, priorityDef: PropertyDefEntity?) {
            val trimmed = title.trim()
            if (trimmed.isNotEmpty()) {
                // appScope: outlives this activity, which finishes immediately.
                container.appScope.launch {
                    val newId: String? = when {
                        targetNode == null ->
                            container.nodes.captureTask(null, trimmed, container.labels, container.properties)
                        // Smart list: addTask applies the list's apply-on-create values
                        // (e.g. Today stamps Due = today); stale target falls back to Inbox.
                        targetIsSmart -> container.smartLists.defById(targetNode)
                            ?.let { container.smartLists.addTask(it, trimmed) }
                            ?: container.nodes.quickCaptureToInbox(trimmed)
                        else -> container.nodes.create(targetNode, NodeType.TASK, trimmed)
                    }
                    if (newId != null) {
                        // Applied after creation so a smart list's own apply-on-create values
                        // land first and an explicit chip choice wins over them.
                        if (dueToday) {
                            container.properties.setDue(newId, System.currentTimeMillis(), hasTime = false, reminderOffsetMin = null)
                        }
                        if (priority != null && priorityDef != null) {
                            container.properties.setValue(newId, priorityDef.id, text = priority)
                        }
                    }
                    WidgetRefresh.refreshListWidgets(applicationContext)
                }
            }
            finish()
        }

        setContent {
            SuperTasksTheme(mode = theme.mode) {
                val y = Yantra.colors
                var text by remember { mutableStateOf("") }
                var dueToday by remember { mutableStateOf(false) }
                var priority by remember { mutableStateOf<String?>(null) }
                var priorityDef by remember { mutableStateOf<PropertyDefEntity?>(null) }
                var priorityOptions by remember { mutableStateOf<List<SelectOption>>(emptyList()) }
                // Where the task will actually land — resolved, not assumed. The widget's own
                // title is not the destination: tapping + on a *Today* widget files the task in
                // that smart list's home list (Inbox), so labelling the chip "Today" would name
                // the button you pressed instead of the place the task goes.
                var destination by remember { mutableStateOf("Inbox") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                LaunchedEffect(Unit) {
                    val resolved = when {
                        targetNode == null -> null
                        targetIsSmart -> container.smartLists.defById(targetNode)
                            ?.homeParentId?.let { container.nodes.byId(it)?.title }
                        else -> container.nodes.byId(targetNode)?.title
                    }
                    destination = resolved?.takeIf { it.isNotBlank() } ?: "Inbox"
                }
                LaunchedEffect(Unit) {
                    val def = container.properties.builtInDefsOnce()
                        .firstOrNull { it.name.equals(BuiltIns.PRIORITY_NAME, ignoreCase = true) }
                    priorityDef = def
                    priorityOptions = def?.let { selectConfig(it).options }.orEmpty()
                }
                val send = { save(text, dueToday, priority, priorityDef) }
                val pickedColor = priority
                    ?.let { name -> priorityOptions.firstOrNull { it.name == name }?.color?.let { Color(it) } }

                Box(
                    Modifier.fillMaxSize().imePadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .background(y.cardBg, RoundedCornerShape(22.dp))
                            .border(1.dp, y.tileBorder, RoundedCornerShape(22.dp))
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = y.textPrimary,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.W500,
                            ),
                            cursorBrush = SolidColor(y.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { send() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) {
                                        Text(
                                            "What needs doing?",
                                            fontSize = 16.5.sp,
                                            fontWeight = FontWeight.W500,
                                            color = y.textMuted,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CaptureChip(
                                icon = Icons.Default.DateRange,
                                label = "Today",
                                on = dueToday,
                                onClick = { dueToday = !dueToday },
                            )
                            if (priorityOptions.isNotEmpty()) {
                                CaptureChip(
                                    icon = Icons.Default.Flag,
                                    label = priority ?: "Priority",
                                    on = priority != null,
                                    onColor = pickedColor,
                                    // Cycles the options and back off — a menu for three values
                                    // costs more taps than it saves at capture speed.
                                    onClick = {
                                        val i = priorityOptions.indexOfFirst { it.name == priority }
                                        priority = priorityOptions.getOrNull(i + 1)?.name
                                    },
                                )
                            }
                            // The destination, stated rather than titled. Not a picker — the
                            // widget you tapped already chose the list — so it is styled as
                            // information, not as a control that happens to be disabled.
                            CaptureChip(
                                icon = Icons.Default.Inbox,
                                label = destination,
                                on = false,
                                readOnly = true,
                            )
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .background(
                                        if (text.isBlank()) y.neutralChipBg else y.accentFill,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable(onClick = send),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Add task",
                                    tint = if (text.isBlank()) y.textDim else y.accent,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Capture-sheet chip. Reuses the in-app pill language so the widget doesn't look like an app of its own. */
@Composable
private fun CaptureChip(
    icon: ImageVector,
    label: String,
    on: Boolean,
    onClick: (() -> Unit)? = null,
    onColor: Color? = null,
    readOnly: Boolean = false,
) {
    val y = Yantra.colors
    // A read-only chip is a statement, so it reads at the same strength as an untoggled control
    // rather than being dimmed — dimming would say "disabled", which invites a tap that does
    // nothing. Only its icon steps back, to mark it as not-a-button.
    val tint = if (on) onColor ?: y.accent else y.textSecondary
    Row(
        Modifier
            .background(
                if (on) (onColor ?: y.accent).copy(alpha = 0.14f) else y.neutralChipBg,
                RoundedCornerShape(99.dp),
            )
            .let { if (onClick != null && !readOnly) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (readOnly) y.textDim else tint,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.W600, color = tint)
    }
}
