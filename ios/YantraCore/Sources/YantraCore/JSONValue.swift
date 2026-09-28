import Foundation

/// A JSON value that keeps its key order and its number literals, so a file re-written from it comes
/// out byte-identical to the Kotlin side (kotlinx.serialization writes objects compactly, in
/// insertion order, and re-emits a number exactly as it was read).
///
/// This used to back a field-by-field manifest merge. Android at 17d0beb has none — a conflicted
/// manifest keeps the local side like every other non-page file — so that merge is gone and only the
/// value type stays.
public indirect enum JSONValue: Equatable, Sendable {
    case object([(String, JSONValue)])
    case array([JSONValue])
    case string(String)
    case number(String)   // the literal as written
    case bool(Bool)
    case null

    public static func == (l: JSONValue, r: JSONValue) -> Bool {
        switch (l, r) {
        case let (.object(a), .object(b)):
            return a.count == b.count && zip(a, b).allSatisfy { $0.0 == $1.0 && $0.1 == $1.1 }
        case let (.array(a), .array(b)): return a == b
        case let (.string(a), .string(b)): return a == b
        case let (.number(a), .number(b)): return a == b
        case let (.bool(a), .bool(b)): return a == b
        case (.null, .null): return true
        default: return false
        }
    }

    public subscript(key: String) -> JSONValue? {
        if case let .object(pairs) = self { return pairs.first { $0.0 == key }?.1 }
        return nil
    }

    public var intValue: Int? {
        if case let .number(s) = self { return Int(s) }
        return nil
    }

    /// Compact serialisation, kotlinx style: no whitespace, insertion order.
    public func compact() -> String {
        switch self {
        case let .object(pairs): return "{" + pairs.map { JSONText.quote($0.0) + ":" + $0.1.compact() }.joined(separator: ",") + "}"
        case let .array(items): return "[" + items.map { $0.compact() }.joined(separator: ",") + "]"
        case let .string(s): return JSONText.quote(s)
        case let .number(n): return n
        case let .bool(b): return b ? "true" : "false"
        case .null: return "null"
        }
    }

    public static func parse(_ text: String) throws -> JSONValue {
        var p = JSONParser(Array(text.utf8))
        let v = try p.value()
        p.skipWS()
        guard p.atEnd else { throw JSONParser.Failure.trailing }
        return v
    }
}

// MARK: - a small strict JSON parser that keeps order and literals

struct JSONParser {
    enum Failure: Error { case unexpected(Int), trailing, badNumber, badString }
    let b: [UInt8]
    var i = 0
    init(_ bytes: [UInt8]) { b = bytes }
    var atEnd: Bool { i >= b.count }

    mutating func skipWS() { while i < b.count, [0x20, 0x09, 0x0A, 0x0D].contains(b[i]) { i += 1 } }

    mutating func value() throws -> JSONValue {
        skipWS()
        guard i < b.count else { throw Failure.unexpected(i) }
        switch b[i] {
        case UInt8(ascii: "{"):
            i += 1; var pairs: [(String, JSONValue)] = []
            skipWS()
            if i < b.count, b[i] == UInt8(ascii: "}") { i += 1; return .object(pairs) }
            while true {
                skipWS(); let k = try string(); skipWS()
                guard i < b.count, b[i] == UInt8(ascii: ":") else { throw Failure.unexpected(i) }
                i += 1
                pairs.append((k, try value())); skipWS()
                guard i < b.count else { throw Failure.unexpected(i) }
                if b[i] == UInt8(ascii: ",") { i += 1; continue }
                if b[i] == UInt8(ascii: "}") { i += 1; return .object(pairs) }
                throw Failure.unexpected(i)
            }
        case UInt8(ascii: "["):
            i += 1; var items: [JSONValue] = []
            skipWS()
            if i < b.count, b[i] == UInt8(ascii: "]") { i += 1; return .array(items) }
            while true {
                items.append(try value()); skipWS()
                guard i < b.count else { throw Failure.unexpected(i) }
                if b[i] == UInt8(ascii: ",") { i += 1; continue }
                if b[i] == UInt8(ascii: "]") { i += 1; return .array(items) }
                throw Failure.unexpected(i)
            }
        case UInt8(ascii: "\""): return .string(try string())
        case UInt8(ascii: "t"): try literal("true"); return .bool(true)
        case UInt8(ascii: "f"): try literal("false"); return .bool(false)
        case UInt8(ascii: "n"): try literal("null"); return .null
        default:
            let start = i
            while i < b.count, "+-0123456789.eE".utf8.contains(b[i]) { i += 1 }
            guard i > start else { throw Failure.unexpected(i) }
            return .number(String(decoding: b[start..<i], as: UTF8.self))
        }
    }

    mutating func literal(_ s: String) throws {
        let u = Array(s.utf8)
        guard i + u.count <= b.count, Array(b[i..<(i + u.count)]) == u else { throw Failure.unexpected(i) }
        i += u.count
    }

    mutating func string() throws -> String {
        guard i < b.count, b[i] == UInt8(ascii: "\"") else { throw Failure.badString }
        i += 1
        var out: [UInt8] = []
        while i < b.count {
            let c = b[i]; i += 1
            if c == UInt8(ascii: "\"") { return String(decoding: out, as: UTF8.self) }
            if c == UInt8(ascii: "\\") {
                guard i < b.count else { throw Failure.badString }
                let e = b[i]; i += 1
                switch e {
                case UInt8(ascii: "\""): out.append(0x22)
                case UInt8(ascii: "\\"): out.append(0x5C)
                case UInt8(ascii: "/"): out.append(0x2F)
                case UInt8(ascii: "b"): out.append(0x08)
                case UInt8(ascii: "f"): out.append(0x0C)
                case UInt8(ascii: "n"): out.append(0x0A)
                case UInt8(ascii: "r"): out.append(0x0D)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "u"):
                    guard i + 4 <= b.count, let cp = UInt32(String(decoding: b[i..<(i + 4)], as: UTF8.self), radix: 16) else { throw Failure.badString }
                    i += 4
                    var scalar = cp
                    // A surrogate pair arrives as two \u escapes.
                    if (0xD800...0xDBFF).contains(cp), i + 6 <= b.count, b[i] == UInt8(ascii: "\\"), b[i + 1] == UInt8(ascii: "u"),
                       let lo = UInt32(String(decoding: b[(i + 2)..<(i + 6)], as: UTF8.self), radix: 16), (0xDC00...0xDFFF).contains(lo) {
                        i += 6
                        scalar = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00)
                    }
                    guard let u = Unicode.Scalar(scalar) else { throw Failure.badString }
                    out.append(contentsOf: Array(String(Character(u)).utf8))
                default: throw Failure.badString
                }
            } else { out.append(c) }
        }
        throw Failure.badString
    }
}
