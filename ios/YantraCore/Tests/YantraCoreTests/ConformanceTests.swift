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

    // MARK: stroke envelope

    struct PointFixture: Decodable { let x: Float; let y: Float; let elapsedMillis: Int32; let pressure: Float; let tiltRadians: Float; let orientationRadians: Float; let strokeUnitLengthCm: Float }
    struct StrokeFixture: Decodable { let family: String; let color: UInt32; let size: Float; let epsilon: Float; let tool: UInt8; let points: [PointFixture]; let file: String }

    func testStrokeEnvelopeDecodesAndReencodesTheAndroidBytes() throws {
        let f = try JSONDecoder().decode(StrokeFixture.self, from: fixture("ink/pen.json"))
        let bytes = try fixture("ink/" + f.file)
        XCTAssertEqual(StrokeEnvelope.kind(bytes), .yantra)

        let e = try StrokeEnvelope.decode(bytes)
        XCTAssertEqual(e.header, .init(family: f.family, color: f.color, size: f.size, epsilon: f.epsilon))
        XCTAssertEqual(e.tool, f.tool)
        XCTAssertEqual(e.points.count, f.points.count)
        for (p, q) in zip(e.points, f.points) {
            XCTAssertEqual(p.x, q.x); XCTAssertEqual(p.y, q.y); XCTAssertEqual(p.elapsedMillis, q.elapsedMillis)
            XCTAssertEqual(p.pressure, q.pressure); XCTAssertEqual(p.tiltRadians, q.tiltRadians)
            XCTAssertEqual(p.orientationRadians, q.orientationRadians); XCTAssertEqual(p.strokeUnitLengthCm, q.strokeUnitLengthCm)
        }
        // The contract: what Android wrote, Swift writes back byte for byte.
        XCTAssertEqual(StrokeEnvelope.encode(e), bytes)
    }

    func testEmptyStrokeAndSidecarContainer() throws {
        let empty = try fixture("ink/empty-highlighter.ynk1")
        let e = try StrokeEnvelope.decode(empty)
        XCTAssertEqual(e.points.count, 0)
        XCTAssertEqual(e.header.family, "highlighter")
        XCTAssertEqual(e.header.color, 0x59E0A83E)
        XCTAssertEqual(StrokeEnvelope.encode(e), empty)

        let sidecar = try fixture("ink/sidecar.ink")
        let strokes = try InkSidecar.decode(sidecar)
        XCTAssertEqual(strokes.count, 2)
        XCTAssertEqual(strokes[0], try fixture("ink/pen.ynk1"))
        XCTAssertEqual(strokes[1], empty)
        XCTAssertEqual(InkSidecar.encode(strokes), sidecar)
    }

    func testLegacyAndUnknownAreSniffedNotDecoded() {
        let legacy = Data([0, 0, 0, 43] + Array("{\"family\":\"marker\"}".utf8) + [9, 9])
        XCTAssertEqual(StrokeEnvelope.kind(legacy), .legacy)
        XCTAssertEqual(StrokeEnvelope.kind(Data("YNK2....".utf8)), .unknown)
        XCTAssertEqual(StrokeEnvelope.kind(Data()), .unknown)
        XCTAssertThrowsError(try StrokeEnvelope.decode(legacy))
    }

    // MARK: session clock

    struct ClockFixture: Decodable { let cases: [String: String] }

    func testSessionClock() throws {
        let f = try JSONDecoder().decode(ClockFixture.self, from: fixture("focus/session-clock.json"))
        for (secs, text) in f.cases { XCTAssertEqual(sessionClock(Int(secs)!), text, "sessionClock(\(secs))") }
    }

    // MARK: manifest merge

    struct MergeCase: Decodable { let name: String; let base: String?; let local: String; let remote: String; let device: String; let otherDevice: String; let merged: String; let reason: String }
    struct MergeFixture: Decodable { let path: String; let cases: [MergeCase] }

    func testManifestMergeMatchesAndroidByteForByte() throws {
        let f = try JSONDecoder().decode(MergeFixture.self, from: fixture("sync/manifest-merge.json"))
        XCTAssertEqual(ManifestMerge.path, f.path)
        for c in f.cases {
            let merged = ManifestMerge.merge(base: c.base, local: c.local, remote: c.remote, device: c.device, otherDevice: c.otherDevice)
            if c.base == nil {
                // No ancestor: the Kotlin engine keeps its local side; the Swift merge declines.
                XCTAssertNil(merged, c.name)
                XCTAssertEqual(c.merged, c.local, c.name)
            } else {
                XCTAssertEqual(merged, c.merged, c.name)
            }
        }
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
    struct CaptureFixture: Decodable { let today: String; let cases: [CaptureCase] }

    func testCaptureGrammarMatchesAndroid() throws {
        let f = try JSONDecoder().decode(CaptureFixture.self, from: fixture("capture/cases.json"))
        let today = LocalDate(f.today)!
        for c in f.cases {
            // `now` pinned to noon so "a time with no day" resolves the same way as on the machine that wrote the fixture.
            let got = CaptureParse.parse(c.input, today: today, now: .init(hour: 12, minute: 0), lists: c.lists, people: c.people)
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

    struct LineCase: Decodable { let source: String, kind: String, rendered: String }

    func testPageLines() throws {
        let cases = try JSONDecoder().decode([LineCase].self, from: fixture("pages/lines.json"))
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let block = PageCodec.decodeBlock(c.source)
            XCTAssertEqual(Self.kindName(block), c.kind, "kind of \(c.source)")
            // Without `raw` the emitter has to rebuild the line from the model, which is the half
            // that pins token order, durations and escaped values.
            XCTAssertEqual(PageCodec.encodeBlock(block.strippingRaw), c.rendered, "render of \(c.source)")
            // And the source itself survives untouched when nothing about it changed. A numbered
            // item is the exception by construction: its ordinal is positional and therefore not
            // part of the model, so a line written `3.` cannot come back as one from a block that
            // does not know it is third. Both platforms re-render it, which is what the fixture's
            // `0.` records.
            if case .numbered = block {} else {
                XCTAssertEqual(PageCodec.encodeBlock(block), c.source.isEmpty ? " " : c.source, "passthrough of \(c.source)")
            }
        }
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
            color: "teal")
        XCTAssertEqual(PageCodec.encode(page), expected)
        // And it reads back as what it was, which is the half no byte fixture can state.
        XCTAssertEqual(PageCodec.decode(expected).blocks.map(\.strippingRaw), page.blocks.map(\.strippingRaw))
        XCTAssertEqual(PageCodec.decode(expected).color, "teal")
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
}
