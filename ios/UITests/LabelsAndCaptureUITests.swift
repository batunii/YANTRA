import XCTest

/// Label colours, and the capture grammar where people actually type: a list's bar, Home's bar,
/// and a subtask on a task's page.
final class LabelsAndCaptureUITests: YantraUITestCase {


    /// One line, everything on it read: the date, the time, the label and the priority come off the
    /// words and onto the task.
    func testALineOnAListIsReadWhole() {
        launch(route: "open:\(Fixture.listId)")
        XCTAssertTrue(captureField.waitForExistence(timeout: 8), "no capture field on the list")
        captureField.tap()
        captureField.typeText("Pick up parcel tomorrow 3pm #errands !High\n")

        let row = el("task.row.Pick up parcel")
        XCTAssertTrue(row.waitForExistence(timeout: 8), "the words were not stripped to a title")
        XCTAssertTrue(el("#errands").waitForExistence(timeout: 4), "the label did not land on the row")
        app.staticTexts["Pick up parcel"].tap()

        XCTAssertTrue(el("task.due").waitForExistence(timeout: 8), "tomorrow 3pm did not become a due date")
        let due = el("task.due").label
        XCTAssertTrue(due.contains("15:00") || due.contains("3"), "the time was lost: \(due)")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "Priority · High")).firstMatch.exists,
                      "!High did not set the priority")
        XCTAssertTrue(el("task.label.errands").exists, "the label is not on the task page")
    }

    /// Home's New sheet makes a task on the Inbox, with the same grammar.
    func testALineFromHomeLandsOnTheInboxParsed() {
        launch(route: "home")
        tap("tab.create", "no New key on Home")
        let field = app.textFields.firstMatch.exists ? app.textFields.firstMatch : app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 6), "the New sheet has nowhere to type")
        app.typeText("Water the plants friday #home")
        let create = app.buttons["Create task"]
        XCTAssertTrue(create.waitForExistence(timeout: 4), "no Create task key")
        create.tap()

        XCTAssertTrue(el("task.row.Water the plants").waitForExistence(timeout: 8),
                      "the task did not land on the Inbox with its title stripped")
        XCTAssertTrue(el("#home").exists, "the label did not land")
    }

    /// A long press recolours a label; a tap still takes it off. And the choice outlives the app.
    func testALabelCanBeRecolouredAndMadeNeutral() {
        launch(route: "open:\(Fixture.listId)")
        XCTAssertTrue(captureField.waitForExistence(timeout: 8))
        captureField.tap()
        captureField.typeText("Label me #errands\n")
        XCTAssertTrue(app.staticTexts["Label me"].waitForExistence(timeout: 8))
        app.staticTexts["Label me"].tap()

        let pill = el("task.label.errands")
        XCTAssertTrue(pill.waitForExistence(timeout: 8), "no label pill on the task page")
        let seeded = pill.value as? String
        XCTAssertNotNil(seeded, "the pill does not say what colour it is")

        let target = seeded == "Teal" ? "Plum" : "Teal"
        pill.press(forDuration: 1.0)
        let choice = app.buttons[target]
        XCTAssertTrue(choice.waitForExistence(timeout: 5), "a long press offered no colours")
        choice.tap()
        XCTAssertTrue(el("task.label.errands").waitForExistence(timeout: 5),
                      "recolouring took the label off the task")
        XCTAssertEqual(el("task.label.errands").value as? String, target, "the colour did not change")

        el("task.label.errands").press(forDuration: 1.0)
        XCTAssertTrue(app.buttons["No colour"].waitForExistence(timeout: 5))
        app.buttons["No colour"].tap()
        XCTAssertEqual(el("task.label.errands").value as? String, "No colour", "neutral did not stick")

        // It is in the workspace's label registry, not in the view: back again after a relaunch.
        launch(route: "open:\(Fixture.listId)", reset: false)
        XCTAssertTrue(app.staticTexts["Label me"].waitForExistence(timeout: 8))
        app.staticTexts["Label me"].tap()
        XCTAssertEqual(el("task.label.errands").value as? String, "No colour", "the colour did not survive a relaunch")

        // And a plain tap still does what it did: takes the label off.
        tap("task.label.errands")
        XCTAssertFalse(el("task.label.errands").waitForExistence(timeout: 2), "a tap no longer detaches")
    }

    /// A subtask typed on a task's page. What the grammar does there, pinned down.
    ///
    /// A task's page is a document on both platforms: no capture bar, and a line typed into it is
    /// saved as typed — `rename`, not `captureTask`. The trailing `#tag` and `!priority` still come
    /// off, because that is the line *format*, not the capture grammar; "tomorrow" stays a word.
    func testASubtaskKeepsItsWordsAndTheFormatTakesItsTokens() {
        launch(route: "open:\(Fixture.plainTaskId)")
        let taskKey = app.buttons.matching(NSPredicate(format: "label == %@", "Task")).firstMatch
        XCTAssertTrue(taskKey.waitForExistence(timeout: 8), "no Task key on the page")
        taskKey.tap()
        // The new line takes the keyboard itself; the first text field on the page is its title.
        XCTAssertTrue(app.textFields["New task"].waitForExistence(timeout: 5)
                        || app.textViews["New task"].exists, "the new subtask has nowhere to type")
        app.typeText("Call the vet tomorrow #pets !High\n")

        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "subtask-after-typing"; shot.lifetime = .keepAlways
        add(shot)

        let line = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "Call the vet tomorrow")).firstMatch
        XCTAssertTrue(line.waitForExistence(timeout: 5), "the subtask's title is not the words before the tokens")

        // The tokens are not lost, only unseen on the line: the subtask's own page carries them.
        launch(route: "open:\(Fixture.plainTaskId)", reset: false)
        XCTAssertTrue(line.waitForExistence(timeout: 8))
        let frame = line.frame
        app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: app.frame.maxX - 30, dy: frame.midY)).tap()
        XCTAssertTrue(el("task.label.pets").waitForExistence(timeout: 8), "#pets did not become a label")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "Priority · High")).firstMatch.exists,
                      "!High did not become the priority")
        XCTAssertFalse(el("task.due").exists, "tomorrow was read as a date — that is new behaviour")
    }

    /// An event written on a list is on the list — the fixture's Groceries holds two.
    func testEventsOnAListAreOnTheList() {
        launch(route: "open:\(Fixture.listId)")
        XCTAssertTrue(el("event.row.\(Fixture.event)").waitForExistence(timeout: 8), "the event is not on its list")
        XCTAssertTrue(el("event.row.\(Fixture.allDayEvent)").exists, "the all-day event is not on its list")
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "list-with-events"; shot.lifetime = .keepAlways
        add(shot)
        app.staticTexts[Fixture.event].tap()
        XCTAssertTrue(el("nav.back").waitForExistence(timeout: 5), "the event row opened nothing")
    }
}
