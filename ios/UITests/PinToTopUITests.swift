import XCTest

/// **Pinning moves a list to the top of Home; it does not copy it** — 17d0beb's Pinned section.
final class PinToTopUITests: YantraUITestCase {

    func testAPinnedListLeavesItsSectionAndComesBackWhenUnpinned() {
        _ = launch(route: "home")
        let row = app.staticTexts[Fixture.secondList]
        XCTAssertTrue(row.waitForExistence(timeout: 8), "no \(Fixture.secondList) on Home")
        XCTAssertFalse(app.staticTexts["PINNED"].exists, "the fixture already has something pinned")

        row.press(forDuration: 1.0)
        let pin = app.buttons["Pin to top"]
        XCTAssertTrue(pin.waitForExistence(timeout: 5), "the list's menu offers no Pin to top")
        pin.tap()
        XCTAssertTrue(app.staticTexts["PINNED"].waitForExistence(timeout: 5), "pinning drew no Pinned section")
        XCTAssertEqual(app.staticTexts.matching(identifier: Fixture.secondList).count, 1,
                       "the pinned list is shown twice: at the top and in its section")

        // And it is a choice that can be taken back, which it was not when Pinned was a type.
        app.staticTexts[Fixture.secondList].press(forDuration: 1.0)
        let unpin = app.buttons["Unpin from top"]
        XCTAssertTrue(unpin.waitForExistence(timeout: 5), "a pinned list offers no Unpin from top")
        unpin.tap()
        XCTAssertTrue(app.staticTexts["PINNED"].waitForNonExistence(timeout: 5), "unpinning left the Pinned section up")
        XCTAssertTrue(app.staticTexts[Fixture.secondList].exists, "unpinning took the list off Home")
    }
}
