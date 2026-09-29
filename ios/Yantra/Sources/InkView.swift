import PencilKit
import SwiftUI
import YantraCore

/// The ink screen on PencilKit: pen, marker (highlighter), eraser, lasso and undo come from the
/// system tool picker; Apple Pencil pressure, tilt and azimuth are recorded per point. Strokes are
/// stored in Android's own `.ink` format (`InkBlob`) and rebuilt as PKStrokes on the way back, so a
/// sketch from either phone is the same sketch on the other.
///
/// **A stroke this screen did not touch is written back as the bytes it was read as.** PencilKit
/// cannot hold a stroke's original inputs, so re-deriving one from its PKStroke would quantise it
/// afresh, drop the channels PencilKit does not keep and rewrite every Android drawing the first
/// time it was opened here. `InkLedger` remembers which PKStroke came from which blob.
struct InkView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.scenePhase) private var scenePhase
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
    /// What the file held when it was read, and which stroke on the canvas each blob became.
    @State private var ledger = InkLedger()

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
                             paper: UIColor(y.inkPaper),
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
        // Written when the app is put away, as Android writes on pause: a sketch left on screen
        // must not wait for someone to press back.
        .onChange(of: scenePhase) { _, phase in if phase != .active { save() } }
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
            let inkType: PKInkingTool.InkType = slot.family == StrokeCodec.familyHighlighter ? .marker
                : slot.family == StrokeCodec.familyMarker ? .monoline : .pen
            // A slot's width is a brush size in document units, as on Android; the tool wants
            // points on this canvas.
            let width = canvasWidth > 0 ? DocumentUnits.toPoints(Double(slot.width), canvasWidth: Double(canvasWidth)) : Double(slot.width)
            return PKInkingTool(inkType,
                                color: UIColor(Color(argb: InkPalette.display(slot.color, dark: y.dark))),
                                width: CGFloat(width))
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
        ledger = InkLedger(blobs: model.storeFor(inkId).readInk(inkId))
        drawing = ledger.drawing(dark: y.dark, canvasWidth: canvasWidth)
    }

    private func save() {
        // Nothing is written before the canvas has been measured: converting at a width of zero
        // would put every stroke at the origin.
        guard canvasWidth > 0, loaded else { return }
        let (blobs, next) = ledger.blobs(for: drawing, canvasWidth: canvasWidth)
        // Opening a drawing and leaving it is not an edit, and must not look like one to sync.
        guard blobs != ledger.original else { ledger = next; return }
        model.write { try model.writerFor(inkId).writeInk(inkId, blobs) }
        ledger = next
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
        // Always light. In a dark style PencilKit treats every ink as a light-mode colour and inverts
        // it, so chalk drawn on dark paper came out dark on dark paper and vanished. The theme swap is
        // Yantra's own — graphite and chalk trade places in `InkBridge`, and nothing else changes —
        // so the canvas must draw each colour as it is given.
        v.overrideUserInterfaceStyle = .light
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
        context.coordinator.attachPinch(to: v)
        context.coordinator.watchForAPencil(on: v)
        context.coordinator.host = host
        return host
    }

    func updateUIView(_ host: InkPaperView, context: Context) {
        let v = host.canvas
        context.coordinator.parent = self
        if v.drawing != drawing, !context.coordinator.isEditing { v.drawing = drawing }
        v.backgroundColor = paper
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
            watch.onPencil = { [weak self, weak v] in
                guard let v, v.drawingPolicy != .pencilOnly else { return }
                v.drawingPolicy = .pencilOnly
                v.panGestureRecognizer.minimumNumberOfTouches = 1
                // A finger no longer draws, so the scroll view's own pinch can have it.
                self?.fingerPinch?.isEnabled = false
                v.pinchGestureRecognizer?.isEnabled = true
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

        // MARK: two fingers, while a finger draws
        //
        // `InkCanvas`'s rule: one finger draws, and **a second finger turns the gesture into a pan
        // and pinch, cancelling the stroke the first had started**. The scroll view's own pinch
        // cannot do this. A real hand never lands two fingers at once: the first arrives a few
        // milliseconds early, PencilKit starts a stroke with it, and the pinch never gets the touch.
        // The simulator's synthetic pinch puts both down together, which is why a UI test passed
        // while nobody could zoom on a phone. So this pinch runs alongside the drawing, and when it
        // begins it takes the gesture: the half-drawn stroke is cancelled and the zoom and the pan
        // are driven from here, about the point between the fingers.

        var fingerPinch: UIPinchGestureRecognizer?
        private var pinchStartZoom: CGFloat = 1
        private var pinchAnchor: CGPoint = .zero      // content point under the fingers, unscaled
        private var drawingAtPinch: PKDrawing?

        func attachPinch(to v: PKCanvasView) {
            let g = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
            g.delegate = self
            v.addGestureRecognizer(g)
            fingerPinch = g
            // One pinch, not two fighting over the zoom. The system's comes back with a Pencil.
            v.pinchGestureRecognizer?.isEnabled = false
        }

        @objc private func pinched(_ g: UIPinchGestureRecognizer) {
            guard let v = canvas ?? (g.view as? PKCanvasView), let host = v.superview else { return }
            switch g.state {
            case .began:
                // Cancel the stroke the first finger started: toggling a recogniser cancels it,
                // and PencilKit drops a stroke whose gesture was cancelled. Whatever it committed
                // anyway is put back below.
                drawingAtPinch = parent.drawing
                v.drawingGestureRecognizer.isEnabled = false
                v.drawingGestureRecognizer.isEnabled = true
                v.panGestureRecognizer.isEnabled = false
                v.panGestureRecognizer.isEnabled = true
                pinchStartZoom = v.zoomScale
                let at = g.location(in: v)
                pinchAnchor = CGPoint(x: at.x / v.zoomScale, y: at.y / v.zoomScale)
            case .changed:
                let zoom = min(max(pinchStartZoom * g.scale, v.minimumZoomScale), v.maximumZoomScale)
                if v.zoomScale != zoom { v.setZoomScale(zoom, animated: false) }
                syncContent()
                // Keep the page point that was under the fingers under them, wherever they go —
                // which is the pan as well as the pinch.
                let finger = g.location(in: host)
                let origin = v.frame.origin
                var offset = CGPoint(x: pinchAnchor.x * zoom - (finger.x - origin.x),
                                     y: pinchAnchor.y * zoom - (finger.y - origin.y))
                let inset = v.adjustedContentInset
                offset.x = min(max(offset.x, -inset.left), max(-inset.left, v.contentSize.width - v.bounds.width + inset.right))
                offset.y = min(max(offset.y, -inset.top), max(-inset.top, v.contentSize.height - v.bounds.height + inset.bottom))
                v.contentOffset = offset
            case .ended, .cancelled, .failed:
                if let before = drawingAtPinch, v.drawing.strokes.count > before.strokes.count {
                    v.drawing = before
                    parent.drawing = before
                }
                drawingAtPinch = nil
                syncContent()
            default:
                break
            }
        }

        // The pinch runs alongside the drawing — that is the whole point of it. The shape drag and
        // the canvas must not both claim the touch.
        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { g === fingerPinch }

        func canvasViewDidBeginUsingTool(_ canvasView: PKCanvasView) {
            isEditing = true
            parent.onBegin()
        }

        func canvasViewDidEndUsingTool(_ canvasView: PKCanvasView) {
            isEditing = false
            snapLastStrokeIfAsked(canvasView)
            keepHighlightersUnder(canvasView)
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

        /// A highlighter just drawn goes under the pen strokes, where Android draws every highlighter:
        /// it tints the ink, it never covers it.
        private func keepHighlightersUnder(_ v: PKCanvasView) {
            guard let last = v.drawing.strokes.last, InkBridge.isHighlighter(last),
                  v.drawing.strokes.dropLast().contains(where: { !InkBridge.isHighlighter($0) }) else { return }
            var next = v.drawing
            next.strokes = InkBridge.highlightersFirst(next.strokes.map { (InkBridge.isHighlighter($0), $0) })
            v.drawing = next
            parent.drawing = next
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

/// Which stroke on the canvas came from which blob in the file.
///
/// Every stroke is known by its path's `creationDate`, which PencilKit carries untouched through
/// undo, redo and the lasso. A stroke built from a blob is given a date that names its place in the
/// file — whole seconds after the reference date, which nobody could have drawn at — and a stroke
/// drawn here keeps the date it was drawn at. For each, the ledger holds its **base**: the blob of
/// the stroke as it stands with no transform. On save a stroke becomes:
///
/// - **untransformed**: its base, byte for byte — so an Android stroke nobody touched is written
///   back exactly as Android wrote it, and a stroke drawn here is encoded once, not on every save;
/// - **moved or resized** by the lasso (a translate or a uniform scale): its base through
///   `InkBlob.transform`, Android's own rule, so the brush comes through exactly. From the base
///   every time, so a second save does not move it twice;
/// - **bent any other way**, or new: encoded from the PKStroke.
///
/// Blobs this build cannot place — v1 pixel strokes, or a blob from a newer format — are never
/// drawn and never dropped: they go back into the file where they were, as they came.
///
/// Everything keeps its place in the file, and new strokes follow. The canvas draws highlighters
/// under pens, as Android does, which is a matter of drawing and not a reason to reorder the file.
struct InkLedger {
    private struct Entry {
        var base: Data
        var points: Int
        /// Where it sits in the file: the index it was read at, or after everything for a new one.
        var order: Int
    }

    /// The file as read, or as last written.
    private(set) var original: [Data] = []
    private var entries: [Double: Entry] = [:]
    private var unplaced: [(order: Int, blob: Data)] = []
    private var nextOrder = 0

    init() {}

    init(blobs: [Data]) {
        original = blobs
        nextOrder = blobs.count
    }

    /// PencilKit keeps a path's date at float precision, so an index is a whole number of seconds
    /// after the reference date — exact in a float up to 2²⁴.
    static func date(forIndex i: Int) -> Date { Date(timeIntervalSinceReferenceDate: Double(i)) }

    mutating func drawing(dark: Bool, canvasWidth: CGFloat) -> PKDrawing {
        entries = [:]
        unplaced = []
        var strokes: [(highlighter: Bool, stroke: PKStroke)] = []
        for (i, blob) in original.enumerated() {
            guard let s = InkBlob.decodeOrNull(blob), s.inputs.count > 0 else {
                unplaced.append((i, blob)); continue
            }
            let pk = InkBridge.stroke(from: s, dark: dark, canvasWidth: canvasWidth, date: Self.date(forIndex: i))
            entries[pk.path.creationDate.timeIntervalSinceReferenceDate] = Entry(base: blob, points: pk.path.count, order: i)
            strokes.append((s.header.family == StrokeCodec.familyHighlighter, pk))
        }
        return PKDrawing(strokes: InkBridge.highlightersFirst(strokes))
    }

    /// The file for `drawing`, and the ledger that knows it — committed by the caller once written.
    func blobs(for drawing: PKDrawing, canvasWidth: CGFloat) -> (blobs: [Data], ledger: InkLedger) {
        let perPoint = Float(DocumentUnits.unitsPerPoint(canvasWidth: Double(canvasWidth)))
        var next = self
        next.entries = [:]
        var out: [(order: Int, blob: Data)] = unplaced
        for s in drawing.strokes {
            let key = s.path.creationDate.timeIntervalSinceReferenceDate
            guard next.entries[key] == nil else { continue }   // the same stroke twice is one stroke
            var entry: Entry
            if let known = entries[key], known.points == s.path.count {
                entry = known
            } else {
                var flat = s
                flat.transform = .identity
                guard let base = try? InkBlob.encode(InkBridge.stroke(from: flat, canvasWidth: canvasWidth)) else { continue }
                entry = Entry(base: base, points: s.path.count, order: entries[key]?.order ?? next.nextOrder)
                if entries[key] == nil { next.nextOrder += 1 }
            }
            next.entries[key] = entry
            let t = s.transform
            let blob: Data?
            if t == .identity {
                blob = entry.base
            } else if t.b == 0, t.c == 0, t.a == t.d, t.a > 0 {
                blob = try? InkBlob.transform(entry.base, scale: Float(t.a),
                                              dx: Float(t.tx) * perPoint, dy: Float(t.ty) * perPoint)
            } else {
                blob = try? InkBlob.encode(InkBridge.stroke(from: s, canvasWidth: canvasWidth))
            }
            if let blob { out.append((entry.order, blob)) }
        }
        let blobs = out.sorted { $0.order < $1.order }.map(\.blob)
        next.original = blobs
        return (blobs, next)
    }
}

/// PKStroke ↔ `InkBlob.Stroke`. Families map onto brush families; the two theme-native inks swap
/// with the theme at render time and are stored as drawn.
enum InkBridge {
    /// Apple Pencil's `UITouch.maximumPossibleForce`. PencilKit reports a point's force on the same
    /// scale; androidx.ink wants pressure from 0 to 1.
    static let pencilMaxForce: CGFloat = 4.1666665
    /// Points per inch of an iPhone or iPad screen, near enough for a physical stroke length.
    static let pointsPerInch = 160.0

    /// How much wider PencilKit records a point's tip than the tool's width — measured by drawing
    /// with each tool on the simulator and reading the size back (a 1.05 pt pen records 3.29 pt;
    /// a 3.62 pt highlighter 5.32 pt).
    ///
    /// A brush size is a line width on both platforms' tools, so this is the exchange rate between
    /// the file and a PKStrokePoint. Without it an Android pen at its default 2.6 du arrived here as
    /// a tip too small for PencilKit to draw at all, and an iPhone pen arrived on Android three
    /// times too thick. Monoline has not been measured; it is assumed to draw at its tip.
    static func tipPerWidth(_ inkType: PKInkingTool.InkType) -> CGFloat {
        switch inkType {
        case .pen: return 3.1437
        case .marker: return 1.4697
        default: return 1
        }
    }

    static func family(_ inkType: PKInkingTool.InkType) -> String {
        switch inkType {
        case .marker: return StrokeCodec.familyHighlighter
        case .monoline, .pencil, .crayon, .fountainPen: return StrokeCodec.familyMarker
        default: return StrokeCodec.familyPressurePen
        }
    }

    /// Every conversion needs the width the canvas is laid out at, because that is the only thing
    /// that turns a point on this screen into a position on the page. See `DocumentUnits`.
    static func stroke(from s: PKStroke, canvasWidth: CGFloat) -> InkBlob.Stroke {
        let family = family(s.ink.inkType)
        var (r, g, b, a): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 1)
        s.ink.color.getRed(&r, green: &g, blue: &b, alpha: &a)
        func byte(_ v: CGFloat) -> UInt32 { UInt32(max(0, min(255, (v * 255).rounded()))) }
        let argb = byte(a) << 24 | byte(r) << 16 | byte(g) << 8 | byte(b)
        let width = Double(canvasWidth)
        let perPoint = DocumentUnits.unitsPerPoint(canvasWidth: width)
        // The stroke's width travels in document units too, or a pen that looks right on a phone
        // arrives on a tablet as a hairline: it is a measurement on the page like everything else.
        let size = Float(Double((s.path.first?.size.width ?? 1) / tipPerWidth(s.ink.inkType)) * perPoint)
        let points = Array(s.path)
        // A finger reports the flat defaults PencilKit fills in; a Pencil never holds them still.
        let stylus = points.contains { abs($0.altitude - .pi / 2) > 0.001 || abs($0.force - 1) > 0.001 }
        let t0 = points.first?.timeOffset ?? 0
        var last: Float = 0
        var x: [Float] = [], y: [Float] = [], t: [Float] = []
        var pressure: [Float] = [], tilt: [Float] = [], orientation: [Float] = []
        for p in points {
            let at = p.location.applying(s.transform)
            x.append(Float(DocumentUnits.toUnits(Double(at.x), canvasWidth: width)))
            y.append(Float(DocumentUnits.toUnits(Double(at.y), canvasWidth: width)))
            last = max(last, Float(max(0, p.timeOffset - t0)))
            t.append(last)
            pressure.append(Float(min(1, max(0, p.force / pencilMaxForce))))
            // PencilKit's altitude is the angle up from the glass; Ink's tilt is the angle away
            // from upright.
            tilt.append(Float(min(.pi / 2, max(0, .pi / 2 - p.altitude))))
            var az = p.azimuth.truncatingRemainder(dividingBy: 2 * .pi)
            if az < 0 { az += 2 * .pi }
            orientation.append(Float(az >= 2 * .pi ? 0 : az))
        }
        let cmPerPoint = 2.54 / pointsPerInch
        let inputs = InkBlob.Inputs(x: x, y: y, t: t,
                                    pressure: stylus ? pressure : nil, tilt: stylus ? tilt : nil,
                                    orientation: stylus ? orientation : nil,
                                    toolType: stylus ? .stylus : .touch,
                                    strokeUnitLengthCm: Float(cmPerPoint / perPoint))
        return .init(header: .init(family: family, color: argb, size: size), inputs: inputs)
    }

    static func stroke(from e: InkBlob.Stroke, dark: Bool, canvasWidth: CGFloat, date: Date) -> PKStroke {
        var argb = e.header.color
        if dark, argb == StrokeCodec.graphite { argb = StrokeCodec.chalk }
        if !dark, argb == StrokeCodec.chalk { argb = StrokeCodec.graphite }
        let highlighter = e.header.family == StrokeCodec.familyHighlighter
        let inkType: PKInkingTool.InkType = highlighter ? .marker
            : e.header.family == StrokeCodec.familyMarker ? .monoline : .pen
        // PencilKit's marker is translucent on its own; the stored 0x59 on top of it would draw a
        // highlighter at a fraction of Android's strength.
        let color = UIColor(Color(argb: highlighter ? argb | 0xFF00_0000 : argb))
        let ink = PKInk(inkType, color: color)
        let onePoint = DocumentUnits.toPoints(1, canvasWidth: Double(canvasWidth))
        let drawn = CGFloat(Double(e.header.size) * onePoint) * tipPerWidth(inkType)
        let size = CGSize(width: drawn, height: drawn)
        let i = e.inputs
        let width = Double(canvasWidth)
        var controls: [PKStrokePoint] = []
        controls.reserveCapacity(i.count)
        for k in 0..<i.count {
            let location = CGPoint(x: DocumentUnits.toPoints(Double(i.x[k]), canvasWidth: width),
                                   y: DocumentUnits.toPoints(Double(i.y[k]), canvasWidth: width))
            let force: CGFloat = i.pressure.map { CGFloat($0[k]) * pencilMaxForce } ?? 1
            let azimuth: CGFloat = i.orientation.map { CGFloat($0[k]) } ?? 0
            let altitude: CGFloat = i.tilt.map { CGFloat.pi / 2 - CGFloat($0[k]) } ?? CGFloat.pi / 2
            controls.append(PKStrokePoint(location: location, timeOffset: TimeInterval(i.t[k]), size: size,
                                          opacity: 1, force: force, azimuth: azimuth, altitude: altitude))
        }
        return PKStroke(ink: ink, path: PKStrokePath(controlPoints: controls, creationDate: date))
    }

    /// Highlighters sit under pens, whatever order they were drawn in — `StrokeCodec.isHighlighter`
    /// and the two-pass draw in `InkCanvas`. Stable within each kind.
    static func highlightersFirst(_ strokes: [(highlighter: Bool, stroke: PKStroke)]) -> [PKStroke] {
        strokes.filter(\.highlighter).map(\.stroke) + strokes.filter { !$0.highlighter }.map(\.stroke)
    }

    static func isHighlighter(_ s: PKStroke) -> Bool { s.ink.inkType == .marker }
}
