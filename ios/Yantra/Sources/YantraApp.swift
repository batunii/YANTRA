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
    @State private var quickAdd = false
    @Environment(\.scenePhase) private var phase

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
                    case .github: SignInView(path: $path)
                    case .archive: ArchiveView(path: $path)
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
            case "quickadd": quickAdd = true
            default: break
            }
        }
        .sheet(isPresented: $quickAdd) { CreateSheet(path: $path) }
        .onChange(of: phase) { _, p in
            if p == .active { model.wake(); model.syncInBackground("opened") }
            if p == .background { model.syncInBackground("leaving app") }
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
                if r.hasPrefix("start:") {   // start:<id>:<secs> — begin a session, for demos
                    let parts = r.dropFirst(6).split(separator: ":")
                    if parts.count == 2, let n = model.index.nodes[String(parts[0])], let secs = Int(parts[1]) {
                        Task { _ = await Notifications.shared.requestPermission() }
                        model.timer.start(nodeId: n.id, title: n.title ?? "", plannedSecs: secs); path.append(Route.focus(nil))
                    }
                    return
                }
                if r == "settings" { path.append(Route.settings); return }
                if r == "github" { path.append(Route.github); return }
                if r == "archive" { path.append(Route.archive); return }
                if r.hasPrefix("ink:") { path.append(Route.ink(String(r.dropFirst(4)))); return }
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
