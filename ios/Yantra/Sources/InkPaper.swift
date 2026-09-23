import UIKit
import PencilKit
import YantraCore

/// The page furniture: the fold between one page and the next, and the number of the page below it.
///
/// Drawn **outside** the zoom on purpose — `InkCanvas.onDraw` says why, and it is worth repeating.
/// A separator is not a line drawn on the page, it is the edge of one, and a page number is a label
/// about the document rather than content in it. Inside the transform they would thin to
/// invisibility as you zoomed out and swell into slabs as you zoomed in: chrome that changes size
/// is chrome that is wrong.
final class InkPageFurniture: UIView {
    /// The page, unzoomed, in this view's points. Width is the canvas's own width at fit-width.
    var pageWidth: CGFloat = 0
    var pageHeight: CGFloat = 0
    var zoom: CGFloat = 1
    var offset: CGPoint = .zero
    var separator: UIColor = .separator
    var label: UIColor = .secondaryLabel

    /// Android draws the number 40px in from the right edge of the page at 28px tall, on a canvas
    /// whose pixels are device pixels. On a 3× screen that is these numbers.
    private let labelInset: CGFloat = 13
    private let labelSize: CGFloat = 9.5

    func place(zoom: CGFloat, offset: CGPoint, pageWidth: CGFloat, pageHeight: CGFloat) {
        guard zoom != self.zoom || offset != self.offset
                || pageWidth != self.pageWidth || pageHeight != self.pageHeight else { return }
        self.zoom = zoom; self.offset = offset
        self.pageWidth = pageWidth; self.pageHeight = pageHeight
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard pageHeight > 0, zoom > 0, let ctx = UIGraphicsGetCurrentContext() else { return }
        let left = -offset.x
        let right = pageWidth * zoom - offset.x
        let topDoc = offset.y / zoom
        let bottomDoc = (offset.y + bounds.height) / zoom

        ctx.setStrokeColor(separator.cgColor)
        ctx.setLineWidth(0.7)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: UIFont(name: "SpaceMono-Bold", size: labelSize)
                ?? UIFont.monospacedDigitSystemFont(ofSize: labelSize, weight: .bold),
            .foregroundColor: label,
        ]

        var boundary = (topDoc / pageHeight).rounded(.up) * pageHeight
        while boundary <= bottomDoc {
            let y = boundary * zoom - offset.y
            // Not the top edge of the document: that is the top of the paper, not a fold.
            if y > 0.5 {
                ctx.move(to: CGPoint(x: left, y: y))
                ctx.addLine(to: CGPoint(x: right, y: y))
                ctx.strokePath()
                let text = "\(Int(boundary / pageHeight) + 1)" as NSString
                let size = text.size(withAttributes: attrs)
                text.draw(at: CGPoint(x: right - labelInset - size.width, y: y + 5), withAttributes: attrs)
            }
            boundary += pageHeight
        }
    }
}

/// The drawing surface and the furniture that floats over it.
///
/// Two views rather than one, because the ink scrolls and zooms and the furniture does not. The
/// canvas is a `UIScrollView`, so a subview of it would be dragged around by the content; a sibling
/// laid out to the same bounds stays where the glass is.
final class InkPaperView: UIView {
    let canvas = PKCanvasView()
    let furniture = InkPageFurniture()

    override init(frame: CGRect) {
        super.init(frame: frame)
        addSubview(canvas)
        addSubview(furniture)
        furniture.isUserInteractionEnabled = false
        furniture.backgroundColor = .clear
        furniture.isOpaque = false
        // Said here rather than with SwiftUI's accessibility modifiers, because those are applied
        // to a representable once and are not refreshed when their arguments change: the page
        // number went on saying "1 of 2" for the whole life of a drawing that had grown to four.
        // A bare PKCanvasView is an unlabelled rectangle to VoiceOver as well as to a test, so the
        // surface says what it is rather than being inferred from a hint line drawn next to it.
        isAccessibilityElement = true
        accessibilityIdentifier = "ink.canvas"
        accessibilityLabel = "Drawing canvas"
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError("not from a nib") }

    override func layoutSubviews() {
        super.layoutSubviews()
        canvas.frame = bounds
        furniture.frame = bounds
    }
}

/// Notices the first time a pencil touches the glass — `onStylusModeChanged` in `InkCanvas`.
///
/// It never recognises: it fails on the first touch it sees, so it takes nothing from the canvas
/// underneath and only reports. There is no other way to ask, and it has to be asked, because the
/// two ways of drawing want opposite things from a finger. Before a pencil, the finger is the pen
/// and two fingers scroll. After one, the finger is a hand resting on the page while you write, and
/// a hand resting on the page must scroll rather than leave a mark.
///
/// `drawingPolicy = .default` sounds like exactly this rule and is not: it stops the finger drawing
/// but does not hand the finger back to the scroll view, so one finger did nothing whatsoever.
final class StylusWatch: UIGestureRecognizer {
    var onPencil: () -> Void = {}

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if touches.contains(where: { $0.type == .pencil }) { onPencil() }
        state = .failed
    }
}
