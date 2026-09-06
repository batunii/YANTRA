package ie.shoonya.yantra.widget

/**
 * Extras MainActivity resolves into a deep link on launch. Used by every widget tap and by
 * reminder notifications (see MainActivity.targetFrom).
 */
object WidgetIntents {
    const val EXTRA_OPEN_NODE = "ie.shoonya.yantra.OPEN_NODE"
    const val EXTRA_OPEN_SMART = "ie.shoonya.yantra.OPEN_SMART"
    const val EXTRA_OPEN_FOCUS = "ie.shoonya.yantra.OPEN_FOCUS"
}
