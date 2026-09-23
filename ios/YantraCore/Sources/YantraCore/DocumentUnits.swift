import Foundation

/// The unit ink is stored in: **document units (du)**, 1000 of them across the width of a page —
/// `data/ink/DocumentUnits.kt`.
///
/// Not pixels, not points, and not a property of any screen. A stroke's `x` is a position on the
/// page, so the same number means the same place on a phone, on a tablet, and in a preview
/// thumbnail — the one thing view coordinates could never do, because a point is only a position if
/// you also know how wide the screen was, and that was never written down.
///
/// The page is A4-proportioned, so 1000 du spans 210 mm and one du is about 0.21 mm. That is worth
/// knowing when picking a pen width or an eraser: the numbers are physical, not arbitrary.
///
/// Page **width** is what is fixed. Zooming out reveals more pages, never a wider page — a page that
/// could widen would be a page whose du meant something different per document.
///
/// **iOS was not doing this.** It wrote `PKStroke` locations straight into the envelope, which are
/// points in whatever the canvas happened to be laid out at — so ink drawn on a phone arrived on
/// Android at roughly a third of the page, and ink from Android arrived off the bottom of the
/// phone. The conformance fixtures did not catch it because they pin the *codec*: the same bytes in
/// and out, whatever the numbers mean.
public enum DocumentUnits {
    /// One thousand across the page, and the page never widens.
    public static let pageWidth: Double = 1000
    /// Page height : width, like an A4 sheet in portrait.
    public static let pageRatio = 2.0.squareRoot()
    public static var pageHeight: Double { pageWidth * pageRatio }

    /// How many document units one point of a canvas laid out `pointsWide` across is worth.
    ///
    /// The whole mapping is this one number: the page is always 1000 du wide, so a canvas 390 points
    /// across draws each du at 0.39 points, and a canvas twice as wide draws the same page twice as
    /// large without changing a single stored coordinate.
    public static func unitsPerPoint(canvasWidth pointsWide: Double) -> Double {
        pointsWide > 0 ? pageWidth / pointsWide : 1
    }

    /// A point on screen as a position on the page.
    public static func toUnits(_ value: Double, canvasWidth: Double) -> Double {
        value * unitsPerPoint(canvasWidth: canvasWidth)
    }

    /// A position on the page as a point on this canvas.
    public static func toPoints(_ units: Double, canvasWidth: Double) -> Double {
        units / unitsPerPoint(canvasWidth: canvasWidth)
    }
}
