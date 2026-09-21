import Foundation
import YantraCore

/// A workspace with known contents, for the UI tests to assert against.
///
/// The ordinary seed is written for somebody opening the app for the first time, so it changes
/// whenever the welcome copy does — which would make every UI test that named a row a test of the
/// marketing. This one is fixed, dull, and exercises each shape the screens have to draw: a list, a
/// smart list, an overdue task, a task due today with a time and a length, a finished task, an
/// event, an all-day event, and a subtask on a task's own page.
///
/// Dates are relative to the day it is written, not literals: a fixture pinned to 2026 starts
/// failing in 2027 for a reason that has nothing to do with the code.
enum UITestFixture {
    /// The names the tests look for. In one place so a rename is a compile error, not a mystery.
    enum Names {
        static let list = "Groceries"
        static let secondList = "Work"
        static let smartList = "Today"
        static let overdueTask = "Overdue thing"
        static let todayTask = "Timed thing"
        static let plainTask = "Plain thing"
        static let doneTask = "Finished thing"
        static let subtask = "A subtask"
        static let focusedTask = "Focused thing"
        static let archivedTask = "Archived thing"
        static let event = "Standup"
        static let allDayEvent = "Conference day"
    }

    /// Stable ids, so a test can be pointed straight at a page with `-route open:<id>` instead of
    /// tapping its way there. A UUID would be a different string on every run and unusable as a route.
    enum Ids {
        static let inbox = "fixture-inbox"
        static let list = "fixture-groceries"
        static let secondList = "fixture-work"
        static let smartList = "fixture-today"
        static let plainTask = "fixture-plain"
        static let focusedTask = "fixture-focused"
        static let archivedTask = "fixture-archived"
        static let ink = "fixture-ink"
    }

    static func seed(_ store: WorkspaceStore) {
        var n = 0
        func id() -> String { n += 1; return "fixture-gen-\(n)" }
        let now = Date()
        let today = LocalDate.today()

        let inbox = Ids.inbox
        store.writePage(PageDoc(id: inbox, type: NodeType.list, parent: nil, title: "Inbox",
                                systemKey: SystemKey.inbox, modifiedAt: now, device: "uitest", blocks: []))

        // A task with a page of its own, so the chevron has somewhere to go.
        let parented = Ids.plainTask
        let groceries = Ids.list
        store.writePage(PageDoc(id: groceries, type: NodeType.list, parent: nil, title: Names.list,
                                modifiedAt: now, device: "uitest", blocks: [
            .task(TaskRef(id: parented, title: Names.plainTask)),
            .task(TaskRef(id: id(), title: Names.overdueTask,
                          due: DueSpec(.allDay(today.adding(days: -3))), priority: "High")),
            .task(TaskRef(id: id(), title: Names.todayTask,
                          due: DueSpec(.at(LocalDateTime(date: today, hour: 14).instant()), duration: .minutes(90)))),
            .task(TaskRef(id: id(), title: Names.doneTask, status: .done, doneAt: today)),
            .event(EventRef(id: id(), title: Names.event,
                            time: EventTime(start: LocalDateTime(date: today, hour: 9),
                                            end: LocalDateTime(date: today, hour: 9, minute: 30)),
                            color: "Blue")),
            .event(EventRef(id: id(), title: Names.allDayEvent,
                            time: EventTime(start: LocalDateTime(date: today),
                                            end: LocalDateTime(date: today).adding(days: 1), allDay: true))),
        ], color: "Teal"))
        store.writePage(PageDoc(id: parented, type: NodeType.task, parent: groceries, title: nil,
                                modifiedAt: now, device: "uitest", blocks: [
            .prose("Some notes on this task."),
            .task(TaskRef(id: id(), title: Names.subtask)),
            .ink(id: Ids.ink),
        ]))

        store.writePage(PageDoc(id: Ids.secondList, type: NodeType.list, parent: nil, title: Names.secondList,
                                modifiedAt: now, device: "uitest", blocks: []))

        // The same Today rule the real seed writes, so the smart list is exercised for real.
        let todayFilter: Filter = .all([.type(NodeType.task), .done(false), .anyOf([
            .prop(defId: BuiltIns.due, op: .lte, dateRel: .todayEnd),
            .prop(defId: BuiltIns.deadline, op: .lte, dateRel: .todayEnd),
        ])])
        let smart = Ids.smartList
        store.writePage(PageDoc(id: smart, type: NodeType.smartList, parent: nil, title: Names.smartList,
                                systemKey: SystemKey.today, modifiedAt: now, device: "uitest", blocks: []))
        store.writeSmartList(SmartListDef(nodeId: smart, filterJson: FilterJSON.encode(todayFilter),
                                          sortJson: FilterJSON.encode([SortSpec(by: .propDate, defId: BuiltIns.due)]),
                                          homeParentId: inbox))

        seedFocus(store, taskId: Ids.focusedTask, listId: groceries, now: now)
        seedArchive(store, pageId: groceries, now: now)
    }

    /// Two finished sessions on a task, so the stats screen has something to count.
    ///
    /// Yesterday and today rather than two of today, because the screen's headline number is how
    /// many of the last seven days had any focus at all — one day would leave that untested.
    private static func seedFocus(_ store: WorkspaceStore, taskId: String, listId: String, now: Date) {
        // The task the sessions point at has to exist, or the breakdown has nothing to name.
        if var page = store.readPage(listId) {
            page.blocks.append(.task(TaskRef(id: taskId, title: Names.focusedTask)))
            store.writePage(page)
        }
        func ms(_ d: Date) -> Int64 { Int64(d.timeIntervalSince1970 * 1000) }
        let yesterday = now.addingTimeInterval(-86_400)
        for (i, start) in [yesterday, now.addingTimeInterval(-3_600)].enumerated() {
            let session = FocusSession(id: "fixture-session-\(i)", nodeId: taskId, startedAt: ms(start),
                                       endedAt: ms(start.addingTimeInterval(1_500)), plannedSecs: 1_500,
                                       actualSecs: 1_500, outcome: FocusOutcome.ranOut)
            store.appendFocus(session.line, month: session.monthKey)
        }
    }

    /// One task that has already left its list, so the archive screen is not just its empty state.
    private static func seedArchive(_ store: WorkspaceStore, pageId: String, now: Date) {
        let line = PageCodec.encodeBlock(.task(TaskRef(
            id: Ids.archivedTask, title: Names.archivedTask, status: .done,
            doneAt: LocalDate.today().adding(days: -40))))
        store.writeArchivedLines(pageId, [line])
    }
}
