package ie.napkin.supertasks.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The bhupura, as the running session's face in the notification shade.
 *
 * The status-bar icon is already this mark, but Android renders a small icon as a flat silhouette in
 * a colour it chooses — it can say *Yantra* and nothing else. The large icon is the one place in a
 * notification that keeps its own colour and its own weight, and a focus session is the app's most
 * present surface, so it should look like the app rather than like a grey tray entry with the app's
 * name attached.
 *
 * **The mark carries state, not progress.** The bindu is filled while the session runs and hollow
 * while it is paused — the same distinction the task glyph draws, and the same one the chronometer
 * beside it is making by existing or not. A progress arc was the obvious other idea and is
 * deliberately absent: the notification is re-posted on *transitions* only, so an arc would be
 * frozen at whatever fraction the last pause left it at and would spend most of a session lying.
 * A number that ticks itself and a mark that only claims what it can keep is the honest division.
 *
 * Drawn rather than stored as a drawable because the ink is the user's chosen accent, resolved at
 * post time, and because the two states differ by one filled circle.
 */
object SessionMark {

    /** The design space the mark is drawn in — the same 28 units as `bhupuraPath` and the tray icon. */
    private const val UNITS = 28f

    /**
     * The mark, [px] square.
     *
     * Inset to 66% of the bitmap, which is not a taste decision: the platform crops a large icon to
     * a circle, and the bhupura's gates and rounded corners sit at the far edge of its own box —
     * about 14.1 units from centre against a half-width of 14. Drawn to fill the square, the crop
     * would take the corners off the one shape in the app that is all corners.
     */
    fun bhupura(px: Int, accent: Int, filledBindu: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val size = px * 0.66f
        val unit = size / UNITS
        val offset = (px - size) / 2f

        // A soft disc of the same ink behind the mark. Without it the outline floats on whatever
        // the shade's background happens to be, and on a light shade a thin accent line at large
        // icon size nearly disappears.
        canvas.drawCircle(
            px / 2f, px / 2f, px / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                alpha = 38
            },
        )

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
            // 1.9 units is the tray icon's weight; the mark should not thin out as it grows.
            strokeWidth = 1.9f * unit
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.save()
        canvas.translate(offset, offset)
        canvas.drawPath(bhupuraPath(unit), stroke)

        // The bindu. Filled is "counting"; hollow is "stopped, and still yours" — the enclosure is
        // untouched either way, because pausing a session does not put the task down.
        val bindu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = if (filledBindu) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 1.6f * unit
            isAntiAlias = true
        }
        val r = if (filledBindu) 3.6f * unit else 3.0f * unit
        canvas.drawCircle(14f * unit, 14f * unit, r, bindu)
        canvas.restore()
        return bmp
    }

    /**
     * The enclosure, in `android.graphics` terms.
     *
     * A transcription of `ui.components.bhupuraPath`, which is a Compose path and cannot be handed
     * to a `Canvas` here. The geometry is copied deliberately rather than generalised: these are two
     * different graphics stacks, and the shared thing is the 28-unit drawing, not a type.
     */
    private fun bhupuraPath(u: Float): Path = Path().apply {
        moveTo(8 * u, 4 * u)
        lineTo(11 * u, 4 * u); lineTo(11 * u, 2 * u)
        lineTo(17 * u, 2 * u); lineTo(17 * u, 4 * u)
        lineTo(20 * u, 4 * u)
        quadTo(24 * u, 4 * u, 24 * u, 8 * u)
        lineTo(24 * u, 11 * u); lineTo(26 * u, 11 * u)
        lineTo(26 * u, 17 * u); lineTo(24 * u, 17 * u)
        lineTo(24 * u, 20 * u)
        quadTo(24 * u, 24 * u, 20 * u, 24 * u)
        lineTo(17 * u, 24 * u); lineTo(17 * u, 26 * u)
        lineTo(11 * u, 26 * u); lineTo(11 * u, 24 * u)
        lineTo(8 * u, 24 * u)
        quadTo(4 * u, 24 * u, 4 * u, 20 * u)
        lineTo(4 * u, 17 * u); lineTo(2 * u, 17 * u)
        lineTo(2 * u, 11 * u); lineTo(4 * u, 11 * u)
        lineTo(4 * u, 8 * u)
        quadTo(4 * u, 4 * u, 8 * u, 4 * u)
        close()
    }
}
