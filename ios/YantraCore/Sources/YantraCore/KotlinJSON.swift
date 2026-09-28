import Foundation

/// JSON written the way `FilterJson` (kotlinx.serialization, `encodeDefaults = false`) writes it:
/// compact, no trailing newline, keys in the order the type encodes them, and numbers printed as the
/// JVM prints them — `5.0`, `1.0E7`, `1.25E-4` — never `5`.
///
/// `JSONEncoder` promises neither key order nor number spelling, so every registry iOS wrote came
/// out shuffled (`{"kind":…,"name":…,"id":…}`) and every `Double` lost its `.0`. Android reads that
/// fine, which is how it went unnoticed, and rewrites it on its next touch, which is how every sync
/// became a diff about nothing.
///
/// Order comes from the `Encodable` implementation: synthesized conformances encode stored
/// properties in declaration order, which is Kotlin's declaration order wherever the Swift type
/// mirrors the Kotlin one (and they are written to). Defaults are omitted the way synthesized
/// `encodeIfPresent` omits a nil; a non-nil default (`SortSpec.desc = false`) has to be skipped by
/// the type's own `encode(to:)`, as `SortSpec` and `SelectConfig` do.
public enum KotlinJSON {
    public static func encode<T: Encodable>(_ value: T) -> String {
        let e = Impl(path: [])
        do { try value.encode(to: e) } catch { return "null" }
        return e.box.node.text
    }

    // MARK: the tree

    final class Box { var node: Node = .null }

    enum Node {
        case object(ObjectBox), array(ArrayBox), literal(String), null
        var text: String {
            switch self {
            case let .object(o): return "{" + o.pairs.map { JSONText.quote($0.0) + ":" + $0.1.node.text }.joined(separator: ",") + "}"
            case let .array(a): return "[" + a.items.map { $0.node.text }.joined(separator: ",") + "]"
            case let .literal(s): return s
            case .null: return "null"
            }
        }
    }
    final class ObjectBox {
        var pairs: [(String, Box)] = []
        func slot(_ key: String) -> Box {
            if let hit = pairs.first(where: { $0.0 == key }) { return hit.1 }
            let b = Box(); pairs.append((key, b)); return b
        }
    }
    final class ArrayBox { var items: [Box] = [] }

    // MARK: scalars

    static func literal(_ v: Bool) -> String { v ? "true" : "false" }
    static func literal(_ v: String) -> String { JSONText.quote(v) }
    static func literal<I: BinaryInteger>(_ v: I) -> String { String(v) }
    static func literal(_ v: Double) -> String { javaDouble(v) }
    static func literal(_ v: Float) -> String { KotlinFloat.string(v) }

    /// `Double.toString()` on the JVM: the shortest digits that round-trip (Swift's own
    /// `description` finds the same ones), laid out plainly between 10⁻³ and 10⁷ with at least one
    /// digit after the point, and as `d.dddE±n` outside that.
    public static func javaDouble(_ d: Double) -> String {
        if d.isNaN { return "NaN" }
        if d.isInfinite { return d > 0 ? "Infinity" : "-Infinity" }
        if d == 0 { return d.sign == .minus ? "-0.0" : "0.0" }
        let sign = d < 0 ? "-" : ""
        // Digits and a decimal point position from Swift's shortest representation.
        let s = "\(abs(d))"
        var mantissa = s, exp = 0
        if let e = s.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            mantissa = String(s[..<e]); exp = Int(s[s.index(after: e)...]) ?? 0
        }
        var digits = mantissa.replacingOccurrences(of: ".", with: "")
        var point = (mantissa.firstIndex(of: ".").map { mantissa.distance(from: mantissa.startIndex, to: $0) } ?? mantissa.count) + exp
        while digits.hasPrefix("0") && digits.count > 1 { digits.removeFirst(); point -= 1 }
        while digits.hasSuffix("0") && digits.count > 1 { digits.removeLast() }
        let a = abs(d)
        if a >= 1e-3 && a < 1e7 {
            if point <= 0 { return sign + "0." + String(repeating: "0", count: -point) + digits }
            if point >= digits.count { return sign + digits + String(repeating: "0", count: point - digits.count) + ".0" }
            let i = digits.index(digits.startIndex, offsetBy: point)
            return sign + digits[..<i] + "." + digits[i...]
        }
        let first = digits.prefix(1), rest = digits.dropFirst()
        return sign + first + "." + (rest.isEmpty ? "0" : rest) + "E" + String(point - 1)
    }

    // MARK: Encoder

    final class Impl: Encoder {
        let box = Box()
        var codingPath: [CodingKey]
        var userInfo: [CodingUserInfoKey: Any] = [:]
        init(path: [CodingKey]) { codingPath = path }

        func container<Key: CodingKey>(keyedBy type: Key.Type) -> KeyedEncodingContainer<Key> {
            let o: ObjectBox
            if case let .object(existing) = box.node { o = existing } else { o = ObjectBox(); box.node = .object(o) }
            return KeyedEncodingContainer(Keyed<Key>(object: o, path: codingPath))
        }
        func unkeyedContainer() -> UnkeyedEncodingContainer {
            let a: ArrayBox
            if case let .array(existing) = box.node { a = existing } else { a = ArrayBox(); box.node = .array(a) }
            return Unkeyed(array: a, path: codingPath)
        }
        func singleValueContainer() -> SingleValueEncodingContainer { Single(box: box, path: codingPath) }
    }

    static func encodeValue<T: Encodable>(_ v: T, into box: Box, path: [CodingKey]) throws {
        switch v {
        case let x as String: box.node = .literal(literal(x))
        case let x as Bool: box.node = .literal(literal(x))
        case let x as Double: box.node = .literal(literal(x))
        case let x as Float: box.node = .literal(literal(x))
        case let x as Int: box.node = .literal(literal(x))
        case let x as Int64: box.node = .literal(literal(x))
        case let x as Int32: box.node = .literal(literal(x))
        case let x as UInt32: box.node = .literal(literal(x))
        case let x as UInt64: box.node = .literal(literal(x))
        default:
            let sub = Impl(path: path)
            sub.box.node = box.node
            try v.encode(to: sub)
            box.node = sub.box.node
        }
    }

    struct Keyed<Key: CodingKey>: KeyedEncodingContainerProtocol {
        let object: ObjectBox
        let path: [CodingKey]
        var codingPath: [CodingKey] { path }
        func put(_ s: String, _ key: Key) { object.slot(key.stringValue).node = .literal(s) }
        mutating func encodeNil(forKey key: Key) throws { object.slot(key.stringValue).node = .null }
        mutating func encode(_ value: Bool, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: String, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: Double, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: Float, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: Int, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: Int8, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: Int16, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: Int32, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: Int64, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: UInt, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: UInt8, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: UInt16, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: UInt32, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode(_ value: UInt64, forKey key: Key) throws { put(literal(value), key) }
        mutating func encode<T: Encodable>(_ value: T, forKey key: Key) throws {
            try KotlinJSON.encodeValue(value, into: object.slot(key.stringValue), path: path + [key])
        }
        mutating func nestedContainer<NestedKey: CodingKey>(keyedBy keyType: NestedKey.Type, forKey key: Key) -> KeyedEncodingContainer<NestedKey> {
            let o = ObjectBox(); object.slot(key.stringValue).node = .object(o)
            return KeyedEncodingContainer(Keyed<NestedKey>(object: o, path: path + [key]))
        }
        mutating func nestedUnkeyedContainer(forKey key: Key) -> UnkeyedEncodingContainer {
            let a = ArrayBox(); object.slot(key.stringValue).node = .array(a)
            return Unkeyed(array: a, path: path + [key])
        }
        mutating func superEncoder() -> Encoder { superEncoder(forKey: Key(stringValue: "super")!) }
        mutating func superEncoder(forKey key: Key) -> Encoder {
            let e = Impl(path: path + [key])
            if let i = object.pairs.firstIndex(where: { $0.0 == key.stringValue }) { object.pairs[i].1 = e.box }
            else { object.pairs.append((key.stringValue, e.box)) }
            return e
        }
    }

    struct Unkeyed: UnkeyedEncodingContainer {
        let array: ArrayBox
        let path: [CodingKey]
        var codingPath: [CodingKey] { path }
        var count: Int { array.items.count }
        func next() -> Box { let b = Box(); array.items.append(b); return b }
        mutating func encodeNil() throws { next().node = .null }
        mutating func encode(_ value: Bool) throws { next().node = .literal(literal(value)) }
        mutating func encode(_ value: String) throws { next().node = .literal(literal(value)) }
        mutating func encode(_ value: Double) throws { next().node = .literal(literal(value)) }
        mutating func encode(_ value: Float) throws { next().node = .literal(literal(value)) }
        mutating func encode(_ value: Int) throws { next().node = .literal(literal(value)) }
        mutating func encode(_ value: Int64) throws { next().node = .literal(literal(value)) }
        mutating func encode<T: Encodable>(_ value: T) throws { try KotlinJSON.encodeValue(value, into: next(), path: path) }
        mutating func nestedContainer<NestedKey: CodingKey>(keyedBy keyType: NestedKey.Type) -> KeyedEncodingContainer<NestedKey> {
            let o = ObjectBox(); next().node = .object(o)
            return KeyedEncodingContainer(Keyed<NestedKey>(object: o, path: path))
        }
        mutating func nestedUnkeyedContainer() -> UnkeyedEncodingContainer {
            let a = ArrayBox(); next().node = .array(a)
            return Unkeyed(array: a, path: path)
        }
        mutating func superEncoder() -> Encoder {
            let e = Impl(path: path); array.items.append(e.box); return e
        }
    }

    struct Single: SingleValueEncodingContainer {
        let box: Box
        let path: [CodingKey]
        var codingPath: [CodingKey] { path }
        mutating func encodeNil() throws { box.node = .null }
        mutating func encode(_ value: Bool) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: String) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: Double) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: Float) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: Int) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: Int8) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: Int16) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: Int32) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: Int64) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: UInt) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: UInt8) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: UInt16) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: UInt32) throws { box.node = .literal(literal(value)) }
        mutating func encode(_ value: UInt64) throws { box.node = .literal(literal(value)) }
        mutating func encode<T: Encodable>(_ value: T) throws { try KotlinJSON.encodeValue(value, into: box, path: path) }
    }
}
