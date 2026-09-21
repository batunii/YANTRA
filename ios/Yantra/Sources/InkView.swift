import PencilKit
import SwiftUI
import YantraCore

/// The ink screen on PencilKit: pen, marker (highlighter), eraser, lasso and undo come from the
/// system tool picker; Apple Pencil pressure, tilt and azimuth are recorded per point. Strokes are
/// stored as YNK1 envelopes — the same bytes the Android app writes — and rebuilt as PKStrokes on
/// the way back, so a sketch from either phone is the same sketch on the other.
struct InkView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    let inkId: String

    @State private var drawing = PKDrawing()
    @State private var loaded = false
    @State private var saveTask: Task<Void, Never>?

    // The kit's state. All of it lives here rather than in the canvas, because the canvas is a
    // UIView that gets rebuilt and the kit must not forget which pen is in hand when it is.
    @State private var slots: [PenSlot] = []
    @State private var active = 0
    @State private var mode: InkMode = .draw
    @State private var snap = false
    @State private var panel: KitPanel?
    @State private var shapeKind: ShapeKind = .rectangle
    @State private var eraserWidth: CGFloat = 16
    @State private var penDown = false
    /// Every drawing this session, for undo. PencilKit has no undo of its own once the system
    /// picker is gone, so the screen keeps the stack the picker used to.
    @State private var history: [PKDrawing] = []
    @State private var future: [PKDrawing] = []

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                NavCircle(mark: .back) { save(); path.removeLast() }
                Spacer()
                Text(model.index.nodes[inkId].flatMap { n in n.parentId.flatMap { model.index.nodes[$0]?.title } }.map { inlinePlain($0) + " · ink" } ?? "Sketch")
                    .font(Face.text(17, .bold)).foregroundStyle(y.ink).lineLimit(1)
                Spacer()
                Color.clear.frame(width: 38, height: 38)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8).padding(.bottom, 8)

            ZStack(alignment: .bottomLeading) {
                PencilCanvas(drawing: $drawing,
                             dark: y.dark,
                             paper: y.dark ? UIColor(oklch(0.152, 0.005, 80)) : .white,
                             tool: tool,
                             shapeKind: mode == .shape ? shapeKind : nil,
                             snap: snap && mode == .draw,
                             onBegin: { penDown = true; pushHistory() },
                             onEnd: { penDown = false },
                             onChange: { scheduleSave() })
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    // A bare PKCanvasView is an unlabelled rectangle to VoiceOver as well as to a
                    // test, so the surface says what it is rather than being inferred from a hint
                    // line drawn next to it.
                    .accessibilityIdentifier("ink.canvas")
                    .accessibilityLabel("Drawing canvas")

                PenKitBar(slots: $slots, active: $active, mode: $mode, snap: $snap, panel: $panel,
                          shapeKind: $shapeKind, eraserWidth: $eraserWidth,
                          dimmed: penDown, canUndo: !history.isEmpty, canRedo: !future.isEmpty,
                          onUndo: undo, onRedo: redo)
                    .padding(12)
            }
            .padding(.horizontal, 12).padding(.bottom, 10)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            guard !loaded else { return }
            slots = InkPalette.defaultSlots(dark: y.dark)
            let strokes = model.store.readInk(inkId).compactMap { try? StrokeEnvelope.decode($0) }
            drawing = PKDrawing(strokes: strokes.map { InkBridge.stroke(from: $0, dark: y.dark) })
            loaded = true
        }
        .onDisappear { save() }
    }

    /// What the canvas should do with the next touch.
    private var tool: PKTool {
        switch mode {
        case .erase:
            // Vector, not bitmap: a stroke is the unit the file stores, so rubbing part of one out
            // would leave the sidecar holding a stroke that no longer matches what is on screen.
            return PKEraserTool(.vector)
        case .lasso:
            return PKLassoTool()
        case .draw, .shape:
            let slot = slots.indices.contains(active) ? slots[active] : InkPalette.defaultSlots(dark: y.dark)[0]
            let inkType: PKInkingTool.InkType = slot.family == StrokeCodec.familyHighlighter ? .marker : .pen
            return PKInkingTool(inkType,
                                color: UIColor(Color(argb: InkPalette.display(slot.color, dark: y.dark))),
                                width: slot.width)
        }
    }

    // MARK: undo, which the kit owns now that the system picker is gone

    private func pushHistory() {
        history.append(drawing)
        if history.count > 50 { history.removeFirst() }
        future.removeAll()
    }

    private func undo() {
        guard let last = history.popLast() else { return }
        future.append(drawing)
        drawing = last
        scheduleSave()
    }

    private func redo() {
        guard let next = future.popLast() else { return }
        history.append(drawing)
        drawing = next
        scheduleSave()
    }

    /// The file is written 900 ms after the pen lifts, as on Android, and on leaving.
    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { try? await Task.sleep(for: .milliseconds(900)); if !Task.isCancelled { save() } }
    }

    private func save() {
        let envelopes = drawing.strokes.map { InkBridge.envelope(from: $0) }
        model.write { try model.writer.writeInk(inkId, envelopes.map { StrokeEnvelope.encode($0) }) }
    }
}

struct PencilCanvas: UIViewRepresentable {
    @Binding var drawing: PKDrawing
    let dark: Bool
    let paper: UIColor
    let tool: PKTool
    /// Non-nil while the shape key is held: a drag lays out this figure instead of freehand.
    let shapeKind: ShapeKind?
    /// Whether a freehand stroke should be snapped to a shape when the pen lifts.
    let snap: Bool
    let onBegin: () -> Void
    let onEnd: () -> Void
    let onChange: () -> Void

    func makeUIView(context: Context) -> PKCanvasView {
        let v = PKCanvasView()
        v.drawing = drawing
        v.backgroundColor = paper
        v.isOpaque = true
        v.drawingPolicy = .anyInput
        v.delegate = context.coordinator
        v.overrideUserInterfaceStyle = dark ? .dark : .light
        v.tool = tool
        // No PKToolPicker. The kit is Yantra's, drawn over the canvas, so the system's tray would
        // be a second set of pens disagreeing with the first about what is in hand.
        context.coordinator.attachShapeGesture(to: v)
        return v
    }

    func updateUIView(_ v: PKCanvasView, context: Context) {
        context.coordinator.parent = self
        if v.drawing != drawing, !context.coordinator.isEditing { v.drawing = drawing }
        v.backgroundColor = paper
        v.overrideUserInterfaceStyle = dark ? .dark : .light
        v.tool = tool
        // A drag lays out a shape only while the shape key is held; otherwise the canvas gets the
        // touch and draws freehand.
        context.coordinator.shapeGesture?.isEnabled = shapeKind != nil
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PKCanvasViewDelegate, UIGestureRecognizerDelegate {
        var parent: PencilCanvas
        var isEditing = false
        weak var canvas: PKCanvasView?
        var shapeGesture: UIPanGestureRecognizer?
        private var shapeStart: CGPoint?
        /// The drawing before the shape drag began, so each move redraws from a clean slate rather
        /// than stacking one preview on the last.
        private var beforeShape: PKDrawing?

        init(_ p: PencilCanvas) { parent = p }

        func attachShapeGesture(to v: PKCanvasView) {
            canvas = v
            let g = UIPanGestureRecognizer(target: self, action: #selector(dragShape(_:)))
            g.delegate = self
            g.isEnabled = parent.shapeKind != nil
            v.addGestureRecognizer(g)
            shapeGesture = g
        }

        @objc private func dragShape(_ g: UIPanGestureRecognizer) {
            guard let v = canvas, let kind = parent.shapeKind else { return }
            let p = g.location(in: v)
            switch g.state {
            case .began:
                shapeStart = p
                beforeShape = v.drawing
                parent.onBegin()
            case .changed, .ended:
                guard let from = shapeStart, let base = beforeShape else { return }
                let pts = ShapeBuilder.points(kind, x0: from.x, y0: from.y, x1: p.x, y1: p.y)
                var next = base
                next.strokes.append(stroke(through: pts))
                v.drawing = next
                if g.state == .ended {
                    shapeStart = nil; beforeShape = nil
                    parent.drawing = next
                    parent.onEnd()
                    parent.onChange()
                }
            case .cancelled, .failed:
                if let base = beforeShape { v.drawing = base }
                shapeStart = nil; beforeShape = nil
                parent.onEnd()
            default:
                break
            }
        }

        /// A clean figure, drawn with the pen currently in hand so a shape and a freehand line are
        /// the same ink.
        private func stroke(through pts: [CGPoint]) -> PKStroke {
            let inking = parent.tool as? PKInkingTool
            let ink = PKInk(inking?.inkType ?? .pen, color: inking?.color ?? .label)
            let w = inking?.width ?? 2.6
            let size = CGSize(width: w, height: w)
            let controls = pts.enumerated().map { i, pt in
                PKStrokePoint(location: pt, timeOffset: TimeInterval(i) / 200, size: size,
                              opacity: 1, force: 1, azimuth: 0, altitude: .pi / 2)
            }
            return PKStroke(ink: ink, path: PKStrokePath(controlPoints: controls, creationDate: Date()))
        }

        // The shape drag and the canvas must not both claim the touch.
        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { false }

        func canvasViewDidBeginUsingTool(_ canvasView: PKCanvasView) {
            isEditing = true
            parent.onBegin()
        }

        func canvasViewDidEndUsingTool(_ canvasView: PKCanvasView) {
            isEditing = false
            snapLastStrokeIfAsked(canvasView)
            parent.onEnd()
        }

        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            parent.drawing = canvasView.drawing
            parent.onChange()
        }

        /// Replaces the stroke just drawn with the clean figure it reads as, if it reads as one.
        ///
        /// On lift rather than while drawing: a shape that formed under the pen would keep changing
        /// its mind mid-stroke, and the whole point is that what you drew is what you get unless it
        /// was plainly a box. Recognition is conservative and plain undo puts the freehand back.
        private func snapLastStrokeIfAsked(_ v: PKCanvasView) {
            guard parent.snap, let last = v.drawing.strokes.last else { return }
            let pts = last.path.map(\.location)
            guard let result = ShapeRecognizer.recognize(pts) else { return }
            var next = v.drawing
            next.strokes.removeLast()
            next.strokes.append(stroke(through: ShapeBuilder.points(for: result)))
            v.drawing = next
            parent.drawing = next
            parent.onChange()
        }
    }
}

/// PKStroke ↔ YNK1. Families map onto brush families; the two theme-native inks swap with the theme.
enum InkBridge {
    static let graphite: UInt32 = 0xFF23211C, chalk: UInt32 = 0xFFF1EEE7

    static func envelope(from s: PKStroke) -> StrokeEnvelope.Envelope {
        let family: String
        switch s.ink.inkType {
        case .marker: family = "highlighter"
        case .pencil, .crayon, .monoline, .fountainPen: family = "marker"
        default: family = "pressure_pen"
        }
        var (r, g, b, a): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 1)
        s.ink.color.getRed(&r, green: &g, blue: &b, alpha: &a)
        var argb = UInt32(a * 255) << 24 | UInt32(r * 255) << 16 | UInt32(g * 255) << 8 | UInt32(b * 255)
        // Chalk on dark paper is graphite in the file, so a sketch reads right on either theme.
        if argb & 0x00FFFFFF == chalk & 0x00FFFFFF { argb = graphite }
        if family == "highlighter" { argb = (argb & 0x00FFFFFF) | 0x59000000 }
        let width = Float(s.path.first?.size.width ?? 2.6)
        let t0 = s.path.first?.timeOffset ?? 0
        let points = s.path.map { p in
            StrokeEnvelope.Point(x: Float(p.location.x), y: Float(p.location.y), elapsedMillis: Int32(max(0, (p.timeOffset - t0) * 1000)),
                                 pressure: Float(p.force), tiltRadians: Float(p.altitude), orientationRadians: Float(p.azimuth), strokeUnitLengthCm: 0)
        }
        return .init(header: .init(family: family, color: argb, size: width, epsilon: 0.1), tool: StrokeEnvelope.toolStylus, points: points)
    }

    static func stroke(from e: StrokeEnvelope.Envelope, dark: Bool) -> PKStroke {
        var argb = e.header.color
        if dark, argb == graphite { argb = chalk }
        if !dark, argb == chalk { argb = graphite }
        let inkType: PKInkingTool.InkType = e.header.family == "highlighter" ? .marker : e.header.family == "marker" ? .monoline : .pen
        let color = UIColor(Color(argb: argb | (e.header.family == "highlighter" ? 0 : 0xFF000000)))
        let ink = PKInk(inkType, color: color)
        let size = CGSize(width: CGFloat(e.header.size), height: CGFloat(e.header.size))
        let controls = e.points.map { p in
            PKStrokePoint(location: CGPoint(x: CGFloat(p.x), y: CGFloat(p.y)), timeOffset: TimeInterval(p.elapsedMillis) / 1000, size: size,
                          opacity: 1, force: CGFloat(p.pressure < 0 ? 1 : p.pressure), azimuth: CGFloat(p.orientationRadians < 0 ? 0 : p.orientationRadians),
                          altitude: CGFloat(p.tiltRadians < 0 ? .pi / 2 : p.tiltRadians))
        }
        return PKStroke(ink: ink, path: PKStrokePath(controlPoints: controls, creationDate: Date()))
    }
}
