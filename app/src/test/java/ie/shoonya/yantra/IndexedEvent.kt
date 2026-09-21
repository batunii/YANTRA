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
    extUid: String? = null,
    extStart: String? = null,
) = ie.shoonya.yantra.data.db.EventWithTitle(
    event = event,
    title = title,
    nodeExtUid = extUid,
    nodeExtStart = extStart,
)
