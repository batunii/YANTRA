import SwiftUI
import UIKit

/// Swipe sideways across a whole pane to page it — the day before, the day after.
///
/// Android puts this on the day and week pane itself (`CalendarScreen.kt`: `DayWithRail(… modifier =
/// Modifier.weight(1f).then(pageGestures))`), not only on the band above it. iOS had it on the
/// heading band and the month grid alone, so on the day view — the one screen where paging is the
/// thing you do most — there was nothing to swipe: the way to yesterday was to reach up to a strip
/// of text at the top of the screen, and most people never found it.
///
/// ## Why this is UIKit and not `.gesture(DragGesture())`
///
/// The day is not SwiftUI all the way down. `RangeMarkSurface` is a `UIViewRepresentable`, and a
/// SwiftUI gesture on an ancestor of a representable never fires — the touch lands on a real
/// `UIView` and SwiftUI's own hit-testing never offers it upward. That trap has already cost this
/// codebase the tap that places a task on the calendar. A `UIPanGestureRecognizer` does not have the
/// problem: it is attached to an ancestor *view*, and UIKit delivers touches to the recognizers of
/// every view in the hit-test chain, representable or not.
///
/// So this is a representable laid out over the pane that reaches upward and installs its
/// recognizer on the hosting view — the same shape as `BackSwipeEnabler`, for the same reason.
///
/// ## Why it attaches so far up, and then narrows again
///
/// There is no UIView per SwiftUI view to attach to. SwiftUI draws its own content into the hosting
/// view's layers, and the only real views in the tree are the representables. Attaching to this
/// view's own superview therefore attaches to a wrapper that contains nothing but this view, which
/// receives no touches at all — measured, not guessed:
///
///     Reacher 402x736 subs=0
///     UIKitPlatformViewHost<…PageSwipe> 402x736 subs=1   <- a wrapper around only us
///     HostingView 402x874 subs=2                         <- the view the timeline is in
///
/// So the recognizer goes on the hosting view, which is the whole screen, and the pane is put back
/// by hand: a swipe only counts if it *started* inside this view's own rectangle. That keeps the
/// heading band's own swipe — which is above the pane — from being answered twice, and it is also
/// where the left edge is excluded, because a swipe from there is the system's back gesture and
/// must not also turn the page.
///
/// ## Why it does not fight the scroll
///
/// The timeline scrolls vertically and pinches to zoom the hour, and both have to keep working.
/// Rather than make the scroll wait for this recognizer to fail — which is what "not simultaneous"
/// means in practice, and which puts a hitch in every scroll — this one runs alongside everything
/// and simply *declines to act* unless the finger clearly went sideways: past `threshold`, and more
/// than twice as far across as down. That is the same rule Android applies before it claims the
/// gesture, arrived at from the other end. A vertical scroll feeds this recognizer too and is
/// ignored, which costs nothing.
struct PageSwipe: UIViewRepresentable {
    /// -1 for the pane before, +1 for the pane after.
    var onPage: (Int) -> Void

    /// How far sideways counts as meaning it. Matches Android's `PAGE_SWIPE`.
    static let threshold: CGFloat = 60

    /// How much of the left edge belongs to the system's back gesture.
    static let edge: CGFloat = 24

    func makeUIView(context: Context) -> UIView {
        let view = Reacher()
        // A measuring stick, not a surface: it is laid out over the pane so its frame says where
        // the pane is, and it must never take a touch from what it is laid over.
        view.isUserInteractionEnabled = false
        view.coordinator = context.coordinator
        context.coordinator.pane = view
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.onPage = onPage
    }

    func makeCoordinator() -> Coordinator { Coordinator(onPage: onPage) }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onPage: (Int) -> Void
        /// The view laid out over the pane, which is how a screen-wide recognizer knows whether a
        /// swipe began somewhere this pane should answer for.
        weak var pane: UIView?
        init(onPage: @escaping (Int) -> Void) { self.onPage = onPage }

        lazy var pan: UIPanGestureRecognizer = {
            let g = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
            g.delegate = self
            // One finger. Two is a pinch, and the timeline is listening for it.
            g.maximumNumberOfTouches = 1
            // Nothing here should change how any other control feels: this recognizer observes and
            // acts at the end, so it must never swallow or delay a touch on the way.
            g.cancelsTouchesInView = false
            g.delaysTouchesBegan = false
            g.delaysTouchesEnded = false
            return g
        }()

        @objc func panned(_ g: UIPanGestureRecognizer) {
            guard g.state == .ended, let view = g.view else { return }
            let moved = g.translation(in: view)
            guard abs(moved.x) >= PageSwipe.threshold, abs(moved.x) > abs(moved.y) * 2 else { return }
            // Leftward is forward, the direction the page moves rather than the finger.
            onPage(moved.x < 0 ? 1 : -1)
        }

        private func startedOnThePane(_ start: CGPoint, in view: UIView) -> Bool {
            guard let pane, let rect = pane.superview?.convert(pane.frame, to: view) else { return false }
            guard rect.contains(start) else { return false }
            // The left edge is the system's back gesture. One swipe must not both leave the screen
            // and turn the page underneath it.
            return start.x >= rect.minX + PageSwipe.edge
        }

        /// Off the pane, this recognizer must not even start.
        ///
        /// Declining to *act* at the end is not enough. The recognizer is on the hosting view, so it
        /// sees drags anywhere on the screen, and merely recognising one is enough to win the
        /// arbitration against a SwiftUI gesture elsewhere — which is how a first attempt at this
        /// silently took the heading band's own swipe away and left `CalendarUITests`
        /// `testTodayKeyAppearsOnlyWhenTodayIsOffScreen` failing. Refusing to begin leaves every
        /// other gesture on the screen exactly as it was.
        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let view = g.view else { return false }
            return startedOnThePane(g.location(in: view), in: view)
        }

        /// Alongside anything it does start with: the timeline scrolls vertically and pinches to
        /// zoom the hour, and neither is in competition with a sideways swipe.
        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }

    /// A view whose only job is to find the pane it was put in the background of, and hang the
    /// recognizer on it.
    final class Reacher: UIView {
        weak var coordinator: Coordinator?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            attachIfNeeded()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            attachIfNeeded()
        }

        private func attachIfNeeded() {
            guard window != nil, !bounds.isEmpty, let coordinator, coordinator.pan.view == nil else { return }
            host()?.addGestureRecognizer(coordinator.pan)
        }

        /// The nearest ancestor that holds more than this view — the hosting view the screen's
        /// content is actually drawn in. A wrapper around only this view receives no touches, which
        /// is the whole reason the first attempt at this did nothing.
        private func host() -> UIView? {
            var candidate = superview
            for _ in 0..<6 {
                guard let v = candidate else { return nil }
                if v.subviews.count > 1, v.bounds.width >= bounds.width { return v }
                candidate = v.superview
            }
            return candidate
        }
    }
}

extension View {
    /// Pages this pane when it is swiped sideways. See `PageSwipe`.
    func pageOnSwipe(_ onPage: @escaping (Int) -> Void) -> some View {
        background(PageSwipe(onPage: onPage).accessibilityHidden(true))
    }
}
