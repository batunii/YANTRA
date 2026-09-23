import SwiftUI
import YantraCore

@main
struct YantraApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var theme = ThemeController()

    /// Registered here rather than in a task or an `onAppear`, because iOS requires every background
    /// task handler to be installed **before launching finishes**. One registered later is never
    /// called and says nothing about it.
    init() {
        BackgroundSync.register()
        Diagnostics.logLaunch()
        Diagnostics.watchForTheEnd()
    }

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

    /// Opens a node, if it is one.
    ///
    /// Shared by the `yantra://open/<id>` handler and the `-route open:<id>` scaffolding so the two
    /// cannot drift: an id naming nothing navigates nowhere, rather than pushing a page for a node
    /// that does not exist — which is a blank screen with a back button on it.
    private func openNode(_ id: String) {
        guard let node = model.index.nodes[id] else { return }
        path.append(node.type == NodeType.smartList ? Route.smart(id) : Route.node(id))
    }

    var body: some View {
        let y = theme.colors(systemDark: scheme == .dark)
        NavigationStack(path: $path) {
            HomeView(path: $path)
                // Inside the stack, not around it: the enabler has to be hosted by a view
                // controller the navigation controller actually owns to be able to find it.
                .backSwipe()
                .navigationDestination(for: Route.self) { r in
                    // Where the week was actually spent. `Diagnostics.screen` keeps only a change,
                    // so the rebuilds SwiftUI does whenever anything on the screen changes do not
                    // turn one visit into fifty lines.
                    let _ = Diagnostics.screen(r.slug)
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
                    case let .ink(id): InkView(path: $path, inkId: id)
                    case .github: SignInView(path: $path)
                    case .archive: ArchiveView(path: $path)
                    case .addWorkspace: AddWorkspaceView(path: $path)
                    case let .workspace(id): WorkspaceView(path: $path, workspaceId: id)
                    case let .calendar(day): CalendarView(path: $path, startOn: day)
                    case .marks: MarkSheetView()
                    }
                }
        }
        .environment(\.y, y)
        .tint(y.accent)
        .preferredColorScheme(theme.mode == .light ? .light : theme.mode == .system ? nil : .dark)
        // The pulse rides the chrome of every screen, because every screen can be the one you are on
        // when a push happens. Top trailing, out of the way of a title and of the thumb.
        .overlay(alignment: .topTrailing) {
            NetworkPulse().padding(.trailing, Layout.pageMargin).padding(.top, 2)
        }
        .overlay(alignment: .bottom) {
            if let r = model.refusal {
                Text(r).font(Face.text(13, .semibold)).foregroundStyle(y.ink).padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Capsule().fill(y.surfaceHigh)).overlay(Capsule().stroke(y.tileBorder, lineWidth: 1))
                    .padding(.bottom, 90).transition(.move(edge: .bottom).combined(with: .opacity))
                    .task { try? await Task.sleep(for: .seconds(3.5)); model.refusal = nil }
            }
        }
        .onOpenURL { url in
            // yantra://open/<id> · yantra://focus · yantra://calendar/<date> · yantra://quickadd
            // — the contract this app's own widgets, notifications and Live Activity use.
            //
            // A custom scheme is not owned: any app on the device can open one of these, so every
            // one of them has to be a *navigation* and nothing else. None of these writes, deletes,
            // signs anything out or spends a token, and an id that names nothing navigates nowhere
            // rather than to a blank page. The reachable surface is "show the person a screen of
            // their own data", which is the same thing the app icon does.
            guard url.scheme == "yantra" else { return }
            switch url.host {
            case "open":
                url.pathComponents.dropFirst().first.map(openNode)
            case "focus":
                path.append(Route.focus(nil))
            case "calendar":
                // A date that will not parse means "the calendar", not a crash and not a nil day.
                let day = url.pathComponents.dropFirst().first.flatMap { LocalDate($0) }
                path.append(Route.calendar(day?.description))
            case "quickadd":
                quickAdd = true
            default:
                break
            }
        }
        .sheet(isPresented: $quickAdd) { CreateSheet(path: $path) }
        .onChange(of: phase) { _, p in
            if p == .active {
                Diagnostics.log("foreground")
                // On the way in as well as out: a session iOS kills while suspended never reaches
                // `.background` again, so flushing only on the way out loses its last counts.
                Diagnostics.flushCounts()
                model.wake(); model.syncInBackground("opened")
            }
            if p == .background {
                Diagnostics.flushCountsNow()
                // Leaving is the one moment "in a moment" may never come, so the coalesced work is
                // run now rather than left on a timer this process may not live to fire.
                Diagnostics.log("background")
                model.flushFollowUp()
                model.syncInBackground("leaving app")
                // And ask to be woken to do it again, which is the only way somebody else's work
                // arrives without this app being opened.
                BackgroundSync.schedule()
            }
        }
        .task {
            // `-route open:<id>` / `-route focus` / `-route home` — a launch argument for UI tests and
            // demos, so a screen can be reached without tapping.
            if let r = LaunchRoute.value {
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
                if r == "marks" { path.append(Route.marks); return }
                if r == "calendar" { path.append(Route.calendar(nil)); return }
                if r.hasPrefix("calendar:") { path.append(Route.calendar(String(r.dropFirst(9)))); return }
                if r == "github" { path.append(Route.github); return }
                if r == "addworkspace" { path.append(Route.settings); path.append(Route.addWorkspace); return }
                if r.hasPrefix("workspace:") { path.append(Route.settings); path.append(Route.workspace(String(r.dropFirst(10)))); return }
                if r == "archive" { path.append(Route.archive); return }
                if r.hasPrefix("ink:") { path.append(Route.ink(String(r.dropFirst(4)))); return }
                // `rules:<id>` opens a smart list with its builder already up — the sheet is not
                // reachable by a route of its own, since it only exists over the list it edits.
                if r.hasPrefix("rules:") {
                    let id = String(r.dropFirst(6))
                    if model.index.smartLists[id] != nil { path.append(Route.smart(id)) }
                    return
                }
                if r == "stats" { path.append(Route.stats); return }
                if r.hasPrefix("open:") { openNode(String(r.dropFirst(5))); return }
            }
            // Nothing pushed: the app opens on Home.
            //
            // It used to land on Today, which is a fine screen and the wrong one to be *put* on:
            // Home is where every list is, including Today, and an app that opens one screen deep
            // starts every session with a back gesture. One tap to Today costs less than one tap
            // out of it, every time.
        }
    }
}

/// The `-route <value>` launch argument, read in one place so a screen that needs to act on it can
/// ask the same question the root asked rather than being handed the answer through view state.
enum LaunchRoute {
    static var value: String? {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "-route"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    /// The smart list whose builder the launch asked to have open, if any.
    static var rulesFor: String? {
        value.flatMap { $0.hasPrefix("rules:") ? String($0.dropFirst(6)) : nil }
    }
}
