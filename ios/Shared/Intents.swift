import AppIntents
import Foundation
import WidgetKit
import YantraCore

/// The five focus commands a widget or the Live Activity can issue — `FocusAction` on Android.
public enum FocusCommand: String, AppEnum {
    case pause, resume, stop, done, startLast
    public static var typeDisplayRepresentation: TypeDisplayRepresentation { "Focus command" }
    public static var caseDisplayRepresentations: [FocusCommand: DisplayRepresentation] = [
        .pause: "Pause", .resume: "Resume", .stop: "Stop", .done: "Done", .startLast: "Start 25m",
    ]
}

public struct FocusIntent: AppIntent {
    public static var title: LocalizedStringResource = "Focus"
    public static var isDiscoverable = false
    @Parameter(title: "Command") public var command: FocusCommand
    public init() {}
    public init(_ c: FocusCommand) { command = c }

    public func perform() async throws -> some IntentResult {
        switch command {
        case .pause: SessionCommands.pause()
        case .resume: SessionCommands.resume()
        case .stop: SessionCommands.stop()
        case .done: SessionCommands.done()
        case .startLast:
            if let node = AppGroup.defaults.string(forKey: "last_focus_node") {
                SessionCommands.start(nodeId: node, title: AppGroup.defaults.string(forKey: "last_focus_title") ?? "", plannedSecs: 25 * 60)
            }
        }
        FocusActivity.sync()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

/// Toggle a task from a widget row — `ToggleDoneAction`. No toast exists on iOS; the row redraws.
public struct ToggleDoneIntent: AppIntent {
    public static var title: LocalizedStringResource = "Toggle done"
    public static var isDiscoverable = false
    @Parameter(title: "Task") public var taskId: String
    public init() {}
    public init(taskId: String) { self.taskId = taskId }

    public func perform() async throws -> some IntentResult {
        let (store, writer) = AppGroup.openWorkspace()
        let ix = WorkspaceIndex.read(store)
        if let n = ix.nodes[taskId] { try? writer.setDone(taskId, !n.done) }
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}
