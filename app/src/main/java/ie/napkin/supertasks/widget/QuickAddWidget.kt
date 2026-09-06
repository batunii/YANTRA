package ie.napkin.supertasks.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ie.napkin.supertasks.R

/** One accent pill that opens straight into capture ([QuickAddActivity]). Static content. */
class QuickAddWidget : GlanceAppWidget() {

    // The one widget with a genuine reason to change shape rather than just scale: dragged down to
    // a single cell there is no room for a word beside the mark, and a truncated "＋ New t…" is
    // worse than the mark alone, which already says the whole thing.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(60.dp, 40.dp), DpSize(110.dp, 40.dp))
    )

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
    // A mark rather than a typed plus. The glyph the app draws for "add" is the one the home
    // screen should show too — the widget was the last place still spelling it with a character
    // borrowed from the font.
    val iconOnly = LocalSize.current.width < 100.dp
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.primary)
            .cornerRadius(R.dimen.widget_radius)
            .clickable(actionStartActivity<QuickAddActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = "New task",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                modifier = GlanceModifier.size(if (iconOnly) 22.dp else 17.dp),
            )
            if (!iconOnly) {
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    "New task",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
