package ie.napkin.supertasks.data.image

import android.content.Context
import android.net.Uri

/**
 * Where a device remembers the full-size picture it happens to have.
 *
 * The workspace carries a downscaled copy, and that is what every device draws. The phone that
 * *picked* the image can do better — it still has the original, or at least a grant to read it — so
 * it draws that instead and everyone else falls back to the copy. Nothing is broken anywhere; one
 * device is simply sharper.
 *
 * **This never goes in a page file.** A `content://` grant is a permission held by one app on one
 * device; written into a file it would sync to a second device as a string that resolves to nothing,
 * which is precisely the failure the downscaled copy exists to end. So it lives here, in local
 * preferences, keyed by the block id — device-local state about a workspace, like a scroll position.
 *
 * Losing it is a non-event: the grant can be revoked, the file deleted, the app reinstalled, and all
 * that happens is the picture falls back to the copy in the repo.
 */
class LocalOriginals(context: Context) {

    private val prefs = context.getSharedPreferences("yantra_image_originals", Context.MODE_PRIVATE)

    fun remember(blockId: String, uri: Uri) {
        prefs.edit().putString(blockId, uri.toString()).apply()
    }

    /** The original, if this device has one and can still read it. */
    fun originalFor(blockId: String): Uri? =
        prefs.getString(blockId, null)?.let { Uri.parse(it) }

    fun forget(blockId: String) {
        prefs.edit().remove(blockId).apply()
    }
}
