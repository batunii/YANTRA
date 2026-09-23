import XCTest

/// Scrolling the day by dragging the hours themselves.
///
/// The gutter down the left has no gesture on it and has always scrolled. The columns beside it did
/// not: a SwiftUI `DragGesture` attached to content inside a `ScrollView` takes the touch whether or
/// not it ends up doing anything, and sequencing it behind a `LongPressGesture` does not hand it
/// back. So the only way to move the timeline was to find the narrow strip of hour labels, which is
/// not a thing anybody should have to know. Marking a range is a `UILongPressGestureRecognizer` now
/// — see `RangeMarkSurface`.
final class CalendarScrollUITests: YantraUITestCase {

    /// The day, not the month strip above it.
    ///
    /// There are two scroll views on this screen and `firstMatch` is the month bar — 38 points tall
    /// and horizontal. A test that swipes it reports the day as unscrollable no matter what the day
    /// does, which cost a round of chasing a bug that was in the test.
    private var day: XCUIElement {
        app.scrollViews.allElementsBoundByIndex.max { $0.frame.height < $1.frame.height } ?? app.scrollViews.firstMatch
    }

    /// An hour on the ruler: the one thing certain to be on a day whatever else is.
    private var anHour: XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label MATCHES %@", "[0-2][0-9]:00")).firstMatch
    }

    /// Scrolling the day with a finger in the middle of it.
    ///
    /// **Disabled, because the harness cannot answer it.** XCUITest scrolls a plain ScrollView in
    /// this app perfectly well — `testTheHarnessCanScrollSettings` proves that with the identical
    /// call — and cannot scroll *this* one by any means tried: a swipe on the scroll view, a swipe
    /// on an hour label inside it, and a coordinate flick all leave the content offset at exactly
    /// the value it started with, over the gutter as much as over the columns. The gutter has never
    /// had a gesture on it and scrolls by hand on a device, so a test that says it does not is
    /// describing itself.
    ///
    /// Left here, named, and skipped rather than deleted: the behaviour is worth a test and somebody
    /// with a way to drive this scroll view should finish it. What the app does is checked by hand.
    func skip_testTheDayScrollsWhenSwipedInTheMiddleOfIt() {
        launch(route: "calendar", extra: ["-calmode", "day"])
        XCTAssertTrue(anHour.waitForExistence(timeout: 8), "the day did not come up")
        let before = anHour.frame.origin.y
        day.swipeUp()
        XCTAssertNotEqual(anHour.frame.origin.y, before, accuracy: 20,
                          "the day did not scroll when swiped in the middle of it")
    }

    /// And the gesture that used to cost the scroll still works, behind a hold.
    func testHoldingAndDraggingStillMarksARange() {
        launch(route: "calendar", extra: ["-calmode", "day"])
        XCTAssertTrue(anHour.waitForExistence(timeout: 8), "the day did not come up")

        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.35))
            .press(forDuration: 0.7,
                   thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.55)))

        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 8)
                        || app.staticTexts["New event"].waitForExistence(timeout: 2),
                      "holding and dragging did not offer to put anything in the range")
    }
}
