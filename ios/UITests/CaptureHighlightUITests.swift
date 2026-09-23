import XCTest

/// Typing into the capture bar, with what the app understood shown as it is typed.
final class CaptureHighlightUITests: YantraUITestCase {

    /// The field is a `UITextView` now rather than a `TextField`, so the first thing worth checking
    /// is the ordinary one: that you can still type into it and still commit with return.
    func testTheCaptureFieldStillTakesALineAndCommitsIt() {
        launch(route: "open:\(Fixture.listId)")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 8), "no capture field on the list")
        field.tap()
        field.typeText("Buy milk #groceries\n")

        XCTAssertTrue(el("task.row.Buy milk").waitForExistence(timeout: 8),
                      "the line did not become a task")
        XCTAssertTrue(el("#groceries").waitForExistence(timeout: 4), "the label did not land")
    }

    /// `~` offers the lists it could mean, and picking one writes it into the line.
    func testTypingATildeOffersTheLists() {
        launch(route: "open:\(Fixture.listId)")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 8), "no capture field on the list")
        field.tap()
        field.typeText("Post the forms ~Wor")

        let suggestion = el("list.suggestion.\(Fixture.secondList)")
        XCTAssertTrue(suggestion.waitForExistence(timeout: 6), "~ offered no lists")
        suggestion.tap()

        let value = field.value as? String ?? ""
        XCTAssertTrue(value.contains("~\(Fixture.secondList)"), "picking a list did not write it: \(value)")
    }
}

/// A screenshot of the field mid-typing, so the tinting can be looked at rather than asserted.
final class CaptureHighlightShot: YantraUITestCase {
    func testShot() {
        launch(route: "open:\(Fixture.listId)")
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 8))
        field.tap()
        field.typeText("Ship the build tomorrow 3pm !High #release ~Work")
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "capture-highlight"
        shot.lifetime = .keepAlways
        add(shot)
    }
}

/// Giving a task to somebody from the capture bar.
///
/// Two bugs lived here. The picker offered the *local* workspace's roster whatever list you were
/// on, so a name from a shared repository was never offered and — because the parser only reads a
/// known login as an assignment — typing it by hand left it in the title. And picking a name put
/// the caret back between the `@` and the login, so the next keystroke landed inside it.
final class AssignFromCaptureUITests: YantraUITestCase {

    func testPickingAPersonWritesTheLoginAndLeavesTheCaretAfterIt() {
        launch(route: "open:\(Fixture.listId)")
        let field = captureField
        XCTAssertTrue(field.waitForExistence(timeout: 8), "no capture field")
        field.tap()
        field.typeText("Send the invoice @")

        // The signed-in account is always offered — it needs no network and covers the common case.
        let me = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "person.suggestion."))
            .firstMatch
        guard me.waitForExistence(timeout: 6) else {
            // A build with nobody signed in offers the way to find out instead, which is the other
            // half of the fix and just as much the point.
            XCTAssertTrue(el("person.fetch").exists, "@ offered neither a person nor a way to find one")
            return
        }
        let login = me.label.replacingOccurrences(of: " · you", with: "")
        me.tap()

        let after = field.value as? String ?? ""
        XCTAssertTrue(after.contains("@\(login)"), "the login was not written: \(after)")

        // The caret is after the name, so carrying on typing appends rather than splitting it.
        field.typeText(" today")
        let carried = field.value as? String ?? ""
        XCTAssertTrue(carried.contains("@\(login) today"),
                      "the caret was left inside the login: \(carried)")
    }
}
