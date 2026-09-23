import XCTest
@testable import YantraCore

/// What a row is allowed to say, given the view it is read in.
///
/// Ported case for case from Android's `SalienceTest.kt`, so the two engines are pinned to the same
/// answers: a row that reads one way on a phone and another on a tablet is a bug nobody can see
/// until both are open side by side.
final class SalienceTests: XCTestCase {

    let due = Field.prop(BuiltIns.due)
    let deadline = Field.prop(BuiltIns.deadline)
    let assignee = Field.prop(BuiltIns.assignee)

    func ctx(_ filter: Filter?, single: Bool = false) -> ViewContext {
        ViewContext(filter: filter, singleWorkspace: single)
    }

    func testAListPagePinsItsListAndDueTakesTheTitleSlot() {
        let g = Salience.grammar(ctx(nil))
        XCTAssertEqual(g.titleSlot, due)
        XCTAssertEqual(g.subLine, [deadline, assignee])
        XCTAssertEqual(g.spine, .workspace)
        XCTAssertTrue(g.pinned.contains(.originList))
    }

    func testTodayBranchesOnBothDates() {
        let today = Filter.all([
            .type(NodeType.task), .done(false),
            .anyOf([
                .prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd),
                .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
            ]),
        ])
        let w = Salience.weigh(ctx(today))
        XCTAssertEqual(w[due], .branched)
        XCTAssertEqual(w[deadline], .branched)
        // Both dates are branched; the first is the slot, the other leads the sub-line. The row
        // shows whichever one the task actually has.
        let g = Salience.grammar(ctx(today))
        XCTAssertEqual(g.titleSlot, due)
        XCTAssertEqual(g.subLine, [deadline, assignee, .originList])
    }

    func testStrictDueTodayPinsDueSoTheDeadlineTakesTheSlot() {
        let f = Filter.all([.prop(defId: BuiltIns.due, op: .eq, dateRel: .todayStart)])
        let g = Salience.grammar(ctx(f))
        XCTAssertTrue(g.pinned.contains(due))
        XCTAssertEqual(g.titleSlot, deadline)
        XCTAssertEqual(g.subLine, [assignee, .originList])
    }

    func testAnAndOfLabelAndWorkspaceHidesBothAndOneWorkspaceHidesTheSpine() {
        let f = Filter.all([.hasLabel("work"), .inWorkspace("ws-a")])
        let g = Salience.grammar(ctx(f, single: true))
        XCTAssertTrue(g.pinned.contains(.label("work")))
        XCTAssertTrue(g.pinned.contains(.workspace))
        XCTAssertNil(g.spine)
    }

    func testAnOrOfTwoWorkspacesPromotesTheWorkspace() {
        let f = Filter.anyOf([.inWorkspace("ws-a"), .inWorkspace("ws-b")])
        XCTAssertEqual(Salience.weigh(ctx(f))[.workspace], .branched)
        let g = Salience.grammar(ctx(f))
        // The workspace is the spine, so it does not crowd the sub-line.
        XCTAssertEqual(g.spine, .workspace)
        XCTAssertEqual(g.titleSlot, due)
    }

    func testAnOrOfTwoLabelsBranchesBoth() {
        let f = Filter.anyOf([.hasLabel("urgent"), .hasLabel("blocked")])
        let w = Salience.weigh(ctx(f))
        XCTAssertEqual(w[.label("urgent")], .branched)
        XCTAssertEqual(w[.label("blocked")], .branched)
    }

    func testAssignedToMePinsTheAssignee() {
        let f = Filter.all([.prop(defId: BuiltIns.assignee, op: .eq, text: "batunii")])
        let g = Salience.grammar(ctx(f))
        XCTAssertTrue(g.pinned.contains(assignee))
        XCTAssertEqual(g.titleSlot, due)
        XCTAssertEqual(g.subLine, [deadline, .originList])
    }

    func testNotSetPinsDueSoItNeverShows() {
        let f = Filter.all([.prop(defId: BuiltIns.due, op: .notSet)])
        let g = Salience.grammar(ctx(f))
        XCTAssertTrue(g.pinned.contains(due))
        XCTAssertEqual(g.titleSlot, deadline)
    }

    func testAFieldInAnAndAndAgainInAnOrKeepsTheOrWeight() {
        let f = Filter.all([
            .prop(defId: BuiltIns.due, op: .isSet),
            .anyOf([.prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd), .hasLabel("urgent")]),
        ])
        XCTAssertEqual(Salience.weigh(ctx(f))[due], .branched)
    }

    func testAOneArmedAnyOfIsAnAll() {
        XCTAssertEqual(Salience.weigh(ctx(.anyOf([.hasLabel("x")])))[.label("x")], .pinned)
    }

    // MARK: what a negation does

    /// `not(anyOf(…))` is an AND of negations, so its arms are not branches.
    ///
    /// Without De Morgan the walk sees an `anyOf` with two arms and promotes both dates to the front
    /// of the row as the reason it is there — when they are precisely the reason it is not.
    func testANegatedOrDoesNotBranch() {
        let f = Filter.not(.anyOf([
            .prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd),
            .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
        ]))
        let w = Salience.weigh(ctx(f))
        XCTAssertEqual(w[due], .bounded)
        XCTAssertEqual(w[deadline], .bounded)
        XCTAssertTrue(Salience.grammar(ctx(f)).branched.isEmpty, "nothing branched, so nothing leads")
    }

    /// `not(all(…))` is an OR of negations: it fixes nothing, so nothing may be pinned.
    func testANegatedAndPinsNothing() {
        let f = Filter.not(.all([
            .prop(defId: BuiltIns.due, op: .isSet),
            .prop(defId: BuiltIns.assignee, op: .isSet),
        ]))
        let w = Salience.weigh(ctx(f))
        XCTAssertEqual(w[due], .bounded)
        XCTAssertEqual(w[assignee], .bounded)
        XCTAssertTrue(Salience.grammar(ctx(f)).pinned.isEmpty)
    }

    func testExcludingOneWorkspaceStillLeavesASpine() {
        let f = Filter.not(.inWorkspace("ws-a"))
        XCTAssertEqual(Salience.weigh(ctx(f))[.workspace], .bounded)
        XCTAssertEqual(Salience.grammar(ctx(f)).spine, .workspace)
    }

    /// A tag absent from every row is as silent as one present on every row — and the second form is
    /// why De Morgan matters: `not(urgent OR blocked)` is `NOT urgent AND NOT blocked`.
    func testAnExcludedTagIsPinnedHoweverTheExclusionIsSpelt() {
        XCTAssertEqual(Salience.weigh(ctx(.not(.hasLabel("x"))))[.label("x")], .pinned)

        let many = Filter.not(.anyOf([.hasLabel("urgent"), .hasLabel("blocked")]))
        let w = Salience.weigh(ctx(many))
        XCTAssertEqual(w[.label("urgent")], .pinned)
        XCTAssertEqual(w[.label("blocked")], .pinned)
        XCTAssertTrue(Salience.grammar(ctx(many)).branched.isEmpty)
    }

    /// Priority is the enclosure around the task glyph, never a word on the meta line, so an engine
    /// that pinned it would be telling the line to stay quiet about what the checkbox is shouting.
    func testPriorityIsTheGlyphsBusiness() {
        let f = Filter.all([.prop(defId: BuiltIns.priority, op: .eq, text: "High")])
        let priority = Field.prop(BuiltIns.priority)
        let g = Salience.grammar(ctx(f))
        XCTAssertNil(Salience.weigh(ctx(f))[priority])
        XCTAssertFalse(g.pinned.contains(priority))
        XCTAssertFalse(g.subLine.contains(priority))
    }

    /// Labels never reach the sub-line — the row already holds the whole set — so `branched` is the
    /// only way a row learns which tag is the reason it is on this screen.
    func testTheMatchedTagsAreReportedThoughTheyNeverReachTheSubLine() {
        let g = Salience.grammar(ctx(.anyOf([.hasLabel("urgent"), .hasLabel("blocked")])))
        XCTAssertTrue(g.branched.contains(.label("urgent")))
        XCTAssertTrue(g.branched.contains(.label("blocked")))
        XCTAssertFalse(g.subLine.contains { if case .label = $0 { return true }; return false },
                       "a tag is never a sub-line field")
    }
}
