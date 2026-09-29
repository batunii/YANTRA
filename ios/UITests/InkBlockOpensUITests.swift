import XCTest

/// **A drawn sketch opens from its page.** The block drew at true scale and did not open when
/// tapped: it took the tap with a SwiftUI gesture beside the page's UIKit swipe, and lost.
final class InkBlockOpensUITests: YantraUITestCase {

    func testTappingADrawnSketchOnItsPageOpensTheDrawing() {
        _ = launch(route: "open:\(Fixture.plainTaskId)")
        let block = el("ink.open.fixture-ink-drawn")
        XCTAssertTrue(block.waitForExistence(timeout: 8), "the drawn sketch is not on its page")
        // Where a finger lands, not `.tap()` on the element: an identifier on a SwiftUI Button can
        // resolve to a wrapper that swallows the tap, which is a test artefact rather than the bug.
        block.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(el("ink.canvas").waitForExistence(timeout: 6), "tapping the sketch did not open it")
    }
}
