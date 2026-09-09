import AppIntents
import SwiftUI
import WidgetKit
import YantraCore

// MARK: - the target picker: any list, smart list or task

struct ListEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "List"
    static var defaultQuery = ListQuery()
    var id: String
    var title: String
    var kind: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)", subtitle: kind == NodeType.smartList ? "Smart list" : kind == NodeType.task ? "Task" : "List") }
}

struct ListQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [ListEntity] { all().filter { identifiers.contains($0.id) } }
    func entities(matching string: String) async throws -> [ListEntity] { all().filter { $0.title.localizedCaseInsensitiveContains(string) } }
    func suggestedEntities() async throws -> [ListEntity] { all().filter { $0.kind != NodeType.task } }
    func all() -> [ListEntity] {
        let (store, _) = AppGroup.openWorkspace()
        let ix = WorkspaceIndex.read(store)
        return ix.nodes.values
            .filter { [NodeType.list, NodeType.smartList].contains($0.type) && $0.parentId == nil || $0.type == NodeType.task }
            .sorted { ($0.type == NodeType.task ? 1 : 0, $0.title ?? "") < ($1.type == NodeType.task ? 1 : 0, $1.title ?? "") }
            .map { ListEntity(id: $0.id, title: inlinePlain($0.title ?? "").isEmpty ? "Untitled" : inlinePlain($0.title ?? ""), kind: $0.type) }
    }
}

struct ListWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "List"
    static var description = IntentDescription("Show a list's tasks on your home screen")
    @Parameter(title: "Shows") var target: ListEntity?
    @Parameter(title: "Opacity", default: 94.0, controlStyle: .slider, inclusiveRange: (50.0, 100.0)) var opacity: Double
}

// MARK: - rows

struct WidgetRow: Identifiable {
    let id: String, title: String, done: Bool, inProgress: Bool, priority: String?, due: String?, overdue: Bool, labels: [String], origin: String?, reminder: Bool
}

struct ListSnapshot {
    var title: String
    var summary: String
    var sections: [(header: String?, urgent: Bool, rows: [WidgetRow])]
    var targetId: String?
    var isSmart: Bool
}

enum WidgetData {
    static func snapshot(targetId: String?, forceToday: Bool, hideTodayDue: Bool) -> ListSnapshot {
        let (store, _) = AppGroup.openWorkspace()
        let ix = WorkspaceIndex.read(store)
        let node: Node? = forceToday ? ix.node(systemKey: SystemKey.today) : targetId.flatMap { ix.nodes[$0] }
        guard let n = node else { return ListSnapshot(title: forceToday ? "Today" : "Yantra list", summary: "", sections: [], targetId: nil, isSmart: forceToday) }
        let isSmart = n.type == NodeType.smartList
        func row(_ t: Node) -> WidgetRow {
            var due: String? = nil
            if let d = t.due { due = dueText(d); if hideTodayDue, due == "Today" { due = nil } }
            let origin = isSmart ? ix.ancestors(of: t.id).last { $0.type == NodeType.list }?.title : nil
            return WidgetRow(id: t.id, title: inlinePlain(t.title ?? ""), done: t.done, inProgress: t.inProgress, priority: t.priority, due: due,
                             overdue: overdue(t), labels: t.labels, origin: origin, reminder: t.due?.reminderMin != nil)
        }
        if isSmart, let def = ix.smartLists[n.id] {
            let open = SmartListQuery.run(def, in: ix).map(row)
            var sections: [(String?, Bool, [WidgetRow])] = []
            if forceToday {
                let over = open.filter(\.overdue), today = open.filter { !$0.overdue }
                if !over.isEmpty, !today.isEmpty { sections.append(("OVERDUE · \(over.count)", true, over)); sections.append(("TODAY", false, today)) }
                else { sections.append((nil, false, open)) }
                if let f = def.filter, let flipped = completedVariant(f) {
                    let doneDef = SmartListDef(nodeId: def.nodeId, scopeRootId: def.scopeRootId, filterJson: FilterJSON.encode(flipped), sortJson: def.sortJson)
                    let todayStart = LocalDate.today().startOfDay()
                    let done = SmartListQuery.run(doneDef, in: ix).filter { ($0.doneAt?.startOfDay() ?? .distantPast) >= todayStart }.prefix(3).map(row)
                    if !done.isEmpty { sections.append(("DONE · \(done.count)", false, Array(done))) }
                    let summary = done.isEmpty ? "\(open.count) task\(open.count == 1 ? "" : "s")" : "\(open.count) of \(open.count + done.count)"
                    return ListSnapshot(title: inlinePlain(n.title ?? "Today"), summary: summary, sections: sections, targetId: n.id, isSmart: true)
                }
            } else { sections.append((nil, false, open)) }
            return ListSnapshot(title: inlinePlain(n.title ?? "List"), summary: "\(open.count) task\(open.count == 1 ? "" : "s")", sections: sections, targetId: n.id, isSmart: true)
        }
        let kids = ix.children(of: n.id).filter { $0.type == NodeType.task }
        let open = kids.filter { !$0.done }.sorted { a, b in a.inProgress != b.inProgress ? a.inProgress : a.rank < b.rank }.map(row)
        return ListSnapshot(title: inlinePlain(n.title ?? "List"), summary: "\(kids.filter(\.done).count) of \(kids.count) done", sections: [(nil, false, open)], targetId: n.id, isSmart: false)
    }

    static func dueText(_ d: DueSpec) -> String {
        let today = LocalDate.today()
        func day(_ x: LocalDate) -> String {
            if x == today { return "Today" }; if x == today.adding(days: 1) { return "Tomorrow" }; if x == today.adding(days: -1) { return "Yesterday" }
            let f = DateFormatter(); f.dateFormat = x.year == today.year ? "d MMM" : "d MMM yyyy"; return f.string(from: x.startOfDay())
        }
        switch d.value {
        case let .allDay(x): return day(x)
        case let .at(t): let f = DateFormatter(); f.dateFormat = "HH:mm"; return day(.of(t)) + " " + f.string(from: t)
        }
    }
    static func overdue(_ n: Node) -> Bool {
        if let d = n.dueDate, d < (n.dueHasTime ? Date() : LocalDate.today().startOfDay()) { return true }
        if let dl = n.deadline, dl < .today() { return true }
        return false
    }
}

// MARK: - widgets

struct ListEntry: TimelineEntry { let date: Date; let snap: ListSnapshot; let opacity: Double }

struct ListProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ListEntry { ListEntry(date: Date(), snap: ListSnapshot(title: "Yantra list", summary: "", sections: [], targetId: nil, isSmart: false), opacity: 0.94) }
    func snapshot(for c: ListWidgetIntent, in context: Context) async -> ListEntry { entry(c) }
    func timeline(for c: ListWidgetIntent, in context: Context) async -> Timeline<ListEntry> { Timeline(entries: [entry(c)], policy: .after(Date().addingTimeInterval(1800))) }
    func entry(_ c: ListWidgetIntent) -> ListEntry { ListEntry(date: Date(), snap: WidgetData.snapshot(targetId: c.target?.id, forceToday: false, hideTodayDue: false), opacity: Double(c.opacity) / 100) }
}

struct ListWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "ie.shoonya.yantra.list", intent: ListWidgetIntent.self, provider: ListProvider()) { e in
            ListPanel(entry: e).containerBackground(SharedPalette().surface.opacity(e.opacity), for: .widget)
        }
        .configurationDisplayName("List")
        .description("Show a list's tasks on your home screen")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct TodayProvider: TimelineProvider {
    func placeholder(in context: Context) -> ListEntry { ListEntry(date: Date(), snap: ListSnapshot(title: "Today", summary: "", sections: [], targetId: nil, isSmart: true), opacity: 0.94) }
    func getSnapshot(in context: Context, completion: @escaping (ListEntry) -> Void) { completion(entry()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<ListEntry>) -> Void) {
        // Refresh at the next midnight so "Today" is today's.
        let midnight = LocalDate.today().adding(days: 1).startOfDay()
        completion(Timeline(entries: [entry()], policy: .after(min(midnight, Date().addingTimeInterval(1800)))))
    }
    func entry() -> ListEntry { ListEntry(date: Date(), snap: WidgetData.snapshot(targetId: nil, forceToday: true, hideTodayDue: true), opacity: 0.94) }
}

struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ie.shoonya.yantra.today", provider: TodayProvider()) { e in
            TodayBody(entry: e)
        }
        .configurationDisplayName("Today")
        .description("Today's and overdue tasks at a glance")
        .supportedFamilies([.systemMedium, .systemLarge, .accessoryRectangular])
    }
}

struct TodayBody: View {
    @Environment(\.widgetFamily) var family
    let entry: ListEntry
    var body: some View {
        if family == .accessoryRectangular {
            let rows = entry.snap.sections.flatMap(\.rows).filter { !$0.done }.prefix(3)
            VStack(alignment: .leading, spacing: 1) {
                Text("TODAY · \(entry.snap.sections.flatMap(\.rows).filter { !$0.done }.count)").font(.system(size: 10, weight: .bold, design: .monospaced))
                ForEach(Array(rows)) { r in Text(r.title.isEmpty ? "Untitled" : r.title).font(.system(size: 12, weight: .medium)).lineLimit(1) }
            }.containerBackground(for: .widget) { Color.clear }.widgetURL(URL(string: "yantra://open/\(entry.snap.targetId ?? "")"))
        } else {
            ListPanel(entry: entry).containerBackground(SharedPalette().surface.opacity(entry.opacity), for: .widget)
        }
    }
}

struct ListPanel: View {
    @Environment(\.widgetFamily) var family
    let entry: ListEntry
    var body: some View {
        let p = SharedPalette()
        let compact = family == .systemMedium
        let rowTitle: CGFloat = compact ? 13 : 15, meta: CGFloat = compact ? 11 : 12, box: CGFloat = compact ? 19 : 23
        VStack(alignment: .leading, spacing: compact ? 3 : 7) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Link(destination: URL(string: "yantra://open/\(entry.snap.targetId ?? "")")!) {
                    Text(entry.snap.title).font(SharedFace.text(compact ? 15 : 19, bold: true)).foregroundStyle(p.ink).lineLimit(1)
                }
                Text(entry.snap.summary).font(SharedFace.text(compact ? 11 : 12.5)).foregroundStyle(p.dim)
                Spacer()
                if let t = entry.snap.targetId {
                    Link(destination: URL(string: "yantra://quickadd/\(t)")!) {
                        ZStack { bhupuraPath(26).fill(p.accent.opacity(0.16)); Image(systemName: "plus").font(.system(size: 13, weight: .bold)).foregroundStyle(p.accent) }.frame(width: 26, height: 26)
                    }
                }
            }
            let allRows = entry.snap.sections
            if allRows.flatMap(\.rows).isEmpty {
                Spacer()
                VStack(spacing: 6) {
                    Image(systemName: "star").foregroundStyle(p.accent)
                    Text(entry.snap.targetId == nil ? "Tap and hold to set up" : "All clear").font(SharedFace.text(12)).foregroundStyle(p.dim)
                }.frame(maxWidth: .infinity)
                Spacer()
            } else {
                let limit = compact ? 3 : 8
                var shown = 0
                ForEach(Array(allRows.enumerated()), id: \.offset) { i, s in
                    if shown < limit {
                        if let h = s.header {
                            if i > 0 { Rectangle().fill(p.ink.opacity(0.09)).frame(height: 1) }
                            Text(h).font(SharedFace.text(compact ? 10 : 11, bold: true)).foregroundStyle(s.urgent ? p.overdue : p.dim)
                        }
                        ForEach(s.rows.prefix(max(0, limit - shown))) { r in
                            let _ = { shown += 1 }()
                            RowView(row: r, titleSize: rowTitle, metaSize: meta, box: box)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        }
    }
}

struct RowView: View {
    let row: WidgetRow
    let titleSize: CGFloat, metaSize: CGFloat, box: CGFloat
    var body: some View {
        let p = SharedPalette()
        HStack(alignment: .top, spacing: 8) {
            Button(intent: ToggleDoneIntent(taskId: row.id)) {
                ZStack {
                    if !row.done { bhupuraPath(box).stroke(row.priority?.lowercased() == "high" ? p.overdue : row.priority?.lowercased() == "medium" ? Color(argb: 0xFFEF9F27) : p.secondary, lineWidth: 1.3) }
                    if row.inProgress { Circle().fill(p.accent.opacity(0.18)).frame(width: box * 0.5); Circle().stroke(p.accent, lineWidth: 1.5).frame(width: box * 0.5) }
                    if row.done { Circle().fill(p.accent).frame(width: box * 0.26) }
                }.frame(width: box, height: box)
            }.buttonStyle(.plain)
            Link(destination: URL(string: "yantra://open/\(row.id)")!) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.title.isEmpty ? "Untitled" : row.title).font(SharedFace.text(titleSize, bold: !row.done)).foregroundStyle(row.done ? p.dim : p.ink).strikethrough(row.done, color: p.accent).lineLimit(2)
                    if !row.done {
                        let parts = [row.due].compactMap { $0 } + row.labels.prefix(2).map { "#\($0)" } + [row.origin].compactMap { $0 }
                        if !parts.isEmpty {
                            HStack(spacing: 4) {
                                if row.reminder { Image(systemName: "bell").font(.system(size: metaSize - 1)).foregroundStyle(p.dim) }
                                Text(parts.joined(separator: " · ")).font(SharedFace.mono(metaSize)).foregroundStyle(row.overdue ? p.overdue : p.dim).lineLimit(1)
                            }
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }
}

/// One-tap capture into the Inbox. On the lock screen too, which Android has no counterpart for.
struct QuickAddWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ie.shoonya.yantra.quickadd", provider: QuickAddProvider()) { _ in
            QuickAddTile()
        }
        .configurationDisplayName("New task")
        .description("One-tap task capture into your Inbox")
        .supportedFamilies([.systemSmall, .accessoryCircular])
    }
}

struct QuickAddEntry: TimelineEntry { let date: Date }
struct QuickAddProvider: TimelineProvider {
    func placeholder(in context: Context) -> QuickAddEntry { QuickAddEntry(date: Date()) }
    func getSnapshot(in context: Context, completion: @escaping (QuickAddEntry) -> Void) { completion(QuickAddEntry(date: Date())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<QuickAddEntry>) -> Void) { completion(Timeline(entries: [QuickAddEntry(date: Date())], policy: .never)) }
}

struct QuickAddTile: View {
    @Environment(\.widgetFamily) var family
    var body: some View {
        let p = SharedPalette()
        Group {
            if family == .accessoryCircular {
                ZStack { AccessoryWidgetBackground(); Image(systemName: "plus").font(.system(size: 22, weight: .bold)) }
                    .containerBackground(for: .widget) { Color.clear }
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "plus").font(.system(size: 26, weight: .bold)).foregroundStyle(p.onAccent)
                    Text("New task").font(SharedFace.text(15, bold: true)).foregroundStyle(p.onAccent)
                }.frame(maxWidth: .infinity, maxHeight: .infinity).containerBackground(p.accent, for: .widget)
            }
        }
        .widgetURL(URL(string: "yantra://quickadd"))
    }
}
