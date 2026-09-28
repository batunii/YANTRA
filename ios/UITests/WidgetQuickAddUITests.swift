import XCTest

/// **A list widget's "+" adds to that list** — `yantra://quickadd/<list>`, as Android's list widget
/// opens QuickAdd on its own node. The id used to be ignored, so the task went to the Inbox.
final class WidgetQuickAddUITests: YantraUITestCase {

    func testTheWidgetsPlusFilesTheTaskInItsOwnList() {
        let app = launch(route: "home")
        app.open(URL(string: "yantra://quickadd/\(Fixture.listId)")!)
        let field = app.textFields["New task"]
        XCTAssertTrue(field.waitForExistence(timeout: 8), "the quick add sheet did not open")
        field.tap()
        field.typeText("Oat milk from the widget")
        app.buttons["Create task"].tap()
        // It lands where it was added, and the page it lands on is that list.
        XCTAssertTrue(app.staticTexts["Oat milk from the widget"].waitForExistence(timeout: 8),
                      "the new task is not on the list the widget belongs to")
        XCTAssertTrue(app.staticTexts[Fixture.list].exists, "quick add from the list's widget did not open that list")
    }
}
