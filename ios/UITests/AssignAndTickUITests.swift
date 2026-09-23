import XCTest

/// Giving a task to somebody, and what happens when one is finished.
final class AssignAndTickUITests: YantraUITestCase {

    /// **Anyone who can push can assign.** There is no owner-only rule anywhere in this app: an
    /// assignee is a word on a line in a file, so the test is simply that the control is there and
    /// that it writes.
    func testATaskCanBeGivenToALoginYouType() {
        launch(route: "open:\(Fixture.plainTaskId)")

        let give = el("task.assign")
        XCTAssertTrue(give.waitForExistence(timeout: 8), "no way to give the task to anybody")
        give.tap()

        let field = app.textFields["octocat"]
        XCTAssertTrue(field.waitForExistence(timeout: 6), "the assignee sheet did not open")
        field.tap()
        field.typeText("batunii")
        app.buttons["Give"].tap()

        // The pill says who has it, and the pill is the way back to change it.
        XCTAssertTrue(el("task.assignee").waitForExistence(timeout: 8), "the task was not given to anybody")
        // Matched on any element: a pill is a Button with its words folded into its label, so the
        // text inside it is not a static text of its own.
        XCTAssertTrue(el("@batunii").waitForExistence(timeout: 4), "the assignee is not shown")
    }

    /// Ticking a task strikes it through **where it is**. It used to vanish from under your finger
    /// into the Done section, taking the feedback with it and jumping everything below up a line.
    func testAFinishedTaskStaysInPlaceUntilTheListIsOpenedAgain() {
        launch(route: "open:\(Fixture.listId)")

        let row = el("task.row.\(Fixture.plainTask)")
        XCTAssertTrue(row.waitForExistence(timeout: 8), "the task is not on the list")
        let wasAt = row.frame.origin.y

        let check = el("task.check.\(Fixture.plainTask)")
        XCTAssertTrue(check.waitForExistence(timeout: 4), "no checkbox")
        check.tap()

        // Still there, still where it was, and now reading as done.
        XCTAssertTrue(row.waitForExistence(timeout: 4), "the task vanished the moment it was ticked")
        XCTAssertEqual(row.frame.origin.y, wasAt, accuracy: 1, "the row moved instead of staying put")
        XCTAssertEqual(el("task.check.\(Fixture.plainTask)").label, "Mark not done", "it did not read as finished")

        // Leaving and coming back is when the list tidies itself. Navigated in the app rather than
        // relaunched: every launch carries `-uitest-reset`, which re-seeds the fixture and would
        // undo the tick this test just made.
        tap("nav.back", "no way back to Home")
        // The row's **title**, not the row. An identifier on a SwiftUI Button resolves to a
        // wrapper that reports as a button and swallows the tap — the trap this suite has hit
        // before, and it fails by leaving you on Home rather than by erroring.
        let listRow = app.staticTexts[Fixture.list]
        XCTAssertTrue(listRow.waitForExistence(timeout: 8), "Home did not come back")
        listRow.tap()
        // By its exact label, the way the rest of the suite reads this header: the fixture starts
        // with one finished task and this test has just made a second.
        XCTAssertTrue(app.staticTexts["DONE \u{b7} 2"].waitForExistence(timeout: 8),
                      "the reopened list does not say two are done")
        XCTAssertFalse(el("task.row.\(Fixture.plainTask)").waitForExistence(timeout: 2),
                       "the finished task is still in the open list after reopening it")
    }
}
