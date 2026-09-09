import SwiftUI
import YantraCore

@main
struct YantraApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var theme = ThemeController()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(theme)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var theme: ThemeController
    @Environment(\.colorScheme) private var scheme
    @State private var path = NavigationPath()

    var body: some View {
        let y = theme.colors(systemDark: scheme == .dark)
        NavigationStack(path: $path) {
            HomeView(path: $path)
                .navigationDestination(for: Route.self) { r in
                    switch r {
                    case let .node(id):
                        if model.index.nodes[id]?.type == NodeType.list || model.index.nodes[id]?.type == NodeType.group {
                            ListView(path: $path, nodeId: id, isSmart: false)
                        } else {
                            TaskPageView(path: $path, nodeId: id)
                        }
                    case let .smart(id): ListView(path: $path, nodeId: id, isSmart: true)
                    case let .focus(id): FocusView(path: $path, requestedNodeId: id)
                    case .stats: StatsView(path: $path)
                    case .settings: SettingsView(path: $path)
                    case .conformance: ConformanceView()
                    case let .ink(id): InkView(path: $path, inkId: id)
                    }
                }
        }
        .environment(\.y, y)
        .tint(y.accent)
        .preferredColorScheme(theme.mode == .light ? .light : theme.mode == .system ? nil : .dark)
        .overlay(alignment: .bottom) {
            if let r = model.refusal {
                Text(r).font(Face.text(13, .semibold)).foregroundStyle(y.ink).padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Capsule().fill(y.surfaceHigh)).overlay(Capsule().stroke(y.tileBorder, lineWidth: 1))
                    .padding(.bottom, 90).transition(.move(edge: .bottom).combined(with: .opacity))
                    .task { try? await Task.sleep(for: .seconds(3.5)); model.refusal = nil }
            }
        }
        .onOpenURL { url in
            // yantra://open/<id> · yantra://focus · yantra://quickadd/<id> — the widget and notification contract.
            guard url.scheme == "yantra" else { return }
            switch url.host {
            case "open": if let id = url.pathComponents.dropFirst().first { path.append(model.index.nodes[id]?.type == NodeType.smartList ? Route.smart(id) : Route.node(id)) }
            case "focus": path.append(Route.focus(nil))
            default: break
            }
        }
        .task {
            // `-route open:<id>` / `-route focus` / `-route home` — a launch argument for UI tests and
            // demos, so a screen can be reached without tapping.
            let args = CommandLine.arguments
            if let i = args.firstIndex(of: "-route"), i + 1 < args.count {
                let r = args[i + 1]
                if r == "home" { return }
                if r == "focus" { path.append(Route.focus(nil)); return }
                if r.hasPrefix("focus:") { path.append(Route.focus(String(r.dropFirst(6)))); return }
                if r == "settings" { path.append(Route.settings); return }
                if r == "stats" { path.append(Route.stats); return }
                if r.hasPrefix("open:") {
                    let id = String(r.dropFirst(5))
                    path.append(model.index.nodes[id]?.type == NodeType.smartList ? Route.smart(id) : Route.node(id)); return
                }
            }
            // Open on Today, as Android's splash does, when it still exists.
            if path.isEmpty, let today = model.index.node(systemKey: SystemKey.today) { path.append(Route.smart(today.id)) }
        }
    }
}

/// The ink screen, first cut: freehand drawing with PencilKit-free Canvas, stored as YNK1 strokes.
/// Pen kit, lasso, shapes and the eraser follow.
struct InkView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    let inkId: String
    @State private var strokes: [StrokeEnvelope.Envelope] = []
    @State private var current: [StrokeEnvelope.Point] = []
    @State private var startedAt: Date? = nil
    @State private var loaded = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                NavCircle(icon: "chevron.left") { save(); path.removeLast() }
                Spacer()
                Text("1 finger draws").font(Face.text(12)).foregroundStyle(y.dim)
                Spacer()
                Button { if !strokes.isEmpty { strokes.removeLast(); save() } } label: {
                    Image(systemName: "arrow.uturn.backward").font(.system(size: 17, weight: .semibold)).foregroundStyle(y.secondary).frame(width: 38, height: 38).background(Circle().fill(y.ink.opacity(0.05)))
                }.buttonStyle(.plain)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8)
            ZStack {
                (y.dark ? oklch(0.152, 0.005, 80) : Color.white)
                Canvas { ctx, size in
                    func draw(_ pts: [StrokeEnvelope.Point], _ color: Color, _ width: CGFloat) {
                        var p = Path()
                        for (i, q) in pts.enumerated() { let pt = CGPoint(x: CGFloat(q.x), y: CGFloat(q.y)); if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) } }
                        ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round))
                    }
                    for s in strokes {
                        var c = Color(argb: s.header.color)
                        if s.header.color == 0xFF23211C, y.dark { c = Color(argb: 0xFFF1EEE7) }
                        draw(s.points, c, CGFloat(s.header.size))
                    }
                    draw(current, y.dark ? Color(argb: 0xFFF1EEE7) : Color(argb: 0xFF23211C), 2.6)
                }
            }
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .local)
                .onChanged { v in
                    if startedAt == nil { startedAt = Date(); current = [] }
                    let t = Int32(Date().timeIntervalSince(startedAt!) * 1000)
                    current.append(.init(x: Float(v.location.x), y: Float(v.location.y), elapsedMillis: t, pressure: -1, tiltRadians: -1, orientationRadians: -1, strokeUnitLengthCm: 0))
                }
                .onEnded { _ in
                    if current.count > 1 {
                        strokes.append(.init(header: .init(family: "pressure_pen", color: 0xFF23211C, size: 2.6, epsilon: 0.1), tool: StrokeEnvelope.toolTouch, points: current))
                        save()
                    }
                    current = []; startedAt = nil
                })
            .clipShape(RoundedRectangle(cornerRadius: 14)).padding(.horizontal, 12).padding(.vertical, 10)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { if !loaded { strokes = model.store.readInk(inkId).compactMap { try? StrokeEnvelope.decode($0) }; loaded = true } }
    }

    private func save() { model.write { try model.writer.writeInk(inkId, strokes.map { StrokeEnvelope.encode($0) }) } }
}
