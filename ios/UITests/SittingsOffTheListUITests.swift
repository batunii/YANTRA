import XCTest

/// **A sitting is not a row on its list** — Android at 17d0beb ("sittings off the list").
///
/// A sitting is an event line with `for:<task>`: the task seen as an hour. It is written on the
/// list, right under its task, and the task's own row already stands for it — drawn as well, it read
/// as the same item twice. The calendar is where a sitting shows.
final class SittingsOffTheListUITests: YantraUITestCase {

    func testTheTaskIsOnTheListOnceAndItsHourIsNot() {
        _ = launch(route: "home")
        openListNamed(Fixture.list)
        XCTAssertTrue(app.staticTexts[Fixture.sittingTask].waitForExistence(timeout: 8),
                      "the task with a sitting is missing from its own list")
        let hour = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@", "event.row.\(Fixture.sittingTask)"))
        XCTAssertEqual(hour.count, 0, "the sitting is drawn on the list as a second row for the same task")
    }
}
