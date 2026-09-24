import Foundation
import YantraCore

/// A workspace that looks like somebody's, for screenshots.
///
/// `UITestFixture` exists to be asserted against, so its content is named for what each row proves —
/// "Plain thing", "Timed thing", "Overdue thing". That is right for a test and wrong for a picture:
/// a screenshot of a product whose tasks are called Plain thing says the product is a test harness.
/// This seeds the same app with content a person might plausibly have, at hours a person is
/// plausibly awake, so the screenshots on the website are the real app rendering real data rather
/// than a mock-up of it.
///
/// Reached with `-demo`, and nothing else turns it on. It writes through `WorkspaceStore` exactly as
/// the app does, so anything visible in a screenshot is something the app can actually produce.
public enum DemoFixture {

    public static func seed(_ store: WorkspaceStore) {
        var n = 0
        func id() -> String { n += 1; return "demo-\(n)" }
        let now = Date()
        let today = LocalDate.today()
        let device = "demo"

        store.writePage(PageDoc(id: "demo-inbox", type: NodeType.list, parent: nil, title: "Inbox",
                                systemKey: SystemKey.inbox, modifiedAt: now, device: device, blocks: [
            .task(TaskRef(id: id(), title: "Ring the dentist back")),
            .task(TaskRef(id: id(), title: "Find the boiler service receipt")),
        ]))

        // Today, with the rule the real seed writes — otherwise the row says "Empty" beside tasks
        // that are plainly due today, which is a screenshot of the app failing.
        let todayFilter: Filter = .all([.type(NodeType.task), .done(false), .anyOf([
            .prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd),
            .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
        ])])
        store.writePage(PageDoc(id: "demo-today", type: NodeType.smartList, parent: nil, title: "Today",
                                systemKey: SystemKey.today, modifiedAt: now, device: device, blocks: []))
        store.writeSmartList(SmartListDef(nodeId: "demo-today",
                                          filterJson: FilterJSON.encode(todayFilter),
                                          sortJson: FilterJSON.encode([SortSpec(by: .propDate, defId: BuiltIns.due)]),
                                          homeParentId: "demo-inbox"))

        // The list a screenshot opens on: a few things done, a few not, dates that read as a real
        // week rather than as fixtures.
        let writing = "demo-writing"
        store.writePage(PageDoc(id: writing, type: NodeType.list, parent: nil, title: "Deep work",
                                modifiedAt: now, device: device, blocks: [
            .task(TaskRef(id: "demo-chapter", title: "Draft the chapter on conflict arbitration",
                          due: DueSpec(.at(LocalDateTime(date: today, hour: 10).instant()), duration: .minutes(90)),
                          priority: "High")),
            .task(TaskRef(id: id(), title: "Read the JGit rebase source properly",
                          due: DueSpec(.allDay(today.adding(days: 1))))),
            .task(TaskRef(id: id(), title: "Write up what the Thursday outage actually was",
                          status: .done, doneAt: today)),
            .task(TaskRef(id: id(), title: "Reply to the review comments on the sync doc",
                          due: DueSpec(.allDay(today.adding(days: -2))), priority: "High")),
            .task(TaskRef(id: id(), title: "Sketch the empty states")),
            .task(TaskRef(id: id(), title: "Cut the onboarding copy in half", status: .inProgress)),
            .event(EventRef(id: id(), title: "Standup",
                            time: EventTime(start: LocalDateTime(date: today, hour: 9, minute: 30),
                                            end: LocalDateTime(date: today, hour: 9, minute: 45)),
                            color: "Blue")),
            .event(EventRef(id: id(), title: "Design review",
                            time: EventTime(start: LocalDateTime(date: today, hour: 14),
                                            end: LocalDateTime(date: today, hour: 15)),
                            color: "Violet")),
            .event(EventRef(id: id(), title: "Walk",
                            time: EventTime(start: LocalDateTime(date: today, hour: 17, minute: 30),
                                            end: LocalDateTime(date: today, hour: 18, minute: 15)),
                            color: "Moss")),
        ], color: "Teal"))

        // The task page a screenshot opens: prose, a subtask, and a drawing, because the claim the
        // page makes is that a task can hold anything a note can.
        store.writePage(PageDoc(id: "demo-chapter", type: NodeType.task, parent: writing, title: nil,
                                modifiedAt: now, device: device, blocks: [
            .prose("The arbitration only has to be right for one person on several devices."),
            .prose("Start from what the merge already does, then say what it should have asked first."),
            .task(TaskRef(id: id(), title: "Re-read WorkspaceLinker")),
            .task(TaskRef(id: id(), title: "Write the adopt / push / ask table", status: .done, doneAt: today)),
            .ink(id: "demo-ink"),
        ]))

        store.writePage(PageDoc(id: "demo-house", type: NodeType.list, parent: nil, title: "House",
                                modifiedAt: now, device: device, blocks: [
            .task(TaskRef(id: id(), title: "Bleed the radiator in the back room")),
            .task(TaskRef(id: id(), title: "Order more coffee", status: .done, doneAt: today.adding(days: -1))),
            .task(TaskRef(id: id(), title: "Book the chimney sweep",
                          due: DueSpec(.allDay(today.adding(days: 4))))),
        ], icon: "\u{1F3E0}"))

        store.writePage(PageDoc(id: "demo-reading", type: NodeType.list, parent: nil, title: "Reading",
                                modifiedAt: now, device: device, blocks: [
            .task(TaskRef(id: id(), title: "Finish the Braiding Sweetgrass chapter")),
            .task(TaskRef(id: id(), title: "The Timeless Way of Building, part two", status: .done, doneAt: today.adding(days: -2))),
        ], icon: "\u{1F4D6}"))

        // A week of finished sessions, so the stats screen is reading a ledger rather than showing
        // its empty state. Lengths and outcomes vary because a week where every session ran exactly
        // to the bell is not a week anybody has had.
        func ms(_ d: Date) -> Int64 { Int64(d.timeIntervalSince1970 * 1000) }
        let sessions: [(days: Int, hour: Int, secs: Int, outcome: String)] = [
            (0, 10, 1_500, FocusOutcome.ranOut),
            (0, 15, 900, FocusOutcome.stopped),
            (1, 9, 1_500, FocusOutcome.ranOut),
            (1, 16, 2_400, FocusOutcome.stopped),
            (2, 11, 600, FocusOutcome.interrupted),
            (3, 10, 1_500, FocusOutcome.ranOut),
            (4, 14, 1_800, FocusOutcome.stopped),
            (6, 9, 1_500, FocusOutcome.ranOut),
        ]
        for (i, s) in sessions.enumerated() {
            let start = LocalDateTime(date: today.adding(days: -s.days), hour: s.hour).instant()
            let session = FocusSession(id: "demo-session-\(i)", nodeId: "demo-chapter",
                                       startedAt: ms(start),
                                       endedAt: ms(start.addingTimeInterval(TimeInterval(s.secs))),
                                       plannedSecs: 1_500, actualSecs: s.secs, outcome: s.outcome)
            store.appendFocus(session.line, month: session.monthKey)
        }
    }
}
