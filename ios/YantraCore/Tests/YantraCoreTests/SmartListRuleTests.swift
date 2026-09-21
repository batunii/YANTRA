import XCTest
@testable import YantraCore

/// The builder's round trip. The property that matters most is the one about *not* understanding
/// something: a rule the editor cannot fully express must come back out unchanged apart from what
/// was actually edited, or looking at a working smart list would quietly break it.
final class SmartListRuleTests: XCTestCase {

    let due = BuiltIns.due, priority = BuiltIns.priority

    func testOpenTasksRoundTrip() {
        let f = SmartListRule.encode(show: .open, conds: [])
        let d = SmartListRule.decode(f)
        XCTAssertEqual(d.show, .open)
        XCTAssertTrue(d.conds.isEmpty)
        XCTAssertTrue(d.extras.isEmpty)
    }

    func testEveryShowModeSurvives() {
        for mode in ShowMode.allCases {
            let d = SmartListRule.decode(SmartListRule.encode(show: mode, conds: []))
            XCTAssertEqual(d.show, mode, "\(mode) did not survive")
        }
    }

    func testStartedBeatsOpenWhenBothAreWritten() {
        // A rule from elsewhere may carry both; started is the narrower claim.
        let f = Filter.all([.type(NodeType.task), .done(false), .inProgress(true)])
        XCTAssertEqual(SmartListRule.decode(f).show, .started)
    }

    func testAPropertyConditionRoundTrips() {
        let c = Cond(defId: priority, op: .eq, text: "High")
        let d = SmartListRule.decode(SmartListRule.encode(show: .open, conds: [c]))
        XCTAssertEqual(d.conds.count, 1)
        XCTAssertEqual(d.conds[0].defId, priority)
        XCTAssertEqual(d.conds[0].op, .eq)
        XCTAssertEqual(d.conds[0].text, "High")
    }

    func testALabelConditionKeepsItsMatchMode() {
        for mode in [LabelMatchMode.any, .all] {
            let c = Cond(labelIds: ["w:label:home", "w:label:urgent"], labelMatch: mode)
            let d = SmartListRule.decode(SmartListRule.encode(show: .open, conds: [c]))
            XCTAssertEqual(d.conds.count, 1)
            XCTAssertEqual(d.conds[0].labelIds, ["w:label:home", "w:label:urgent"])
            XCTAssertEqual(d.conds[0].labelMatch, mode, "match mode lost")
        }
    }

    func testAnEmptyLabelConditionWritesNothing() {
        // A label condition is empty for the moment between adding it and picking a label. It must
        // not become a clause that matches nothing.
        let f = SmartListRule.encode(show: .open, conds: [Cond(labelIds: [])])
        guard case let .all(parts) = f else { return XCTFail("expected an all") }
        XCTAssertFalse(parts.contains { if case .anyOf = $0 { return true }; return false })
    }

    // MARK: the part that matters

    /// Today's own rule: "due today-or-earlier OR deadline today-or-earlier". There is no control
    /// for an OR of two properties, so it is an extra — and it has to come back out identical.
    func testARuleTheBuilderCannotExpressSurvivesBeingEdited() {
        let orBranch = Filter.anyOf([
            .prop(defId: due, op: .lte, dateRel: .todayEnd),
            .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
        ])
        let original = Filter.all([.type(NodeType.task), .done(false), orBranch])

        let d = SmartListRule.decode(original)
        XCTAssertEqual(d.show, .open)
        XCTAssertEqual(d.extras.count, 1, "the OR branch should be carried as an extra")
        XCTAssertEqual(d.extras.first, orBranch)

        // Now edit something the builder *does* control, and re-emit.
        let edited = SmartListRule.encode(show: .all, conds: d.conds, extras: d.extras)
        guard case let .all(parts) = edited else { return XCTFail("expected an all") }
        XCTAssertTrue(parts.contains(orBranch), "the branch it does not understand was dropped")
        XCTAssertFalse(parts.contains(.done(false)), "the edit did not take")
    }

    func testWorkspacesRoundTripAndEverywhereWritesNothing() {
        // Nothing at all for "everywhere": the absence is what keeps the rule portable, and one
        // clause per repository would stop covering a workspace added later.
        guard case let .all(none) = SmartListRule.encode(show: .open, conds: [], workspaces: []) else {
            return XCTFail()
        }
        XCTAssertFalse(none.contains { if case .inWorkspace = $0 { return true }; return false })

        let one = SmartListRule.encode(show: .open, conds: [], workspaces: ["w1"])
        XCTAssertEqual(SmartListRule.decode(one).workspaces, ["w1"])

        let many = SmartListRule.encode(show: .open, conds: [], workspaces: ["w2", "w1"])
        XCTAssertEqual(SmartListRule.decode(many).workspaces.sorted(), ["w1", "w2"])
    }

    // MARK: sorting and the operators on offer

    func testADatedRuleSortsByThatDate() {
        let conds = [Cond(defId: due, op: .lte, dateRel: .todayEnd)]
        let specs = SmartListRule.sort(conds)
        XCTAssertEqual(specs.first?.by, .propDate)
        XCTAssertEqual(specs.first?.defId, due)
    }

    func testAnUndatedRuleSortsNewestFirst() {
        let specs = SmartListRule.sort([Cond(defId: priority, op: .eq, text: "High")])
        XCTAssertEqual(specs.first?.by, .created)
        XCTAssertEqual(specs.first?.desc, true)
    }

    func testEachKindOffersItsOwnOperators() {
        XCTAssertTrue(SmartListRule.ops(for: PropertyKind.date).contains { $0.dateRel == .todayEnd })
        XCTAssertTrue(SmartListRule.ops(for: PropertyKind.checkbox).allSatisfy { $0.bool != nil })
        XCTAssertTrue(SmartListRule.ops(for: PropertyKind.select).contains { $0.op == .neq })
        // An unknown kind still offers something usable rather than nothing.
        XCTAssertFalse(SmartListRule.ops(for: "something-new").isEmpty)
    }

    // MARK: the templates

    func testTemplatesPrefillWhatTheyClaim() {
        let defs = WorkspaceStore.builtInProperties()
        let (showDue, dueConds) = SmartTemplate.dueToday.preset(defs)
        XCTAssertEqual(showDue, .open)
        XCTAssertEqual(dueConds.first?.dateRel, .todayEnd)

        let (_, highConds) = SmartTemplate.highPriority.preset(defs)
        XCTAssertEqual(highConds.first?.text, "High")

        let (_, openConds) = SmartTemplate.allOpen.preset(defs)
        XCTAssertTrue(openConds.isEmpty)
    }

    /// The seeded Today list is the rule most likely to be opened in the builder, so it is the one
    /// that must survive a look without being rewritten.
    func testTheSeededTodayRuleSurvivesARoundTrip() {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("smart-\(UUID())")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = WorkspaceStore(root: dir, id: "")
        store.scaffold(name: "T", now: 0)
        WorkspaceSeeder.seed(store)

        guard let today = store.readSmartLists().first(where: { $0.sort.first?.defId == BuiltIns.due }),
              let filter = today.filter else { return XCTFail("no seeded Today rule") }

        let d = SmartListRule.decode(filter)
        let again = SmartListRule.encode(show: d.show, conds: d.conds, extras: d.extras,
                                         workspaces: Set(d.workspaces))
        XCTAssertEqual(again, filter, "opening Today in the builder would have rewritten it")
    }
}
