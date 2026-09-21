package ie.shoonya.yantra.widget

/**
 * Extras MainActivity resolves into a deep link on launch. Used by every widget tap and by
 * reminder notifications (see MainActivity.targetFrom).
 */
object WidgetIntents {
    const val EXTRA_OPEN_NODE = "ie.shoonya.yantra.OPEN_NODE"
    const val EXTRA_OPEN_SMART = "ie.shoonya.yantra.OPEN_SMART"
    const val EXTRA_OPEN_FOCUS = "ie.shoonya.yantra.OPEN_FOCUS"

    /**
     * The calendar, on a given day — an ISO date, `2026-09-18`.
     *
     * A date rather than a node id because the thing being opened is a **day**: the calendar
     * widget's heading, its column headings and its empty days all point here, and none of them
     * is about a particular event.
     */
    const val EXTRA_OPEN_CALENDAR = "ie.shoonya.yantra.OPEN_CALENDAR"
}
