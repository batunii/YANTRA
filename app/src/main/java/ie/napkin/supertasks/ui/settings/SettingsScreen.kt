package ie.napkin.supertasks.ui.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import ie.napkin.supertasks.ui.Routes
import ie.napkin.supertasks.ui.components.Compass
import ie.napkin.supertasks.ui.components.PropertyChip
import ie.napkin.supertasks.ui.components.ChipData
import ie.napkin.supertasks.ui.components.SectionLabel
import ie.napkin.supertasks.ui.components.TaskCheck
import ie.napkin.supertasks.ui.theme.LocalThemeController
import ie.napkin.supertasks.ui.theme.ThemeMode
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.oklch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
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
            Box(
                Modifier.size(38.dp)
                    .background(y.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .clickable { nav.popBackStack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = y.textPrimary, modifier = Modifier.size(20.dp))
            }
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
                ThemeChip("Dark", theme.mode == ThemeMode.DARK, Modifier.weight(1f)) { theme.update(ctx, mode = ThemeMode.DARK) }
                ThemeChip("OLED", theme.mode == ThemeMode.OLED, Modifier.weight(1f)) { theme.update(ctx, mode = ThemeMode.OLED) }
                ThemeChip("Light", theme.mode == ThemeMode.LIGHT, Modifier.weight(1f)) { theme.update(ctx, mode = ThemeMode.LIGHT) }
            }

            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(y.accent, RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    SectionLabel("Accent hue")
                    Text("${theme.hue.roundToInt()}°", color = y.textMuted, fontSize = 12.5.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Hue reference bar — every hue at the fixed accent lightness/chroma.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(
                        Brush.horizontalGradient((0..12).map { oklch(0.70f, 0.15f, it * 30f) }),
                        RoundedCornerShape(5.dp),
                    ),
            )
            Slider(
                value = theme.hue,
                onValueChange = { theme.update(ctx, hue = it) },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = y.accent,
                    activeTrackColor = y.accent,
                    inactiveTrackColor = y.textPrimary.copy(alpha = 0.14f),
                ),
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("Preview")
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(y.cardBg, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskCheck(done = true, onToggle = {})
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Wire up sync", color = y.textPrimary, fontWeight = FontWeight.W600, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PropertyChip(ChipData("d", "Due today", null))
                        PropertyChip(ChipData("p", "High", y.accent))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Compass(fraction = 0.6f, size = 32.dp)
            }
        }
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .background(if (selected) y.accentFill else y.neutralChipBg, shape)
            .border(1.dp, if (selected) y.accentBorder else y.tileBorder, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) y.accentText else y.textSecondary,
            fontWeight = FontWeight.W700,
            fontSize = 13.5.sp,
        )
    }
}
