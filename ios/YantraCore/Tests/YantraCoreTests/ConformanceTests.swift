import XCTest
@testable import YantraCore

/// Runs the golden files in `conformance/` — written and checked by the Android app — through the
/// Swift implementations. When these pass on both sides, two devices agree on bytes.
final class ConformanceTests: XCTestCase {

    static let root: URL = {
        // ios/YantraCore/Tests/YantraCoreTests/ConformanceTests.swift → repo root
        var u = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { u.deleteLastPathComponent() }
        return u.appendingPathComponent("conformance")
    }()

    func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: Self.root.appendingPathComponent(name))
    }

    // MARK: rank

    struct RankCase: Decodable { let a: String?; let b: String?; let result: String }
    struct RankFixture: Decodable { let alphabet: String; let first: String; let cases: [RankCase] }

    func testRankVectors() throws {
        let f = try JSONDecoder().decode(RankFixture.self, from: fixture("rank/vectors.json"))
        XCTAssertEqual(String(Rank.alphabet), f.alphabet)
        XCTAssertEqual(Rank.first, f.first)
        for c in f.cases {
            let r = Rank.between(c.a, c.b)
            XCTAssertEqual(r, c.result, "between(\(c.a ?? "nil"), \(c.b ?? "nil"))")
            XCTAssertFalse(r.hasSuffix("0"))
            if let a = c.a { XCTAssertLessThan(a, r) }
            if let b = c.b { XCTAssertLessThan(r, b) }
        }
    }

    // Ink lives in InkBlobTests, against blobs the real androidx.ink library wrote.

    // MARK: session clock

    struct ClockFixture: Decodable { let cases: [String: String] }

    func testSessionClock() throws {
        let f = try JSONDecoder().decode(ClockFixture.self, from: fixture("focus/session-clock.json"))
        for (secs, text) in f.cases { XCTAssertEqual(sessionClock(Int(secs)!), text, "sessionClock(\(secs))") }
    }

    func testJSONRoundTripKeepsOrderAndLiterals() throws {
        let text = "{\"formatVersion\":2,\"name\":\"t \\\"q\\\"\",\"createdAt\":1788956797699,\"x\":[1,2.50,true,null],\"z\":{}}"
        XCTAssertEqual(try JSONValue.parse(text).compact(), text)
    }
}

// MARK: - capture grammar

extension ConformanceTests {
    struct CaptureCase: Decodable {
        let input: String; let lists: [String]; let people: [String]
        let title: String; let date: String?; let time: String?; let labels: [String]; let priority: String?; let assignee: String?
        let list: String?; let listIsNew: Bool; let spans: [String]
    }
    struct CaptureFixture: Decodable { let today: String; let now: String; let cases: [CaptureCase] }

    func testCaptureGrammarMatchesAndroid() throws {
        let f = try JSONDecoder().decode(CaptureFixture.self, from: fixture("capture/cases.json"))
        // The clock the fixture was written against, read from the file rather than hard-coded:
        // "dinner 6pm" is this evening before six and tomorrow evening after it, so a test that
        // assumed noon agreed with a fixture generated at any other hour only by luck.
        let parts = f.now.split(separator: ":").compactMap { Int($0) }
        XCTAssertEqual(parts.count >= 2, true, "the capture fixture does not say what time it was written for")
        let fixtureNow = Captured.Time(hour: parts[0], minute: parts[1])
        let today = LocalDate(f.today)!
        for c in f.cases {
            // `now` pinned to noon so "a time with no day" resolves the same way as on the machine that wrote the fixture.
            let got = CaptureParse.parse(c.input, today: today, now: fixtureNow, lists: c.lists, people: c.people)
            XCTAssertEqual(got.title, c.title, c.input)
            XCTAssertEqual(got.date?.description, c.date, c.input)
            XCTAssertEqual(got.time.map { String(format: "%02d:%02d", $0.hour, $0.minute) }, c.time, c.input)
            XCTAssertEqual(got.labels, c.labels, c.input)
            XCTAssertEqual(got.priority, c.priority, c.input)
            XCTAssertEqual(got.assignee, c.assignee, c.input)
            XCTAssertEqual(got.list, c.list, c.input)
            XCTAssertEqual(got.listIsNew, c.listIsNew, c.input)
            XCTAssertEqual(got.spans.map { "\($0.kind.rawValue):\($0.range.location)-\($0.range.location + $0.range.length)" }, c.spans, c.input)
        }
    }

    // MARK: the page format

    /// The Kotlin class name for each block kind, which the fixture records so a line that parsed
    /// to the *wrong kind* fails here rather than by rendering identically for a different reason.
    static func kindName(_ b: Block) -> String {
        switch b {
        case .prose: return "Prose"
        case .heading: return "Heading"
        case .bullet: return "Bullet"
        case .numbered: return "Numbered"
        case .task: return "TaskRef"
        case .ink: return "InkRef"
        case .image: return "ImageRef"
        case .event: return "EventRef"
        }
    }

    /// `source` through 17d0beb's `decodeBlock`: the Kotlin class it became, `encodeBlock` of it with
    /// `raw` stripped (the model, rebuilt), and `encodeBlock` of it as parsed (raw preservation).
    /// Written by `conformance/tools/oracle.sh`, which runs the Kotlin in `app/` itself.
    struct LineCase: Decodable { let source: String, kind: String, rendered: String, passthrough: String }

    func testPageLines() throws {
        let cases = try JSONDecoder().decode([LineCase].self, from: fixture("pages/lines.json"))
        XCTAssertGreaterThan(cases.count, 150)
        for c in cases {
            let block = PageCodec.decodeBlock(c.source)
            let where_ = c.source.debugDescription
            XCTAssertEqual(Self.kindName(block), c.kind, "kind of \(where_)")
            // Without `raw` the emitter has to rebuild the line from the model, which is the half
            // that pins token order, durations and escaped values. Compared as bytes: Swift's `==`
            // on strings is canonical equivalence, and a combining mark in the wrong place is
            // exactly what it would forgive.
            assertBytes(PageCodec.encodeBlock(block.strippingRaw), c.rendered, "render of \(where_)")
            // And what raw preservation writes back — the source itself for everything but a
            // numbered item, whose ordinal is positional and so cannot survive `encodeBlock`'s 0.
            assertBytes(PageCodec.encodeBlock(block), c.passthrough, "passthrough of \(where_)")
        }
    }

    /// Whole pages through 17d0beb's `encode(decode(text))`: frontmatter order, `pinned`, unknown
    /// and duplicate keys, precise `modified_at`, fences, CRLF, a BOM, blank-line layout.
    func testWholePagesMatchKotlin() throws {
        let dir = Self.root.appendingPathComponent("pages/roundtrip")
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.appendingPathComponent("in").path).filter { $0.hasSuffix(".md") }.sorted()
        XCTAssertGreaterThan(names.count, 20)
        for n in names {
            let input = try Data(contentsOf: dir.appendingPathComponent("in/\(n)"))
            let expected = try Data(contentsOf: dir.appendingPathComponent("out/\(n)"))
            let got = Data(PageCodec.encode(PageCodec.decode(WorkspaceStore.decodeText(input))).utf8)
            XCTAssertEqual(got, expected, "\(n):\n\(String(decoding: got, as: UTF8.self))\n--- Kotlin ---\n\(String(decoding: expected, as: UTF8.self))")
        }
    }

    func assertBytes(_ a: String, _ b: String, _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(Array(a.utf8), Array(b.utf8), "\(message): \(a.debugDescription) vs \(b.debugDescription)", file: file, line: line)
    }

    func testPageFile() throws {
        let expected = try String(data: fixture("pages/sample.md"), encoding: .utf8)!
        let page = PageDoc(
            id: "p1", type: NodeType.list, parent: nil, title: "Sample", systemKey: SystemKey.inbox,
            modifiedAt: InstantText.parse("2026-09-11T14:22:31.402Z")!, device: "android-a",
            blocks: [
                .heading("Welcome"),
                .prose("A list holds tasks."),
                .prose(""),
                .task(TaskRef(id: "t1", title: "First", due: DueSpec(.allDay(LocalDate("2026-09-11")!)))),
                .task(TaskRef(id: "t2", title: "Second", indent: 1)),
                .event(EventRef(id: "e1", title: "Standup", time: EventTime(
                    start: LocalDateTime("2026-09-11T09:00")!, end: LocalDateTime("2026-09-11T09:15")!))),
                .numbered("one"),
                .numbered("two"),
                .bullet("a bullet"),
                .ink(id: "i1"),
            ],
            icon: "📥", color: "teal")
        XCTAssertEqual(PageCodec.encode(page), expected)
        // And it reads back as what it was, which is the half no byte fixture can state.
        XCTAssertEqual(PageCodec.decode(expected).blocks.map(\.strippingRaw), page.blocks.map(\.strippingRaw))
        XCTAssertEqual(PageCodec.decode(expected).color, "teal")
        XCTAssertEqual(PageCodec.decode(expected).icon, "📥")
    }

    /// The when-slot's readings, spelled out — the table in `PageCodec.parseWhen`.
    func testEventWhenSlot() {
        func when(_ s: String) -> EventTime? {
            guard case let .event(e)? = Optional(PageCodec.decodeBlock("@ \(s) Thing")) else { return nil }
            return e.time
        }
        // All-day: inclusive in the text, exclusive in the model.
        XCTAssertEqual(when("2026-09-11")?.end, LocalDateTime("2026-09-12T00:00"))
        XCTAssertEqual(when("2026-09-11/2026-09-13")?.end, LocalDateTime("2026-09-14T00:00"))
        XCTAssertEqual(when("2026-09-11")?.allDay, true)
        // A moment has no span.
        XCTAssertEqual(when("2026-09-11T14:00")?.isInstantaneous, true)
        XCTAssertEqual(when("2026-09-11T14:00/PT1H")?.duration, ISODuration.hours(1))
        XCTAssertEqual(when("2026-09-11T14:00/2026-09-11T15:30")?.duration, ISODuration.minutes(90))
        XCTAssertEqual(when("2026-09-11T09:00[Europe/Dublin]/PT30M")?.zone, "Europe/Dublin")
        // An end before the start, and a zone that is not one, are not events.
        XCTAssertNil(when("2026-09-11T14:00/2026-09-11T13:00"))
        XCTAssertNil(when("2026-09-11T09:00[Nowhere/Fake]/PT30M"))
    }

    /// `java.time.Duration`'s printed form, which the when-slot writes.
    func testDurationText() {
        XCTAssertEqual(ISODuration(seconds: 0).description, "PT0S")
        XCTAssertEqual(ISODuration.hours(1).description, "PT1H")
        XCTAssertEqual(ISODuration.minutes(90).description, "PT1H30M")
        XCTAssertEqual(ISODuration(seconds: 86_400).description, "PT24H")   // a day is hours, as Java prints it
        XCTAssertEqual(ISODuration("PT1H30M")?.seconds, 5400)
        XCTAssertEqual(ISODuration("P1DT2H")?.seconds, 93_600)
        XCTAssertNil(ISODuration("PT1.5H"))
        XCTAssertNil(ISODuration("1H"))
    }

    // MARK: the label palette

    struct SwatchFixture: Decodable { let name: String, light: String, dark: String }
    struct SeedCase: Decodable { let name: String, swatch: String, light: String }
    struct PaletteFixture: Decodable { let swatches: [SwatchFixture], seeds: [SeedCase] }

    func testLabelPalette() throws {
        let f = try JSONDecoder().decode(PaletteFixture.self, from: fixture("labels/palette.json"))
        func hex(_ v: Int64) -> String { String(format: "%08X", v) }

        XCTAssertEqual(LabelPalette.swatches.map(\.name), f.swatches.map(\.name))
        for (mine, theirs) in zip(LabelPalette.swatches, f.swatches) {
            XCTAssertEqual(hex(mine.light), theirs.light, theirs.name)
            XCTAssertEqual(hex(mine.dark), theirs.dark, theirs.name)
            // The twin swap is what lets one stored value dress for both themes.
            XCTAssertEqual(LabelPalette.display(mine.light, dark: true), mine.dark)
            XCTAssertEqual(LabelPalette.display(mine.dark, dark: false), mine.light)
        }
        // Anything not from the palette passes through untouched.
        XCTAssertEqual(LabelPalette.display(0xFF123456, dark: true), 0xFF123456)

        for c in f.seeds {
            XCTAssertEqual(LabelPalette.defaultNameFor(c.name), c.swatch, "seed of \(c.name.debugDescription)")
            XCTAssertEqual(hex(LabelPalette.defaultFor(c.name)), c.light, "seed of \(c.name.debugDescription)")
        }
    }


    // MARK: the list icon

    struct IconCase: Decodable { let input: String, cleaned: String? }
    struct IconFixture: Decodable { let suggested: [String], cases: [IconCase] }

    /// The grapheme boundary, which the two platforms find by different means — Kotlin through
    /// `BreakIterator`, Swift through `Character`. A list wearing a ZWJ sequence on one device must
    /// not wear half of it on the other.
    func testListIcon() throws {
        let f = try JSONDecoder().decode(IconFixture.self, from: fixture("labels/list-icon.json"))
        XCTAssertEqual(ListIcon.suggested, f.suggested,
                       "the suggested grid differs, so a list would find its icon in another cell")
        for c in f.cases {
            XCTAssertEqual(ListIcon.clean(c.input), c.cleaned, "clean(\(c.input.debugDescription))")
        }
    }

    /// The offsets a due token carries, which became a list rather than a single number.
    func testReminderOffsets() {
        func due(_ token: String) -> DueSpec? {
            guard case let .task(t) = PageCodec.decodeBlock("- [ ] x ^i due:\(token)") else { return nil }
            return t.due
        }
        XCTAssertEqual(due("2026-09-11T14:00:00Z+r30")?.reminders, [30])
        XCTAssertEqual(due("2026-09-11T14:00:00Z+r1440,30")?.reminders, [1440, 30])
        // Canonical: largest first, no repeats — two devices holding the same task write the same bytes.
        XCTAssertEqual(due("2026-09-11T14:00:00Z+r30,1440,30")?.reminders, [1440, 30])
        // Negative means after the due moment.
        XCTAssertEqual(due("2026-09-11T14:00:00Z+r-15")?.reminders, [-15])
        XCTAssertEqual(due("2026-09-11T14:00:00Z")?.reminders, [])
        // All or nothing: a partly unreadable tail is refused, not quietly truncated to `+r30`,
        // because a task silently losing one of its two reminders is the failure to avoid.
        XCTAssertNil(due("2026-09-11T14:00:00Z+r30,x"))
        XCTAssertNil(due("2026-09-11T14:00:00Z+rx"))
    }

    /// The index's own spelling of the same thing, which is forgiving where the file parser is not.
    func testRemindersColumnIsForgiving() {
        XCTAssertEqual(Reminders.parse("1440,30"), [1440, 30])
        XCTAssertEqual(Reminders.parse("30,1440,30"), [1440, 30])
        // One unreadable element does not drop the ones beside it that are perfectly readable.
        XCTAssertEqual(Reminders.parse("1440,x,30"), [1440, 30])
        XCTAssertEqual(Reminders.parse(nil), [])
        XCTAssertEqual(Reminders.store([30, 1440, 30]), "1440,30")
        XCTAssertNil(Reminders.store([]), "empty is not a value")
    }
}
