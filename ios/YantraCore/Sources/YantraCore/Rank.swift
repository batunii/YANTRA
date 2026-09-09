import Foundation

/// Fractional (LexoRank-style) sibling ordering. Ranks are base-36 strings compared
/// lexicographically; `between` returns a rank strictly between its two bounds without
/// renumbering neighbours, which keeps concurrent reorders conflict-free under sync.
///
/// Invariant: generated ranks never end in `0`, so a valid midpoint always exists.
///
/// A byte-for-byte port of `data/rank/Rank.kt`. Ranks are never written to files — both apps
/// regenerate them from line order — so they must agree exactly, and `conformance/rank` says so.
public enum Rank {
    static let alphabet: [Character] = Array("0123456789abcdefghijklmnopqrstuvwxyz")
    static let base = alphabet.count

    /// Rank for the first item in an empty sibling set.
    public static let first: String = between(nil, nil)

    /// A rank r with a < r < b (lexicographically). nil bounds mean -inf / +inf.
    public static func between(_ a: String?, _ b: String?) -> String {
        if let a, let b { precondition(a < b, "invalid rank bounds: '\(a)' !< '\(b)'") }
        let lo = a.map(Array.init) ?? []
        var hi: [Character]? = b.map(Array.init)
        var out: [Character] = []
        var i = 0
        while out.count < 128 {
            let dLo = i < lo.count ? digit(lo[i]) : 0
            let dHi: Int
            if let h = hi { dHi = i < h.count ? digit(h[i]) : 0 } else { dHi = base }
            if dHi - dLo > 1 {
                out.append(alphabet[(dLo + dHi) / 2])
                return String(out)
            } else if dHi == dLo {
                out.append(alphabet[dLo])
                i += 1
            } else {
                // Gap of exactly 1: take the low digit; the upper bound is then satisfied by this
                // position alone, so it becomes +inf below here.
                out.append(alphabet[dLo])
                hi = nil
                i += 1
            }
        }
        preconditionFailure("rank generation did not converge between '\(a ?? "-inf")' and '\(b ?? "+inf")'")
    }

    /// Rank after all existing siblings (`last` = current max rank, or nil if none).
    public static func after(_ last: String?) -> String { between(last, nil) }

    /// Rank before all existing siblings (`first` = current min rank, or nil if none).
    public static func before(_ first: String?) -> String { between(nil, first) }

    private static func digit(_ c: Character) -> Int {
        guard let d = alphabet.firstIndex(of: c) else { preconditionFailure("invalid rank char: \(c)") }
        return d
    }
}
