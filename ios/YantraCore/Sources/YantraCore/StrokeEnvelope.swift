import Foundation

/// One ink stroke on disk — Yantra's own layout, shared with the Android app.
///
/// ```
/// "YNK1"                        4 bytes, magic + version
/// int32  headerLength           big-endian
/// bytes  header                 UTF-8 JSON: {family, color, size, epsilon}
/// uint8  tool                   0 unknown · 1 mouse · 2 touch · 3 stylus
/// int32  pointCount
/// per point, big-endian:
///   float32 x, float32 y        stroke units (document space)
///   int32   elapsedMillis       since the stroke began
///   float32 pressure            0..1, negative when the device did not report one
///   float32 tiltRadians         negative when not reported
///   float32 orientationRadians  negative when not reported
///   float32 strokeUnitLengthCm  0 when unknown
/// ```
///
/// A sidecar (`pages/<blockId>.ink`) is `int32 count` then `int32 length + bytes` per stroke;
/// see `InkSidecar`. Strokes whose magic this build does not know are carried through a rewrite
/// untouched and skipped on render, never dropped.
public enum StrokeEnvelope {

    public static let magic = Array("YNK1".utf8)

    public enum Kind { case yantra, legacy, unknown }

    public struct Header: Codable, Equatable, Sendable {
        public var family: String
        /// ARGB as an unsigned 32-bit value.
        public var color: UInt32
        public var size: Float
        public var epsilon: Float
        public init(family: String, color: UInt32, size: Float, epsilon: Float) {
            self.family = family; self.color = color; self.size = size; self.epsilon = epsilon
        }
    }

    public struct Point: Equatable, Sendable {
        public var x: Float
        public var y: Float
        public var elapsedMillis: Int32
        public var pressure: Float
        public var tiltRadians: Float
        public var orientationRadians: Float
        public var strokeUnitLengthCm: Float
        public init(x: Float, y: Float, elapsedMillis: Int32, pressure: Float, tiltRadians: Float, orientationRadians: Float, strokeUnitLengthCm: Float) {
            self.x = x; self.y = y; self.elapsedMillis = elapsedMillis; self.pressure = pressure
            self.tiltRadians = tiltRadians; self.orientationRadians = orientationRadians; self.strokeUnitLengthCm = strokeUnitLengthCm
        }
    }

    public struct Envelope: Equatable, Sendable {
        public var header: Header
        public var tool: UInt8
        public var points: [Point]
        public init(header: Header, tool: UInt8, points: [Point]) {
            self.header = header; self.tool = tool; self.points = points
        }
        public func translated(dx: Float, dy: Float) -> Envelope {
            var e = self
            e.points = points.map { var p = $0; p.x += dx; p.y += dy; return p }
            return e
        }
    }

    public static let toolUnknown: UInt8 = 0, toolMouse: UInt8 = 1, toolTouch: UInt8 = 2, toolStylus: UInt8 = 3

    public enum DecodeError: Error { case notYantra(Kind), truncated, badHeader, trailingBytes, implausible(String) }

    /// Sniffs the bytes without decoding them. The first format opened with a big-endian header
    /// length — first byte zero, fifth byte `{`. Ours opens with `YNK`. Anything else, including a
    /// `YNK` with a digit this build does not know, is `.unknown`.
    public static func kind(_ data: Data) -> Kind {
        let b = [UInt8](data.prefix(5))
        if b.count >= 4, b[0] == magic[0], b[1] == magic[1], b[2] == magic[2] {
            return b[3] == magic[3] ? .yantra : .unknown
        }
        if b.count >= 5, b[0] == 0, b[4] == UInt8(ascii: "{") { return .legacy }
        return .unknown
    }

    public static func encode(_ e: Envelope) -> Data {
        // The header must serialise exactly as kotlinx.serialization does for byte parity:
        // {"family":"…","color":N,"size":F,"epsilon":F}, no spaces, this key order.
        let header = headerJSON(e.header)
        var out = Data(capacity: 64 + header.count + e.points.count * 28)
        out.append(contentsOf: magic)
        out.appendBE(Int32(header.count))
        out.append(header)
        out.append(e.tool)
        out.appendBE(Int32(e.points.count))
        for p in e.points {
            out.appendBE(p.x); out.appendBE(p.y); out.appendBE(p.elapsedMillis)
            out.appendBE(p.pressure); out.appendBE(p.tiltRadians); out.appendBE(p.orientationRadians); out.appendBE(p.strokeUnitLengthCm)
        }
        return out
    }

    public static func decode(_ data: Data) throws -> Envelope {
        let k = kind(data)
        guard k == .yantra else { throw DecodeError.notYantra(k) }
        var r = BigEndianReader(data, offset: 4)
        let headerLen = Int(try r.int32())
        guard (1...65536).contains(headerLen) else { throw DecodeError.implausible("header length \(headerLen)") }
        let headerData = try r.bytes(headerLen)
        let header = try parseHeader(headerData)
        let tool = try r.uint8()
        let count = Int(try r.int32())
        guard count >= 0 else { throw DecodeError.implausible("negative point count") }
        var points: [Point] = []
        points.reserveCapacity(count)
        for _ in 0..<count {
            points.append(Point(
                x: try r.float(), y: try r.float(), elapsedMillis: try r.int32(),
                pressure: try r.float(), tiltRadians: try r.float(), orientationRadians: try r.float(),
                strokeUnitLengthCm: try r.float()))
        }
        guard r.atEnd else { throw DecodeError.trailingBytes }
        return Envelope(header: header, tool: tool, points: points)
    }

    // MARK: header JSON, byte-compatible with the Kotlin side

    static func headerJSON(_ h: Header) -> Data {
        var s = "{\"family\":" + JSONText.quote(h.family)
        s += ",\"color\":\(h.color)"
        s += ",\"size\":" + JSONText.kotlinFloat(h.size)
        s += ",\"epsilon\":" + JSONText.kotlinFloat(h.epsilon) + "}"
        return Data(s.utf8)
    }

    static func parseHeader(_ data: Data) throws -> Header {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let family = obj["family"] as? String,
              let color = obj["color"] as? NSNumber,
              let size = obj["size"] as? NSNumber,
              let epsilon = obj["epsilon"] as? NSNumber else { throw DecodeError.badHeader }
        return Header(family: family, color: UInt32(truncatingIfNeeded: color.int64Value), size: size.floatValue, epsilon: epsilon.floatValue)
    }
}

/// The whole-sidecar container: `int32 count`, then `int32 length` + bytes per stroke.
public enum InkSidecar {
    public static func encode(_ strokes: [Data]) -> Data {
        var out = Data()
        out.appendBE(Int32(strokes.count))
        for s in strokes { out.appendBE(Int32(s.count)); out.append(s) }
        return out
    }
    public static func decode(_ data: Data) throws -> [Data] {
        var r = BigEndianReader(data, offset: 0)
        let n = Int(try r.int32())
        guard n >= 0 else { throw StrokeEnvelope.DecodeError.implausible("negative stroke count") }
        var out: [Data] = []
        for _ in 0..<n { out.append(try r.bytes(Int(try r.int32()))) }
        return out
    }
}

// MARK: - byte helpers

struct BigEndianReader {
    let data: Data
    var offset: Int
    init(_ data: Data, offset: Int) { self.data = data; self.offset = data.startIndex + offset }
    var atEnd: Bool { offset == data.endIndex }
    mutating func bytes(_ n: Int) throws -> Data {
        guard n >= 0, offset + n <= data.endIndex else { throw StrokeEnvelope.DecodeError.truncated }
        defer { offset += n }
        return data.subdata(in: offset..<(offset + n))
    }
    mutating func uint8() throws -> UInt8 { try bytes(1)[0] }
    mutating func int32() throws -> Int32 {
        let b = try bytes(4)
        return Int32(bitPattern: UInt32(b[b.startIndex]) << 24 | UInt32(b[b.startIndex + 1]) << 16 | UInt32(b[b.startIndex + 2]) << 8 | UInt32(b[b.startIndex + 3]))
    }
    mutating func float() throws -> Float { Float(bitPattern: UInt32(bitPattern: try int32())) }
}

extension Data {
    mutating func appendBE(_ v: Int32) {
        let u = UInt32(bitPattern: v)
        append(contentsOf: [UInt8(u >> 24), UInt8((u >> 16) & 0xFF), UInt8((u >> 8) & 0xFF), UInt8(u & 0xFF)])
    }
    mutating func appendBE(_ v: Float) { appendBE(Int32(bitPattern: v.bitPattern)) }
}

/// Just enough JSON text handling to match kotlinx.serialization byte for byte where it matters.
enum JSONText {
    static func quote(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        return out + "\""
    }

    /// Kotlin's `Float.toString()`: shortest repr that round-trips, always with a decimal point
    /// (`2.6`, `9.0`, `0.1`), scientific only outside 1e-3 ..< 1e7. Matches how the Kotlin side
    /// writes `size` and `epsilon` into the header.
    static func kotlinFloat(_ f: Float) -> String {
        if f.isNaN { return "NaN" }
        if f.isInfinite { return f > 0 ? "Infinity" : "-Infinity" }
        let a = abs(f)
        if a != 0 && (a < 1e-3 || a >= 1e7) {
            // Java: d.dddE±n. Swift's shortest repr gives the digits; reshape the exponent.
            let s = "\(f)" // e.g. "1e-05" or "1.5e+07"
            guard let eIdx = s.firstIndex(where: { $0 == "e" || $0 == "E" }) else { return s }
            var mant = String(s[..<eIdx]); let exp = Int(s[s.index(after: eIdx)...]) ?? 0
            if !mant.contains(".") { mant += ".0" }
            return "\(mant)E\(exp)"
        }
        let s = "\(f)"
        return s.contains(".") ? s : s + ".0"
    }
}
