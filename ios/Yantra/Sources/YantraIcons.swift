import SwiftUI

/// The functional icon set — a port of `ui/components/YantraIcons.kt`, path for path.
///
/// Every mark is drawn in the same 28-unit space at the same 1.6-unit stroke as the task glyph, so
/// a row of icons and a checkbox beside them are demonstrably one family. This file is the single
/// source of truth: nothing else in the app may redefine one of these marks locally, and nothing
/// here carries a colour.
///
/// **Why these and not SF Symbols.** The app used SF Symbols, which is the right instinct on iOS
/// and the wrong answer here: Yantra's marks are a drawn language with a stated stroke ratio, and
/// borrowing Apple's meant the two apps disagreed about what a list, a label or a repeat looks
/// like. It also let a real mistake through — Home's primary key, which *makes* something, was
/// drawing a gearshape.
///
/// **Monochrome by construction.** A mark is a path; its ink arrives as `tint` at the call site.
///
/// **Fill is the exception, not the default.** There are exactly two, both written down so a third
/// cannot arrive quietly:
///
/// 1. **The bindu.** `more` is three of them and `ringLive` carries one; the bindu is a filled point
///    everywhere in this language, so drawing it hollow would make it a different mark.
/// 2. **The transport keys.** `play` and `pause` are solid, because the play key is the *only*
///    control in the now player and a hairline triangle read as decoration beside its own filled
///    counter. It is the control, not the mark, that earns this.
///
/// Note what is *not* the exception: `add`, the primary action on two screens, stays hairline
/// because it sits on a filled key already carrying the weight.
enum YantraMark: String, CaseIterable {
    case task, focus, smartList, list, properties, priority, play, pause, add, more
    case ink, image, delete, calendar, stats, settings
    /// A task you have taken up.
    case ring
    /// The same ring with the centre point in it: the card the clock is on.
    case ringLive
    /// This tap leaves Yantra.
    case openOut
    case back, forward
    /// Up and down. The same wedge as `back`; a chevron is one drawing at four rotations.
    case up, down
    /// A tick. Confirmation, and the "this is chosen" state on a chip.
    case check
    /// A cross. Dismiss, and remove-this-one. Never destructive — that is `delete`.
    case close
    case stop, undo, redo
    /// A reminder that will speak. A bell, not a clock: the point is that it comes to you.
    case alarm
    /// A time. One mark for both a due time and a deadline — the chips already say which in words.
    case clock
    /// A label. The tag shape, matching the `#name` form a label takes in the file.
    case label
    case heading, numbered
    /// The grip on a block you can move.
    case drag
    case copy
    /// Send. The quick-add bar's key.
    case send
    case refresh
    /// This repeats. Two arcs chasing each other, not a cycle arrow.
    case repeatMark
    case expand, collapse, indentIn, indentOut
    /// Shapes, in the ink kit: a square and a circle overlapping.
    case shapes
    /// Somebody. A bindu for the head over an arc for the shoulders.
    case person
    case personOff
    case lasso, eraser
    /// A group: a gate with rules inside it. Not a folder — nothing here is filed.
    case group
}

enum YantraIcons {
    /// The design space every path is written in.
    static let space: CGFloat = 28
    /// Stroke width in design units. 1.6 / 28 is the task glyph's ratio.
    static let stroke: CGFloat = 1.6
    /// The only three sizes. In a chip, in a row or circle, on a key.
    static let small: CGFloat = 16, medium: CGFloat = 20, large: CGFloat = 24
}

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
enum YantraMarkDrawing {

    static func draw(_ mark: YantraMark, in ctx: GraphicsContext, rect: CGRect, color: Color) {
        let u = min(rect.width, rect.height) / YantraIcons.space
        let shading = GraphicsContext.Shading.color(color)
        let style = StrokeStyle(lineWidth: YantraIcons.stroke * u, lineCap: .round, lineJoin: .round)

        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * u, y: y * u) }

        func line(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) {
            var path = Path(); path.move(to: p(x1, y1)); path.addLine(to: p(x2, y2))
            ctx.stroke(path, with: shading, style: style)
        }
        func dot(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
            ctx.fill(Path(ellipseIn: CGRect(x: (cx - r) * u, y: (cy - r) * u, width: 2 * r * u, height: 2 * r * u)), with: shading)
        }
        func ring(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
            ctx.stroke(Path(ellipseIn: CGRect(x: (cx - r) * u, y: (cy - r) * u, width: 2 * r * u, height: 2 * r * u)),
                       with: shading, style: style)
        }
        func box(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, radius: CGFloat = 0, fill: Bool = false) {
            let path = Path(roundedRect: CGRect(x: x * u, y: y * u, width: w * u, height: h * u),
                            cornerRadius: radius * u)
            if fill { ctx.fill(path, with: shading) } else { ctx.stroke(path, with: shading, style: style) }
        }
        /// A polyline through the given points, optionally closed.
        func poly(_ pts: [(CGFloat, CGFloat)], closed: Bool = false, fill: Bool = false) {
            var path = Path()
            guard let first = pts.first else { return }
            path.move(to: p(first.0, first.1))
            for q in pts.dropFirst() { path.addLine(to: p(q.0, q.1)) }
            if closed { path.closeSubpath() }
            if fill { ctx.fill(path, with: shading) } else { ctx.stroke(path, with: shading, style: style) }
        }
        /// An arc, in the same terms Compose's `drawArc` takes: a bounding box, a start angle
        /// measured from three o'clock, and a sweep.
        func arc(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat,
                 start: Double, sweep: Double, dashed: Bool = false) {
            let box = CGRect(x: x * u, y: y * u, width: w * u, height: h * u)
            // Always drawn in the increasing-angle direction, with a negative sweep folded into its
            // own start. A stroke has no direction, so this is the same arc either way — the point
            // is that it removes SwiftUI's `clockwise:` flag, which reads as the opposite of what
            // it does in a y-down space and is silent when it is wrong.
            let from = sweep < 0 ? start + sweep : start
            let span = abs(sweep)
            var path = Path()
            path.addArc(center: CGPoint(x: box.midX, y: box.midY),
                        radius: box.width / 2,
                        startAngle: .degrees(from), endAngle: .degrees(from + span),
                        clockwise: false)
            // An ellipse is drawn as a circle then squashed, which is what Compose's arc-in-a-rect
            // does and the only way to get a non-square sweep out of Path.addArc.
            if box.height != box.width {
                path = path.applying(CGAffineTransform(translationX: 0, y: -box.midY)
                    .concatenating(CGAffineTransform(scaleX: 1, y: box.height / box.width))
                    .concatenating(CGAffineTransform(translationX: 0, y: box.midY)))
            }
            var s = style
            if dashed { s.dash = [2.6 * u, 2.2 * u] }
            ctx.stroke(path, with: shading, style: s)
        }

        switch mark {
        case .task:
            // The task glyph's own outline, so a mark and a checkbox are the same shape.
            ctx.stroke(bhupuraPath(min(rect.width, rect.height)), with: shading, style: style)

        case .focus:
            ring(14, 14, 9)
            // A quarter from twelve o'clock: the mark is a ledger showing some of the circle is
            // spent — not a clock hand, and not a progress bar to be read off.
            arc(5, 5, 18, 18, start: -90, sweep: 90)
            dot(14, 14, 2.2)

        case .smartList:
            line(4, 8, 24, 8); line(8, 14, 20, 14); line(12, 20, 16, 20); dot(14, 25, 1.6)

        case .list:
            line(5, 8, 23, 8); line(5, 14, 23, 14); line(5, 20, 23, 20)

        case .properties:
            box(3, 7, 22, 6, radius: 3); box(3, 16, 14, 6, radius: 3)

        case .priority:
            line(7, 4, 7, 25)
            poly([(8, 5), (22, 5), (18, 10), (22, 15), (8, 15)])

        case .play:
            poly([(9, 6), (23, 14), (9, 22)], closed: true, fill: true)

        case .pause:
            // Bars rather than two fat strokes: a filled rectangle is a shape with a width, where a
            // thickened line is a stroke pretending to be one, and the two do not scale alike.
            box(10, 7, 3.2, 14, radius: 0.8, fill: true)
            box(16.8, 7, 3.2, 14, radius: 0.8, fill: true)

        case .add:
            line(14, 5, 14, 23); line(5, 14, 23, 14)

        case .more:
            dot(14, 6, 1.9); dot(14, 14, 1.9); dot(14, 22, 1.9)

        case .ink:
            poly([(19, 4), (24, 9), (11, 22), (5, 24), (7, 18)], closed: true)
            line(7, 18, 11, 22)

        case .image:
            box(4, 6, 20, 17, radius: 3)
            poly([(6, 20), (12, 13), (16, 17), (19, 14), (22, 17)])
            dot(10, 11, 1.5)

        case .delete:
            line(6, 9, 22, 9); line(6, 19, 22, 19); line(4, 23, 24, 5)

        case .calendar:
            box(4, 6, 20, 18, radius: 3)
            line(10, 3, 10, 8); line(18, 3, 18, 8); line(4, 12, 24, 12)
            dot(14, 18, 2)

        case .stats:
            box(6, 16, 4.5, 8, radius: 2); box(12, 11, 4.5, 13, radius: 2); box(18, 6, 4.5, 18, radius: 2)

        case .settings:
            line(5, 8, 23, 8); line(5, 14, 23, 14); line(5, 20, 23, 20)
            dot(18, 8, 2.4); dot(10, 14, 2.4); dot(19, 20, 2.4)

        case .ring:
            ring(14, 14, 9)

        case .ringLive:
            ring(14, 14, 9); dot(14, 14, 2.9)

        case .openOut:
            poly([(14, 5), (5, 5), (5, 23), (23, 23), (23, 14)])
            line(14, 14, 24, 4)
            poly([(18, 4), (24, 4), (24, 10)])

        case .back:    poly([(17, 5), (8, 14), (17, 23)])
        case .forward: poly([(11, 5), (20, 14), (11, 23)])
        case .up:      poly([(5, 18), (14, 9), (23, 18)])
        case .down:    poly([(5, 10), (14, 19), (23, 10)])

        case .check:   poly([(6, 15), (11.5, 20.5), (22, 8)])
        case .close:   line(7, 7, 21, 21); line(21, 7, 7, 21)

        case .stop:    box(9, 9, 10, 10, radius: 1.2, fill: true)

        case .undo:
            poly([(9, 10), (5, 14), (9, 18)])
            arc(5, 6, 18, 16, start: 180, sweep: -150)

        case .redo:
            poly([(19, 10), (23, 14), (19, 18)])
            arc(5, 6, 18, 16, start: 0, sweep: 150)

        case .alarm:
            var bell = Path()
            bell.move(to: p(8, 18))
            bell.addLine(to: p(8, 13))
            bell.addCurve(to: p(14, 7), control1: p(8, 9), control2: p(10.5, 7))
            bell.addCurve(to: p(20, 13), control1: p(17.5, 7), control2: p(20, 9))
            bell.addLine(to: p(20, 18))
            bell.closeSubpath()
            ctx.stroke(bell, with: shading, style: style)
            line(6, 18, 22, 18)
            line(12.5, 21.5, 15.5, 21.5)

        case .clock:
            ring(14, 14, 9); line(14, 8.5, 14, 14); line(14, 14, 18, 16.5)

        case .label:
            poly([(14, 5), (23, 5), (23, 14), (12, 25), (3, 16)], closed: true)
            dot(19, 9, 1.5)

        case .heading:
            line(6, 6, 6, 20); line(14, 6, 14, 20); line(6, 13, 14, 13); line(18, 13, 23, 13)

        case .numbered:
            line(12, 8, 23, 8); line(12, 14, 23, 14); line(12, 20, 23, 20)
            line(6, 6, 6, 10); line(4.5, 7.5, 6, 6)
            poly([(4.5, 12.5), (7.5, 12.5), (4.5, 16), (7.5, 16)])
            poly([(4.5, 18.5), (7.5, 18.5), (7.5, 21.5), (4.5, 21.5)])

        case .drag:
            line(9, 11, 19, 11); line(9, 17, 19, 17)

        case .copy:
            box(4, 4, 16, 16, radius: 2); box(9, 9, 16, 16, radius: 2)

        case .send:
            poly([(4, 14), (24, 5), (15, 24), (12.5, 15.5)], closed: true)

        case .refresh:
            arc(5, 5, 18, 18, start: 60, sweep: 280)
            poly([(17, 4), (22.5, 8.5), (17, 11.5)])

        case .repeatMark:
            // Two arcs chasing each other. Not a closed cycle: what repeats is the next one, not a
            // loop you are inside.
            arc(5, 6, 18, 16, start: 150, sweep: 150)
            poly([(18, 4.5), (22.5, 8), (17.5, 10.5)])
            arc(5, 6, 18, 16, start: -30, sweep: 150)
            poly([(10, 23.5), (5.5, 20), (10.5, 17.5)])

        case .expand:
            poly([(12, 5), (23, 5), (23, 16)])
            poly([(16, 23), (5, 23), (5, 12)])
            line(23, 5, 15, 13); line(5, 23, 13, 15)

        case .collapse:
            poly([(23, 12), (16, 12), (16, 5)])
            poly([(5, 16), (12, 16), (12, 23)])
            line(16, 12, 23, 5); line(12, 16, 5, 23)

        case .indentIn:
            line(11, 7, 23, 7); line(11, 14, 23, 14); line(11, 21, 23, 21)
            poly([(4, 10), (8, 14), (4, 18)])

        case .indentOut:
            line(11, 7, 23, 7); line(11, 14, 23, 14); line(11, 21, 23, 21)
            poly([(8, 10), (4, 14), (8, 18)])

        case .shapes:
            box(4, 4, 13, 13, radius: 1.5); ring(18, 18, 6)

        case .person:
            // The head is a bindu, the shoulders an arc — the two forms this language already has.
            dot(14, 9.5, 3.6)
            arc(6, 15, 16, 16, start: 180, sweep: 180)

        case .personOff:
            dot(14, 9.5, 3.6)
            arc(6, 15, 16, 16, start: 180, sweep: 180)
            // Corner to corner, so it reads as negation rather than as part of the figure.
            line(5, 5, 23, 23)

        case .group:
            box(4, 6, 20, 16, radius: 2)
            line(9, 12.5, 19, 12.5); line(9, 17, 19, 17)

        case .lasso:
            // A loop that does not quite close — a lasso is a region you indicate, not a shape you draw.
            arc(4.5, 4.5, 19, 17, start: 110, sweep: 320, dashed: true)
            line(8, 21, 6.5, 25)

        case .eraser:
            // The block, tilted, with the rule it is riding along.
            poly([(9, 19), (17, 6), (24, 10), (16, 23)], closed: true)
            line(4, 23, 20, 23)
        }
    }
}
