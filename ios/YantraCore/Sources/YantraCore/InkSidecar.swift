import Foundation

/// `pages/<inkBlockId>.ink`: `int32 count`, then `int32 length` + blob per stroke, big-endian, in
/// draw order — Android's `DataOutputStream`. An empty list deletes the file; the file is always
/// rewritten whole. Each blob is an `InkBlob`.
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
        guard n >= 0 else { throw InkBlob.DecodeError.truncated }
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
        guard n >= 0, offset + n <= data.endIndex else { throw InkBlob.DecodeError.truncated }
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
}
