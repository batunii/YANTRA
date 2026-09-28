import XCTest

/// **A sitting can be edited and saved** — Android's `EventSheet` in its sitting form.
///
/// A sitting has no title of its own; it borrows its task's. The sheet used to demand one before
/// Save would enable, so a sitting could be opened and never saved. Now it says TIME FOR and the
/// task, offers no title field, and saves.
final class SittingSheetUITests: YantraUITestCase {

    func testASittingOpensAsTimeForItsTaskAndSaves() {
        _ = launch(route: "calendar")
        let block = app.descendants(matching: .any)[Fixture.sittingTask].firstMatch
        XCTAssertTrue(block.waitForExistence(timeout: 8), "the sitting is not on today's calendar")
        block.tap()
        XCTAssertTrue(app.staticTexts["TIME FOR"].waitForExistence(timeout: 5),
                      "a sitting opened as a plain event, with a title field of its own")
        XCTAssertFalse(app.textFields["New event"].exists, "a sitting offers a title field")
        let save = app.buttons["Save"]
        XCTAssertTrue(save.isEnabled, "a sitting cannot be saved, because it has no title")
        save.tap()
        XCTAssertTrue(app.staticTexts["TIME FOR"].waitForNonExistence(timeout: 5), "Save did not close the sheet")
    }
}
