import XCTest
@testable import YantraCore

/// `PageCodecTest.kt`, `PinnedCodecTest.kt`, `EventAuthorTest.kt`, `EventCodecTest.kt`,
/// `TokenValueTest.kt`, `ExternalNoteCodecTest.kt` and the codec half of `LinksTest.kt`, ported case
/// for case from `app/src/test` at 17d0beb — plus the cases in `web/src/format/pageCodec.test.ts`
/// the Kotlin suite does not already have.
///
/// Where one of these disagrees with the Kotlin, the port is wrong. The byte-level contract is in
/// `ConformanceTests` (fixtures the Kotlin itself wrote); these pin the *model* a line reads as.
final class PageCodecPortTests: XCTestCase {

    let canonical = """
    ---
    id: 7c3f
    type: task
    parent: 1a2b
    title: Wire up the sync worker
    modified_at: 2026-08-25T14:22:31.402Z
    device: sm-s921b
    ---
    # Welcome

    A list holds tasks.

    - [ ] Tasks can nest ^9f1e due:2026-08-26 !high #sync @batunii
    » - [x] An indented, finished subtask ^4d2c

    ![[ink:5b8a]]

    """

    let header = "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n"

    func page(_ blocks: [Block]) -> PageDoc {
        PageDoc(id: "a", type: "task", parent: nil, title: nil, modifiedInstant: .epoch, device: nil, blocks: blocks)
    }

    func one(_ line: String) -> TaskRef {
        let blocks = PageCodec.decode(header + line + "\n").blocks
        guard blocks.count == 1, case let .task(t) = blocks[0] else { XCTFail("not one task: \(line)"); return TaskRef(id: "", title: "") }
        return t
    }

    func render(_ t: TaskRef) -> String {
        PageCodec.encode(page([.task(t)])).split(separator: "\n").map(String.init).first { $0.hasPrefix("- [") }!
    }

    func event(_ line: String) -> EventRef? {
        if case let .event(e) = PageCodec.decodeBlock(line) { return e }
        return nil
    }

    // MARK: the round trip

    func testCanonicalTextRoundTripsByteForByte() {
        XCTAssertEqual(Array(PageCodec.encode(PageCodec.decode(canonical)).utf8), Array(canonical.utf8))
    }

    func testDecodingIsStableAcrossARoundTrip() {
        let once = PageCodec.decode(canonical)
        XCTAssertEqual(PageCodec.decode(PageCodec.encode(once)), once)
    }

    func testFrontmatterSurvives() {
        let p = PageCodec.decode(canonical)
        XCTAssertEqual(p.id, "7c3f"); XCTAssertEqual(p.type, "task"); XCTAssertEqual(p.parent, "1a2b")
        XCTAssertEqual(p.title, "Wire up the sync worker")
        XCTAssertEqual(p.modifiedInstant, JavaInstant.parse("2026-08-25T14:22:31.402Z"))
        XCTAssertEqual(p.device, "sm-s921b")
    }

    func testEveryBlockTypeIsRecognised() {
        let b = PageCodec.decode(canonical).blocks
        XCTAssertEqual(b.map(\.nodeType), [NodeType.heading, NodeType.paragraph, NodeType.task, NodeType.task, NodeType.ink])
        XCTAssertEqual(b[3].indent, 1)
        if case let .task(t) = b[3] { XCTAssertEqual(t.status, .done) } else { XCTFail() }
    }

    // MARK: what an edit is allowed to disturb

    func testAModifiedBlockIsReRenderedEvenThoughItsRawLineIsStale() {
        var p = PageCodec.decode(canonical)
        guard case let .prose(_, i, raw) = p.blocks[1] else { return XCTFail() }
        p.blocks[1] = .prose("Rewritten prose.", indent: i, raw: raw)
        let out = PageCodec.encode(p)
        XCTAssertTrue(out.contains("Rewritten prose."))
        XCTAssertFalse(out.contains("A list holds tasks."), "stale line survived")
    }

    func testAMovedNumberedItemIsRenumbered() {
        let p = PageCodec.decode(header + "1. first\n2. second\n3. third\n")
        var r = p; r.blocks = [p.blocks[2], p.blocks[0], p.blocks[1]]
        let lines = PageCodec.encode(r).split(separator: "\n").map(String.init).filter { $0.contains(". ") }
        XCTAssertEqual(lines, ["1. third", "2. first", "3. second"])
    }

    func testEditingOneBlockLeavesItsNeighboursByteIdentical() {
        var p = PageCodec.decode(canonical)
        guard case var .task(t) = p.blocks[2] else { return XCTFail() }
        t.status = .done
        p.blocks[2] = .task(t)
        let out = PageCodec.encode(p)
        XCTAssertTrue(out.contains("- [x] Tasks can nest ^9f1e"))
        for line in canonical.split(separator: "\n").map(String.init) where !KText.isBlank(line) && !line.contains("Tasks can nest") {
            XCTAssertTrue(out.contains(line), "lost or reformatted: \(line)")
        }
    }

    func testAnUntouchedHandEditedLineKeepsItsOwnSpacing() {
        var p = PageCodec.decode(header + "-  [ ] loosely typed ^z1\n- [ ] normal ^z2\n")
        guard case var .task(t) = p.blocks[1] else { return XCTFail() }
        t.title = "renamed"; p.blocks[1] = .task(t)
        let out = PageCodec.encode(p)
        XCTAssertTrue(out.contains("-  [ ] loosely typed ^z1"), "reformatted a line it did not touch")
        XCTAssertTrue(out.contains("- [ ] renamed ^z2"))
    }

    // MARK: the right-to-left token scan

    func testTrailingTokensAreParsedAndAHashInsideATitleIsNot() {
        let t = one("- [ ] Buy milk ^a1 due:2026-08-26 !high #shop @batunii")
        XCTAssertEqual(t.title, "Buy milk"); XCTAssertEqual(t.id, "a1"); XCTAssertEqual(t.priority, "high")
        XCTAssertEqual(t.labels, ["shop"]); XCTAssertEqual(t.assignee, "batunii")
        XCTAssertEqual(t.due?.value, .allDay(LocalDate(year: 2026, month: 8, day: 26)))
    }

    func testATokenLookingWordMidTitleStaysInTheTitle() {
        let t = one("- [ ] Buy #2 pencils ^a1")
        XCTAssertEqual(t.title, "Buy #2 pencils"); XCTAssertEqual(t.id, "a1"); XCTAssertTrue(t.labels.isEmpty)
    }

    func testLabelsKeepTheirWrittenOrder() {
        XCTAssertEqual(one("- [ ] x ^i #one #two #three").labels, ["one", "two", "three"])
    }

    func testATaskWithNoTokensAtAllIsJustATitle() {
        let t = one("- [ ] Just a task")
        XCTAssertEqual(t.title, "Just a task"); XCTAssertEqual(t.id, ""); XCTAssertNil(t.due)
    }

    // MARK: due encoding

    func testAllDayAndTimedAreDistinguishedByTheDateTimeItself() {
        XCTAssertEqual(one("- [ ] x ^i due:2026-08-26").due?.value, .allDay(LocalDate(year: 2026, month: 8, day: 26)))
        XCTAssertEqual(one("- [ ] x ^i due:2026-08-26T09:00:00Z").due?.value, .at(JavaInstant.parse("2026-08-26T09:00:00Z")!.date))
    }

    func testANegativeReminderOffsetSurvives() {
        let due = one("- [ ] x ^i due:2026-08-26+r-540").due
        XCTAssertEqual(due?.value, .allDay(LocalDate(year: 2026, month: 8, day: 26)))
        XCTAssertEqual(due?.reminders, [-540])
        XCTAssertTrue(render(TaskRef(id: "i", title: "x", due: due!)).hasSuffix("due:2026-08-26+r-540"))
    }

    func testAMalformedDueIsLeftInTheTitleRatherThanSwallowed() {
        let t = one("- [ ] pay rent due:not-a-date ^a1")
        XCTAssertEqual(t.title, "pay rent due:not-a-date"); XCTAssertNil(t.due)
    }

    // MARK: forgiveness

    func testAnUnclassifiableLineBecomesProseHoldingItsOriginalText() {
        XCTAssertEqual(PageCodec.decode(header + "| a | markdown | table |\n").blocks, [.prose("| a | markdown | table |", raw: "| a | markdown | table |")])
    }

    func testFrontmatterFromANewerAppSurvivesARoundTrip() {
        let p = PageCodec.decode("---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\nfuture_field: keep me\n---\nhello\n")
        XCTAssertEqual(p.unknownKeys.map { "\($0.0)=\($0.1)" }, ["future_field=keep me"])
        XCTAssertTrue(PageCodec.encode(p).contains("future_field: keep me"))
    }

    func testAFileWithNoFrontmatterStillParses() {
        let p = PageCodec.decode("# just a heading\n")
        XCTAssertEqual(p.id, ""); XCTAssertEqual(p.blocks.count, 1)
    }

    func testWindowsLineEndingsAreAccepted() {
        let p = PageCodec.decode(canonical.replacingOccurrences(of: "\n", with: "\r\n"))
        XCTAssertEqual(p.blocks.count, 5); XCTAssertEqual(p.id, "7c3f")
        // web: and it comes back as the canonical LF text.
        XCTAssertEqual(PageCodec.encode(p), canonical)
    }

    // MARK: rendering rules

    func testNumberedItemsAreRenumberedFromTheirOwnRun() {
        let out = PageCodec.encode(page([.numbered("first"), .numbered("second"), .numbered("third"), .prose("a break"), .numbered("restarts")]))
        for s in ["1. first", "2. second", "3. third", "1. restarts"] { XCTAssertTrue(out.contains(s), s) }
    }

    func testARenderedTaskAlwaysProducesTheSameBytes() {
        let t = TaskRef(id: "a1", title: "x", due: DueSpec(.allDay(LocalDate(year: 2026, month: 1, day: 1))),
                        deadline: LocalDate(year: 2026, month: 2, day: 2), priority: "high", labels: ["b", "a"], assignee: "me")
        XCTAssertEqual(render(t), render(t))
        XCTAssertEqual(render(t), "- [ ] x ^a1 due:2026-01-01 deadline:2026-02-02 !high #b #a @me")
    }

    func testIndentIsWrittenBackAtTheDepthItWasRead() {
        let b = PageCodec.decode(header + "» » deep prose\n").blocks[0]
        XCTAssertEqual(b.indent, 2)
        XCTAssertTrue(PageCodec.encode(page([.prose("deep prose", indent: 2)])).contains("» » deep prose"))
    }

    func testMarkdownBlockquotesAndCalloutsStayProseAtDepthZero() {
        for line in ["> a quote", ">> a nested quote", "> [!NOTE] a callout"] {
            let b = PageCodec.decode(header + line + "\n").blocks[0]
            XCTAssertEqual(b, .prose(line, raw: line)); XCTAssertEqual(b.indent, 0)
        }
    }

    func testInkAndImageBlocksKeepTheirIds() {
        let blocks = PageCodec.decode(header + "![[ink:abc]]\n![[image:content://x/y]]\n").blocks
        XCTAssertEqual(blocks.map(\.strippingRaw), [.ink(id: "abc"), .image(uri: "content://x/y")])
    }

    func testBulletsAreNotMistakenForTasks() {
        XCTAssertEqual(PageCodec.decode(header + "- plain bullet\n").blocks.map(\.strippingRaw), [.bullet("plain bullet")])
    }

    func testAnEmptyTaskIsStillATaskWithOrWithoutItsTrailingSpace() {
        for line in ["- [ ]", "- [ ] ", "- [x]", "- [~] "] {
            guard case let .task(t) = PageCodec.decodeBlock(line) else { XCTFail(line); continue }
            XCTAssertEqual(t.title, "")
        }
    }

    func testAnEmptyTaskRoundTripsIdAndAll() {
        for task in [TaskRef(id: "", title: ""), TaskRef(id: "abc-123", title: ""), TaskRef(id: "abc-123", title: "", status: .inProgress)] {
            let line = PageCodec.encodeBlock(.task(task))
            XCTAssertEqual(line, KText.trimEnd(line))
            XCTAssertEqual(PageCodec.decodeBlock(line).strippingRaw, .task(task))
        }
    }

    func testEveryBlockKindSurvivesAFullPageRoundTrip() {
        let blocks: [Block] = [.heading(""), .bullet(""), .numbered(""), .prose(""), .task(TaskRef(id: "t1", title: "")),
                               .task(TaskRef(id: "t2", title: "named")), .ink(id: "ink-1"), .image(uri: "pic-1")]
        XCTAssertEqual(PageCodec.decode(PageCodec.encode(page(blocks))).blocks.map(\.nodeType), blocks.map(\.nodeType))
    }

    func testAnEmptyBlockDoesNotComeBackHoldingWhitespace() {
        let line = PageCodec.encodeBlock(.prose(""))
        XCTAssertFalse(line.isEmpty)
        XCTAssertEqual(PageCodec.decodeBlock(line).text, "")
        XCTAssertEqual(PageCodec.decode(PageCodec.encode(page([.prose(""), .prose("after")]))).blocks.map(\.text), ["", "after"])
        // web: and it is a lone space on its own line.
        XCTAssertTrue(PageCodec.encode(page([.prose("")])).hasSuffix("---\n \n"))
    }

    // MARK: links on a task line

    func testALinkInATaskTitleIsNotReadAsTrailingTokens() {
        let t = one("- [ ] See [[Buy milk #2|^abc]] first ^t1 #real !High")
        XCTAssertEqual(t.title, "See [[Buy milk #2|^abc]] first"); XCTAssertEqual(t.id, "t1")
        XCTAssertEqual(t.labels, ["real"]); XCTAssertEqual(t.priority, "High")
    }

    func testALinkAtTheVeryEndOfATitleSurvivesTheRoundTrip() {
        let line = "- [ ] Ask [[Bob|^9f1e]] ^t2 @batunii"
        let t = one(line)
        XCTAssertEqual(t.title, "Ask [[Bob|^9f1e]]"); XCTAssertEqual(t.assignee, "batunii")
        XCTAssertEqual(PageCodec.encodeBlock(Block.task(t).strippingRaw), line)
    }

    func testAnAtSignInsideALinkIsNotAnAssignee() {
        let t = one("- [ ] Ping [[ask @bob|^x]]")
        XCTAssertEqual(t.title, "Ping [[ask @bob|^x]]"); XCTAssertNil(t.assignee)
        // web: nor inside something that only looks like one.
        let u = one("- [ ] ping [[the @team page]] ^a1")
        XCTAssertNil(u.assignee); XCTAssertEqual(u.title, "ping [[the @team page]]")
    }

    // MARK: a list's appearance

    func testAListKeepsItsIconAndItsColour() {
        let text = PageCodec.encode(PageDoc(id: "L1", type: "list", parent: nil, title: "Shopping", modifiedInstant: .epoch,
                                            device: nil, blocks: [], icon: "🛒", color: "Teal"))
        XCTAssertTrue(text.contains("icon: 🛒")); XCTAssertTrue(text.contains("color: Teal"))
        let back = PageCodec.decode(text)
        XCTAssertEqual(back.icon, "🛒"); XCTAssertEqual(back.color, "Teal")
    }

    func testAnIconWithoutAColourAndAColourWithoutAnIconBothRoundTrip() {
        let iconOnly = PageDoc(id: "L1", type: "list", parent: nil, title: nil, modifiedInstant: .epoch, device: nil, blocks: [], icon: "🎯")
        let a = PageCodec.decode(PageCodec.encode(iconOnly))
        XCTAssertEqual(a.icon, "🎯"); XCTAssertNil(a.color)
        var colourOnly = iconOnly; colourOnly.icon = nil; colourOnly.color = "Plum"
        let b = PageCodec.decode(PageCodec.encode(colourOnly))
        XCTAssertNil(b.icon); XCTAssertEqual(b.color, "Plum")
    }

    func testIconIsNotMistakenForAKeyThisVersionDoesNotUnderstand() {
        let p = PageCodec.decode("---\nid: L1\ntype: list\nicon: 📚\nmodified_at: 2026-01-01T00:00:00Z\n---\n")
        XCTAssertEqual(p.icon, "📚"); XCTAssertTrue(p.unknownKeys.isEmpty)
        XCTAssertEqual(PageCodec.encode(p).split(separator: "\n").filter { $0.hasPrefix("icon: ") }.count, 1)
    }

    // MARK: several reminders on one task

    func testATaskCanCarrySeveralReminders() {
        let due = one("- [ ] x ^i due:2026-08-26T09:00:00Z+r1440,30").due
        XCTAssertEqual(due?.reminders, [1440, 30])
        XCTAssertTrue(render(TaskRef(id: "i", title: "x", due: due!)).hasSuffix("+r1440,30"))
    }

    func testOneReminderIsWrittenExactlyAsItAlwaysWas() {
        let due = one("- [ ] x ^i due:2026-08-26T09:00:00Z+r30").due
        XCTAssertEqual(due?.reminders, [30])
        XCTAssertTrue(render(TaskRef(id: "i", title: "x", due: due!)).hasSuffix("+r30"))
    }

    func testRemindersComeBackInACanonicalOrder() {
        XCTAssertEqual(one("- [ ] x ^i due:2026-08-26T09:00:00Z+r30,1440,30").due?.reminders, [1440, 30])
    }

    func testABrokenReminderTailDoesNotHalfParse() {
        XCTAssertNil(one("- [ ] x ^i due:2026-08-26T09:00:00Z+r30,x").due)
    }

    func testNoReminderWritesNoTail() {
        let due = one("- [ ] x ^i due:2026-08-26T09:00:00Z").due
        XCTAssertEqual(due?.reminders, [])
        XCTAssertFalse(render(TaskRef(id: "i", title: "x", due: due!)).contains("+r"))
    }

    // MARK: web — cases the Kotlin suite does not spell out

    func testWebHandSpacedLinePassesThrough() {
        let odd = "- [ ]    lots   of   spacing   ^a1"
        XCTAssertEqual(PageCodec.encodeBlock(PageCodec.decodeBlock(odd)), odd)
    }

    func testWebSwappedNumberedItemsRenumber() {
        let p = PageCodec.decode(header + "1. first\n2. second\n")
        var r = p; r.blocks = [p.blocks[1], p.blocks[0]]
        let out = PageCodec.encode(r)
        XCTAssertTrue(out.contains("1. second")); XCTAssertTrue(out.contains("2. first"))
    }

    func testWebEveryTokenRendersInItsFixedOrder() {
        let expected = "- [x] x ^a1 due:2026-08-26 deadline:2026-08-30 done:2026-08-27 !high #one #two @batunii"
        let t = TaskRef(id: "a1", title: "x", status: .done, due: DueSpec(.allDay(LocalDate("2026-08-26")!)), deadline: LocalDate("2026-08-30"),
                        priority: "high", labels: ["one", "two"], assignee: "batunii", doneAt: LocalDate("2026-08-27"))
        XCTAssertEqual(PageCodec.encodeBlock(.task(t)), expected)
        XCTAssertEqual(PageCodec.encodeBlock(PageCodec.decodeBlock(expected)), expected)
    }

    func testWebDoneIsWrittenOnlyOnAFinishedTask() {
        XCTAssertFalse(PageCodec.encodeBlock(.task(TaskRef(id: "a1", title: "x", doneAt: LocalDate("2026-08-27")))).contains("done:"))
    }

    func testWebNumberedRunsRestartAfterProse() {
        let out = PageCodec.encode(PageCodec.decode(header + "1. a\n2. b\n\nprose\n\n1. c\n"))
        for s in ["1. a", "2. b", "1. c"] { XCTAssertTrue(out.contains(s)) }
    }

    func testWebDeepIndentedTaskPassesThrough() {
        let line = "» » - [ ] deep ^a1"
        XCTAssertEqual(PageCodec.decodeBlock(line).indent, 2)
        XCTAssertEqual(PageCodec.encodeBlock(PageCodec.decodeBlock(line)), line)
    }

    func testWebEmptyTaskWithAnId() {
        let line = "- [ ] ^a1"
        XCTAssertEqual(PageCodec.decodeBlock(line).text, "")
        XCTAssertEqual(PageCodec.encodeBlock(PageCodec.decodeBlock(line)), line)
    }

    // MARK: tokenStart

    func testTokenStartIsWhereTheTitleEnds() {
        XCTAssertEqual(PageCodec.tokenStart("Buy milk @sai"), 9)
        XCTAssertEqual(PageCodec.tokenStart("Buy #2 pencils"), 14)
        XCTAssertEqual(PageCodec.tokenStart("  Buy milk #shop"), 11)
        XCTAssertEqual(PageCodec.tokenStart(""), 0)
        // In UTF-16 units, as a text field counts: the emoji is two.
        XCTAssertEqual(PageCodec.tokenStart("😀 x @sai"), 5)
    }
}

// MARK: - pinned

/// `PinnedCodecTest.kt`.
final class PinnedCodecPortTests: XCTestCase {
    func page(_ pinned: Bool?, type: String = "smart_list", parent: String? = nil) -> PageDoc {
        PageDoc(id: "n1", type: type, parent: parent, title: "Today", modifiedInstant: JavaInstant.parse("2026-09-23T10:00:00Z")!,
                device: "test", blocks: [], pinned: pinned)
    }
    func roundTrip(_ d: PageDoc) -> PageDoc { PageCodec.decode(PageCodec.encode(d)) }

    func testAPinnedListSaysSoInItsFile() {
        XCTAssertTrue(PageCodec.encode(page(true)).contains("pinned: true"))
        XCTAssertEqual(roundTrip(page(true)).pinned, true)
    }

    func testUnpinningIsWrittenDownNotLeftOut() {
        XCTAssertTrue(PageCodec.encode(page(false)).contains("pinned: false"))
        XCTAssertEqual(roundTrip(page(false)).pinned, false)
    }

    func testAFileThatNeverMentionsPinningSaysNothingRatherThanNo() {
        XCTAssertFalse(PageCodec.encode(page(nil)).contains("pinned:"))
        XCTAssertNil(roundTrip(page(nil)).pinned)
    }

    func testAPageWrittenBeforePinningExistedDecodesAsUnspecified() {
        XCTAssertNil(PageCodec.decode("---\nid: n1\ntype: smart_list\ntitle: Today\nmodified_at: 2026-09-23T10:00:00Z\n---").pinned)
    }

    func testAValueThisBuildDoesNotUnderstandIsUnspecifiedRatherThanAGuess() {
        XCTAssertNil(PageCodec.decode("---\nid: n1\ntype: smart_list\ntitle: Today\npinned: maybe\nmodified_at: 2026-09-23T10:00:00Z\n---").pinned)
        // And `pinned` is a known key, so it is never also carried as an unknown one.
        XCTAssertTrue(PageCodec.decode("---\nid: n1\npinned: maybe\n---").unknownKeys.isEmpty)
    }

    func testTheFallbackIsExactlyTheRuleTheSectionUsedToBe() {
        XCTAssertTrue(page(nil).effectivePinned)
        XCTAssertFalse(page(nil, parent: "g1").effectivePinned)
        XCTAssertFalse(page(nil, type: "list").effectivePinned)
        XCTAssertFalse(page(false).effectivePinned)
        XCTAssertTrue(page(true, type: "list").effectivePinned)
    }

    /// Where it goes: after `color:`, before `modified_at:` — the order `PageCodec.encode` writes.
    func testPinnedSitsBetweenColourAndModifiedAt() {
        var p = page(true); p.color = "Teal"; p.icon = "📥"
        let lines = PageCodec.encode(p).split(separator: "\n").map(String.init)
        XCTAssertEqual(lines, ["---", "id: n1", "type: smart_list", "title: Today", "icon: 📥", "color: Teal", "pinned: true",
                               "modified_at: 2026-09-23T10:00:00Z", "device: test", "---"])
    }
}

// MARK: - events

/// `EventAuthorTest.kt`, `EventCodecTest.kt`, `ExternalNoteCodecTest.kt`, `TokenValueTest.kt`.
final class EventCodecPortTests: XCTestCase {
    func parse(_ line: String) -> EventRef? { if case let .event(e) = PageCodec.decodeBlock(line) { return e }; return nil }
    func line(_ e: EventRef) -> String { PageCodec.encodeBlock(.event(e)) }

    /// Parse, render, parse again — the second block must equal the first.
    @discardableResult
    func roundTrip(_ l: String, file: StaticString = #filePath, lineNo: UInt = #line) -> EventRef {
        guard let once = parse(l) else { XCTFail("not an event: \(l)", file: file, line: lineNo); return EventRef(id: "", title: "", time: EventTime(start: LocalDateTime("2026-01-01")!, end: LocalDateTime("2026-01-01")!)) }
        var bare = once; bare.raw = nil
        let rendered = line(bare)
        guard var twice = parse(rendered) else { XCTFail("re-render did not parse: \(rendered)", file: file, line: lineNo); return once }
        twice.raw = nil
        XCTAssertEqual(bare, twice, "not a fixed point: \(l) -> \(rendered)", file: file, line: lineNo)
        return once
    }
    func rebuilt(_ e: EventRef) -> String { var x = e; x.raw = nil; return line(x) }

    // author (EventAuthorTest)

    let start = LocalDateTime("2026-09-23T11:00")!
    func authored(_ author: String?) -> EventRef {
        EventRef(id: "79d24109", title: "Intro Call", time: EventTime(start: start, end: start.adding(seconds: 1800)),
                 external: ExternalRef("_60q30c1g"), author: author)
    }

    func testAnAuthoredMeetingSaysWhoseItIs() {
        XCTAssertTrue(line(authored("batunii")).contains("by:batunii"))
        XCTAssertEqual(parse(line(authored("batunii")))?.author, "batunii")
    }

    func testAnUnclaimedMeetingWritesNoTokenAtAll() {
        XCTAssertFalse(line(authored(nil)).contains("by:"))
        XCTAssertNil(parse(line(authored(nil)))?.author)
    }

    func testTheAuthorSurvivesBesideTheExternalReference() {
        let back = parse(line(authored("batunii")))
        XCTAssertEqual(back?.external?.uid, "_60q30c1g"); XCTAssertEqual(back?.author, "batunii"); XCTAssertEqual(back?.title, "Intro Call")
    }

    func testALineWrittenBeforeAuthorshipExistedStillParses() {
        let back = parse("@ 2026-09-23T11:00/PT30M Intro Call ^79d24109 ext:_60q30c1g")
        XCTAssertNil(back?.author); XCTAssertEqual(back?.external?.uid, "_60q30c1g")
    }

    /// The audit's case: Android's canonical line, which iOS used to stop reading at `by:` — and so
    /// took the id and the meeting for title words.
    func testTheCanonicalAndroidLineKeepsItsIdAndMeeting() {
        let l = "@ 2026-09-23T11:00/PT30M Intro Call ^79d24109 ext:_60q30c1g by:batunii remind:15"
        let e = parse(l)!
        XCTAssertEqual(e.id, "79d24109"); XCTAssertEqual(e.title, "Intro Call"); XCTAssertEqual(e.external?.uid, "_60q30c1g")
        XCTAssertEqual(e.author, "batunii"); XCTAssertEqual(e.reminderMin, 15)
        XCTAssertEqual(rebuilt(e), l)
    }

    /// Android's quirk, reproduced: `by:` is encoded on the way out and not decoded on the way in, so
    /// an author with a space grows a layer of `%25` each time the line is rebuilt.
    func testByIsEncodedOnWriteButNotDecodedOnRead() {
        let e = authored("Sai E")
        XCTAssertTrue(line(e).contains("by:Sai%20E"))
        let once = parse(line(e))!
        XCTAssertEqual(once.author, "Sai%20E")
        XCTAssertTrue(rebuilt(once).contains("by:Sai%2520E"))
        // An unedited line is still written back as it was — raw preservation.
        XCTAssertEqual(PageCodec.encodeBlock(.event(once)), line(e))
    }

    // the when-slot (EventCodecTest)

    func testAnAllDayEventIsABareDate() {
        let e = roundTrip("@ 2026-09-11 Deirdre's birthday")
        XCTAssertTrue(e.time.allDay); XCTAssertEqual(e.time.start, LocalDateTime("2026-09-11T00:00"))
        XCTAssertEqual(e.time.end, LocalDateTime("2026-09-12T00:00")); XCTAssertEqual(e.title, "Deirdre's birthday")
    }

    func testAnAllDaySpanReadsInclusivelyAndStoresExclusively() {
        let e = roundTrip("@ 2026-09-11/2026-09-13 Conference")
        XCTAssertTrue(e.time.allDay); XCTAssertEqual(e.time.end, LocalDateTime("2026-09-14T00:00"))
        XCTAssertEqual(e.time.duration, ISODuration(seconds: 3 * 86_400))
        XCTAssertEqual(rebuilt(e), "@ 2026-09-11/2026-09-13 Conference")
    }

    func testATimedEventCarriesADuration() {
        let e = roundTrip("@ 2026-09-11T14:00/PT1H Design review")
        XCTAssertFalse(e.time.allDay); XCTAssertEqual(e.time.duration, .hours(1))
    }

    func testAnExplicitEndIsAcceptedAndRenderedAsADuration() {
        let e = parse("@ 2026-09-11T14:00/2026-09-11T15:30 Design review")!
        XCTAssertEqual(e.time.duration, .minutes(90))
        XCTAssertEqual(rebuilt(e), "@ 2026-09-11T14:00/PT1H30M Design review")
    }

    func testAHandWrittenExplicitEndIsLeftAlone() {
        let l = "@ 2026-09-11T14:00/2026-09-11T15:30 Design review"
        XCTAssertEqual(line(parse(l)!), l)
    }

    func testAMomentHasNoTail() {
        let e = roundTrip("@ 2026-09-11T14:00 Doorbell")
        XCTAssertTrue(e.time.isInstantaneous); XCTAssertEqual(rebuilt(e), "@ 2026-09-11T14:00 Doorbell")
    }

    func testAZoneSurvivesBracketsAndSlashesAndAll() {
        let e = roundTrip("@ 2026-09-11T14:00[Europe/Dublin]/PT1H Call with Tokyo")
        XCTAssertEqual(e.time.zone, "Europe/Dublin"); XCTAssertEqual(e.time.duration, .hours(1)); XCTAssertEqual(e.title, "Call with Tokyo")
    }

    func testNoZoneMeansFloating() { XCTAssertNil(roundTrip("@ 2026-09-11T09:00/PT15M Morning pages").time.zone) }

    func testAnEndBeforeItsStartIsNotAnEvent() { XCTAssertNil(parse("@ 2026-09-11T15:00/2026-09-11T14:00 Backwards")) }

    func testEveryTokenRoundTrips() {
        let e = roundTrip("@ 2026-09-11T14:00/PT1H Design review ^e1 rrule:FREQ=WEEKLY loc:Room4 remind:15 !High #work @sam @deirdre")
        XCTAssertEqual(e.id, "e1"); XCTAssertEqual(e.title, "Design review"); XCTAssertEqual(e.rrule, "FREQ=WEEKLY")
        XCTAssertEqual(e.location, "Room4"); XCTAssertEqual(e.reminderMin, 15); XCTAssertEqual(e.priority, "High")
        XCTAssertEqual(e.labels, ["work"]); XCTAssertEqual(e.attendees, ["sam", "deirdre"])
    }

    func testTheTitleKeepsWordsThatOnlyLookLikeTokens() {
        XCTAssertEqual(roundTrip("@ 2026-09-11 Buy #2 pencils").title, "Buy #2 pencils")
    }

    func testAnUnknownTokenStaysInTheTitleRatherThanVanishing() {
        let e = parse("@ 2026-09-11 Standup zoom:abc123")!
        XCTAssertEqual(e.title, "Standup zoom:abc123")
        XCTAssertEqual(line(e), "@ 2026-09-11 Standup zoom:abc123")
    }

    func testASeriesLineCarriesItsRule() {
        let e = roundTrip("@ 2026-09-07T09:00/PT15M Standup ^s1 rrule:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        XCTAssertEqual(e.rrule, "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"); XCTAssertNil(e.series)
    }

    func testAMovedOccurrenceNamesTheOneItReplaces() {
        let e = roundTrip("@ 2026-10-28T09:30/PT15M Standup ^s1x1 series:s1@2026-10-28T09:00")
        XCTAssertEqual(e.series?.id, "s1"); XCTAssertEqual(e.series?.originalStart, LocalDateTime("2026-10-28T09:00")); XCTAssertFalse(e.cancelled)
    }

    func testACancellationSittingOnItsOwnStartNeedsNoAtSign() {
        let e = roundTrip("@ 2026-11-04T09:00/PT15M Standup ^s1x2 series:s1 cancelled")
        XCTAssertEqual(e.series?.id, "s1"); XCTAssertNil(e.series?.originalStart); XCTAssertTrue(e.cancelled)
    }

    func testAnOverrideThatDidNotMoveDropsTheRedundantOriginal() {
        XCTAssertEqual(rebuilt(parse("@ 2026-11-04T09:00/PT15M Standup ^s1x2 series:s1@2026-11-04T09:00")!),
                       "@ 2026-11-04T09:00/PT15M Standup ^s1x2 series:s1")
    }

    func testASittingPointsAtTheTaskItIsTimeFor() {
        let e = roundTrip("@ 2026-09-12T14:00/PT2H ^s1 for:t1")
        XCTAssertEqual(e.forTaskId, "t1"); XCTAssertEqual(e.title, "")
    }

    func testASittingWithATitleKeepsIt() {
        XCTAssertEqual(roundTrip("@ 2026-09-12T14:00/PT2H Deck, second go ^s1 for:t1").title, "Deck, second go")
    }

    func testAnOrdinaryEventHasNoReferent() { XCTAssertNil(roundTrip("@ 2026-09-11T14:00/PT1H Design review ^e1").forTaskId) }

    func testForAndRruleAndAReminderCoexist() {
        let e = roundTrip("@ 2026-09-12T09:00/PT1H ^s2 rrule:FREQ=WEEKLY for:t1 remind:5")
        XCTAssertEqual(e.forTaskId, "t1"); XCTAssertEqual(e.rrule, "FREQ=WEEKLY"); XCTAssertEqual(e.reminderMin, 5)
    }

    func testATaskAndItsTwoSittingsSurviveAWholePageRoundTrip() {
        let page = "---\nid: p1\ntype: list\nmodified_at: 2026-09-12T10:00:00Z\n---\n- [ ] Write the deck ^t1 due:2026-09-18\n@ 2026-09-12T14:00/PT2H ^s1 for:t1\n@ 2026-09-13T09:00/PT1H ^s2 for:t1\n"
        let doc = PageCodec.decode(page)
        XCTAssertEqual(doc.blocks.compactMap { if case let .event(e) = $0 { return e.forTaskId }; return nil }, ["t1", "t1"])
        XCTAssertEqual(PageCodec.encode(doc), page)
    }

    func testAnEventCanSayWhatColourItWears() { XCTAssertEqual(roundTrip("@ 2026-09-13T14:00/PT1H Design review ^e1 col:Teal").color, "Teal") }

    func testAColourThisBuildDoesNotKnowIsKept() { XCTAssertEqual(roundTrip("@ 2026-09-13T14:00/PT1H Design review ^e1 col:Vermilion").color, "Vermilion") }

    func testNoColourIsTheOrdinaryCaseAndWritesNothing() {
        XCTAssertNil(roundTrip("@ 2026-09-13T14:00/PT1H Design review ^e1").color)
        XCTAssertEqual(rebuilt(parse("@ 2026-09-13T14:00/PT1H Design review ^e1")!), "@ 2026-09-13T14:00/PT1H Design review ^e1")
    }

    func testASittingCanWearAColourToo() {
        let e = roundTrip("@ 2026-09-13T14:00/PT2H ^s1 for:t1 col:Plum")
        XCTAssertEqual(e.forTaskId, "t1"); XCTAssertEqual(e.color, "Plum")
    }

    func testABulletIsStillABullet() {
        XCTAssertEqual(PageCodec.decodeBlock("- Buy milk").nodeType, NodeType.bullet)
        XCTAssertEqual(PageCodec.decodeBlock("* Buy milk").nodeType, NodeType.paragraph)
    }

    func testAnAtSignLineWithNoReadableTimeIsProse() {
        let b = PageCodec.decodeBlock("@ sometime next week, ask Sam")
        XCTAssertEqual(b.nodeType, NodeType.paragraph); XCTAssertEqual(PageCodec.encodeBlock(b), "@ sometime next week, ask Sam")
    }

    func testAnEmailAddressAtTheStartOfALineIsNotAnEvent() {
        XCTAssertEqual(PageCodec.decodeBlock("@sam owes me a review").nodeType, NodeType.paragraph)
    }

    func testAnEventSurvivesAWholePageRoundTrip() {
        let page = "---\nid: p1\ntype: list\nmodified_at: 2026-09-09T10:00:00Z\n---\n# Week\n\n- [ ] Write the deck ^t1 due:2026-09-12\n@ 2026-09-11T14:00/PT1H Design review ^e1 loc:Room4 @sam\n@ 2026-09-07T09:00/PT15M Standup ^s1 rrule:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR\n"
        let doc = PageCodec.decode(page)
        XCTAssertEqual(doc.blocks.filter { $0.nodeType == NodeType.event }.count, 2)
        XCTAssertEqual(PageCodec.encode(doc), page)
    }

    // ExternalNoteCodecTest

    func exact(_ l: String) -> EventRef {
        let e = parse(l)!
        XCTAssertEqual(rebuilt(e), l, "not a fixed point")
        return e
    }

    func testANoteNamesTheMeetingItIsAbout() {
        let e = exact("@ 2026-09-16T14:00/PT1H Design review ^n1 ext:abc123")
        XCTAssertEqual(e.external?.uid, "abc123"); XCTAssertNil(e.external?.occurrence)
    }

    func testAUidContainingAnAtSignSurvivesWhole() {
        let e = exact("@ 2026-09-16T14:00/PT1H Design review ^n1 ext:abc123@google.com")
        XCTAssertEqual(e.external?.uid, "abc123@google.com"); XCTAssertNil(e.external?.occurrence)
    }

    func testOneOccurrenceOfARepeatingMeetingNamesWhich() {
        let e = exact("@ 2026-09-16T09:00/PT30M Standup ^n2 ext:abc123@google.com@2026-09-16T09:00")
        XCTAssertEqual(e.external?.uid, "abc123@google.com"); XCTAssertEqual(e.external?.occurrence, LocalDateTime("2026-09-16T09:00"))
    }

    func testAUidWithNoAtSignStillTakesAnOccurrence() {
        let e = exact("@ 2026-09-16T09:00/PT30M Standup ^n3 ext:plainuid@2026-09-16T09:00")
        XCTAssertEqual(e.external?.uid, "plainuid"); XCTAssertEqual(e.external?.occurrence, LocalDateTime("2026-09-16T09:00"))
    }

    func testAnOrdinaryEventAnnotatesNothing() { XCTAssertNil(exact("@ 2026-09-16T14:00/PT1H Design review ^e1").external) }

    func testAnEmptyExtIsNotAReferenceAndStaysInTheTitle() {
        let e = parse("@ 2026-09-16T14:00/PT1H Design review ext:")!
        XCTAssertNil(e.external); XCTAssertEqual(e.title, "Design review ext:")
    }

    func testANoteCanCarryEverythingElseALineCan() {
        let e = exact("@ 2026-09-16T14:00/PT1H Design review ^n1 col:Teal ext:abc123@google.com remind:15")
        XCTAssertEqual(e.external?.uid, "abc123@google.com"); XCTAssertEqual(e.color, "Teal"); XCTAssertEqual(e.reminderMin, 15)
    }

    func testANoteSurvivesAWholePageRoundTrip() {
        let page = "---\nid: p1\ntype: list\nmodified_at: 2026-09-16T10:00:00Z\n---\n@ 2026-09-16T14:00/PT1H Design review ^n1 ext:abc123@google.com\n"
        XCTAssertEqual(PageCodec.encode(PageCodec.decode(page)), page)
    }

    // TokenValueTest

    func valued(location: String? = nil, uid: String? = nil) -> EventRef {
        EventRef(id: "n1", title: "Pluto x Napkin", time: EventTime(start: LocalDateTime("2026-09-16T10:00")!, end: LocalDateTime("2026-09-16T11:00")!),
                 external: uid.map { ExternalRef($0) }, location: location)
    }
    func valueTrip(_ e: EventRef) -> EventRef {
        let l = rebuilt(e)
        XCTAssertFalse(l.contains("\n"), "a line must stay one line: \(l)")
        return parse(l)!
    }

    func testALocationWithSpacesKeepsTheId() {
        let back = valueTrip(valued(location: "Microsoft Teams Meeting", uid: "abc@google.com"))
        XCTAssertEqual(back.id, "n1"); XCTAssertEqual(back.location, "Microsoft Teams Meeting")
        XCTAssertEqual(back.external?.uid, "abc@google.com"); XCTAssertEqual(back.title, "Pluto x Napkin")
    }

    func testTheAwkwardValuesARealCalendarHandsBackAllSurvive() {
        for w in ["Room 4", "Napkin HQ, 3rd floor", "Microsoft Teams Meeting", "50% full", "https://meet.google.com/abc-def", "Café — back room"] {
            let back = valueTrip(valued(location: w, uid: "u@example.com"))
            XCTAssertEqual(back.location, w); XCTAssertEqual(back.id, "n1", "the id must survive \(w)")
        }
    }

    func testAPercentInALocationIsNotDoubledOnTheWayBack() {
        XCTAssertEqual(valueTrip(valueTrip(valued(location: "50% full"))).location, "50% full")
    }

    func testAnOrdinaryLocationIsWrittenPlainly() { XCTAssertTrue(line(valued(location: "Room4")).contains("loc:Room4")) }

    func testAUidWithASpaceInItSurvivesToo() {
        let back = valueTrip(valued(uid: "weird uid@example.com"))
        XCTAssertEqual(back.external?.uid, "weird uid@example.com"); XCTAssertEqual(back.id, "n1")
    }
}

// MARK: - links

/// The codec half of `LinksTest.kt` — `collapse` and its offset tables are the editor's, not the
/// format's, and iOS has no port of them yet.
final class LinksPortTests: XCTestCase {
    let id = "9f1e2c3d-4a5b-6c7d-8e9f-0a1b2c3d4e5f"

    func testALinkIsItsLabelAndItsTarget() {
        let text = "See [[Call Bob|^\(id)]] before Friday"
        let found = Links.links(text)
        XCTAssertEqual(found.count, 1); XCTAssertEqual(found[0].label, "Call Bob"); XCTAssertEqual(found[0].targetId, id)
        XCTAssertEqual((text as NSString).substring(with: found[0].range), "[[Call Bob|^\(id)]]")
    }

    func testTwoLinksOnOneLineStayTwoLinks() { XCTAssertEqual(Links.targets("[[A|^a]] and [[B|^b]]"), ["a", "b"]) }

    func testABracketedNameWithNoIdIsNotALink() {
        XCTAssertTrue(Links.links("[[Call Bob]]").isEmpty)
        XCTAssertTrue(Links.links("[[Call Bob|9f1e]]").isEmpty)
        XCTAssertEqual(Links.plain("[[Call Bob]]"), "[[Call Bob]]")
    }

    func testAnUnclosedBracketClaimsNothing() { XCTAssertTrue(Links.links("[[Call Bob|^\(id) and then some").isEmpty) }

    func testEncodingReplacesTheCharactersTheSyntaxSpends() {
        XCTAssertEqual(Links.encode(label: "a|b]c", targetId: "x"), "[[a b c|^x]]")
        XCTAssertEqual(Links.encode(label: "   ", targetId: "x"), "[[Untitled|^x]]")
        let back = Links.links(Links.encode(label: "Call Bob", targetId: id))
        XCTAssertEqual(back.first?.label, "Call Bob"); XCTAssertEqual(back.first?.targetId, id)
    }

    /// Java's `\s+` is ASCII: a tab run collapses, a no-break space is a letter like any other —
    /// and Kotlin's `trim()` then takes it off the ends.
    func testEncodingCollapsesWhitespaceTheWayJavaDoes() {
        XCTAssertEqual(Links.encode(label: "a \t\n b", targetId: "x"), "[[a b|^x]]")
        XCTAssertEqual(Links.encode(label: "a\u{00A0}\u{00A0}b", targetId: "x"), "[[a\u{00A0}\u{00A0}b|^x]]")
        XCTAssertEqual(Links.encode(label: "\u{00A0}a\u{00A0}", targetId: "x"), "[[a|^x]]")
    }

    func testAnIdWithANoBreakSpaceIsStillALink() {
        // `[^\[\]|\s]` in Java does not exclude U+00A0; ICU's `\s` would.
        XCTAssertEqual(Links.targets("[[a|^b\u{00A0}c]]"), ["b\u{00A0}c"])
    }

    func testPlainTextIsTheLabelWhenNothingResolves() { XCTAssertEqual(Links.plain("See [[Call Bob|^\(id)]] now"), "See Call Bob now") }

    func testAResolvedTitleWinsOverTheStoredLabel() {
        XCTAssertEqual(Links.plain("See [[Call Bob|^\(id)]] now") { $0 == self.id ? "Ring Bob" : nil }, "See Ring Bob now")
    }

    func testADraftIsTheUnclosedBracketTheCaretIsInside() {
        let text = "see [[cal"
        let (range, typed) = Links.draft(text, caret: (text as NSString).length)!
        XCTAssertEqual(typed, "cal"); XCTAssertEqual((text as NSString).substring(with: range), "[[cal")
    }

    func testAFinishedLinkIsNotADraft() {
        let text = "see [[Call Bob|^\(id)]] now"
        XCTAssertNil(Links.draft(text, caret: (text as NSString).length))
    }

    func testTheCaretHasToBePastTheBracket() {
        XCTAssertNil(Links.draft("see [[", caret: 5))
        XCTAssertEqual(Links.draft("see [[", caret: 6)?.1, "")
    }

    func testADraftEndsAtALineBreak() { XCTAssertNil(Links.draft("see [[cal\nmore", caret: 14)) }
}
