package ie.napkin.supertasks.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/** One accent pill that opens straight into capture ([QuickAddActivity]). Static content. */
class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val custom = yantraGlanceColors(context)
            if (custom != null) GlanceTheme(colors = custom) { QuickAddContent() }
            else GlanceTheme { QuickAddContent() }
        }
    }
}

@Composable
private fun QuickAddContent() {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.primary)
            .cornerRadius(24.dp)
            .clickable(actionStartActivity<QuickAddActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "＋ New task",
            style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
