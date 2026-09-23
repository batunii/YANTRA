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
    /// What the canvas is laid out at, which is the whole of the mapping to document units.
    @State private var canvasWidth: CGFloat = 0
    /// How far in the camera is. The page being read needs no state here: it is drawn on the fold
    /// it belongs to and said out loud by the canvas itself.
    @State private var zoomPercent = 100
    @State private var fitToken = 0
    @State private var history: [PKDrawing] = []
    @State private var future: [PKDrawing] = []

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                NavCircle(mark: .back) { save(); path.removeLast() }.accessibilityIdentifier("nav.back")
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
                             pages: pages,
                             fitToken: fitToken,
                             separator: UIColor(y.inkPageSep),
                             pageLabel: UIColor(y.dim),
                             onBegin: { penDown = true; pushHistory() },
                             onEnd: { penDown = false },
                             onChange: { scheduleSave() },
                             onZoom: { zoomPercent = $0 })
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    // What it is and which page you are on are set on the view itself — see
                    // `InkPaperView`.
                    // The width the page is drawn at, reported so strokes can be stored as
                    // positions on the page rather than points on this screen.
                    .background(GeometryReader { g in
                        Color.clear
                            .onAppear { canvasWidth = g.size.width }
                            .onChange(of: g.size.width) { _, w in canvasWidth = w }
                    })

                PenKitBar(slots: $slots, active: $active, mode: $mode, snap: $snap, panel: $panel,
                          shapeKind: $shapeKind, eraserWidth: $eraserWidth,
                          dimmed: penDown, canUndo: !history.isEmpty, canRedo: !future.isEmpty,
                          onUndo: undo, onRedo: redo)
                    .padding(12)
            }
            // How far in you are, and the way back out. Only while you are in: at one page across
            // it would be a control that says "you are where you started".
            .overlay(alignment: .topTrailing) {
                if zoomPercent != 100 {
                    Button { fitToken += 1 } label: {
                        HStack(spacing: 8) {
                            Text("\(zoomPercent)%").font(Face.mono(11, bold: true)).foregroundStyle(y.dim)
                            Text("fit").font(Face.text(11, .bold)).foregroundStyle(y.accentText)
                        }
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: Layout.panelRadius).fill(y.cardBg))
                        .overlay(RoundedRectangle(cornerRadius: Layout.panelRadius).stroke(y.tileBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("ink.zoom")
                    .accessibilityLabel("\(zoomPercent) per cent — fit the page to the width")
                    .padding(12)
                }
            }
            .padding(.horizontal, 12).padding(.bottom, 10)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            guard !loaded, canvasWidth > 0 else { return }
            load()
        }
        // The page cannot be placed until the canvas has a width, and on a first appearance the
        // layout has not happened yet. Loading again when the width arrives — and when it changes,
        // as it does on a rotation — is what keeps a stroke on the same part of the page whatever
        // shape the glass is.
        .onChange(of: canvasWidth) { _, width in
            guard width > 0 else { return }
            if !loaded { load() } else { redraw() }
        }
        .onDisappear { save() }
    }

    /// How many pages the document has: as many as the ink reaches onto, plus one to grow into.
    ///
    /// Measured from the drawing rather than stored, so it is never stale and never a second answer
    /// to a question the strokes already answer.
    private var pages: Int {
        guard canvasWidth > 0, !drawing.bounds.isNull else { return InkPages.totalPages(maxDocY: 0) }
        return InkPages.totalPages(maxDocY: DocumentUnits.toUnits(Double(drawing.bounds.maxY),
                                                                 canvasWidth: Double(canvasWidth)))
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

    /// Reads the page in, at the width the canvas currently has.
    private func load() {
        slots = InkPalette.defaultSlots(dark: y.dark)
        redraw()
        loaded = true
    }

    /// Re-places what is stored at the current width, without touching the file.
    private func redraw() {
        let strokes = model.storeFor(inkId).readInk(inkId).compactMap { try? StrokeEnvelope.decode($0) }
        drawing = PKDrawing(strokes: strokes.map { InkBridge.stroke(from: $0, dark: y.dark, canvasWidth: canvasWidth) })
    }

    private func save() {
        // Nothing is written before the canvas has been measured: converting at a width of zero
        // would put every stroke at the origin.
        guard canvasWidth > 0 else { return }
        let envelopes = drawing.strokes.map { InkBridge.envelope(from: $0, canvasWidth: canvasWidth) }
        model.write { try model.writerFor(inkId).writeInk(inkId, envelopes.map { StrokeEnvelope.encode($0) }) }
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
    /// How many pages the document is treated as having: the ink's own, plus one to grow into.
    let pages: Int
    /// Bumped to put the page back to one across. A count rather than a flag, because "fit" asked
    /// for twice in a row is two requests, and a flag would swallow the second.
    let fitToken: Int
    let separator: UIColor
    let pageLabel: UIColor
    let onBegin: () -> Void
    let onEnd: () -> Void
    let onChange: () -> Void
    /// The zoom as a percentage, whenever it changes. The page being read does not come this way:
    /// it is the canvas's own accessibility value, set where it cannot go stale.
    var onZoom: (Int) -> Void = { _ in }

    func makeUIView(context: Context) -> InkPaperView {
        let host = InkPaperView()
        let v = host.canvas
        v.drawing = drawing
        v.backgroundColor = paper
        v.isOpaque = true
        // A finger draws until a pencil turns up, and scrolls from then on — `onStylusModeChanged`
        // in `InkCanvas`, which is the same rule. The switch is `StylusWatch`; until it fires, one
        // finger draws and two pan and pinch.
        v.drawingPolicy = .anyInput
        v.delegate = context.coordinator
        v.overrideUserInterfaceStyle = dark ? .dark : .light
        v.tool = tool
        // The document is taller than the glass and can be looked at from closer or further away.
        // The limits are Android's — see `InkPages`.
        v.minimumZoomScale = CGFloat(InkPages.minZoom)
        v.maximumZoomScale = CGFloat(InkPages.maxZoom)
        v.bouncesZoom = true
        v.showsVerticalScrollIndicator = false
        v.showsHorizontalScrollIndicator = false
        v.panGestureRecognizer.minimumNumberOfTouches = 2
        // No PKToolPicker. The kit is Yantra's, drawn over the canvas, so the system's tray would
        // be a second set of pens disagreeing with the first about what is in hand.
        context.coordinator.attachShapeGesture(to: v)
        context.coordinator.watchForAPencil(on: v)
        context.coordinator.host = host
        return host
    }

    func updateUIView(_ host: InkPaperView, context: Context) {
        let v = host.canvas
        context.coordinator.parent = self
        if v.drawing != drawing, !context.coordinator.isEditing { v.drawing = drawing }
        v.backgroundColor = paper
        v.overrideUserInterfaceStyle = dark ? .dark : .light
        v.tool = tool
        host.furniture.separator = separator
        host.furniture.label = pageLabel
        // A drag lays out a shape only while the shape key is held; otherwise the canvas gets the
        // touch and draws freehand.
        context.coordinator.shapeGesture?.isEnabled = shapeKind != nil
        if context.coordinator.lastFitToken != fitToken {
            context.coordinator.lastFitToken = fitToken
            // Not animated. The document's size has to be recomputed for the new zoom, and doing
            // that while the scroll view is mid-animation cancels the animation and leaves the page
            // wherever it had got to — a "fit" that fits nothing.
            v.setZoomScale(1, animated: false)
        }
        context.coordinator.syncContent()
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PKCanvasViewDelegate, UIGestureRecognizerDelegate {
        var parent: PencilCanvas
        var isEditing = false
        weak var canvas: PKCanvasView?
        weak var host: InkPaperView?
        var shapeGesture: UIPanGestureRecognizer?
        var lastFitToken = 0
        // What the screen was last told, so it is not told again for no reason. Every one of these
        // writes SwiftUI state, and an unchanged report during a pinch costs a frame's worth of
        // work per frame to say nothing.
        private var lastPage = -1, lastPages = -1, lastPercent = -1, lastReportedZoom = -1
        private var shapeStart: CGPoint?
        /// The drawing before the shape drag began, so each move redraws from a clean slate rather
        /// than stacking one preview on the last.
        private var beforeShape: PKDrawing?

        init(_ p: PencilCanvas) { parent = p }

        /// The moment a pencil is used, the finger stops being the pen and goes back to scrolling.
        func watchForAPencil(on v: PKCanvasView) {
            let watch = StylusWatch(target: nil, action: nil)
            watch.onPencil = { [weak v] in
                guard let v, v.drawingPolicy != .pencilOnly else { return }
                v.drawingPolicy = .pencilOnly
                v.panGestureRecognizer.minimumNumberOfTouches = 1
            }
            v.addGestureRecognizer(watch)
        }

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
            syncContent()
        }

        // MARK: the camera
        //
        // PKCanvasView *is* a UIScrollView, so the pan, the clamp and the pinch about a focal point
        // are already written and already correct. What is left is the size of the document it is
        // looking at, and telling the screen what it is looking at — see `InkPages`.

        /// The document's size in the canvas's own coordinates, and the furniture over it.
        func syncContent() {
            guard let host, host.canvas.bounds.width > 0 else { return }
            let v = host.canvas
            let width = v.bounds.width
            let pageHeight = width * CGFloat(DocumentUnits.pageRatio)
            let z = v.zoomScale
            let size = CGSize(width: width * z, height: pageHeight * CGFloat(max(parent.pages, 1)) * z)
            if v.contentSize != size { v.contentSize = size }
            // A page narrower than the glass sits in the middle of it. Pinned to one edge with all
            // the empty space on the other reads as the page having slipped.
            let slack = max(0, (v.bounds.width - size.width) / 2)
            if v.contentInset.left != slack {
                v.contentInset = UIEdgeInsets(top: 0, left: slack, bottom: 0, right: slack)
            }
            host.furniture.place(zoom: z, offset: v.contentOffset, pageWidth: width, pageHeight: pageHeight)
            report()
        }

        private func report() {
            guard let host, host.canvas.bounds.width > 0 else { return }
            let v = host.canvas
            let width = Double(v.bounds.width)
            let z = Double(v.zoomScale)
            guard z > 0 else { return }
            let topDu = DocumentUnits.toUnits(Double(v.contentOffset.y) / z, canvasWidth: width)
            let visibleDu = DocumentUnits.toUnits(Double(v.bounds.height) / z, canvasWidth: width)
            let page = InkPages.currentPage(topDu: topDu, visibleHeightDu: visibleDu, pages: parent.pages)
            let percent = InkPages.percent(zoom: z)
            guard page != lastPage || parent.pages != lastPages || percent != lastPercent else { return }
            lastPage = page; lastPages = parent.pages; lastPercent = percent
            // The fold and its number are pixels on a page: drawn for the eye and absent from the
            // accessibility tree, exactly like the now-playing bar used to be.
            host.accessibilityValue = "Page \(page) of \(parent.pages)"
            guard percent != lastReportedZoom else { return }
            lastReportedZoom = percent
            // Next turn of the loop, not this one. "Fit" arrives through `updateUIView`, and a
            // SwiftUI state change made during a view update is discarded — the zoom went back to
            // one page across and the readout went on saying 267%, because the assignment that
            // would have hidden it happened at the one moment SwiftUI ignores.
            let say = parent.onZoom
            DispatchQueue.main.async { say(percent) }
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) { syncContent() }

        func scrollViewDidZoom(_ scrollView: UIScrollView) { syncContent() }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
            syncContent()
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

    /// Every conversion needs the width the canvas is laid out at, because that is the only thing
    /// that turns a point on this screen into a position on the page. See `DocumentUnits`.
    static func envelope(from s: PKStroke, canvasWidth: CGFloat) -> StrokeEnvelope.Envelope {
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
        // The **stroke's width travels in document units too**, or a pen that looks right on a
        // phone arrives on a tablet as a hairline: it is a measurement on the page, like everything
        // else in the envelope.
        let perPoint = DocumentUnits.unitsPerPoint(canvasWidth: Double(canvasWidth))
        let width = Float(Double(s.path.first?.size.width ?? 2.6) * perPoint)
        let t0 = s.path.first?.timeOffset ?? 0
        let points = s.path.map { p in
            StrokeEnvelope.Point(x: Float(DocumentUnits.toUnits(Double(p.location.x), canvasWidth: Double(canvasWidth))),
                                 y: Float(DocumentUnits.toUnits(Double(p.location.y), canvasWidth: Double(canvasWidth))),
                                 elapsedMillis: Int32(max(0, (p.timeOffset - t0) * 1000)),
                                 pressure: Float(p.force), tiltRadians: Float(p.altitude), orientationRadians: Float(p.azimuth), strokeUnitLengthCm: 0)
        }
        return .init(header: .init(family: family, color: argb, size: width, epsilon: 0.1), tool: StrokeEnvelope.toolStylus, points: points)
    }

    static func stroke(from e: StrokeEnvelope.Envelope, dark: Bool, canvasWidth: CGFloat) -> PKStroke {
        var argb = e.header.color
        if dark, argb == graphite { argb = chalk }
        if !dark, argb == chalk { argb = graphite }
        let inkType: PKInkingTool.InkType = e.header.family == "highlighter" ? .marker : e.header.family == "marker" ? .monoline : .pen
        let color = UIColor(Color(argb: argb | (e.header.family == "highlighter" ? 0 : 0xFF000000)))
        let ink = PKInk(inkType, color: color)
        let onePoint = DocumentUnits.toPoints(1, canvasWidth: Double(canvasWidth))
        let drawn = CGFloat(Double(e.header.size) * onePoint)
        let size = CGSize(width: drawn, height: drawn)
        let controls = e.points.map { p in
            PKStrokePoint(location: CGPoint(x: DocumentUnits.toPoints(Double(p.x), canvasWidth: Double(canvasWidth)),
                                            y: DocumentUnits.toPoints(Double(p.y), canvasWidth: Double(canvasWidth))),
                          timeOffset: TimeInterval(p.elapsedMillis) / 1000, size: size,
                          opacity: 1, force: CGFloat(p.pressure < 0 ? 1 : p.pressure), azimuth: CGFloat(p.orientationRadians < 0 ? 0 : p.orientationRadians),
                          altitude: CGFloat(p.tiltRadians < 0 ? .pi / 2 : p.tiltRadians))
        }
        return PKStroke(ink: ink, path: PKStrokePath(controlPoints: controls, creationDate: Date()))
    }
}
