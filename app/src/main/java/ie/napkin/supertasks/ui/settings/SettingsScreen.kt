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
import ie.napkin.supertasks.ui.components.LocalCompletionTempo
import ie.napkin.supertasks.ui.components.LocalYantraHaptics
import ie.napkin.supertasks.ui.components.TaskState
import ie.napkin.supertasks.ui.components.YantraCheckbox
import ie.napkin.supertasks.ui.theme.LocalThemeController
import ie.napkin.supertasks.ui.theme.ThemeMode
import ie.napkin.supertasks.ui.theme.AccentColor
import ie.napkin.supertasks.ui.theme.LauncherIcon
import ie.napkin.supertasks.ui.appContainer
import kotlinx.coroutines.launch
import ie.napkin.supertasks.ui.theme.Yantra
import androidx.compose.ui.graphics.Color

@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = appContainer()
    val theme = LocalThemeController.current
    val y = Yantra.colors

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
