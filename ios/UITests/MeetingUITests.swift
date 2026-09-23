import XCTest
import EventKit

/// Opening somebody else's meeting.
///
/// A device event used to be a block you could see and could not touch: the tap did nothing at all
/// unless a task of yours happened to be attached to it. Android opens a meeting page; this is that.
///
/// The event is real. It is written into the simulator's own calendar database from this process —
/// the runner is in the simulator, so its EventKit is the app's EventKit — because a fake injected
/// into the view would test the sheet and skip the half that was actually missing: reading a meeting
/// out of the provider at all.
final class MeetingUITests: YantraUITestCase {

    private let store = EKEventStore()
    private var made: EKEvent?

    override func tearDownWithError() throws {
        if let made { try? store.remove(made, span: .thisEvent, commit: true) }
        try super.tearDownWithError()
    }

    private func grantedStore() throws -> EKEventStore {
        var granted = false
        let waited = expectation(description: "calendar access")
        if #available(iOS 17.0, *) {
            store.requestFullAccessToEvents { ok, _ in granted = ok; waited.fulfill() }
        } else {
            store.requestAccess(to: .event) { ok, _ in granted = ok; waited.fulfill() }
        }
        wait(for: [waited], timeout: 20)
        try XCTSkipUnless(granted, "the test runner was not given calendar access")
        return store
    }

    /// A meeting on today, with everything a meeting page is for: a place, a call, and some words.
    private func writeMeeting() throws -> EKEvent {
        let store = try grantedStore()
        try XCTSkipUnless(store.defaultCalendarForNewEvents != nil, "the simulator has no writable calendar")
        let event = EKEvent(eventStore: store)
        event.title = "Quarterly review"
        event.calendar = store.defaultCalendarForNewEvents
        event.startDate = Calendar.current.date(bySettingHour: 10, minute: 0, second: 0, of: Date())!
        event.endDate = event.startDate.addingTimeInterval(3600)
        event.location = "https://meet.google.com/abc-defg-hij"
        event.notes = "Agenda:<br>Numbers &amp; risks<br>See https://example.com/deck"
        try store.save(event, span: .thisEvent, commit: true)
        made = event
        return event
    }

    func testTappingAMeetingOpensItAsAPageOfYourOwn() throws {
        let event = try writeMeeting()
        launch(route: "calendar", extra: ["-calmode", "day", "-uitest-calendars"])
        let block = el(event.title)
        XCTAssertTrue(block.waitForExistence(timeout: 10), "the meeting was not drawn on the day")
        block.tap()

        // A page, not a card: Android settled this — an event is a node like any other, and a
        // meeting is a thing you work on, with notes under it.
        //
        // Whose it is comes first, which is what stops a read-only meeting looking like yours.
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "FROM ")).firstMatch
                        .waitForExistence(timeout: 8),
                      "tapping the meeting opened nothing")
        // The call, read out of the location and named rather than shown as a URL.
        XCTAssertTrue(el("Join Google Meet").waitForExistence(timeout: 5), "the video call was not offered")
        // And it is a page you can write on, which is the whole point of it being a node.
        XCTAssertTrue(el("page.add").waitForExistence(timeout: 5) || app.textViews.firstMatch.exists
                        || app.textFields.firstMatch.exists,
                      "the meeting page has nothing to write in")
    }

    /// One page for one meeting: made on the first tap, found on every one after.
    func testTheSameMeetingOpensTheSamePage() throws {
        let event = try writeMeeting()
        launch(route: "calendar", extra: ["-calmode", "day", "-uitest-calendars"])
        let block = el(event.title)
        XCTAssertTrue(block.waitForExistence(timeout: 10), "the meeting was not drawn on the day")
        block.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "FROM ")).firstMatch
                        .waitForExistence(timeout: 8), "the meeting did not open")

        tap("nav.back", "no way back from the meeting")
        let again = el(event.title)
        XCTAssertTrue(again.waitForExistence(timeout: 8), "the calendar did not come back")
        again.tap()

        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "FROM ")).firstMatch
                        .waitForExistence(timeout: 8), "the meeting did not open the second time")

        // Two taps, one page. A second node would draw as a second block on the day, which is the
        // failure `ext:` exists to prevent — counted on the calendar, where a block is a Button with
        // its words folded into its label.
        tap("nav.back", "no way back from the meeting")
        XCTAssertTrue(el(event.title).waitForExistence(timeout: 8), "the calendar did not come back")
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", event.title)).count, 1,
                       "tapping twice made a second page for one meeting")
    }
}
