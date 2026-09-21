import Foundation

/// A stretch of time set aside for a task — `SittingSpan` in `data/db/Daos.kt`.
///
/// The title comes down with the span because the bar has to name a task that may not be in
/// progress yet: the whole point of readiness is that nothing has been written about it anywhere
/// else.
public struct SittingSpan: Equatable, Sendable {
    public var taskId: String
    public var title: String?
    public var startUtc: Int64
    public var endUtc: Int64

    public init(taskId: String, title: String? = nil, startUtc: Int64, endUtc: Int64) {
        self.taskId = taskId; self.title = title; self.startUtc = startUtc; self.endUtc = endUtc
    }

    /// True while `at` is inside it. The end is exclusive: a sitting ending at 15:00 is over at 15:00.
    public func covers(_ at: Int64) -> Bool { at >= startUtc && at < endUtc }
}

/// What you have on the go, and which one of them has a clock — `domain/RunningTask.kt`.
///
/// Two things mean "in progress" and neither knows about the other on its own: the focus timer,
/// which holds a single live session and the clock that goes with it, and the task's own
/// `in progress` flag, written to its line in the workspace and synced.
///
/// They are one fact with two halves, and the halves are **not** symmetrical:
///
/// - **Starting a session marks the task.** You cannot be focusing on something you have not started.
/// - **Marking a task does not start a session.** Saying "I have picked this up" is a claim about
///   what is on your plate; committing a block of time to it is a separate decision you may not have
///   made yet.
///
/// And the two have different arities, which is the whole shape of this. **Several tasks can be in
/// progress** — that is the ordinary state of a day, and an app that allows only one makes you lie
/// about the rest. **Only one can be timed**, because a clock measures attention and you only have
/// the one. So the bar is a stack: a card per started task, and at most one of them counting.
public enum RunningStack {

    /// One started task, as the bar draws it.
    public struct Now: Equatable, Identifiable, Sendable {
        public var nodeId: String
        public var title: String
        /// Null for everything except the card whose focus is actually running — the honest reading,
        /// and the one that keeps a card from inventing a number. It is null on every card of a
        /// device that merely received the flags through sync: the claims travel, the stopwatch
        /// does not.
        public var elapsedSecs: Int?
        /// Its sitting is happening right now. Ready, whether or not anybody has picked it up.
        public var scheduled: Bool
        /// The list this task lives on — its name and its colour.
        ///
        /// **A word, not a spine.** The bar shows a title, and a title alone does not say whether
        /// "Draft the deck" is work or the side project. The answer is to print the list's name in
        /// the list's colour: the colour is a glance, the word is the fact, and neither has to carry
        /// the other.
        public var listName: String?
        public var listColour: String?
        /// The workspace this task lives in, as a palette name. Null while only one workspace is
        /// open, by the rule the app states elsewhere: a colour that always means the same thing
        /// means nothing.
        public var workspaceColour: String?

        public var id: String { nodeId }
        public var hasSession: Bool { elapsedSecs != nil }

        public init(nodeId: String, title: String, elapsedSecs: Int? = nil, scheduled: Bool = false,
                    listName: String? = nil, listColour: String? = nil, workspaceColour: String? = nil) {
            self.nodeId = nodeId; self.title = title; self.elapsedSecs = elapsedSecs
            self.scheduled = scheduled; self.listName = listName; self.listColour = listColour
            self.workspaceColour = workspaceColour
        }
    }

    /// The bar, in order — pure, because the ordering is the part that is easy to get wrong and hard
    /// to see going wrong.
    ///
    /// Two sources, and neither is a subset of the other. A task you have picked up is on the go
    /// whatever the calendar says; a task whose sitting is happening now is **ready** even though
    /// nothing has been written about it anywhere. Both belong on the bar, once each.
    ///
    /// The order is a claim about what deserves the front card:
    ///
    /// 1. **The timed one**, always. It is the only card reporting something that changes, and
    ///    having to swipe to find out how long you have been at it defeats showing it at all.
    /// 2. **Then whatever is scheduled now.** Newest-first is a reasonable default with nothing
    ///    better to go on; a sitting is something better to go on — it is you, earlier, saying this
    ///    is the hour for this.
    /// 3. **Then the rest**, in the order they came, which is newest first.
    public static func stack(
        started: [(String, String)],
        timing: (String, Int)? = nil,
        sittings: [SittingSpan] = [],
        at: Int64,
        /// Task id to the list it lives on: its name and colour.
        lists: [String: (String?, String?)] = [:],
        /// Task id to the palette name of its workspace. Absent means one workspace is open.
        workspaces: [String: String?] = [:]
    ) -> [Now] {
        let nowOn = sittings.filter { $0.covers(at) }
        let scheduledIds = Set(nowOn.map(\.taskId))
        let startedIds = Set(started.map(\.0))

        var cards = started.map { id, title in
            Now(nodeId: id, title: title,
                elapsedSecs: timing.flatMap { $0.0 == id ? $0.1 : nil },
                scheduled: scheduledIds.contains(id),
                listName: lists[id]?.0, listColour: lists[id]?.1,
                workspaceColour: workspaces[id] ?? nil)
        }

        // Two sittings for the same task in one hour is one card, not two.
        var seen = Set<String>()
        for s in nowOn where !startedIds.contains(s.taskId) && seen.insert(s.taskId).inserted {
            cards.append(Now(nodeId: s.taskId, title: Links.plain(s.title ?? ""),
                             elapsedSecs: nil, scheduled: true,
                             listName: lists[s.taskId]?.0, listColour: lists[s.taskId]?.1,
                             workspaceColour: workspaces[s.taskId] ?? nil))
        }

        // Stable, so within each rank the order the sources gave is kept. Swift's `sorted` is not
        // guaranteed stable, so the rank carries the original position as its last term.
        return cards.enumerated()
            .sorted { a, b in
                let ra = (a.element.hasSession ? 0 : 1, a.element.scheduled ? 0 : 1, a.offset)
                let rb = (b.element.hasSession ? 0 : 1, b.element.scheduled ? 0 : 1, b.offset)
                return ra < rb
            }
            .map(\.element)
    }

    /// What a play attempt met.
    public enum Play: Equatable, Sendable {
        /// The clock is now on this task.
        case started
        /// Another task has it.
        ///
        /// The one exclusivity left in the app. Several tasks can be on the go — that is what the
        /// player swipes through — but a session measures attention and there is one of that. Taking
        /// the clock closes the other session as interrupted, in a ledger someone will read later,
        /// so the person says when rather than the button.
        case occupied(byId: String, byTitle: String)
    }
}
