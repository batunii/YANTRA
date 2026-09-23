import XCTest
@testable import YantraCore

/// The numbers the ink screen shows and the limits it works within, against Android's.
final class InkPagesTests: XCTestCase {

    func testAnEmptyDrawingIsOnePageWithOneToGrowInto() {
        XCTAssertEqual(InkPages.contentPages(maxDocY: 0), 1)
        XCTAssertEqual(InkPages.totalPages(maxDocY: 0), 2)
    }

    /// A du of slack at the fold, so ink running onto the next page has a next page to run onto.
    func testInkThatReachesTheFoldHasReachedTheNextPage() {
        let h = DocumentUnits.pageHeight
        XCTAssertEqual(InkPages.contentPages(maxDocY: h - 2), 1)
        XCTAssertEqual(InkPages.contentPages(maxDocY: h), 2)
    }

    func testPagesGrowWithTheInk() {
        let h = DocumentUnits.pageHeight
        XCTAssertEqual(InkPages.contentPages(maxDocY: h * 2.5), 3)
        XCTAssertEqual(InkPages.totalPages(maxDocY: h * 2.5), 4)
    }

    /// The page being read is the one four tenths down the view, not the one at the very top.
    func testTheCurrentPageIsTheOneBeingWorkedOn() {
        let h = DocumentUnits.pageHeight
        // Looking at the whole of page one.
        XCTAssertEqual(InkPages.currentPage(topDu: 0, visibleHeightDu: h, pages: 4), 1)
        // Scrolled so the fold is just above the middle: page two is the one in hand.
        XCTAssertEqual(InkPages.currentPage(topDu: h * 0.8, visibleHeightDu: h, pages: 4), 2)
        // Never past the end of the document, however far a bounce carries it.
        XCTAssertEqual(InkPages.currentPage(topDu: h * 90, visibleHeightDu: h, pages: 4), 4)
        XCTAssertEqual(InkPages.currentPage(topDu: -h, visibleHeightDu: h, pages: 4), 1)
    }

    func testTheZoomReadoutIsWholePerCentAndNeverZero() {
        XCTAssertEqual(InkPages.percent(zoom: 1), 100)
        XCTAssertEqual(InkPages.percent(zoom: 2.379), 237)
        XCTAssertEqual(InkPages.percent(zoom: 0.4), 40)
        XCTAssertEqual(InkPages.percent(zoom: 0.001), 1, "a readout of 0% would say the page had vanished")
    }

    func testTheLimitsAreAndroidsAndTheyDimAtTheEnds() {
        XCTAssertEqual(InkPages.minZoom, 0.4)
        XCTAssertEqual(InkPages.maxZoom, 8)
        XCTAssertFalse(InkPages.canZoomOut(InkPages.minZoom))
        XCTAssertFalse(InkPages.canZoomIn(InkPages.maxZoom))
        XCTAssertTrue(InkPages.canZoomIn(1))
        XCTAssertTrue(InkPages.canZoomOut(1))
    }
}
