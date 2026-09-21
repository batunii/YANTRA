package ie.shoonya.yantra.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import java.time.LocalDate

/**
 * The keys a calendar widget's own controls turn — see [CalendarWidgetKeys].
 *
 * Every one of these writes Glance state and then re-renders **that one widget**, never all of
 * them: two calendar widgets on one home screen are two different questions, and paging one back to
 * last month should not drag the other with it.
 */
private suspend fun retarget(
    context: Context,
    glanceId: GlanceId,
    change: (MutablePreferences, CalendarWidgetView, LocalDate, LocalDate) -> Unit,
) {
    updateAppWidgetState(context, glanceId) { prefs ->
        val today = LocalDate.now()
        val view = CalendarWidgetKeys.view(prefs)
        val anchor = CalendarWidgetKeys.anchor(prefs, today)
        change(prefs, view, anchor, today)
    }
    YantraCalendarWidget().update(context, glanceId)
}

/** Writes an anchor **and the day it was written on**, which is what keeps it from going stale. */
private fun MutablePreferences.moveTo(to: LocalDate, today: LocalDate) {
    this[CalendarWidgetKeys.ANCHOR] = to.toString()
    this[CalendarWidgetKeys.ANCHOR_SET] = today.toString()
}

/** The paging keys. Steps by whatever the view is a view *of* — a month, three days, a day. */
class CalendarStepAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DELTA] ?: return
        retarget(context, glanceId) { prefs, view, anchor, today ->
            prefs.moveTo(view.step(anchor, delta), today)
        }
    }

    companion object { val DELTA = ActionParameters.Key<Int>("delta") }
}

/**
 * Back to today.
 *
 * There is no key for this. The date itself is the control: it opens the app when you are on
 * today and comes home when you are not, which is why the header has one fewer thing in it.
 */
class CalendarTodayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        retarget(context, glanceId) { prefs, _, _, today -> prefs.moveTo(today, today) }
    }
}

/**
 * Month → three days → day, cycling.
 *
 * Changing the view re-anchors on **today** rather than keeping where you had paged to: the views
 * are different sizes of window, and a 3-day view anchored on the 1st of a month you had scrolled
 * to is a place nobody asked to be.
 */
class CalendarSetViewAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val next = CalendarWidgetView.of(parameters[VIEW] ?: return)
        retarget(context, glanceId) { prefs, _, _, today ->
            prefs[CalendarWidgetKeys.VIEW] = next.store
            prefs.moveTo(today, today)
        }
    }

    companion object { val VIEW = ActionParameters.Key<String>("view") }
}
