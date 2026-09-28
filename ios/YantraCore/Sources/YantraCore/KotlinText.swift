import Foundation

/// The handful of `kotlin.text` and `java.lang` string operations the file format is defined by,
/// reproduced exactly — `PageCodec.kt` is written in them, and the bytes follow from them.
///
/// Swift's own versions are not the same functions under other names, and the differences are the
/// kind that only show on somebody else's data:
///
/// - `String.count` and `hasPrefix` work in **grapheme clusters**; Kotlin's `length` and
///   `startsWith` work in UTF-16 units. `"#\u{301}"` is one Swift character and two Kotlin chars, so
///   `w.count > 1` refused a label Kotlin accepts, and `hasPrefix("#")` said no to a word Kotlin
///   says yes to. A space followed by a combining mark is not `" "` to Swift at all.
/// - `trimmingCharacters(in: .whitespaces)` is Unicode `Zs` plus tab. Kotlin's `trim()` is
///   `Character.isWhitespace || Character.isSpaceChar`, which also takes the line separators and the
///   ASCII information separators, and does not take NEL. The exact set is below, taken from a JVM.
/// - ICU's `\d` and `\s` are Unicode; `java.util.regex`'s are ASCII.
/// - `Int("…")` is 64-bit and ASCII; Kotlin's `toIntOrNull` is 32-bit and takes any decimal digit.
///
/// Every delimiter the format uses is ASCII or in the BMP, so working on Unicode scalars gives the
/// same boundaries Kotlin's UTF-16 indexing does, without ever splitting a surrogate pair.
enum KText {
    /// `Char.isWhitespace()` on the JVM: `Character.isWhitespace(c) || Character.isSpaceChar(c)`.
    /// Enumerated from Java 25 over the whole BMP; nothing outside the BMP qualifies.
    static func isWhitespace(_ u: Unicode.Scalar) -> Bool {
        switch u.value {
        case 0x09...0x0D, 0x1C...0x20, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: return true
        default: return false
        }
    }

    /// `\s` in `java.util.regex` without `UNICODE_CHARACTER_CLASS`: `[ \t\n\x0B\f\r]`.
    static func isRegexSpace(_ u: Unicode.Scalar) -> Bool {
        switch u.value { case 0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D: return true; default: return false }
    }

    /// A line terminator as `java.util.regex` sees one, for `.` and `$`.
    static func isLineTerminator(_ u: Unicode.Scalar) -> Bool {
        switch u.value { case 0x0A, 0x0D, 0x85, 0x2028, 0x2029: return true; default: return false }
    }

    static func isAsciiDigit(_ u: Unicode.Scalar) -> Bool { u.value >= 0x30 && u.value <= 0x39 }

    /// `String.trim()`.
    static func trim(_ s: String) -> String {
        let v = s.unicodeScalars
        guard let first = v.firstIndex(where: { !isWhitespace($0) }) else { return "" }
        let last = v.lastIndex(where: { !isWhitespace($0) })!
        return String(String.UnicodeScalarView(v[first...last]))
    }

    /// `String.trimStart()`.
    static func trimStart(_ s: String) -> String {
        let v = s.unicodeScalars
        guard let first = v.firstIndex(where: { !isWhitespace($0) }) else { return "" }
        return String(String.UnicodeScalarView(v[first...]))
    }

    /// `String.trimEnd()`.
    static func trimEnd(_ s: String) -> String {
        let v = s.unicodeScalars
        guard let last = v.lastIndex(where: { !isWhitespace($0) }) else { return "" }
        return String(String.UnicodeScalarView(v[...last]))
    }

    /// `CharSequence.isBlank()`.
    static func isBlank(_ s: String) -> Bool { s.unicodeScalars.allSatisfy(isWhitespace) }

    /// `split(c)` — every piece kept, empty ones included.
    static func split(_ s: String, _ sep: Unicode.Scalar) -> [String] {
        s.unicodeScalars.split(separator: sep, omittingEmptySubsequences: false).map { String(String.UnicodeScalarView($0)) }
    }

    /// `startsWith(prefix)`, by code unit rather than by grapheme.
    static func starts(_ s: String, _ prefix: String) -> Bool { s.unicodeScalars.starts(with: prefix.unicodeScalars) }

    /// `endsWith(suffix)`, by code unit.
    static func ends(_ s: String, _ suffix: String) -> Bool {
        let a = Array(s.unicodeScalars), b = Array(suffix.unicodeScalars)
        return a.count >= b.count && Array(a[(a.count - b.count)...]) == b
    }

    /// `drop(n)` for a prefix of `n` scalars — callers only ever drop an ASCII prefix they just
    /// matched, so scalars and UTF-16 units are the same count.
    static func drop(_ s: String, _ n: Int) -> String {
        String(String.UnicodeScalarView(s.unicodeScalars.dropFirst(n)))
    }

    /// `removePrefix(prefix)`.
    static func removePrefix(_ s: String, _ prefix: String) -> String {
        starts(s, prefix) ? drop(s, prefix.unicodeScalars.count) : s
    }

    /// `length`, which is UTF-16 units.
    static func length(_ s: String) -> Int { s.utf16.count }

    /// `contains(other)`, literally — no canonical equivalence.
    static func contains(_ s: String, _ other: String) -> Bool {
        s.range(of: other, options: .literal) != nil
    }

    /// Exact equality by code unit. Swift's `==` on `String` is canonical equivalence, Kotlin's is
    /// not; the two only disagree on text whose normalisation differs, which is exactly the text a
    /// comparison must not quietly treat as the same bytes.
    static func same(_ a: String, _ b: String) -> Bool { a.utf16.elementsEqual(b.utf16) }

    /// The decimal value of a character as `Character.digit(c, 10)` reads it: any BMP decimal digit.
    static func digit(_ u: Unicode.Scalar) -> Int? {
        if isAsciiDigit(u) { return Int(u.value - 0x30) }
        guard u.value <= 0xFFFF, u.properties.numericType == .decimal, let v = u.properties.numericValue else { return nil }
        return Int(v)
    }

    /// `String.toLongOrNull()` within `[min, max]` — Kotlin's own algorithm: an optional leading
    /// `+` or `-`, then at least one decimal digit, and nothing that overflows.
    static func toInteger(_ s: String, min: Int64, max: Int64) -> Int64? {
        let v = Array(s.unicodeScalars)
        guard !v.isEmpty else { return nil }
        var i = 0, negative = false
        if v[0] == "-" || v[0] == "+" {
            guard v.count > 1 else { return nil }
            negative = v[0] == "-"; i = 1
        }
        var total: Int64 = 0
        while i < v.count {
            guard let d = digit(v[i]) else { return nil }
            let (m, o1) = total.multipliedReportingOverflow(by: 10)
            if o1 { return nil }
            let (n, o2) = negative ? m.subtractingReportingOverflow(Int64(d)) : m.addingReportingOverflow(Int64(d))
            if o2 { return nil }
            total = n
            if total > max || total < min { return nil }
            i += 1
        }
        return total
    }

    /// `String.toIntOrNull()`.
    static func toInt(_ s: String) -> Int? { toInteger(s, min: Int64(Int32.min), max: Int64(Int32.max)).map { Int($0) } }

    /// `String.toLongOrNull()`.
    static func toLong(_ s: String) -> Int64? { toInteger(s, min: Int64.min, max: Int64.max) }

    /// `readLines()`: `\n`, `\r\n` and a lone `\r` all end a line, and a final terminator does not
    /// start another one.
    static func readLines(_ s: String) -> [String] {
        var out: [String] = [], cur = String.UnicodeScalarView()
        var it = s.unicodeScalars.makeIterator(), pendingCR = false
        while let u = it.next() {
            if pendingCR { pendingCR = false; if u == "\n" { continue } }
            if u == "\n" { out.append(String(cur)); cur = .init() }
            else if u == "\r" { out.append(String(cur)); cur = .init(); pendingCR = true }
            else { cur.append(u) }
        }
        if !cur.isEmpty { out.append(String(cur)) }
        return out
    }
}
