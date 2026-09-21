package ie.shoonya.yantra

/**
 * An indexed event as the calendar receives it — the row plus the node facts joined beside it.
 *
 * [nodeExtUid] is deliberately separate from the embedded row's dead `ext_uid`: the link lives on
 * the node now, and a test that set the wrong one would pass while the app failed.
 */
internal fun indexed(
    event: ie.shoonya.yantra.data.db.EventEntity,
    title: String? = null,
    /** The title of the task a sitting is for, as the `for_node_id` join supplies it. */
    forTitle: String? = null,
    extUid: String? = null,
    extStart: String? = null,
) = ie.shoonya.yantra.data.db.EventWithTitle(
    event = event,
    ownTitle = title,
    forTitle = forTitle,
    nodeExtUid = extUid,
    nodeExtStart = extStart,
)
