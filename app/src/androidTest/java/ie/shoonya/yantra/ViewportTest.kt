package ie.shoonya.yantra

import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import ie.shoonya.yantra.data.ink.PAGE_HEIGHT_DU
import ie.shoonya.yantra.data.ink.PAGE_WIDTH_DU
import ie.shoonya.yantra.ui.ink.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The camera, on a device, because it composes real `android.graphics.Matrix` instances.
 *
 * This is where zoom correctness actually lives. The pinch gesture itself is a thin wrapper — the
 * midpoint and mean spread of the pointers, handed to [Viewport.zoomBy] — so what is worth pinning
 * down is that zooming about a focal point keeps the document under that point, that the clamps hold,
 * and that the two matrices really are inverses. A canvas that drew ink somewhere other than where
 * the finger was would be this class being wrong.
 */
@RunWith(AndroidJUnit4::class)
class ViewportTest {

    /** A phone-shaped viewport at rest: one page across, top of the document. */
    private fun phone(pages: Int = 4) = Viewport().apply {
        resize(1080f, 2340f)
        setPages(pages)
    }

    @Test
    fun atRestOnePageSpansTheWidth() {
        val v = phone()
        assertEquals(1080f / PAGE_WIDTH_DU, v.scale, 1e-4f)
        assertEquals(0f, v.toDocX(0f), 0.01f)
        assertEquals(PAGE_WIDTH_DU, v.toDocX(1080f), 0.01f)
        assertTrue(v.isFitWidth)
        assertEquals(100, v.percent())
    }

    @Test
    fun theSameGestureOnATabletReachesTheSamePlace() {
        // The portability claim, at the camera rather than in the file: a touch three-quarters
        // across is 750 du on any screen, so ink drawn there lands on the same part of the page.
        val tablet = Viewport().apply { resize(2000f, 1200f); setPages(4) }
        assertEquals(phone().toDocX(0.75f * 1080f), tablet.toDocX(0.75f * 2000f), 0.01f)
    }

    @Test
    fun zoomingKeepsTheDocumentUnderTheFingers() {
        val v = phone()
        val focusX = 700f
        val focusY = 900f
        val anchorX = v.toDocX(focusX)
        val anchorY = v.toDocY(focusY)
        v.zoomBy(2f, focusX, focusY)
        assertEquals(2f, v.zoom, 1e-4f)
        // The whole point of pinching about a focal point: what you were looking at does not move
        // out from under the gesture.
        assertEquals(anchorX, v.toDocX(focusX), 0.05f)
        assertEquals(anchorY, v.toDocY(focusY), 0.05f)
    }

    @Test
    fun zoomingOutAndBackInReturnsToWhereItStarted() {
        val v = phone()
        v.panBy(0f, -600f)
        val before = v.panYDu
        v.zoomBy(3f, 540f, 1170f)
        v.zoomBy(1f / 3f, 540f, 1170f)
        assertEquals(1f, v.zoom, 1e-3f)
        assertEquals(before, v.panYDu, 0.5f)
    }

    @Test
    fun zoomIsClampedBothWays() {
        val v = phone()
        v.zoomBy(1000f, 540f, 1170f)
        assertEquals(Viewport.MAX_ZOOM, v.zoom, 1e-4f)
        assertFalse(v.canZoomIn)
        v.zoomBy(0.00001f, 540f, 1170f)
        assertEquals(Viewport.MIN_ZOOM, v.zoom, 1e-4f)
        assertFalse(v.canZoomOut)
    }

    @Test
    fun thePageCannotBePannedOffScreen() {
        val v = phone()
        v.zoomBy(4f, 540f, 1170f)
        // Shove it hard in every direction; it must stay showing page.
        v.panBy(-99999f, -99999f)
        assertTrue("panX ${v.panXDu}", v.panXDu <= PAGE_WIDTH_DU)
        assertTrue("panY ${v.panYDu}", v.panYDu <= v.pages * PAGE_HEIGHT_DU)
        v.panBy(99999f, 99999f)
        assertTrue("panX ${v.panXDu}", v.panXDu >= 0f)
        assertEquals(0f, v.panYDu, 0.01f)
    }

    @Test
    fun zoomedOutBelowAPageTheSheetIsCentred() {
        val v = phone()
        v.zoomBy(0.5f, 540f, 1170f)
        // Half a page across means the view is wider than the page. It should sit in the middle
        // rather than pinned to one edge with all the empty space on the other.
        val visibleW = v.visibleRight() - v.visibleLeft()
        assertTrue(visibleW > PAGE_WIDTH_DU)
        assertEquals(
            "left gap should equal right gap",
            -v.panXDu,
            v.visibleRight() - PAGE_WIDTH_DU,
            0.5f,
        )
    }

    @Test
    fun theTwoMatricesAreInverses() {
        val v = phone()
        v.zoomBy(2.7f, 300f, 800f)
        v.panBy(-120f, -340f)
        val round = Matrix()
        round.set(v.docToView)
        round.postConcat(v.viewToDoc)
        val pts = floatArrayOf(0f, 0f, 137f, 942f, 1000f, 1414f)
        val out = pts.copyOf()
        round.mapPoints(out)
        for (i in pts.indices) assertEquals(pts[i], out[i], 0.05f)
    }

    @Test
    fun aViewPointMapsThroughTheMatrixTheSameWayAsThroughTheAccessors() {
        // The renderer uses the matrix and the input paths use toDocX/toDocY. If those two ever
        // disagreed, ink would appear somewhere other than where it was drawn — which is the whole
        // class of bug this replaced.
        val v = phone()
        v.zoomBy(3.3f, 812f, 455f)
        v.panBy(-40f, -900f)
        val viewPt = floatArrayOf(640f, 1500f)
        val doc = floatArrayOf(v.toDocX(viewPt[0]), v.toDocY(viewPt[1]))
        val mapped = doc.copyOf()
        v.docToView.mapPoints(mapped)
        assertEquals(viewPt[0], mapped[0], 0.05f)
        assertEquals(viewPt[1], mapped[1], 0.05f)
    }

    @Test
    fun aViewDistanceBecomesASmallerDocumentDistanceAsYouZoomIn() {
        // What keeps the eraser the size of the thing in your hand.
        val v = phone()
        val atRest = v.toDocSpan(100f)
        v.zoomBy(4f, 540f, 1170f)
        assertEquals(atRest / 4f, v.toDocSpan(100f), 0.01f)
    }

    @Test
    fun fitWidthReturnsToOnePageAcrossWithoutLosingThePlace() {
        val v = phone()
        v.panBy(0f, -1200f)
        val page = v.currentPage()
        v.zoomBy(5f, 200f, 300f)
        v.fitWidth()
        assertEquals(1f, v.zoom, 1e-4f)
        assertTrue(v.isFitWidth)
        assertEquals("should still be reading roughly the same page", page, v.currentPage())
    }

    @Test
    fun thePageIndicatorCountsWithinTheDocument() {
        val v = phone(pages = 3)
        assertEquals(1, v.currentPage())
        v.panToDocY(PAGE_HEIGHT_DU * 2)
        assertEquals(3, v.currentPage())
        v.panToDocY(PAGE_HEIGHT_DU * 99)
        assertTrue("never past the end", v.currentPage() <= 3)
    }

    @Test
    fun anUnsizedViewportDoesNotDivideByZero() {
        // It is asked for coordinates before the first layout pass.
        val v = Viewport()
        assertEquals(1f, v.scale, 1e-4f)
        assertTrue(v.toDocX(10f).isFinite())
        assertTrue(v.toDocY(10f).isFinite())
        assertEquals(1, v.currentPage())
    }
}
