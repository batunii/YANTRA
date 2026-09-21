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

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                NavCircle(mark: .back) { save(); path.removeLast() }
                Spacer()
                Text(model.index.nodes[inkId].flatMap { n in n.parentId.flatMap { model.index.nodes[$0]?.title } }.map { inlinePlain($0) + " · ink" } ?? "Sketch")
                    .font(Face.text(17, .bold)).foregroundStyle(y.ink).lineLimit(1)
                Spacer()
                Text(UIDevice.current.userInterfaceIdiom == .pad ? "Pen draws" : "1 finger draws").font(Face.text(12)).foregroundStyle(y.dim)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8).padding(.bottom, 8)
            PencilCanvas(drawing: $drawing, dark: y.dark, paper: y.dark ? UIColor(oklch(0.152, 0.005, 80)) : .white) { scheduleSave() }
                .clipShape(RoundedRectangle(cornerRadius: 14)).padding(.horizontal, 12).padding(.bottom, 10)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            guard !loaded else { return }
            let strokes = model.store.readInk(inkId).compactMap { try? StrokeEnvelope.decode($0) }
            drawing = PKDrawing(strokes: strokes.map { InkBridge.stroke(from: $0, dark: y.dark) })
            loaded = true
        }
        .onDisappear { save() }
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
    let onChange: () -> Void

    func makeUIView(context: Context) -> PKCanvasView {
        let v = PKCanvasView()
        v.drawing = drawing
        v.backgroundColor = paper
        v.isOpaque = true
        v.drawingPolicy = .anyInput
        v.delegate = context.coordinator
        v.overrideUserInterfaceStyle = dark ? .dark : .light
        v.tool = PKInkingTool(.pen, color: dark ? UIColor(Color(argb: 0xFFF1EEE7)) : UIColor(Color(argb: 0xFF23211C)), width: 2.6)
        let picker = PKToolPicker()
        picker.setVisible(true, forFirstResponder: v)
        picker.addObserver(v)
        context.coordinator.picker = picker
        DispatchQueue.main.async { v.becomeFirstResponder() }
        return v
    }

    func updateUIView(_ v: PKCanvasView, context: Context) {
        if v.drawing != drawing, !context.coordinator.isEditing { v.drawing = drawing }
        v.backgroundColor = paper
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        var parent: PencilCanvas
        var picker: PKToolPicker?
        var isEditing = false
        init(_ p: PencilCanvas) { parent = p }
        func canvasViewDidBeginUsingTool(_ canvasView: PKCanvasView) { isEditing = true }
        func canvasViewDidEndUsingTool(_ canvasView: PKCanvasView) { isEditing = false }
        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            parent.drawing = canvasView.drawing
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
