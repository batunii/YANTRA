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
