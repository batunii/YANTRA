import SwiftUI
import UIKit

/// The surface you hold to mark out a range on a day.
///
/// **Why this is UIKit and not a SwiftUI gesture.** A `DragGesture` attached to content inside a
/// `ScrollView` takes the touch, and it takes it whether or not it ends up doing anything —
/// including when it is sequenced behind a `LongPressGesture`, which looks like it should hand the
/// touch back and does not. The result was a day that could only be scrolled by the narrow strip of
/// hour labels down the left, because that strip was the one part with no gesture on it. Nobody
/// should have to know that.
///
/// A `UILongPressGestureRecognizer` behaves the way the interaction wants, because UIKit already
/// arbitrates this: it does nothing until the press ripens, and a finger that moves first fails it
/// and belongs to the scroll view. After it fires it keeps reporting movement, so the hold and the
/// drag that follows are one gesture rather than two that have to be stitched together.
///
/// Sits **behind** the blocks rather than over them, so a tap on a meeting is still a tap on that
/// meeting: marking a range only ever made sense on the empty part of a day.
///
/// It carries the plain tap too, for the same reason it carries the hold. A SwiftUI `.gesture` on
/// the column around it never fired at all once this view was in the stack: the touch lands on a
/// real `UIView`, and the recogniser SwiftUI would have used for an ancestor does not get it back.
/// Putting the tap on the same recogniser chain as the hold is one arbitration instead of two, and
/// UIKit already knows how to tell a tap from a press from a scroll.
struct RangeMarkSurface: UIViewRepresentable {
    /// Where the press landed, in this view's own coordinates.
    var onBegan: (CGFloat) -> Void
    var onChanged: (CGFloat) -> Void
    var onEnded: (Bool) -> Void
    /// A plain tap on the empty part of the day, in this view's own coordinates. This is how a task
    /// picked up from the rail is put down.
    var onTap: (CGFloat) -> Void = { _ in }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        let press = UILongPressGestureRecognizer(target: context.coordinator,
                                                 action: #selector(Coordinator.handle(_:)))
        press.minimumPressDuration = 0.3
        // Generous, because a thumb is never still. Below about this the press fails on the tremor
        // of holding still and the gesture feels broken rather than strict.
        press.allowableMovement = 14
        view.addGestureRecognizer(press)
        let tap = UITapGestureRecognizer(target: context.coordinator,
                                         action: #selector(Coordinator.tapped(_:)))
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) { context.coordinator.owner = self }

    final class Coordinator: NSObject {
        var owner: RangeMarkSurface
        init(_ owner: RangeMarkSurface) { self.owner = owner }

        @objc func tapped(_ g: UITapGestureRecognizer) {
            guard g.state == .ended else { return }
            owner.onTap(g.location(in: g.view).y)
        }

        @objc func handle(_ g: UILongPressGestureRecognizer) {
            let y = g.location(in: g.view).y
            switch g.state {
            case .began:
                UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.6)
                owner.onBegan(y)
            case .changed:
                owner.onChanged(y)
            case .ended:
                owner.onEnded(true)
            // A cancel is the system taking the gesture away — a call arriving, the app leaving.
            // Committing whatever had been marked at that point would put an event in somebody's
            // day that they did not finish asking for.
            case .cancelled, .failed:
                owner.onEnded(false)
            default:
                break
            }
        }
    }
}
