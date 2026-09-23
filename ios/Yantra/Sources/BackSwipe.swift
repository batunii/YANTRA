import SwiftUI
import UIKit

/// Gives the swipe-from-the-edge back its meaning on screens that hide the navigation bar.
///
/// Every screen in this app draws its own header and calls `.toolbar(.hidden, for: .navigationBar)`,
/// which is what a designed app does — and a `UINavigationController` with a hidden bar switches
/// its interactive pop gesture off, because the gesture is nominally the bar's back button under
/// your thumb. The result was an app where the one gesture every iPhone owner makes without
/// thinking did nothing at all, on every screen, and the only way back was the circle in the corner.
///
/// So the recognizer is handed a delegate of our own that answers the one question it is switched
/// off for: is there anything to go back to. `NavigationStack` keeps its own path, but it drives a
/// real navigation controller underneath, and popping that is what moves the path — the two do not
/// come apart, so there is nothing here to keep in step.
///
/// Installed once from the root by `backSwipe()`, rather than per screen: a screen that forgot it
/// would be a screen where the gesture mysteriously stops, which is worse than it never working.
struct BackSwipeEnabler: UIViewControllerRepresentable {
    func makeCoordinator() -> PopDelegate { PopDelegate() }

    func makeUIViewController(context: Context) -> UIViewController {
        Enabler(delegate: context.coordinator)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        // The stack this sits in can be rebuilt — a scene restoring, an iPad split changing shape —
        // and the recognizer that comes back is a different object with the stock delegate on it.
        (controller as? Enabler)?.adopt()
    }

    final class PopDelegate: NSObject, UIGestureRecognizerDelegate {
        weak var nav: UINavigationController?

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            // The root screen has nothing behind it, and letting the gesture start there leaves the
            // stack wedged in a transition it can never finish.
            (nav?.viewControllers.count ?? 0) > 1
        }

        /// Never alongside another recognizer. A row's swipe-to-mark and an edge swipe both start
        /// as a rightward drag, and letting both run means one swipe marking a task *and* leaving
        /// the screen it is on.
        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { false }
    }

    final class Enabler: UIViewController {
        private let popDelegate: PopDelegate
        init(delegate: PopDelegate) {
            popDelegate = delegate
            super.init(nibName: nil, bundle: nil)
            view.isUserInteractionEnabled = false
        }
        @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            adopt()
        }

        func adopt() {
            guard let nav = navigationController else { return }
            popDelegate.nav = nav
            nav.interactivePopGestureRecognizer?.delegate = popDelegate
            nav.interactivePopGestureRecognizer?.isEnabled = true
        }
    }
}

extension View {
    /// Puts the edge swipe back, from the root of a `NavigationStack`.
    func backSwipe() -> some View {
        background(BackSwipeEnabler().frame(width: 0, height: 0).accessibilityHidden(true))
    }
}
