package ie.napkin.supertasks.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * Turning a picked photo into something a git repository can carry.
 *
 * A phone photo is several megabytes, git keeps every version of a binary forever, and the repo is
 * cloned to every device — so what goes in is a downscaled copy, and the original stays where it
 * was. See `ARCHITECTURE.md` §5.
 *
 * **Re-encoding is also how the location is removed**, which is the part that matters most and shows
 * least. A photo carries GPS coordinates in its EXIF, and committing one to a shared workspace
 * publishes where you were, to everyone with access, permanently — git does not forget. Decoding to
 * a bitmap and compressing it again produces a file with no metadata at all.
 *
 * Which creates the trap this class exists to avoid: **orientation is EXIF too.** A phone almost
 * never rotates the pixels it captures; it writes them as the sensor saw them and records a flag
 * saying which way is up. Drop the metadata without acting on it first and every photo taken in
 * portrait arrives on its side. So the flag is read, applied to the pixels, and then discarded along
 * with everything else.
 */
object ImageImport {

    /** Long edge, in pixels. Comfortably beyond what any phone screen can show. */
    const val MAX_EDGE = 2048

    /** High enough that the difference is invisible on a photograph, low enough to matter in a repo. */
    const val QUALITY = 85

    /**
     * Reads [uri], corrects its orientation, scales it down, and returns JPEG bytes with no metadata.
     * Null if the image cannot be read or decoded — a picked file that is not really an image.
     */
    fun downscale(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE): ByteArray? {
        val resolver = context.contentResolver

        // Two passes. The first reads only the header, so a 50-megapixel photo does not have to fit
        // in memory to find out how big it is.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            .getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { degreesFor(ExifInterface(it)) }
        }.getOrNull() ?: 0

        val opts = BitmapFactory.Options().apply {
            // Powers of two only, and it must not undershoot: decode to the smallest size that is
            // still at least what we want, then scale the rest of the way exactly.
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null

        val scaled = scaleTo(decoded, maxEdge)
        val upright = rotate(scaled, rotation)
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            upright.recycle()
            out.toByteArray()
        }
    }

    private fun degreesFor(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    internal fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxEdge && height / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    private fun scaleTo(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val factor = maxEdge.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val turned = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) },
            true,
        )
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}
