package ie.napkin.supertasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import ie.napkin.supertasks.ui.components.bhupuraPath
import ie.napkin.supertasks.ui.components.drawPartialPath
import ie.napkin.supertasks.ui.theme.Yantra
import ie.napkin.supertasks.ui.theme.YantraDisplay

@Composable
fun SplashScreen(nav: NavHostController) {
    val y = Yantra.colors
    val container = appContainer()
    LaunchedEffect(Unit) {
        // As-long-as-needed splash: wait for first-run seeding, then land on Today with Home
        // beneath it on the back stack. If the user deleted the Today smart list, open Home.
        container.seeding.join()
        val today = container.nodes.todaySmartList()
        nav.navigate(Routes.HOME) {
            popUpTo(Routes.SPLASH) { inclusive = true }
        }
        if (today != null) nav.navigate(Routes.smart(today.id))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(y.page),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // The mark draws itself on, once. A one-shot transition is exactly what the motion law
            // permits — punctuation, not texture — and the pen arriving is the right first sentence
            // for an app whose whole vocabulary is marks made by hand. The tinted rounded tile it
            // used to sit in is gone: the bhupura is already an enclosure, so framing it in a second
            // one said the shape could not hold its own.
            val draw = remember { Animatable(0f) }
            val bindu = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                draw.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
                bindu.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
            }
            Canvas(Modifier.size(96.dp)) {
                val s = size.minDimension
                drawPartialPath(bhupuraPath(s), draw.value, y.checkOutline, s * 1.6f / 28f)
                if (bindu.value > 0f) {
                    drawCircle(y.accent, radius = s * 3.6f / 28f * bindu.value, center = center)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Yantra", fontFamily = YantraDisplay, fontSize = 46.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.5).sp, color = y.textPrimary)
                Text(
                    "Yet Another Notes, Todos & Reminder App",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.W600,
                    color = y.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        }
    }
}
