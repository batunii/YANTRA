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
    @State private var breathing = false

    var body: some View {
        if let what = activity.current {
            HStack(spacing: 6) {
                Circle().fill(y.accent).frame(width: 6, height: 6)
                    .opacity(breathing ? 0.35 : 1)
                    .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: breathing)
                Text(what).font(Face.text(11.5, .medium)).foregroundStyle(y.muted).lineLimit(1)
            }
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Capsule().fill(y.surfaceHigh))
            .overlay(Capsule().stroke(y.tileBorder, lineWidth: 1))
            .transition(.opacity.combined(with: .scale(scale: 0.96)))
            .onAppear { breathing = true }
            .accessibilityIdentifier("network.pulse")
            .animation(.easeInOut(duration: 0.2), value: activity.current)
        }
    }
}
