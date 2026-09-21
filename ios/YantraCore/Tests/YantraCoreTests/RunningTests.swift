import XCTest
@testable import YantraCore

/// The bar's order. Pure, and tested as such, because "which card is in front" is easy to get wrong
/// and nearly impossible to see going wrong by looking at the screen.
final class RunningTests: XCTestCase {

    let noon: Int64 = 1_700_000_000_000

    func span(_ id: String, _ title: String? = nil, from: Int64, to: Int64) -> SittingSpan {
        SittingSpan(taskId: id, title: title, startUtc: from, endUtc: to)
    }

    // MARK: covers

    func testASittingEndsAtItsEnd() {
        let s = span("a", from: 100, to: 200)
        XCTAssertTrue(s.covers(100), "a sitting has started at its start")
        XCTAssertTrue(s.covers(199))
        XCTAssertFalse(s.covers(200), "a sitting ending at 15:00 is over at 15:00")
        XCTAssertFalse(s.covers(99))
    }

    // MARK: what goes on the bar

    func testStartedTasksAllGetACard() {
        // Several things on the go at once is the ordinary state of a day, not an error.
        let cards = RunningStack.stack(started: [("a", "A"), ("b", "B"), ("c", "C")], at: noon)
        XCTAssertEqual(cards.map(\.nodeId), ["a", "b", "c"])
        XCTAssertTrue(cards.allSatisfy { !$0.hasSession }, "nothing is timed, so no card may claim a clock")
    }

    func testATaskWhoseSittingIsNowIsOnTheBarWithoutBeingStarted() {
        // Readiness is the case where nothing has been written about the task anywhere else, so its
        // title has to come down with the span.
        let cards = RunningStack.stack(started: [], sittings: [span("s", "Sit", from: noon - 10, to: noon + 10)], at: noon)
        XCTAssertEqual(cards.map(\.nodeId), ["s"])
        XCTAssertEqual(cards[0].title, "Sit")
        XCTAssertTrue(cards[0].scheduled)
        XCTAssertFalse(cards[0].hasSession, "a sitting is not a stopwatch")
    }

    func testASittingThatIsNotHappeningNowIsNotOnTheBar() {
        let cards = RunningStack.stack(started: [], sittings: [span("s", "Later", from: noon + 100, to: noon + 200)], at: noon)
        XCTAssertTrue(cards.isEmpty)
    }

    func testAStartedTaskWithASittingIsOneCardNotTwo() {
        let cards = RunningStack.stack(started: [("a", "A")],
                                       sittings: [span("a", "A", from: noon - 10, to: noon + 10)], at: noon)
        XCTAssertEqual(cards.count, 1, "the same task appeared from both sources")
        XCTAssertTrue(cards[0].scheduled, "the card lost the fact that its hour is now")
    }

    func testTwoSittingsForOneTaskInTheSameHourAreOneCard() {
        let cards = RunningStack.stack(started: [], sittings: [
            span("a", "A", from: noon - 10, to: noon + 10),
            span("a", "A", from: noon - 5, to: noon + 20),
        ], at: noon)
        XCTAssertEqual(cards.count, 1)
    }

    // MARK: the order, which is the point

    func testTheTimedCardLeadsHoweverLateItWasStarted() {
        // It is the only card reporting something that changes; having to swipe to find out how long
        // you have been at it defeats showing it at all.
        let cards = RunningStack.stack(started: [("a", "A"), ("b", "B"), ("c", "C")],
                                       timing: ("c", 90), at: noon)
        XCTAssertEqual(cards.first?.nodeId, "c")
        XCTAssertEqual(cards.first?.elapsedSecs, 90)
        XCTAssertEqual(cards.dropFirst().map(\.nodeId), ["a", "b"], "the rest lost their given order")
    }

    func testScheduledComesNextAndTheRestKeepTheirOrder() {
        // A sitting is you, earlier, saying this is the hour for this — better than newest-first.
        let cards = RunningStack.stack(
            started: [("a", "A"), ("b", "B"), ("c", "C")],
            timing: ("a", 10),
            sittings: [span("c", "C", from: noon - 10, to: noon + 10)],
            at: noon)
        XCTAssertEqual(cards.map(\.nodeId), ["a", "c", "b"])
    }

    func testTheOrderWithinARankIsTheOrderItWasGiven() {
        // Stable ordering: Swift's sort is not stable on its own, and a bar that reshuffled equal
        // cards on every tick would be unreadable.
        let started = (0..<12).map { ("t\($0)", "T\($0)") }
        let cards = RunningStack.stack(started: started, at: noon)
        XCTAssertEqual(cards.map(\.nodeId), started.map(\.0))
    }

    func testOnlyTheTimedCardCarriesAClock() {
        let cards = RunningStack.stack(started: [("a", "A"), ("b", "B")], timing: ("b", 42), at: noon)
        XCTAssertEqual(cards.first { $0.nodeId == "b" }?.elapsedSecs, 42)
        XCTAssertNil(cards.first { $0.nodeId == "a" }?.elapsedSecs,
                     "a card invented a number it has no session for")
    }

    /// A device that received the flags through sync has started tasks and no session at all: the
    /// claims travel, the stopwatch does not.
    func testASyncedDeviceShowsTheCardsWithNoClock() {
        let cards = RunningStack.stack(started: [("a", "A"), ("b", "B")], timing: nil, at: noon)
        XCTAssertEqual(cards.count, 2)
        XCTAssertTrue(cards.allSatisfy { $0.elapsedSecs == nil })
    }

    func testTimingATaskThatIsNotStartedDoesNotConjureACard() {
        // Starting a session marks the task, so this state should not arise; if it does, the bar
        // reports what is actually started rather than papering over the disagreement.
        let cards = RunningStack.stack(started: [("a", "A")], timing: ("zzz", 5), at: noon)
        XCTAssertEqual(cards.map(\.nodeId), ["a"])
        XCTAssertNil(cards[0].elapsedSecs)
    }

    // MARK: the colours a card wears

    func testACardCarriesItsListAndWorkspace() {
        let cards = RunningStack.stack(
            started: [("a", "A")], at: noon,
            lists: ["a": ("Work", "col:teal")],
            workspaces: ["a": "col:plum"])
        XCTAssertEqual(cards[0].listName, "Work")
        XCTAssertEqual(cards[0].listColour, "col:teal")
        XCTAssertEqual(cards[0].workspaceColour, "col:plum")
    }

    func testOneWorkspaceMeansNoWorkspaceColour() {
        // A colour that always means the same thing means nothing.
        let cards = RunningStack.stack(started: [("a", "A")], at: noon)
        XCTAssertNil(cards[0].workspaceColour)
    }

    func testASittingsTitleIsReadAsPlainText() {
        // A title on the line may point at another page; the bar shows the words, not the link.
        let cards = RunningStack.stack(
            started: [],
            sittings: [span("s", "Read \(Links.encode(label: "the memo", targetId: "x"))", from: noon - 1, to: noon + 1)], at: noon)
        XCTAssertEqual(cards[0].title, "Read the memo")
    }
}
