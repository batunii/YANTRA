import SwiftUI
import UniformTypeIdentifiers

/// A task being dragged onto the calendar, as the system carries it.
///
/// **Why the system's drag and drop, and not a SwiftUI gesture.** The rail used to carry a task with
/// `LongPressGesture.sequenced(before: DragGesture)`, and on a simulator — and in a UI test, where
/// the press and the drag are synthesised with perfect timing — that worked. Under a real thumb it
/// did not: the rail is a scrolling list, and a finger that moves a hair during the quarter-second
/// press hands the touch to the scroll view, which then owns it for the rest of the gesture. The
/// task never lifts and nothing happens, every time, which is exactly what was reported.
///
/// A drag *session* is not a gesture competing for a touch. UIKit runs the lift itself, takes the
/// touch off the scroll view when it succeeds, carries a preview above every other view, and hands
/// the drop to whatever is under the finger — across a drawer's edge, out of a scroll view, and into
/// a view that was never in the same gesture space. It is the mechanism this screen always wanted.
struct CarriedTaskRef: Codable, Transferable, Equatable {
    let id: String
    let title: String

    static var transferRepresentation: some TransferRepresentation {
        // A type of our own, so nothing else on the device can drop something the calendar would
        // try to schedule, and so dragging a task into another app does nothing rather than
        // something surprising.
        CodableRepresentation(contentType: .yantraTask)
    }
}

extension UTType {
    /// Yantra's own drag type. Exported rather than imported: this app is what defines it.
    static let yantraTask = UTType(exportedAs: "ie.shoonya.yantra.task")
}
