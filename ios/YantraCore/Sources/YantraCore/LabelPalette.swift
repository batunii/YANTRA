import Foundation

/// The five colours a label may wear.
///
/// Labels are the app's one open-ended, user-extensible mechanism, and they are the only place a
/// user picks a colour — the accent is not a preference and never becomes one. So this is a closed,
/// curated set rather than a colour wheel: five hues chosen to sit on warm paper.
///
/// **Why these five.** The colour law gives three hues jobs already — crimson (H≈25), coral (H≈38)
/// and amber (H≈70) — so the whole 24°–71° arc is spoken for and a label may not enter it. The five
/// below are spread across the remaining arc at roughly 50° apart, which is the closest two labels
/// can sit and still be told apart on an 11pt chip.
///
/// **Why they look like a set.** Every swatch is the same OKLCH lightness and chroma; only the hue
/// moves. Chroma is capped at what teal can actually reach in sRGB, so the set stays uniform rather
/// than letting four hues be vivid and one be flat.
///
/// **Stored vs shown.** A label stores its light-mode value; `display` swaps in the dark twin at
/// render time. So a label named in light mode still reads correctly at night, one stored value
/// covers both, and anything not from this palette passes through untouched.
///
/// Deliberately free of SwiftUI types, matching the Kotlin: nothing under the data layer may reach
/// up into the views, and the Color-shaped convenience lives at the call site.
public enum LabelPalette {

    /// One colour, in its two theme dresses. `light` is the canonical stored value.
    public struct Swatch: Equatable, Sendable {
        public let name: String, light: Int64, dark: Int64
    }

    // Generated at OKLCH L=0.600 C=0.104 (light) and L=0.730 C=0.125 (dark).
    // Hues: 140 / 190 / 240 / 290 / 335 — every one of them clear of the 24°–71° reserved arc.
    public static let swatches: [Swatch] = [
        Swatch(name: "Moss",   light: 0xFF5D8F52, dark: 0xFF7CBB6E),
        Swatch(name: "Teal",   light: 0xFF00948E, dark: 0xFF0AC0B9),
        Swatch(name: "Blue",   light: 0xFF3D88B8, dark: 0xFF54B1EE),
        Swatch(name: "Violet", light: 0xFF8075BA, dark: 0xFFA799F1),
        Swatch(name: "Plum",   light: 0xFFA66799, dark: 0xFFD889C7),
    ]

    private static let darkOf: [Int64: Int64] = Dictionary(uniqueKeysWithValues: swatches.map { ($0.light, $0.dark) })
    private static let lightOf: [Int64: Int64] = Dictionary(uniqueKeysWithValues: swatches.map { ($0.dark, $0.light) })

    /// The value to actually paint. Palette colours swap to their twin for the current theme;
    /// everything else — a legacy value, a select option's colour — is returned unchanged.
    public static func display(_ stored: Int64, dark: Bool) -> Int64 {
        (dark ? darkOf[stored] : lightOf[stored]) ?? stored
    }

    /// A palette name to its swatch, or nil if nothing is called that.
    public static func byName(_ name: String?) -> Swatch? {
        guard let n = name?.trimmingCharacters(in: .whitespaces), !n.isEmpty else { return nil }
        return swatches.first { $0.name.caseInsensitiveCompare(n) == .orderedSame }
    }

    /// Kotlin's `String.hashCode`, which is what seeds a default colour.
    ///
    /// Reproduced exactly — 32-bit, wrapping, over UTF-16 code units — because the seed has to agree
    /// across platforms: a list coloured by its name on Android and read on iOS must come out the
    /// same colour, or the same repository would look like two different ones.
    static func javaHash(_ s: String) -> Int32 {
        var h: Int32 = 0
        for u in s.utf16 { h = h &* 31 &+ Int32(u) }
        return h
    }

    static func swatchIndex(for name: String) -> Int {
        let h = Int(javaHash(name.trimmingCharacters(in: .whitespaces).lowercased()))
        let n = swatches.count
        return ((h % n) + n) % n
    }

    /// The colour a label gets when nobody picks one.
    ///
    /// Derived from the name rather than assigned in order, so the same tag is the same colour on
    /// every device and across a reinstall — and so a handful of new labels come out different
    /// colours instead of all landing on the first swatch.
    public static func defaultFor(_ name: String) -> Int64 { swatches[swatchIndex(for: name)].light }

    /// The same seed, given as a palette **name** — for the things that store a colour rather than
    /// recompute it, a workspace or a list, so the seed and the stored value are the same kind of
    /// thing and a hand-edited file can hold either.
    public static func defaultNameFor(_ name: String) -> String { swatches[swatchIndex(for: name)].name }

    /// The hues of the five light swatches, in HSV degrees: Moss 109, Teal 178, Blue 203,
    /// Violet 250, Plum 312. Measured from `swatches`, not chosen — they are the same arc.
    private static let hues: [Double] = [109, 178, 203, 250, 312]

    /// Somebody else's colour, snapped into ours — the one way a third-party hue may reach a surface
    /// of this app.
    ///
    /// A device calendar arrives with whatever colour a server assigned it, and drawing that raw is
    /// the colour law switched off: it can land anywhere, including in the 24–71 arc the law
    /// reserves for priority and effort. Snapping by hue keeps the one thing that matters — the same
    /// calendar is always the same colour — while the ink it wears is this app's.
    ///
    /// Nil when the colour is not a hue at all. A grey or near-black calendar has nothing to snap
    /// to, and the honest reading of "no colour" is frame ink rather than a hue picked by rounding.
    public static func nearest(argb: Int64, dark: Bool) -> Int64? {
        let r = Double((argb >> 16) & 0xFF) / 255, g = Double((argb >> 8) & 0xFF) / 255, b = Double(argb & 0xFF) / 255
        let maxV = max(r, g, b), minV = min(r, g, b)
        let delta = maxV - minV
        let saturation = maxV == 0 ? 0 : delta / maxV
        if saturation < 0.12 || maxV < 0.15 { return nil }
        var hue: Double
        if delta == 0 { hue = 0 }
        else if maxV == r { hue = 60 * ((g - b) / delta).truncatingRemainder(dividingBy: 6) }
        else if maxV == g { hue = 60 * ((b - r) / delta + 2) }
        else { hue = 60 * ((r - g) / delta + 4) }
        if hue < 0 { hue += 360 }

        var best = 0, bestDistance = 360.0
        for (i, h) in hues.enumerated() {
            let raw = abs(hue - h)
            let d = min(raw, 360 - raw)
            if d < bestDistance { bestDistance = d; best = i }
        }
        return dark ? swatches[best].dark : swatches[best].light
    }
}
