package ie.napkin.supertasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import ie.napkin.supertasks.ui.components.YantraMark
import ie.napkin.supertasks.ui.theme.Yantra

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
        Box(
            Modifier
                .size(340.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(y.accent.copy(alpha = 0.16f), Color.Transparent),
                        radius = 340f,
                    ),
                    CircleShape,
                ),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .background(y.accentFill, RoundedCornerShape(26.dp))
                    .border(1.dp, y.accentBorder, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                YantraMark(Modifier.size(52.dp), tint = y.accent, checkTint = y.textPrimary)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Yantra", fontFamily = ie.napkin.supertasks.ui.theme.YantraDisplay, fontSize = 46.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.5).sp, color = y.textPrimary)
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
