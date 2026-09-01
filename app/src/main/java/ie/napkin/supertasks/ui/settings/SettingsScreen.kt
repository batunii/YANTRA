package ie.napkin.supertasks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import ie.napkin.supertasks.ui.components.NavCircle
import ie.napkin.supertasks.ui.components.SelectChip
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.ConfirmDialog
import ie.napkin.supertasks.ui.components.ChipSize
import ie.napkin.supertasks.ui.components.LocalCompletionTempo
import ie.napkin.supertasks.ui.components.LocalYantraHaptics
import ie.napkin.supertasks.ui.components.TaskState
import ie.napkin.supertasks.ui.components.YantraCheckbox
import ie.napkin.supertasks.ui.theme.LocalThemeController
import ie.napkin.supertasks.ui.theme.ThemeMode
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import ie.napkin.supertasks.ui.theme.AccentColor
import ie.napkin.supertasks.ui.theme.LauncherIcon
import ie.napkin.supertasks.ui.appContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.lifecycle.compose.LifecycleResumeEffect
import ie.napkin.supertasks.data.sync.Credentials
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.theme.Yantra
import androidx.compose.ui.graphics.Color

@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = appContainer()
    val theme = LocalThemeController.current
    val lastSync by (container.syncState() ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    val syncStatus = lastSync?.let { r ->
        when {
            r.error != null -> "Not synced: ${r.error}"
            r.conflicts.isNotEmpty() -> "Synced · ${r.conflicts.size} conflict(s) resolved"
            r.committed || r.pushed -> "Synced"
            else -> "Nothing to sync"
        }
    }
    val y = Yantra.colors

    // Read on every return to this screen, not once: adding a workspace happens on another screen
    // and comes back here, and a list that still showed the old set would read as the add having
    // silently failed.
    var account by remember { mutableStateOf<String?>(null) }
    var spaces by remember { mutableStateOf<List<WorkspaceRow>>(emptyList()) }
    /** The workspace being let go of, while the confirm is up. */
    var forgetting by remember { mutableStateOf<WorkspaceRow?>(null) }
    val settingsScope = rememberCoroutineScope()
    var archiveDays by remember { mutableStateOf(0) }
    var archived by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        val job = container.appScope.launch {
            container.seeding.join()
            val rows = withContext(Dispatchers.IO) {
                container.workspaces.all.map { store ->
                    WorkspaceRow(
                        id = store.id,
                        name = store.readManifest()?.name ?: "Workspace",
                        slug = container.slugOf(store.id),
                    )
                }
            }
            account = container.credentials.login(Credentials.ACCOUNT)
            spaces = rows
            archiveDays = container.archiveAfterDays("")
            archived = container.archivedCount()
        }
        onPauseOrDispose { job.cancel() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(y.page)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavCircle(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                onClick = { nav.popBackStack() },
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = y.textPrimary)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 40.dp),
        ) {
            SectionLabel("Theme")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SelectChip("System", theme.mode == ThemeMode.SYSTEM, onClick = { theme.update(ctx, mode = ThemeMode.SYSTEM) }, modifier = Modifier.weight(1f), stretch = true)
                SelectChip("Dark", theme.mode == ThemeMode.DARK, onClick = { theme.update(ctx, mode = ThemeMode.DARK) }, modifier = Modifier.weight(1f), stretch = true)
                SelectChip("OLED", theme.mode == ThemeMode.OLED, onClick = { theme.update(ctx, mode = ThemeMode.OLED) }, modifier = Modifier.weight(1f), stretch = true)
                SelectChip("Light", theme.mode == ThemeMode.LIGHT, onClick = { theme.update(ctx, mode = ThemeMode.LIGHT) }, modifier = Modifier.weight(1f), stretch = true)
            }

            Spacer(Modifier.height(28.dp))
            // The account is not a workspace and is listed apart from them on purpose: it is what
            // lets the app *make* one, and it is also the name that ends up on the commits and
            // behind an assignment.
            SectionLabel("GitHub")
            Spacer(Modifier.height(10.dp))
            SettingRow(
                title = account ?: "Not signed in",
                subtitle = account?.let { "Signed in — tap to manage" }
                    ?: "Sync across devices, and share a list with other people",
                onClick = { nav.navigate(Routes.GITHUB) },
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Workspaces")
            Spacer(Modifier.height(2.dp))
            Text(
                "Each one is a repository. Today spans all of them.",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(10.dp))
            spaces.forEach { space ->
                SettingRow(
                    title = space.name,
                    subtitle = space.slug ?: "On this device only",
                    // Nothing to open yet — the switcher is Phase 5. Showing where each workspace
                    // points is the part that is useful now, and a row that navigated nowhere would
                    // be worse than one that does not pretend to.
                    onClick = null,
                    // Personal has no forget: it is the one workspace that must exist, and
                    // forgetWorkspace refuses it anyway. Everything else can be let go of — which
                    // it could not before, so a workspace joined by mistake was permanent.
                    trailing = if (space.id.isEmpty()) null else ({
                        SelectChip("Forget", selected = false, size = ChipSize.Small) { forgetting = space }
                    }),
                )
                Spacer(Modifier.height(8.dp))
            }
            forgetting?.let { space ->
                ConfirmDialog(
                    title = "Forget ${space.name}?",
                    body = "Its tasks stay in ${space.slug ?: "the repository"} — this only removes " +
                        "the copy on this device, and it stops syncing here. You can join it again.",
                    confirmLabel = "Forget",
                    onDismiss = { forgetting = null },
                    onConfirm = {
                        forgetting = null
                        settingsScope.launch {
                            container.forgetWorkspace(space.id)
                            spaces = spaces.filterNot { it.id == space.id }
                        }
                    },
                )
            }
            SettingRow(
                title = "Add a workspace",
                subtitle = "Join a repository, or start a shared one",
                icon = true,
                onClick = { nav.navigate(Routes.ADD_WORKSPACE) },
            )

            Spacer(Modifier.height(28.dp))
            // Everything commits on its own — this is for when you want to know it has, which
            // matters more than it should on Android, where the system is free to decide your
            // background work can wait until tomorrow.
            SectionLabel("Sync")
            Spacer(Modifier.height(2.dp))
            Text(
                syncStatus ?: "Every change is saved to a file and committed on its own",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            SelectChip(
                "Sync now",
                selected = false,
                modifier = Modifier.fillMaxWidth(),
                stretch = true,
                onClick = { container.syncNow("asked to sync") },
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Archive")
            Spacer(Modifier.height(2.dp))
            Text(
                "Finished tasks leave your lists after a while. They stay in the repository and can " +
                    "be brought back — this is about keeping lists short, not deleting anything.",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // Never first, and the default, because archiving moves someone's work: opting in
                // should be a decision rather than something that already happened.
                listOf("Never" to 0, "30 days" to 30, "90 days" to 90, "A year" to 365).forEach { (label, days) ->
                    SelectChip(
                        label,
                        selected = archiveDays == days,
                        modifier = Modifier.weight(1f),
                        stretch = true,
                        onClick = {
                            archiveDays = days
                            said = null
                            container.appScope.launch { container.setArchiveAfterDays("", days) }
                        },
                    )
                }
            }

            if (archiveDays > 0) {
                Spacer(Modifier.height(12.dp))
                SelectChip(
                    if (busy) "Working…" else "Archive finished tasks now",
                    selected = false,
                    modifier = Modifier.fillMaxWidth(),
                    stretch = true,
                    onClick = {
                        if (!busy) {
                            busy = true
                            container.appScope.launch {
                                val moved = container.archiveNow()
                                archived = container.archivedCount()
                                said = if (moved == 0) "Nothing was old enough yet"
                                else "$moved moved out of your lists"
                                busy = false
                            }
                        }
                    },
                )
            }

            if (archived > 0) {
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    title = "$archived archived",
                    subtitle = "See what left, and put any of it back",
                    onClick = { nav.navigate(Routes.ARCHIVE) },
                )
            }
            said?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = y.textSecondary, fontSize = 12.5.sp)
            }

            Spacer(Modifier.height(28.dp))
            // One choice, not a wheel. The set is closed so the effort ink can never land on the
            // priority hues, which is the constraint the colour law actually rests on.
            SectionLabel("Accent")
            Spacer(Modifier.height(2.dp))
            Text(
                "The ink that means your effort",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AccentColor.entries.forEach { option ->
                    AccentSwatch(
                        color = option.ink(y.isDark),
                        selected = theme.accent == option,
                        onClick = {
                            theme.update(ctx, accent = option)
                            // Off the main thread: enabling a component makes the package manager
                            // rebuild and the launcher re-read, which is far too slow to do under
                            // a tap. App.onCreate reconciles anyway, so a dropped call self-heals.
                            container.appScope.launch { LauncherIcon.apply(ctx, option) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            // Priority and structure are still not preferences: those hues carry meaning the user
            // does not get to reassign, so this says what they mean rather than offering to change
            // them. The accent above is the one ink whose hue is a choice.
            SectionLabel("Ink")
            Spacer(Modifier.height(2.dp))
            Text(
                "Each colour means one thing, so a glance is enough",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            InkLegendRow(y.checkOutline, "Structure", "frames, tracks, text")
            InkLegendRow(y.accent, "Your effort", "focus sessions, what you finished")
            InkLegendRow(y.overdue, "High priority", "the world asking")
            InkLegendRow(y.warning, "Medium priority", "the world, quieter")

            Spacer(Modifier.height(28.dp))
            // The task glyph, all three states side by side and live. Tap them: this is the real
            // component, not a picture of it, so the choreography and the haptics are the ones the
            // lists use. A task is a gated square you can enter, a circle you are inside, or a
            // bindu — the mark left behind.
            SectionLabel("The task glyph")
            Spacer(Modifier.height(2.dp))
            Text(
                "Tap to complete · swipe a task right to mark what you are on",
                color = y.textMuted,
                fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(y.cardBg, RoundedCornerShape(16.dp))
                    .padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphSample("Open", TaskState.OPEN)
                GlyphSample("On it", TaskState.IN_PROGRESS)
                GlyphSample("Done", TaskState.DONE)
            }
        }
    }
}


/** An accent option. Bigger than a label swatch, because this one repaints the whole app. */
@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val y = Yantra.colors
    Box(
        // clickable before the insets: the target is the whole swatch, not the drawn circle.
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.border(2.dp, y.textPrimary, CircleShape) else Modifier)
            .padding(if (selected) 6.dp else 3.dp)
            .background(color, CircleShape),
    )
}

/**
 * One line of the ink legend: the colour, what layer it owns, and where you will meet it. A swatch
 * plus a noun — the point is that the palette is explainable, not adjustable.
 */
@Composable
private fun InkLegendRow(color: Color, name: String, where: String) {
    val y = Yantra.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Text(name, color = y.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.W600)
        Spacer(Modifier.width(8.dp))
        Text(where, color = y.textMuted, fontSize = 12.sp)
    }
}

/**
 * One live task glyph with its name under it. Keeps its own state so the preview is a thing you can
 * actually operate — a still image of a component whose whole point is how it moves would be the
 * wrong way to document it.
 */
@Composable
private fun GlyphSample(label: String, initial: TaskState) {
    val y = Yantra.colors
    var state by remember { mutableStateOf(initial) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        YantraCheckbox(
            state = state,
            taskId = "preview-$label",
            onComplete = { state = TaskState.DONE },
            onUndo = { state = TaskState.OPEN },
            tempo = LocalCompletionTempo.current,
            haptics = LocalYantraHaptics.current,
            darkTheme = y.isDark,
            size = 34.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(label, color = y.textMuted, fontSize = 11.5.sp, fontWeight = FontWeight.W600)
    }
}

/** One workspace, as the settings list needs it: what it is called and where it points. */
private data class WorkspaceRow(val id: String, val name: String, val slug: String?)

/**
 * A settings line with somewhere to go.
 *
 * [onClick] is nullable rather than defaulted to a no-op so a row that leads nowhere *looks* like it
 * leads nowhere — no chevron, no ripple. A tappable row that does nothing when tapped is the kind of
 * small lie that makes a whole screen feel broken.
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    icon: Boolean = false,
    /** An action that belongs to this row rather than to opening it. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val y = Yantra.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(y.cardBg, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(Icons.Default.Add, null, tint = y.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = y.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.W600)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = y.textMuted, fontSize = 11.5.sp)
        }
        trailing?.invoke()
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = y.textDim, modifier = Modifier.size(18.dp),
            )
        }
    }
}
