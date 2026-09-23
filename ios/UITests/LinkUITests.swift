import XCTest

/// Making a link, and following one.
///
/// A link's id is never typed: `[[` opens a picker, you choose, and the file gets
/// `[[Call Bob|^9f1e…]]`. That is why requiring the id costs nothing, and why resolving links by
/// title was rejected — two tasks called the same thing is a Tuesday, and a link that quietly points
/// at the wrong one is worse than a link never made.
final class LinkUITests: YantraUITestCase {

    func testTypingTwoBracketsOffersTheTasksItCouldMean() {
        launch(route: "open:\(Fixture.listId)")

        let capture = captureField
        XCTAssertTrue(capture.waitForExistence(timeout: 8), "no capture bar on the list")
        capture.tap()
        capture.typeText("See [[Plain")

        let suggestion = el("link.suggestion.\(Fixture.plainTask)")
        XCTAssertTrue(suggestion.waitForExistence(timeout: 6), "\u{5b}\u{5b} offered nothing")
        suggestion.tap()

        // The id goes into the text, which is what makes the link point at a task rather than at a
        // name. The label is what the file says when nothing resolves it.
        let value = capture.value as? String ?? ""
        XCTAssertTrue(value.contains("^\(Fixture.plainTaskId)"), "the link carries no id: \(value)")
        XCTAssertTrue(value.contains(Fixture.plainTask), "the link carries no label: \(value)")
    }

    /// A link reads as the target's current title and goes there when tapped.
    ///
    /// On a **page's blocks**, which is where links are live. A list row draws its link as plain
    /// words on purpose — the row's whole job is to open the task it is a row for, and a word inside
    /// it that went somewhere else would be a trap. Android draws rows the same way.
    func testALinkInAPageIsFollowedToItsTask() {
        launch(route: "open:\(Fixture.plainTaskId)")

        let link = el("link.\(Fixture.overdueTask)")
        XCTAssertTrue(link.waitForExistence(timeout: 8), "the link in the page is not drawn as a link")
        link.tap()

        // Arrived at the task it points at, on its own page.
        XCTAssertTrue(app.textFields[Fixture.overdueTask].waitForExistence(timeout: 8)
                        || el("task.due").waitForExistence(timeout: 4),
                      "following the link did not reach \(Fixture.overdueTask)")
    }
}

/// The capture grammar, end to end.
///
/// The parser itself is pinned by `CaptureTests` in the core. What those cannot see is the wiring:
/// whether the screen hands the parser the people it needs, and whether what comes back is actually
/// written onto the task. Both were missing until the assignee work — `@name` parsed perfectly and
/// was then dropped on the floor.
final class CaptureGrammarUITests: YantraUITestCase {

    func testOneTypedLineCarriesTheDatePriorityAndLabel() {
        launch(route: "open:\(Fixture.listId)")

        let capture = captureField
        XCTAssertTrue(capture.waitForExistence(timeout: 8), "no capture bar on the list")
        capture.tap()
        capture.typeText("Ship the build tomorrow 3pm !High #release\n")

        // The words that were read come off the title; what is left is the task.
        let row = el("task.row.Ship the build")
        XCTAssertTrue(row.waitForExistence(timeout: 8),
                      "the line was not parsed — the whole sentence is probably still the title")

        // And each piece landed where it belongs, which is what the row draws.
        XCTAssertTrue(el("#release").waitForExistence(timeout: 4), "the label did not land")
        row.tap()
        XCTAssertTrue(el("task.due").waitForExistence(timeout: 8), "no due date on the task")
        // `el`, not `staticTexts`: a pill is a Button with its words folded into its label.
        XCTAssertTrue(el("Priority \u{b7} High").waitForExistence(timeout: 4), "the priority did not land")
    }
}
