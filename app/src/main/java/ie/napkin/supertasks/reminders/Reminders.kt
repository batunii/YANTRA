package ie.napkin.supertasks.reminders

/**
 * Notification plumbing constants. Reminders are derived from the built-in Due property's
 * offset encoding (see BuiltIns) — the old separate Reminder def ('builtin-reminder') was
 * merged into Due by MIGRATION_3_4, which references it by string literal.
 */
object Reminders {
    const val CHANNEL_ID = "reminders"
    const val ACTION_FIRE = "ie.napkin.supertasks.action.REMINDER_FIRE"
    const val ACTION_MARK_DONE = "ie.napkin.supertasks.action.REMINDER_MARK_DONE"
    const val EXTRA_NODE_ID = "node_id"
    const val EXTRA_AT = "at_millis"
}
