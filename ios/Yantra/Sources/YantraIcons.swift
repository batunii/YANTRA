import SwiftUI

/// One mark, in one ink.
struct YantraIcon: View {
    let mark: YantraMark
    var size: CGFloat = YantraIcons.medium
    var tint: Color?
    var label: String?
    @Environment(\.y) private var y
    /// The mark scales with the reader's text size like everything else — see `Face`.
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let side = size * scale
        Canvas { ctx, canvasSize in
            YantraMarkDrawing.draw(mark, in: ctx,
                                   rect: CGRect(origin: .zero, size: canvasSize),
                                   color: tint ?? y.secondary)
        }
        .frame(width: side, height: side)
        .accessibilityHidden(label == nil)
        .accessibilityLabel(label ?? "")
    }
}

/// The paths themselves. Separate from the view so a call site that already owns a `Canvas` — a
/// row background, a block, a composed glyph — can draw one without nesting another.
