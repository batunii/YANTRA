import Foundation
import zlib

/// One ink stroke on disk, in exactly the bytes Android's `StrokeCodec` writes at `17d0beb`.
///
/// ```
/// int32 BE  headerLength
/// bytes     header    compact UTF-8 JSON, keys in this order:
///                     {"family":…,"color":<uint32 ARGB>,"size":<float>,"epsilon":<float>,"v":2,"unit":"du1000"}
/// bytes     gzip(ink.proto.CodedStrokeInputBatch)   to the end of the blob
/// ```
///
/// The payload is androidx.ink's own serialised `StrokeInputBatch` — Android calls
/// `stroke.inputs.encode(stream)` and the library gzips a `CodedStrokeInputBatch` protobuf into it.
/// The encoder below is a port of `EncodeStrokeInputBatch` in google/ink
/// (`ink/storage/stroke_input_batch.cc`), quantisation and field order included, so a stroke drawn
/// here decodes on Android as if Android had drawn it.
///
/// **A blob with no `v` is a v1 pixel stroke** and cannot be placed: its numbers are pixels on a
/// screen whose width was never recorded. Android drops those on read; so does `decode` here. What
/// neither app may do is rewrite a stroke it did not touch — see `InkDocument`, which carries every
/// untouched blob through a save as its original bytes.
public enum InkBlob {

    public static let versionPx = 1
    public static let versionDu = 2
    public static let unitPx = "px"
    public static let unitDu = "du1000"

    public enum DecodeError: Error, Equatable {
        case truncated
        case badHeader
        case unplaceable(version: Int)
        case badGzip
        case badProto(String)
        case invalid(String)
    }

    /// The brush a stroke names. Mirrors Kotlin's `StrokeCodec.Header`.
    public struct Header: Equatable, Sendable {
        public var family: String
        /// ARGB as an unsigned 32-bit value, exactly as drawn.
        public var color: UInt32
        public var size: Float
        public var epsilon: Float
        public var v: Int
        public var unit: String
        public init(family: String, color: UInt32, size: Float, epsilon: Float = 0.1,
                    v: Int = InkBlob.versionDu, unit: String = InkBlob.unitDu) {
            self.family = family; self.color = color; self.size = size; self.epsilon = epsilon
            self.v = v; self.unit = unit
        }
    }

    /// The Ink library's `InputToolType`, which the proto stores once per stroke.
    public enum ToolType: UInt64, Sendable { case unknown = 0, mouse = 1, touch = 2, stylus = 3 }

    /// A stroke's inputs, one column per channel, in document units and seconds.
    ///
    /// Columnar because the file is: every channel is its own run, and a channel is present for the
    /// whole stroke or absent for all of it. `pressure`, `tilt`, `orientation` and `barrelTwist` are
    /// nil when the device reported none.
    public struct Inputs: Equatable, Sendable {
        public var x: [Float]
        public var y: [Float]
        public var t: [Float]
        public var pressure: [Float]?
        public var tilt: [Float]?
        public var orientation: [Float]?
        public var barrelTwist: [Float]?
        public var toolType: ToolType
        /// Centimetres the pointer travelled per document unit. Nil or zero means unknown.
        public var strokeUnitLengthCm: Float?
        public var noiseSeed: UInt32

        public init(x: [Float], y: [Float], t: [Float], pressure: [Float]? = nil, tilt: [Float]? = nil,
                    orientation: [Float]? = nil, barrelTwist: [Float]? = nil, toolType: ToolType = .unknown,
                    strokeUnitLengthCm: Float? = nil, noiseSeed: UInt32 = 0) {
            self.x = x; self.y = y; self.t = t; self.pressure = pressure; self.tilt = tilt
            self.orientation = orientation; self.barrelTwist = barrelTwist; self.toolType = toolType
            self.strokeUnitLengthCm = strokeUnitLengthCm; self.noiseSeed = noiseSeed
        }

        public var count: Int { x.count }
    }

    public struct Stroke: Equatable, Sendable {
        public var header: Header
        public var inputs: Inputs
        public init(header: Header, inputs: Inputs) { self.header = header; self.inputs = inputs }
    }

    // MARK: - Header

    /// The version a blob claims, read without touching its geometry — Kotlin's `StrokeCodec.version`.
    /// Zero for anything unreadable, so a corrupt blob and one from the future are both "not something
    /// to draw" without needing to be told apart.
    public static func version(_ blob: Data) -> Int {
        (try? readHeader(blob).header.v) ?? 0
    }

    /// True for a blob this build can place on a page.
    public static func isPortable(_ blob: Data) -> Bool { version(blob) >= versionDu }

    static func readHeader(_ blob: Data) throws -> (header: Header, payloadStart: Int) {
        var r = BigEndianReader(blob, offset: 0)
        let n = Int(try r.int32())
        guard n >= 0 else { throw DecodeError.badHeader }
        let bytes = try r.bytes(n)
        guard let obj = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let family = obj["family"] as? String,
              let color = (obj["color"] as? NSNumber)?.int64Value,
              let size = (obj["size"] as? NSNumber)?.floatValue,
              let epsilon = (obj["epsilon"] as? NSNumber)?.floatValue
        else { throw DecodeError.badHeader }
        // Absent `v` is v1 — Kotlin declares v1's values as the defaults and omits defaults.
        let v = (obj["v"] as? NSNumber)?.intValue ?? versionPx
        let unit = obj["unit"] as? String ?? unitPx
        let header = Header(family: family, color: UInt32(truncatingIfNeeded: color), size: size,
                            epsilon: epsilon, v: v, unit: unit)
        return (header, r.offset - blob.startIndex)
    }

    /// `{"family":…,"color":…,"size":…,"epsilon":…,"v":2,"unit":"du1000"}` — kotlinx.serialization's
    /// compact output for `StrokeCodec.Header`: declaration order, no spaces, floats as Kotlin's
    /// `Float.toString`, and `v`/`unit` present because they differ from the (v1) defaults.
    static func headerJSON(_ h: Header) -> String {
        var s = "{\"family\":\(JSONText.quote(h.family)),\"color\":\(h.color),"
        s += "\"size\":\(KotlinFloat.string(h.size)),\"epsilon\":\(KotlinFloat.string(h.epsilon))"
        if h.v != versionPx { s += ",\"v\":\(h.v)" }
        if h.unit != unitPx { s += ",\"unit\":\(JSONText.quote(h.unit))" }
        return s + "}"
    }

    // MARK: - Decode

    /// Decodes a blob, or throws — for a v1 pixel stroke, `.unplaceable`.
    public static func decode(_ blob: Data) throws -> Stroke {
        let (header, start) = try readHeader(blob)
        guard header.v >= versionDu else { throw DecodeError.unplaceable(version: header.v) }
        let proto = try Gzip.decompress(blob.subdata(in: (blob.startIndex + start)..<blob.endIndex))
        return Stroke(header: header, inputs: try InkProto.decodeBatch(proto))
    }

    /// Decodes, or nil for a blob that cannot be placed — Kotlin's `decodeOrNull`.
    public static func decodeOrNull(_ blob: Data) -> Stroke? { try? decode(blob) }

    // MARK: - Encode

    /// Encodes a stroke as Android would, forcing the highlighter's alpha as Kotlin's `brush()` does
    /// before `encode` reads the colour back out of the brush.
    public static func encode(_ stroke: Stroke) throws -> Data {
        var header = stroke.header
        header.v = versionDu
        header.unit = unitDu
        if header.family == StrokeCodec.familyHighlighter {
            header.color = (header.color & 0x00FF_FFFF) | (UInt32(StrokeCodec.highlighterAlpha) << 24)
        }
        let batch = try InkProto.encodeBatch(stroke.inputs)
        let json = Data(headerJSON(header).utf8)
        var out = Data()
        out.appendBE(Int32(json.count))
        out.append(json)
        out.append(try Gzip.compress(batch))
        return out
    }

    /// The same stroke scaled by `scale` about (`anchorX`, `anchorY`) and moved by (`dx`, `dy`), in
    /// document units — Kotlin's `StrokeCodec.transform`, which is how a lasso'd catch is moved or
    /// resized.
    ///
    /// Goes through the header rather than through anything re-derived from a renderer, so the brush
    /// comes out the far side what it was: family, colour and epsilon untouched, and the size scaled
    /// with the ink, because handwriting made bigger should look like handwriting written bigger.
    public static func transform(_ blob: Data, scale: Float, anchorX: Float = 0, anchorY: Float = 0,
                                 dx: Float = 0, dy: Float = 0) throws -> Data {
        var stroke = try decode(blob)
        stroke.inputs.x = stroke.inputs.x.map { anchorX + ($0 - anchorX) * scale + dx }
        stroke.inputs.y = stroke.inputs.y.map { anchorY + ($0 - anchorY) * scale + dy }
        stroke.header.size *= scale
        return try encode(stroke)
    }

    public static func translate(_ blob: Data, dx: Float, dy: Float) throws -> Data {
        try transform(blob, scale: 1, dx: dx, dy: dy)
    }
}

/// The brush families a stroke's header can name — Kotlin's `StrokeCodec` constants.
public enum StrokeCodec {
    public static let familyPressurePen = "pressure_pen"
    public static let familyMarker = "marker"
    public static let familyHighlighter = "highlighter"
    /// Highlighters tint, never cover: about 35% opacity, forced whatever swatch was picked.
    public static let highlighterAlpha: UInt8 = 0x59
    /// Graphite and chalk are the two theme-native inks: stored as drawn, swapped at render time.
    public static let graphite: UInt32 = 0xFF23_211C
    public static let chalk: UInt32 = 0xFFF1_EEE7
}

// MARK: - CodedStrokeInputBatch

/// `ink.proto.CodedStrokeInputBatch` and `CodedNumericRun`, from google/ink `ink/storage/proto/`:
///
/// ```
/// CodedNumericRun       1 deltas packed sint32 · 2 scale float [default 1] · 3 offset float
/// CodedStrokeInputBatch 1 x · 2 y · 3 elapsed_time_seconds · 4 pressure · 5 tilt · 6 orientation
///                       (all CodedNumericRun) · 7 tool_type enum · 8 stroke_unit_length_in_centimeters
///                       float · 9 noise_seed fixed32 · 11 barrel_twist CodedNumericRun
/// ```
/// `value[i] = offset + scale · Σ deltas[0…i]`.
enum InkProto {

    // The inverse scales `EncodeStrokeInputBatch` quantises with.
    static let inverseEnvelopeScale: Float = 4096
    static let defaultInverseTimeScale: Float = 1e6
    static let inversePressureScale: Float = 4096
    // `numbers::kPi` is a double, so these are worked out in double and stored as float.
    static let inverseTiltScale = Float(4096 / (Double.pi / 2))
    static let inverseOrientationScale = Float(4096 / (2 * Double.pi))

    struct Run: Equatable {
        var deltas: [Int32] = []
        var scale: Float = 1
        var offset: Float?
    }

    // MARK: encode

    /// A port of `EncodeStrokeInputBatch`. Float arithmetic is kept in Float where the C++ is, and in
    /// Double where the C++ promotes (`2.0 * semi_width / k`), so the quantisation lands on the same
    /// integers.
    static func encodeBatch(_ inputs: InkBlob.Inputs) throws -> Data {
        try validate(inputs)
        var w = ProtoWriter()
        let n = inputs.count
        guard n > 0 else {
            w.fixed32(field: 9, inputs.noiseSeed)
            return w.data
        }
        var minX = inputs.x[0], maxX = inputs.x[0], minY = inputs.y[0], maxY = inputs.y[0]
        var tMax: Float = 0
        for i in 0..<n {
            minX = min(minX, inputs.x[i]); maxX = max(maxX, inputs.x[i])
            minY = min(minY, inputs.y[i]); maxY = max(maxY, inputs.y[i])
            tMax = Float(fmax(Double(tMax), Double(inputs.t[i])))
        }
        // `Rect::SemiWidth` is `0.5f * x_max - 0.5f * x_min`, which is not always `(max - min) / 2`.
        let semiW: Float = 0.5 * maxX - 0.5 * minX, semiH: Float = 0.5 * maxY - 0.5 * minY
        let invX: Float = semiW > 0 ? (0.5 * inverseEnvelopeScale) / semiW : 1
        let invY: Float = semiH > 0 ? (0.5 * inverseEnvelopeScale) / semiH : 1
        // androidx.ink 1.0.0 — the build Android ships — stores the reciprocal of the inverse scale.
        // (google/ink's main has since moved to `2.0 * semi_width / 4096`, which lands an ulp away
        // for some spans; the device-written fixtures pin which one Android actually uses.)
        let scaleX: Float = semiW > 0 ? 1 / invX : 1
        let scaleY: Float = semiH > 0 ? 1 / invY : 1
        let invT: Float = tMax > 0
            ? Float(fmin(Double(defaultInverseTimeScale), Double(Float(Int32.max / 2)) / Double(tMax)))
            : defaultInverseTimeScale

        let originX = minX * invX, originY = minY * invY
        var x = Run(scale: scaleX, offset: minX), y = Run(scale: scaleY, offset: minY)
        var t = Run(scale: 1 / invT)
        var p = inputs.pressure.map { _ in Run(scale: 1 / inversePressureScale) }
        var tl = inputs.tilt.map { _ in Run(scale: 1 / inverseTiltScale) }
        var o = inputs.orientation.map { _ in Run(scale: 1 / inverseOrientationScale) }
        var b = inputs.barrelTwist.map { _ in Run(scale: 1 / inverseOrientationScale) }
        var last = (x: Int32(0), y: Int32(0), t: Int32(0), p: Int32(0), tl: Int32(0), o: Int32(0), b: Int32(0))
        for i in 0..<n {
            let ix = quantize(inputs.x[i], invX, originX), iy = quantize(inputs.y[i], invY, originY)
            x.deltas.append(ix &- last.x); y.deltas.append(iy &- last.y); last.x = ix; last.y = iy
            let it = quantize(inputs.t[i], invT)
            t.deltas.append(it &- last.t); last.t = it
            if let v = inputs.pressure?[i] { let q = quantize(v, inversePressureScale); p!.deltas.append(q &- last.p); last.p = q }
            if let v = inputs.tilt?[i] { let q = quantize(v, inverseTiltScale); tl!.deltas.append(q &- last.tl); last.tl = q }
            if let v = inputs.orientation?[i] { let q = quantize(v, inverseOrientationScale); o!.deltas.append(q &- last.o); last.o = q }
            if let v = inputs.barrelTwist?[i] { let q = quantize(v, inverseOrientationScale); b!.deltas.append(q &- last.b); last.b = q }
        }
        w.message(field: 1, encode(run: x))
        w.message(field: 2, encode(run: y))
        w.message(field: 3, encode(run: t))
        if let p { w.message(field: 4, encode(run: p)) }
        if let tl { w.message(field: 5, encode(run: tl)) }
        if let o { w.message(field: 6, encode(run: o)) }
        w.varint(field: 7, inputs.toolType.rawValue)
        if let cm = inputs.strokeUnitLengthCm { w.float(field: 8, cm) }
        w.fixed32(field: 9, inputs.noiseSeed)
        if let b { w.message(field: 11, encode(run: b)) }
        return w.data
    }

    /// `static_cast<int>(std::round(value * inverse_scale - scaled_origin))`.
    private static func quantize(_ v: Float, _ inverse: Float, _ origin: Float = 0) -> Int32 {
        let q = (v * inverse - origin).rounded(.toNearestOrAwayFromZero)
        return Int32(max(Float(Int32.min), min(Float(Int32.max) - 128, q)))
    }

    private static func encode(run: Run) -> Data {
        var w = ProtoWriter()
        w.packedSInt32(field: 1, run.deltas)
        w.float(field: 2, run.scale)
        if let offset = run.offset { w.float(field: 3, offset) }
        return w.data
    }

    /// The rules the native decoder enforces, checked before anything is written: Android drops a
    /// stroke that fails them, so writing one would be writing ink that silently is not there.
    static func validate(_ i: InkBlob.Inputs) throws {
        let n = i.count
        guard i.y.count == n, i.t.count == n,
              i.pressure.map({ $0.count == n }) ?? true, i.tilt.map({ $0.count == n }) ?? true,
              i.orientation.map({ $0.count == n }) ?? true, i.barrelTwist.map({ $0.count == n }) ?? true
        else { throw InkBlob.DecodeError.invalid("channels of different lengths") }
        if let cm = i.strokeUnitLengthCm, !(cm.isFinite && cm >= 0) {
            throw InkBlob.DecodeError.invalid("stroke unit length must be finite and not negative")
        }
        var previous: Float = 0
        for k in 0..<n {
            guard i.x[k].isFinite, i.y[k].isFinite else { throw InkBlob.DecodeError.invalid("non-finite position") }
            guard i.t[k].isFinite, i.t[k] >= 0 else { throw InkBlob.DecodeError.invalid("time must be finite and not negative") }
            guard i.t[k] >= previous else { throw InkBlob.DecodeError.invalid("time must not decrease") }
            previous = i.t[k]
            if let p = i.pressure?[k], !(p >= 0 && p <= 1) { throw InkBlob.DecodeError.invalid("pressure outside 0…1") }
            if let a = i.tilt?[k], !(a >= 0 && a <= .pi / 2) { throw InkBlob.DecodeError.invalid("tilt outside 0…π/2") }
            if let a = i.orientation?[k], !(a >= 0 && a < 2 * .pi) { throw InkBlob.DecodeError.invalid("orientation outside 0…2π") }
        }
    }

    // MARK: decode

    static func decodeBatch(_ data: Data) throws -> InkBlob.Inputs {
        var r = ProtoReader(data)
        var runs: [UInt64: Run] = [:]
        var tool: UInt64 = 0
        var cm: Float?
        var seed: UInt32 = 0
        while !r.atEnd {
            let (field, wire) = try r.tag()
            switch (field, wire) {
            case (1...6, 2), (11, 2): runs[field] = try decodeRun(r.lengthDelimited())
            case (7, 0): tool = try r.varint()
            case (8, 5): cm = Float(bitPattern: try r.fixed32())
            case (9, 5): seed = try r.fixed32()
            default: try r.skip(wire: wire)
            }
        }
        func values(_ f: UInt64) throws -> [Float]? {
            guard let run = runs[f] else { return nil }
            guard (run.offset ?? 0).isFinite, run.scale.isFinite else {
                throw InkBlob.DecodeError.badProto("non-finite scale or offset")
            }
            // `CodedNumericRunIterator<float>`: `static_cast<float>(offset + (double)scale * cumulative)`
            // — in double, then rounded once. Float all the way lands an ulp away often enough to
            // change the next encoding's scale.
            var sum: Int64 = 0
            let offset = Double(run.offset ?? 0), scale = Double(run.scale)
            return run.deltas.map { d in sum += Int64(d); return Float(offset + scale * Double(sum)) }
        }
        let x = try values(1) ?? [], y = try values(2) ?? [], t = try values(3) ?? []
        let n = x.count
        let p = try values(4), tl = try values(5), o = try values(6), b = try values(11)
        guard y.count == n, t.count == n, [p, tl, o, b].allSatisfy({ $0 == nil || $0!.count == n }) else {
            throw InkBlob.DecodeError.badProto("runs of different lengths")
        }
        // The native decoder skips an input identical in position and time to the one before it.
        var keep: [Int] = []
        for k in 0..<n {
            if let j = keep.last, x[j] == x[k], y[j] == y[k], t[j] == t[k] { continue }
            keep.append(k)
        }
        let pick: ([Float]?) -> [Float]? = { $0.map { a in keep.map { a[$0] } } }
        let inputs = InkBlob.Inputs(x: keep.map { x[$0] }, y: keep.map { y[$0] }, t: keep.map { t[$0] },
                                    pressure: pick(p), tilt: pick(tl), orientation: pick(o), barrelTwist: pick(b),
                                    toolType: InkBlob.ToolType(rawValue: tool) ?? .unknown,
                                    strokeUnitLengthCm: cm, noiseSeed: seed)
        var previous: Float = 0
        for v in inputs.t {
            guard v.isFinite, v >= previous else { throw InkBlob.DecodeError.badProto("time must be finite and non-decreasing") }
            previous = v
        }
        return inputs
    }

    private static func decodeRun(_ data: Data) throws -> Run {
        var r = ProtoReader(data)
        var run = Run()
        while !r.atEnd {
            let (field, wire) = try r.tag()
            switch (field, wire) {
            case (1, 2):
                var inner = ProtoReader(try r.lengthDelimited())
                while !inner.atEnd { run.deltas.append(zigzag(try inner.varint())) }
            case (1, 0): run.deltas.append(zigzag(try r.varint()))
            case (2, 5): run.scale = Float(bitPattern: try r.fixed32())
            case (3, 5): run.offset = Float(bitPattern: try r.fixed32())
            default: try r.skip(wire: wire)
            }
        }
        return run
    }

    private static func zigzag(_ v: UInt64) -> Int32 {
        let u = UInt32(truncatingIfNeeded: v)
        return Int32(bitPattern: (u >> 1) ^ (0 &- (u & 1)))
    }
}

// MARK: - protobuf wire

struct ProtoWriter {
    var data = Data()

    mutating func rawVarint(_ v: UInt64) {
        var v = v
        while v >= 0x80 { data.append(UInt8(v & 0x7F) | 0x80); v >>= 7 }
        data.append(UInt8(v))
    }
    mutating func tag(_ field: Int, _ wire: UInt64) { rawVarint(UInt64(field) << 3 | wire) }
    mutating func varint(field: Int, _ v: UInt64) { tag(field, 0); rawVarint(v) }
    mutating func fixed32(field: Int, _ v: UInt32) {
        tag(field, 5)
        data.append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)])
    }
    mutating func float(field: Int, _ v: Float) { fixed32(field: field, v.bitPattern) }
    mutating func message(field: Int, _ bytes: Data) { tag(field, 2); rawVarint(UInt64(bytes.count)); data.append(bytes) }
    /// Packed, and — like protobuf C++ — omitted entirely when empty.
    mutating func packedSInt32(field: Int, _ values: [Int32]) {
        guard !values.isEmpty else { return }
        var inner = ProtoWriter()
        for v in values { inner.rawVarint(UInt64(UInt32(bitPattern: (v << 1) ^ (v >> 31)))) }
        message(field: field, inner.data)
    }
}

struct ProtoReader {
    let data: Data
    var i: Int
    init(_ data: Data) { self.data = data; self.i = data.startIndex }
    var atEnd: Bool { i >= data.endIndex }

    mutating func byte() throws -> UInt8 {
        guard i < data.endIndex else { throw InkBlob.DecodeError.badProto("truncated") }
        defer { i += 1 }
        return data[i]
    }
    mutating func varint() throws -> UInt64 {
        var v: UInt64 = 0, shift: UInt64 = 0
        while true {
            let b = try byte()
            guard shift < 64 else { throw InkBlob.DecodeError.badProto("varint too long") }
            v |= UInt64(b & 0x7F) << shift
            if b < 0x80 { return v }
            shift += 7
        }
    }
    mutating func tag() throws -> (UInt64, UInt64) {
        let t = try varint()
        return (t >> 3, t & 7)
    }
    mutating func fixed32() throws -> UInt32 {
        let b0 = UInt32(try byte()), b1 = UInt32(try byte()), b2 = UInt32(try byte()), b3 = UInt32(try byte())
        return b0 | b1 << 8 | b2 << 16 | b3 << 24
    }
    mutating func lengthDelimited() throws -> Data {
        let n = Int(try varint())
        guard n >= 0, i + n <= data.endIndex else { throw InkBlob.DecodeError.badProto("truncated") }
        defer { i += n }
        return data.subdata(in: i..<(i + n))
    }
    mutating func skip(wire: UInt64) throws {
        switch wire {
        case 0: _ = try varint()
        case 1: for _ in 0..<8 { _ = try byte() }
        case 2: _ = try lengthDelimited()
        case 5: _ = try fixed32()
        default: throw InkBlob.DecodeError.badProto("unknown wire type \(wire)")
        }
    }
}

// MARK: - gzip

/// Java's `GZIPOutputStream` framing around zlib's raw deflate: the ten-byte header Android writes
/// (`1f 8b 08 00 00 00 00 00 00 ff`), the deflate stream at the default level, then CRC-32 and the
/// input length, little-endian. Reading accepts any valid gzip member.
enum Gzip {
    static let header: [UInt8] = [0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF]

    static func compress(_ input: Data) throws -> Data {
        var stream = z_stream()
        guard deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY,
                            ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK
        else { throw InkBlob.DecodeError.badGzip }
        defer { deflateEnd(&stream) }
        var out = Data(header)
        let bound = Int(deflateBound(&stream, uLong(input.count)))
        var buffer = [UInt8](repeating: 0, count: bound + 16)
        let status: Int32 = input.withUnsafeBytes { src in
            buffer.withUnsafeMutableBytes { dst in
                stream.next_in = UnsafeMutablePointer(mutating: src.bindMemory(to: Bytef.self).baseAddress)
                stream.avail_in = uInt(input.count)
                stream.next_out = dst.bindMemory(to: Bytef.self).baseAddress
                stream.avail_out = uInt(dst.count)
                return deflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END else { throw InkBlob.DecodeError.badGzip }
        out.append(contentsOf: buffer[0..<Int(stream.total_out)])
        let crc = input.withUnsafeBytes { crc32(0, $0.bindMemory(to: Bytef.self).baseAddress, uInt(input.count)) }
        for v in [UInt32(crc), UInt32(truncatingIfNeeded: input.count)] {
            out.append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)])
        }
        return out
    }

    static func decompress(_ input: Data) throws -> Data {
        guard input.count >= 18, input[input.startIndex] == 0x1F, input[input.startIndex + 1] == 0x8B else {
            throw InkBlob.DecodeError.badGzip
        }
        var stream = z_stream()
        // 16 + MAX_WBITS: expect and check a gzip wrapper, CRC included.
        guard inflateInit2_(&stream, 16 + MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK
        else { throw InkBlob.DecodeError.badGzip }
        defer { inflateEnd(&stream) }
        var out = Data()
        var chunk = [UInt8](repeating: 0, count: 64 * 1024)
        var status: Int32 = Z_OK
        try input.withUnsafeBytes { src in
            stream.next_in = UnsafeMutablePointer(mutating: src.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(input.count)
            repeat {
                let produced: Int = chunk.withUnsafeMutableBytes { dst in
                    stream.next_out = dst.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(dst.count)
                    status = inflate(&stream, Z_NO_FLUSH)
                    return dst.count - Int(stream.avail_out)
                }
                guard status == Z_OK || status == Z_STREAM_END else { throw InkBlob.DecodeError.badGzip }
                out.append(contentsOf: chunk[0..<produced])
            } while status != Z_STREAM_END
        }
        return out
    }
}

// MARK: - Kotlin Float.toString

/// Kotlin/JVM `Float.toString`: the shortest digits that read back as the same float, written as a
/// plain decimal with at least one fractional digit for 10⁻³ ≤ |x| < 10⁷ (`2.6`, `0.1`, `3.0`), and
/// as `d.dddE±n` outside it (`1.0E-4`, `1.2345678E7`).
enum KotlinFloat {
    static func string(_ value: Float) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value < 0 ? "-Infinity" : "Infinity" }
        if value == 0 { return value.sign == .minus ? "-0.0" : "0.0" }
        // Swift's description is the shortest round-trip digits too; only the layout differs.
        let (negative, digits, exponent) = shortest(value)
        let sign = negative ? "-" : ""
        let magnitude = abs(value)
        if magnitude >= 1e-3 && magnitude < 1e7 {
            // exponent is the power of ten of the first digit.
            if exponent >= 0 {
                let whole = digits.count > exponent + 1
                    ? String(digits.prefix(exponent + 1)) : digits + String(repeating: "0", count: exponent + 1 - digits.count)
                let frac = digits.count > exponent + 1 ? String(digits.dropFirst(exponent + 1)) : "0"
                return sign + whole + "." + frac
            }
            return sign + "0." + String(repeating: "0", count: -exponent - 1) + digits
        }
        let frac = digits.count > 1 ? String(digits.dropFirst()) : "0"
        return sign + String(digits.prefix(1)) + "." + frac + "E" + String(exponent)
    }

    /// (negative, significant digits without trailing zeros, decimal exponent of the first digit).
    private static func shortest(_ v: Float) -> (Bool, String, Int) {
        var s = "\(abs(v))"   // e.g. "2.6", "0.0001", "1e-05", "12345678.0", "1.2345678e+07"
        var exp = 0
        if let e = s.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            exp = Int(s[s.index(after: e)...])!
            s = String(s[..<e])
        }
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        let intPart = String(parts[0]), fracPart = parts.count > 1 ? String(parts[1]) : ""
        var all = intPart + fracPart
        var point = intPart.count + exp   // digits before the decimal point
        while all.hasPrefix("0") && all.count > 1 { all.removeFirst(); point -= 1 }
        while all.hasSuffix("0") && all.count > 1 { all.removeLast() }
        return (v < 0, all, point - 1)
    }
}
