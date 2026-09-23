import Foundation

/// How far a drawing can be zoomed, how many pages it has, and which one is being looked at —
/// `ui/ink/Viewport.kt`.
///
/// Android's `Viewport` is a camera: it owns the document-to-view transform, because its canvas is
/// hand-rolled and there was nowhere else to put it. iOS has nowhere to put it *either*, for the
/// opposite reason — `PKCanvasView` is a `UIScrollView`, so the pan, the clamp and the pinch about
/// a focal point are already implemented, correctly, by the class the ink is drawn in. Reimplementing
/// them here would mean two answers to where the page is, and PencilKit's would be the one the
/// strokes actually used.
///
/// So what is left is what a scroll view has no opinion about: the limits, the pagination, and the
/// numbers a person is shown. Those are the parts that have to agree with Android, and they are the
/// parts that are worth a test.
public enum InkPages {
    /// Furthest out and furthest in. Out reveals more pages; in is for going back over detail.
    public static let minZoom: Double = 0.4
    public static let maxZoom: Double = 8

    /// How many pages the ink itself reaches onto.
    ///
    /// The `+ 1` du inside the ceiling is Android's, and it is a hair of slack at the fold: ink
    /// that reaches the bottom of a page has reached the next one, so the page it is running onto
    /// is already there rather than appearing a stroke later.
    public static func contentPages(maxDocY: Double) -> Int {
        max(1, Int(((maxDocY + 1) / DocumentUnits.pageHeight).rounded(.up)))
    }

    /// Content pages plus one blank page to grow into, which is what stops a document from ending
    /// exactly where you stopped writing.
    public static func totalPages(maxDocY: Double) -> Int {
        contentPages(maxDocY: maxDocY) + 1
    }

    /// Which page is being read.
    ///
    /// Forty per cent down the view rather than the middle: what you are working on sits above the
    /// centre of a screen more often than below it, and a boundary crossing the exact middle would
    /// flip the number back and forth on the smallest scroll.
    public static func currentPage(topDu: Double, visibleHeightDu: Double, pages: Int) -> Int {
        guard DocumentUnits.pageHeight > 0 else { return 1 }
        let middleish = topDu + visibleHeightDu * 0.4
        return min(max(Int(middleish / DocumentUnits.pageHeight) + 1, 1), max(pages, 1))
    }

    /// Zoom expressed for a human — "120%" — for a readout that has to mean something.
    public static func percent(zoom: Double) -> Int { max(Int(zoom * 100), 1) }

    /// Whether a further pinch would do anything, so a control can dim when it would not.
    public static func canZoomIn(_ zoom: Double) -> Bool { zoom < maxZoom - 1e-4 }
    public static func canZoomOut(_ zoom: Double) -> Bool { zoom > minZoom + 1e-4 }
}
