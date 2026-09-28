import SwiftUI
import YantraCore

/// What the app is currently saying to the network, so the screen can say it too —
/// `data/sync/NetworkActivity.kt`.
///
/// **Why this exists.** Everything this app does over the network it does on its own initiative: a
/// change commits and pushes at once, a token renews itself, a background pass runs when iOS allows
/// it. All of it was invisible, which left the honest question "is it doing anything?" with no
/// answer anywhere on screen — and made a slow push indistinguishable from a broken one, and from
/// nothing happening at all.
///
/// **A count, not a flag.** Two workspaces can push at once and a token can renew in the middle of a
/// sync; a boolean would be cleared by whichever finished first while the other was still running.
/// The label is whatever started most recently, because a queue of labels nobody reads is more
/// honest and less useful.
///
/// **Nothing here decides anything.** It is a description of work already happening, so a failure to
/// report is a missing line on screen rather than a sync that does not run.
@MainActor
final class NetworkActivity: ObservableObject {
    static let shared = NetworkActivity()

    /// What is happening now, in words for a person, or nil when nothing is.
    @Published private(set) var current: String?
    private var depth = 0

    /// Runs `work`, saying `what` while it does.
    ///
    /// The previous label is restored rather than cleared when an inner piece finishes, so a
    /// renewal inside a sync leaves the sync's own description behind it instead of silence. The
    /// restore happens however the work ends: a throw is the case where a stuck "Syncing…" would be
    /// most misleading, because it is exactly when nothing is happening any more.
    func during<T>(_ what: String, _ work: () async -> T) async -> T {
        let previous = current
        depth += 1
        current = what
        let result = await work()
        depth -= 1
        current = depth <= 0 ? nil : (previous ?? what)
        return result
    }

    /// The words the screen shows. Kept together so they read like one voice rather than five.
    enum Words {
        static let syncing = "Syncing with GitHub"
        static let linking = "Connecting to GitHub"
        static let renewing = "Renewing access"
        static let checking = "Checking GitHub"
    }
}

/// A quiet line saying the app is talking to GitHub — and nothing at all when it is not.
///
/// **It never reports failure.** A pass that fails is reported where a person can act on it: the
/// sync section in Settings, and the note on the GitHub screen. A red mark in the chrome of every
/// screen over a push that will retry in a minute is alarm without a remedy. This says *something is
/// happening*, which is the question it exists to answer, and stops there.
struct NetworkPulse: View {
    @StateObject private var activity = NetworkActivity.shared
    @Environment(\.y) private var y
    /// What is shown lags what is true, on purpose, and only in the direction of staying longer.
    @State private var shown: String?
    @State private var hide: Task<Void, Never>?

    /// Long enough to read three words, short enough not to outstay a sync that is already done.
    static let minVisible: Duration = .milliseconds(900)

    var body: some View {
        Group {
            if let what = shown {
                // Still, rather than spinning or breathing. A spinner is the shape of something you
                // are waiting for, and you are not waiting for this; a breathing dot was tried on
                // Android and broke the motion law — nothing moves at rest. Arriving and leaving
                // already say that something began and ended, and the label says what.
                HStack(spacing: 6) {
                    Circle().fill(y.accent).frame(width: 6, height: 6)
                    Text(what).font(Face.text(11, .semibold)).foregroundStyle(y.muted).lineLimit(1)
                }
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(RoundedRectangle(cornerRadius: 8).fill(y.neutralChipBg))
                .transition(.opacity)
                .accessibilityIdentifier("network.pulse")
            }
        }
        .animation(.easeInOut(duration: 0.2), value: shown)
        // A sync with nothing to send finishes in milliseconds, and a label that appears and
        // vanishes inside two frames reads as a flicker rather than as a thing that happened.
        .onChange(of: activity.current, initial: true) { _, now in
            hide?.cancel()
            if let now { shown = now; return }
            guard shown != nil else { return }
            hide = Task { @MainActor in
                try? await Task.sleep(for: Self.minVisible)
                if !Task.isCancelled { shown = nil }
            }
        }
    }
}
