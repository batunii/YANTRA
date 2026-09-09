import ActivityKit
import Foundation

/// The running session as a Live Activity — the iOS home of what Android puts in the status-bar chip
/// and the shade notification.
public struct FocusAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var title: String
        /// When running: the instant the clock counts from (open) or towards (committed).
        public var startedAt: Date
        public var endAt: Date?
        public var paused: Bool
        /// Frozen readout while paused.
        public var frozen: String
        public var isOpen: Bool
        public var finished: Bool
        public init(title: String, startedAt: Date, endAt: Date?, paused: Bool, frozen: String, isOpen: Bool, finished: Bool) {
            self.title = title; self.startedAt = startedAt; self.endAt = endAt; self.paused = paused; self.frozen = frozen; self.isOpen = isOpen; self.finished = finished
        }
    }
    public var nodeId: String
    public var sessionId: String
    public init(nodeId: String, sessionId: String) { self.nodeId = nodeId; self.sessionId = sessionId }
}

public enum FocusActivity {
    public static func state(for s: LiveSession, finished: Bool = false) -> FocusAttributes.ContentState {
        let now = Date()
        return .init(
            title: s.title.isEmpty ? "Untitled" : s.title,
            startedAt: s.effectiveStart(at: now),
            endAt: s.endDate(at: now),
            paused: s.isPaused,
            frozen: sessionClockShared(s.isOpen ? s.elapsed(at: now) : s.remaining(at: now)),
            isOpen: s.isOpen,
            finished: finished)
    }

    /// Reflects the shared session into the one Live Activity: starts it, updates it, or ends it.
    public static func sync() {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let live = LiveSession.load()
        let existing = Activity<FocusAttributes>.activities
        Task {
            if let s = live {
                let content = ActivityContent(state: state(for: s), staleDate: s.endDate())
                if let a = existing.first(where: { $0.attributes.sessionId == s.sessionId }) {
                    await a.update(content)
                    for other in existing where other.id != a.id { await other.end(nil, dismissalPolicy: .immediate) }
                } else {
                    for other in existing { await other.end(nil, dismissalPolicy: .immediate) }
                    _ = try? Activity.request(attributes: FocusAttributes(nodeId: s.nodeId, sessionId: s.sessionId), content: content, pushType: nil)
                }
            } else {
                for a in existing { await a.end(nil, dismissalPolicy: .immediate) }
            }
        }
    }
}

/// `m:ss` / `h:mm:ss` — duplicated here only so the extension does not need the whole core for one line.
func sessionClockShared(_ secs: Int) -> String {
    let s = max(secs, 0), h = s / 3600
    return h > 0 ? String(format: "%d:%02d:%02d", h, (s % 3600) / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}
