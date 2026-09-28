import XCTest
@testable import YantraCore

/// The `.ink` stroke blob, against Android's `StrokeCodec` at 17d0beb.
///
/// `conformance/ink/android/` holds blobs the real androidx.ink library wrote on a device
/// (`InkInteropFixturesTest.theLibraryWritesTheGoldenStrokes`), each beside what the library reads
/// back out of it. `conformance/ink/ios/` holds blobs this encoder wrote, with
/// `android-decoded.json` recording what Android read from them
/// (`InkInteropFixturesTest.whatIosWroteDecodesHere`). `scripts/ink-interop.sh` regenerates both.
final class InkBlobTests: XCTestCase {

    static let root = ConformanceTests.root.appendingPathComponent("ink")

    // MARK: header

    func testTheHeaderIsTheOneAndroidWrites() {
        let h = InkBlob.Header(family: "pressure_pen", color: 4_294_045_415, size: 2.6, epsilon: 0.1)
        XCTAssertEqual(InkBlob.headerJSON(h),
                       #"{"family":"pressure_pen","color":4294045415,"size":2.6,"epsilon":0.1,"v":2,"unit":"du1000"}"#)
    }

    func testFloatsAreWrittenAsKotlinWritesThem() {
        let cases: [(Float, String)] = [
            (2.6, "2.6"), (0.1, "0.1"), (9, "9.0"), (2.1542726, "2.1542726"), (0.001, "0.001"),
            (0.0001, "1.0E-4"), (1e7, "1.0E7"), (12_345_678, "1.2345678E7"), (9_999_999, "9999999.0"),
            (-3.5, "-3.5"), (0, "0.0"), (123.456, "123.456"),
        ]
        for (f, s) in cases { XCTAssertEqual(KotlinFloat.string(f), s, "\(f)") }
    }

    func testAStrokeWithNoVersionIsAPixelStrokeAndIsNotPlaced() throws {
        let json = Data(#"{"family":"marker","color":4278190080,"size":3.0,"epsilon":0.1}"#.utf8)
        var blob = Data(); blob.appendBE(Int32(json.count)); blob.append(json)
        blob.append(try Gzip.compress(Data()))
        XCTAssertEqual(InkBlob.version(blob), 1)
        XCTAssertFalse(InkBlob.isPortable(blob))
        XCTAssertThrowsError(try InkBlob.decode(blob)) { XCTAssertEqual($0 as? InkBlob.DecodeError, .unplaceable(version: 1)) }
        XCTAssertNil(InkBlob.decodeOrNull(blob))
        XCTAssertEqual(InkBlob.version(Data([1, 2])), 0, "garbage claims no version")
    }

    // MARK: gzip framing

    func testGzipOpensWithJavasTenByteHeaderAndRoundTrips() throws {
        let payload = Data((0..<3000).map { UInt8(truncatingIfNeeded: $0 * 7) })
        let z = try Gzip.compress(payload)
        XCTAssertEqual([UInt8](z.prefix(10)), [0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF])
        XCTAssertEqual(try Gzip.decompress(z), payload)
        var broken = z; broken[broken.count - 6] ^= 0xFF
        XCTAssertThrowsError(try Gzip.decompress(broken), "a bad CRC must not pass")
    }

    // MARK: encode rules

    func testWhatIsWrittenDecodesToWhatWasMeantWithinTheQuantum() throws {
        let n = 40
        let inputs = InkBlob.Inputs(
            x: (0..<n).map { 100 + Float($0) * 3.3 }, y: (0..<n).map { 400 + 30 * sin(Float($0) / 5) },
            t: (0..<n).map { Float($0) * 0.008 }, pressure: (0..<n).map { 0.2 + Float($0 % 7) / 10 },
            tilt: (0..<n).map { 0.2 + Float($0) / 100 }, orientation: (0..<n).map { Float($0) / 10 },
            toolType: .stylus, strokeUnitLengthCm: 0.021)
        let stroke = InkBlob.Stroke(header: .init(family: StrokeCodec.familyPressurePen, color: StrokeCodec.chalk, size: 2.6),
                                    inputs: inputs)
        let back = try InkBlob.decode(try InkBlob.encode(stroke))
        XCTAssertEqual(back.header, stroke.header)
        XCTAssertEqual(back.inputs.count, n)
        XCTAssertEqual(back.inputs.toolType, .stylus)
        XCTAssertEqual(back.inputs.strokeUnitLengthCm, 0.021)
        let spanX = inputs.x.max()! - inputs.x.min()!
        for i in 0..<n {
            XCTAssertEqual(back.inputs.x[i], inputs.x[i], accuracy: spanX / 4096)
            XCTAssertEqual(back.inputs.t[i], inputs.t[i], accuracy: 1e-6)
            XCTAssertEqual(back.inputs.pressure![i], inputs.pressure![i], accuracy: 1 / 4096)
            XCTAssertEqual(back.inputs.orientation![i], inputs.orientation![i], accuracy: 2 * .pi / 4096)
        }
        XCTAssertNil(InkBlob.Inputs(x: [1], y: [1], t: [0]).pressure, "no channel is invented")
    }

    func testAHighlighterIsStoredAtItsForcedAlpha() throws {
        let s = InkBlob.Stroke(header: .init(family: StrokeCodec.familyHighlighter, color: 0xFFE0A83E, size: 9),
                               inputs: .init(x: [0, 10], y: [0, 0], t: [0, 0.01]))
        XCTAssertEqual(try InkBlob.decode(try InkBlob.encode(s)).header.color, 0x59E0A83E)
    }

    func testInputsTheAndroidDecoderWouldRefuseAreNeverWritten() {
        func refuses(_ i: InkBlob.Inputs, _ why: String) {
            let s = InkBlob.Stroke(header: .init(family: StrokeCodec.familyPressurePen, color: 0xFF000000, size: 2.6), inputs: i)
            XCTAssertThrowsError(try InkBlob.encode(s), why)
        }
        refuses(.init(x: [0, 1], y: [0, 1], t: [0.02, 0.01]), "time went backwards")
        refuses(.init(x: [0, 1], y: [0, 1], t: [-1, 0]), "negative time")
        refuses(.init(x: [0, .nan], y: [0, 1], t: [0, 1]), "non-finite position")
        refuses(.init(x: [0, 1], y: [0, 1], t: [0, 1], pressure: [0.5, 1.5]), "pressure above 1")
        refuses(.init(x: [0, 1], y: [0, 1], t: [0, 1], pressure: [0.5]), "a channel shorter than the stroke")
    }

    func testMovingAStrokeKeepsItsBrushAndScalesItsSize() throws {
        let s = InkBlob.Stroke(header: .init(family: StrokeCodec.familyMarker, color: 0xFF3A6EA5, size: 4, epsilon: 0.1),
                               inputs: .init(x: [100, 200], y: [100, 150], t: [0, 0.016], toolType: .touch))
        let moved = try InkBlob.decode(try InkBlob.transform(try InkBlob.encode(s), scale: 2, anchorX: 100, anchorY: 100, dx: 5, dy: -5))
        XCTAssertEqual(moved.header.family, StrokeCodec.familyMarker)
        XCTAssertEqual(moved.header.color, 0xFF3A6EA5)
        XCTAssertEqual(moved.header.size, 8)
        XCTAssertEqual(moved.inputs.x[1], 305, accuracy: 0.1)
        XCTAssertEqual(moved.inputs.y[1], 195, accuracy: 0.1)
        XCTAssertEqual(moved.inputs.toolType, .touch)
    }

    func testTheSidecarCarriesBlobsItCannotReadUntouched() throws {
        let unknown = Data([0, 0, 0, 2, 0x7B, 0x7D, 9, 9, 9])
        let sidecar = InkSidecar.encode([unknown, Data([1, 2, 3])])
        XCTAssertEqual(try InkSidecar.decode(sidecar), [unknown, Data([1, 2, 3])])
        XCTAssertEqual(InkSidecar.encode(try InkSidecar.decode(sidecar)), sidecar)
    }

    // MARK: against the real library

    struct Decoded: Decodable {
        let colorArgb: UInt32; let size: Float; let epsilon: Float; let toolType: String
        let strokeUnitLengthCm: Float; let hasPressure: Bool; let hasTilt: Bool; let hasOrientation: Bool
        let x: [Float]; let y: [Float]; let tMillis: [Int64]
        let pressure: [Float]; let tilt: [Float]; let orientation: [Float]
    }

    /// What androidx.ink wrote, decoded here to what androidx.ink read back from it.
    func testBlobsTheAndroidLibraryWroteDecodeToTheSamePoints() throws {
        let dir = Self.root.appendingPathComponent("android")
        let names = try JSONDecoder().decode([String].self, from: Data(contentsOf: dir.appendingPathComponent("android-index.json")))
        XCTAssertFalse(names.isEmpty)
        for name in names {
            let blob = try Data(contentsOf: dir.appendingPathComponent("\(name).blob"))
            let want = try JSONDecoder().decode(Decoded.self, from: Data(contentsOf: dir.appendingPathComponent("\(name).json")))
            let got = try InkBlob.decode(blob)
            assertSame(got, want, name)
            // An untouched stroke is carried as its bytes; a decoded one re-encodes to the same protobuf.
            let (_, start) = try InkBlob.readHeader(blob)
            let theirs = try Gzip.decompress(blob.subdata(in: start..<blob.count))
            XCTAssertEqual(try InkProto.encodeBatch(got.inputs), theirs,
                           "\(name): this encoder is not the library's — the same inputs quantised differently")
        }
    }

    /// What this encoder wrote, as the Android library read it.
    func testBlobsThisEncoderWroteDecodeOnAndroid() throws {
        let dir = Self.root.appendingPathComponent("ios")
        let decoded = try JSONDecoder().decode([String: Decoded?].self,
                                               from: Data(contentsOf: dir.appendingPathComponent("android-decoded.json")))
        XCTAssertEqual(Set(decoded.keys), Set(Self.iosFixtures.keys))
        for (name, stroke) in Self.iosFixtures {
            let blob = try Data(contentsOf: dir.appendingPathComponent("\(name).blob"))
            XCTAssertEqual(blob, try InkBlob.encode(stroke), "\(name): the encoder's output changed since Android read it")
            guard let want = decoded[name] ?? nil else { XCTFail("\(name): Android could not decode it"); continue }
            assertSame(try InkBlob.decode(blob), want, name)
            // And Android, re-encoding what it read, writes the protobuf this encoder writes for the
            // same points. (Not the original blob's: a decode lands on floats that re-quantise an ulp
            // away, on both sides alike — which is why untouched strokes are carried as bytes.)
            let android = try Data(contentsOf: dir.appendingPathComponent("\(name).android.blob"))
            let (_, a) = try InkBlob.readHeader(android), (_, b) = try InkBlob.readHeader(blob)
            XCTAssertEqual(try Gzip.decompress(android.subdata(in: a..<android.count)),
                           try InkProto.encodeBatch(try InkBlob.decode(blob).inputs), name)
            XCTAssertEqual(android.prefix(a), blob.prefix(b), "\(name): the header differs")
        }
    }

    private func assertSame(_ got: InkBlob.Stroke, _ want: Decoded, _ name: String) {
        XCTAssertEqual(got.header.color, want.colorArgb, name)
        XCTAssertEqual(got.header.size, want.size, name)
        XCTAssertEqual(got.header.epsilon, want.epsilon, name)
        XCTAssertEqual(got.inputs.count, want.x.count, name)
        guard got.inputs.count == want.x.count else { return }
        XCTAssertTrue(want.toolType.uppercased().hasSuffix("\(got.inputs.toolType)".uppercased()), "\(name): tool \(want.toolType)")
        XCTAssertEqual(got.inputs.pressure != nil, want.hasPressure, name)
        XCTAssertEqual(got.inputs.tilt != nil, want.hasTilt, name)
        XCTAssertEqual(got.inputs.orientation != nil, want.hasOrientation, name)
        if want.strokeUnitLengthCm > 0 { XCTAssertEqual(got.inputs.strokeUnitLengthCm, want.strokeUnitLengthCm, name) }
        for i in 0..<want.x.count {
            XCTAssertEqual(got.inputs.x[i], want.x[i], accuracy: 1e-3, "\(name) x[\(i)]")
            XCTAssertEqual(got.inputs.y[i], want.y[i], accuracy: 1e-3, "\(name) y[\(i)]")
            XCTAssertEqual(Double(got.inputs.t[i]) * 1000, Double(want.tMillis[i]), accuracy: 1, "\(name) t[\(i)]")
            if let p = got.inputs.pressure { XCTAssertEqual(p[i], want.pressure[i], accuracy: 1e-5, "\(name) pressure[\(i)]") }
            if let a = got.inputs.tilt { XCTAssertEqual(a[i], want.tilt[i], accuracy: 1e-5, "\(name) tilt[\(i)]") }
            if let a = got.inputs.orientation { XCTAssertEqual(a[i], want.orientation[i], accuracy: 1e-5, "\(name) orientation[\(i)]") }
        }
    }

    // MARK: the iOS side of the fixtures

    /// Strokes shaped like what the iPhone canvas produces: a Pencil stroke with every channel, a
    /// finger stroke with none, a highlighter, and a single dot.
    static let iosFixtures: [String: InkBlob.Stroke] = {
        let n = 36
        let pencil = InkBlob.Inputs(
            x: (0..<n).map { 210 + Float($0) * 4.25 }, y: (0..<n).map { 520 + 18 * cos(Float($0) / 4) },
            t: (0..<n).map { Float($0) / 240 }, pressure: (0..<n).map { 0.15 + Float($0) / 60 },
            tilt: (0..<n).map { 0.5 + Float($0) / 200 }, orientation: (0..<n).map { 2.2 + Float($0) / 90 },
            toolType: .stylus, strokeUnitLengthCm: 0.0205)
        let finger = InkBlob.Inputs(x: (0..<n).map { 700 - Float($0) * 9 }, y: (0..<n).map { 1500 + Float($0) * 3 },
                                    t: (0..<n).map { Float($0) / 60 }, toolType: .touch, strokeUnitLengthCm: 0.0205)
        let mark = InkBlob.Inputs(x: (0..<12).map { 80 + Float($0) * 25 }, y: Array(repeating: 1800, count: 12),
                                  t: (0..<12).map { Float($0) / 120 }, pressure: Array(repeating: 1, count: 12),
                                  toolType: .stylus, strokeUnitLengthCm: 0.0205)
        let dot = InkBlob.Inputs(x: [512.5], y: [760.25], t: [0], pressure: [0.6], toolType: .stylus, strokeUnitLengthCm: 0.0205)
        return [
            "pencil-pen": .init(header: .init(family: StrokeCodec.familyPressurePen, color: StrokeCodec.graphite, size: 2.6), inputs: pencil),
            "finger-marker": .init(header: .init(family: StrokeCodec.familyMarker, color: 0xFFC2410C, size: 5.25), inputs: finger),
            "highlighter": .init(header: .init(family: StrokeCodec.familyHighlighter, color: 0x59E0A83E, size: 9), inputs: mark),
            "dot": .init(header: .init(family: StrokeCodec.familyPressurePen, color: StrokeCodec.chalk, size: 2.6), inputs: dot),
        ]
    }()

    /// `YANTRA_WRITE_INK_FIXTURES=1 swift test --filter InkBlobTests/testWriteIosFixtures` writes the
    /// iOS blobs for the device run to read.
    func testWriteIosFixtures() throws {
        guard ProcessInfo.processInfo.environment["YANTRA_WRITE_INK_FIXTURES"] == "1" else { throw XCTSkip("set YANTRA_WRITE_INK_FIXTURES=1") }
        let dir = Self.root.appendingPathComponent("ios")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for (name, stroke) in Self.iosFixtures {
            try InkBlob.encode(stroke).write(to: dir.appendingPathComponent("\(name).blob"))
        }
    }
}
