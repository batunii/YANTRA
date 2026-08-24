package ie.napkin.supertasks.widget

/**
 * Extras MainActivity resolves into a deep link on launch. Used by every widget tap and by
 * reminder notifications (see MainActivity.targetFrom).
 */
object WidgetIntents {
    const val EXTRA_OPEN_NODE = "ie.napkin.supertasks.OPEN_NODE"
    const val EXTRA_OPEN_SMART = "ie.napkin.supertasks.OPEN_SMART"
    const val EXTRA_OPEN_FOCUS = "ie.napkin.supertasks.OPEN_FOCUS"
}
