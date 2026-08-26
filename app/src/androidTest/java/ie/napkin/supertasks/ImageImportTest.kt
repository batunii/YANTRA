package ie.napkin.supertasks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.image.ImageImport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Bringing a photo into the workspace.
 *
 * Two of these guard things that are invisible when wrong. A photo's GPS coordinates would be
 * committed to a repository that may be shared, permanently, and nothing on screen would say so. And
 * orientation lives in the same metadata that carries the location — strip one without acting on the
 * other and every portrait photo arrives on its side.
 */
@RunWith(AndroidJUnit4::class)
class ImageImportTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val made = mutableListOf<File>()

    @After
    fun tearDown() {
        made.forEach { it.delete() }
    }

    /** A JPEG on disk, optionally claiming an orientation, returned as a file:// uri. */
    private fun jpeg(width: Int, height: Int, orientation: Int? = null, gps: Boolean = false): Uri {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            // A recognisable corner, so a rotation is detectable rather than merely plausible.
            for (x in 0 until minOf(24, width)) for (y in 0 until minOf(24, height)) setPixel(x, y, Color.RED)
        }
        val f = File(ctx.cacheDir, "img-${System.nanoTime()}.jpg").also { made += it }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()

        if (orientation != null || gps) {
            ExifInterface(f.absolutePath).apply {
                orientation?.let { setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
                if (gps) {
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE, "53/1,20/1,0/1")
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                    setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "6/1,15/1,0/1")
                    setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                }
                saveAttributes()
            }
        }
        return Uri.fromFile(f)
    }

    private fun decode(bytes: ByteArray): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }

    @Test
    fun aLargePhotoComesBackWithinTheLongEdge() {
        val out = ImageImport.downscale(ctx, jpeg(4032, 3024))!!
        val bounds = decode(out)
        assertEquals(ImageImport.MAX_EDGE, maxOf(bounds.outWidth, bounds.outHeight))
        // Proportions survive: a squashed photo would be worse than a large one.
        assertEquals(4032.0 / 3024.0, bounds.outWidth.toDouble() / bounds.outHeight, 0.01)
    }

    @Test
    fun aSmallPictureIsLeftAtItsOwnSize() {
        val out = ImageImport.downscale(ctx, jpeg(800, 600))!!
        val bounds = decode(out)
        assertEquals(800, bounds.outWidth)
        assertEquals(600, bounds.outHeight)
    }

    @Test
    fun theLocationIsGone() {
        // The one that matters. A shared workspace would otherwise publish where the photo was taken,
        // to everyone with access, in a history that does not forget.
        val out = ImageImport.downscale(ctx, jpeg(1200, 900, gps = true))!!
        val f = File(ctx.cacheDir, "checked-${System.nanoTime()}.jpg").also { made += it }
        f.writeBytes(out)

        val exif = ExifInterface(f.absolutePath)
        assertNull("latitude survived", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull("longitude survived", exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull("a lat/long pair survived", exif.latLong?.let { "present" })
    }

    @Test
    fun aRotatedPhotoIsUprightedBeforeItsMetadataIsDropped() {
        // Portrait photo stored landscape with a flag saying "turn me". Dropping the flag without
        // applying it leaves the picture on its side with nothing left to say so.
        val out = ImageImport.downscale(ctx, jpeg(1200, 900, orientation = ExifInterface.ORIENTATION_ROTATE_90))!!
        val bounds = decode(out)
        assertTrue(
            "not rotated: ${bounds.outWidth}x${bounds.outHeight}",
            bounds.outHeight > bounds.outWidth,
        )
    }

    @Test
    fun anUnrotatedPhotoIsLeftAlone() {
        val out = ImageImport.downscale(ctx, jpeg(1200, 900))!!
        val bounds = decode(out)
        assertTrue(bounds.outWidth > bounds.outHeight)
    }

    @Test
    fun theCopyIsMuchSmallerThanThePhoto() {
        val uri = jpeg(4032, 3024)
        val original = File(uri.path!!).length()
        val out = ImageImport.downscale(ctx, uri)!!
        assertTrue("copy $out.size vs original $original", out.size < original / 2)
    }

    @Test
    fun somethingThatIsNotAnImageIsRefusedRatherThanCrashing() {
        val f = File(ctx.cacheDir, "not-an-image-${System.nanoTime()}.jpg").also { made += it }
        f.writeText("this is a text file with a misleading name")
        assertNull(ImageImport.downscale(ctx, Uri.fromFile(f)))
    }

    @Test
    fun aMissingFileIsRefusedRatherThanCrashing() {
        assertNull(ImageImport.downscale(ctx, Uri.fromFile(File(ctx.cacheDir, "nope.jpg"))))
    }

    @Test
    fun theSampleSizeNeverUndershoots() {
        // Decoding below the target would mean upscaling afterwards, which is worse than doing
        // nothing: a blurry picture that also costs a resize.
        listOf(4032 to 3024, 8000 to 6000, 2049 to 2049, 100 to 100).forEach { (w, h) ->
            val s = ImageImport.sampleSize(w, h, 2048)
            assertTrue("$w x $h sampled by $s", w / s >= 2048 || h / s >= 2048 || maxOf(w, h) <= 2048)
        }
    }
}
