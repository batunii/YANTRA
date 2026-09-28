import SwiftUI
import YantraCore

enum Route: Hashable {
    case node(String)
    case smart(String)
    case focus(String?)
    case stats
    case settings
    case ink(String)
    case github
    case archive
    /// Joining a repository, or starting one. `AddWorkspaceScreen` on Android.
    case addWorkspace
    /// One linked workspace: where it points, and how to remove it.
    case workspace(String)
    /// The calendar, optionally landing on a day — how the widget points at one.
    case calendar(String?)
    /// Every mark at once, for checking the drawings. `-route marks`.
    case marks

    /// What to call this screen in the diagnostics log. The kind only — never the id, because a
    /// node id names one of the person's own pages and this log is meant to be shareable.
    var slug: String {
        switch self {
        case .node: return "page"
        case .smart: return "smartList"
        case .focus: return "focus"
        case .stats: return "stats"
        case .settings: return "settings"
        case .ink: return "ink"
        case .github: return "signIn"
        case .archive: return "archive"
        case .addWorkspace: return "addWorkspace"
        case .workspace: return "workspace"
        case .calendar: return "calendar"
        case .marks: return "marks"
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    /// The one sheet this screen can show. Two `.sheet` modifiers on a view is not two sheets —
    /// SwiftUI honours one and drops the rest without a word, which is how a working control ends
    /// up looking broken.
    enum Presented: Identifiable {
        case create
        case look(Node)
        var id: String { if case let .look(n) = self { return "look:\(n.id)" }; return "create" }
    }
    @State private var presented: Presented?
    @State private var folded: Set<String> = FoldedGroups.load()
    /// The list whose appearance is being chosen, if any.

    /// Every list and smart list, wherever it is filed.
    private var allLists: [Node] {
        model.index.nodes.values.filter { $0.type == NodeType.list || $0.type == NodeType.smartList }
            .sorted { $0.rank < $1.rank }
    }

    /// Views — the top of Home, the way a mail app puts its inboxes above its folders: Inbox and
    /// Today first, then every other smart list (`HomeScreen.kt` at 17d0beb). A view is somewhere you
    /// *look*, not somewhere tasks live, so it sits above the lists that own them — and it is always
    /// there, in the same place, without having to be pinned to get there.
    ///
    /// The local workspace's Inbox when there is one: that is where capture lands. Another
    /// repository's Inbox stays in that repository's section.
    private var views: [Node] {
        let inbox = allLists.first { $0.systemKey == SystemKey.inbox && $0.workspaceId.isEmpty }
            ?? allLists.first { $0.systemKey == SystemKey.inbox }
        let today = allLists.first { $0.systemKey == SystemKey.today }
        return [inbox, today].compactMap { $0 }
            + allLists.filter { $0.type == NodeType.smartList && $0.id != today?.id }
    }
    private var viewIds: Set<String> { Set(views.map(\.id)) }

    /// Pinned is a choice, not a type. A pinned list keeps its place in its repository: pinning
    /// moves it to the top, it does not put a copy there — so it is left out of its section and its
    /// group below, or the same list shows twice on a screen this short.
    private var pinned: [Node] { allLists.filter { $0.pinned && !viewIds.contains($0.id) } }

    /// One section per open repository, in registry order with the local one first — or a single
    /// "Lists" when there is only one and the split would say nothing. A nil id means everything.
    private var sections: [(id: String?, name: String)] {
        let stores = model.allStores
        guard stores.count > 1 else { return [(nil, "Lists")] }
        return stores.map { ($0.id as String?, model.workspaceName($0.id)) }
    }

    /// A section's lists and groups **in one rank order**, not lists-then-groups: a group is a
    /// sibling of a top-level list, and taking them apart threw away the order they were dragged
    /// into.
    private func entries(in workspace: String?) -> [Node] {
        model.index.children(of: nil).filter {
            ($0.type == NodeType.list || $0.type == NodeType.group || $0.type == NodeType.smartList)
                && !viewIds.contains($0.id) && !$0.pinned
                && (workspace == nil || $0.workspaceId == workspace)
        }
    }
    private func listsIn(_ group: Node) -> [Node] {
        model.index.children(of: group.id).filter {
            ($0.type == NodeType.list || $0.type == NodeType.smartList) && !viewIds.contains($0.id) && !$0.pinned
        }
    }

    /// Lists that **own** their tasks. A smart list re-counts tasks that live in one of these, so
    /// summing every entry would tally the same task twice.
    private var openCount: Int {
        allLists.filter { $0.type == NodeType.list }.reduce(0) { $0 + model.index.openChildCount($1.id) }
    }

    /// Today's events as the calendar buckets them — repeats expanded, sittings included.
    private var todayEvents: [DayItem.EventItem] {
        let today = LocalDate.today()
        let days = CalendarBucketer.bucket(nodes: Array(model.index.nodes.values), from: today,
                                           toExclusive: today.adding(days: 1))
        return (days[today] ?? []).compactMap { if case let .event(e) = $0 { return e }; return nil }
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        switch h { case 5...11: return "Morning"; case 12...16: return "Afternoon"; case 17...21: return "Evening"; default: return "Late one" }
    }

    /// What the day costs, not how it is filed: "13 open · 2h 20m booked". "Across 2 lists" told you
    /// nothing you could not get by looking down the screen; booked time is the one figure Home
    /// cannot otherwise show. Numerals in mono, per the type rule.
    private func tally(booked: Int) -> Text {
        let open = openCount
        if open == 0 && booked == 0 { return Text("Nothing open. Breathe.") }
        var t = Text("\(open)").font(Face.mono(12.5)) + Text(" open")
        if booked > 0 {
            t = t + Text(" · ") + Text(HomeDay.bookedWords(booked)).font(Face.mono(12.5)) + Text(" booked")
        }
        return t
    }

    private static let dateLine: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "EEEE · d MMM"; return f
    }()

    var body: some View {
        let events = todayEvents
        let booked = HomeDay.bookedMinutes(events, today: LocalDate.today())
        let next = HomeDay.next(events, now: Date())
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(Self.dateLine.string(from: Date())).font(Face.text(12.5, .medium)).foregroundStyle(y.muted)
                            Text(greeting).font(Face.display(24)).tracking(-0.3).foregroundStyle(y.ink)
                                .padding(.top, 3)
                                .accessibilityIdentifier("home.greeting")
                            tally(booked: booked).font(Face.text(12.5, .medium)).foregroundStyle(y.muted)
                                .padding(.top, 6)
                                .accessibilityIdentifier("home.tally")
                        }
                        Spacer()
                        NavCircle(mark: .settings) { path.append(Route.settings) }
                            .accessibilityIdentifier("home.settings").accessibilityLabel("Settings")
                    }
                    .padding(.top, 10).padding(.bottom, 6)

                    ForEach(views) { n in
                        ViewRow(node: n, open: openIn(n)) {
                            path.append(n.type == NodeType.smartList ? Route.smart(n.id) : Route.node(n.id))
                        } onLook: { presented = .look($0) }
                    }

                    // NEXT — under the views, not over them. It sat between the greeting and Inbox,
                    // and the views have no header of their own, so Inbox and Today read as more
                    // rows of NEXT. The views are the fixed part of Home and hold the top; the
                    // contextual part comes after them, under its own header.
                    if let next {
                        HomeSection(text: "Next")
                        // Neutral while a session runs: two accented things at opposite ends of the
                        // screen signal two kinds of urgency at once, and the one you are *in* wins.
                        NextRow(event: next, accented: model.timer.state == nil) {
                            // The task, not the sitting: a sitting has no page worth opening.
                            path.append(Route.node(next.forTaskId ?? next.nodeId))
                        }
                    }

                    if views.isEmpty && pinned.isEmpty && sections.allSatisfy({ entries(in: $0.id).isEmpty }) {
                        ComposedEmpty(line: "Nothing here yet", action: "Make a list") { presented = .create }
                    }

                    // Pinned is deliberately not grouped by repository: it goes on top, ungrouped,
                    // which is where All Inboxes sits in Mail.
                    if !pinned.isEmpty {
                        HomeSection(text: "Pinned")
                        ForEach(pinned) { n in row(n) }
                    }

                    ForEach(sections, id: \.name) { section in
                        let rows = entries(in: section.id)
                        if !rows.isEmpty {
                            // The heading is the legend the spine is read against: said once here,
                            // not on every row.
                            HomeSection(text: section.name,
                                        ink: section.id.flatMap { LabelPalette.swatchColor(model.workspaceColorName($0), dark: y.dark) })
                            ForEach(rows) { n in
                                if n.type == NodeType.group {
                                    GroupBanner(group: n, count: listsIn(n).count, folded: folded.contains(n.id)) {
                                        // Device-local, not in the file: the format has no `collapsed`,
                                        // and folding is a thing about this screen on this phone.
                                        if folded.contains(n.id) { folded.remove(n.id) } else { folded.insert(n.id) }
                                        FoldedGroups.save(folded)
                                    }
                                    if !folded.contains(n.id) {
                                        ForEach(listsIn(n)) { kid in
                                            row(kid).padding(.leading, 14)
                                        }
                                    }
                                } else {
                                    row(n)
                                }
                            }
                        }
                    }
                    Spacer().frame(height: 120)
                }
                .padding(.horizontal, Layout.pageMargin)
                .readableColumn()
            }
            // The player is never the outermost thing on a screen with a permanent bar: it slots in
            // above the nav strip, so the bottom of the app does not reshuffle between screens.
            BottomBar(onOpenNow: { path.append(Route.focus($0.nodeId)) }) {
                HomeTabBar(onCalendar: { path.append(Route.calendar(nil)) },
                           onCreate: { presented = .create },
                           onStats: { path.append(Route.stats) })
            }
        }
        // Pull the list down to sync. The one deliberate, waited-for sync in the app: everything
        // else happens on its own and says so quietly in the chrome. This one is asked for, so it
        // holds the spinner until the pass is done and then says what the pass came to.
        // Scrolling the list away is how a person says they are done typing, so the keyboard goes
        // with the gesture rather than needing a second one aimed at a Done key.
        .scrollDismissesKeyboard(.interactively)
        .refreshable { await model.syncNowAndWait() }
        .background(y.page.ignoresSafeArea())
        // One modifier: a second `.sheet` on the same view is silently dropped, which is how the
        // create key and "Icon & colour" ended up fighting over which one opened.
        .sheet(item: $presented) { what in
            switch what {
            case .create: CreateSheet(path: $path)
            case let .look(n):
                ListLookSheet(nodeId: n.id, title: inlinePlain(n.title ?? ""), smart: n.type == NodeType.smartList)
            }
        }
    }
}


extension HomeView {
    fileprivate func row(_ n: Node) -> some View {
        let smart = n.type == NodeType.smartList
        return HomeRow(node: n, isSmart: smart, open: { path.append(smart ? Route.smart(n.id) : Route.node(n.id)) },
                       onLook: { presented = .look($0) })
    }

    /// Open work, the one number a view is looked at for.
    fileprivate func openIn(_ n: Node) -> Int {
        n.type == NodeType.smartList ? model.smartListRows(n).filter { !$0.done }.count : model.index.openChildCount(n.id)
    }
}

/// A section's heading on Home — Android's `SectionHeader`: 18 above, 4 below.
struct HomeSection: View {
    let text: String
    var ink: Color? = nil
    var body: some View {
        SectionLabel(text: text, color: ink).padding(.top, 18).padding(.bottom, 4)
    }
}

/// A view at the top of Home: its mark, its name, and how much is open in it.
///
/// Deliberately lighter than a list row. A list row carries "4 of 11 done" because a list is where
/// the work lives; a view is a way of looking at work that lives elsewhere, and what you want from
/// it before opening it is only whether there is anything there.
struct ViewRow: View {
    @Environment(\.y) private var y
    let node: Node
    let open: Int
    let action: () -> Void
    var onLook: (Node) -> Void

    private var mark: YantraMark {
        switch node.systemKey {
        case SystemKey.inbox: return .list
        case SystemKey.today: return .calendar
        default: return .smartList
        }
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                YantraIcon(mark: mark, size: 22,
                           tint: node.color.flatMap { LabelPalette.swatchColor($0, dark: y.dark) } ?? y.accent)
                Text(inlinePlain(node.title ?? "").isEmpty ? "Untitled" : inlinePlain(node.title ?? ""))
                    .font(Face.display(15, .medium)).foregroundStyle(y.ink).lineLimit(1)
                Spacer(minLength: 8)
                // Nothing when there is none: an empty view says so by having no badge.
                if open > 0 {
                    Text("\(open)").font(Face.mono(11)).foregroundStyle(y.muted)
                        .padding(.horizontal, 9).padding(.vertical, 3)
                        .background(Capsule().fill(y.surfaceHigh))
                }
            }
            .padding(.vertical, 11)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.view.\(inlinePlain(node.title ?? ""))")
        .contextMenu { Button("Colour…") { onLook(node) } }
    }
}

/// The next thing today. A row, because at-a-glance information wants to be tappable, and it sits
/// in the same grammar as the rows around it.
struct NextRow: View {
    @Environment(\.y) private var y
    let event: DayItem.EventItem
    let accented: Bool
    let action: () -> Void

    var body: some View {
        let ink = accented ? y.accent : y.checkOutline
        Button(action: action) {
            HStack(spacing: 13) {
                // A clock, not the calendar: this row is a time, and the calendar mark is Today's.
                // Two accented calendars a row apart read as one thing listed twice.
                YantraIcon(mark: .clock, tint: ink).frame(width: 34, height: 34)
                VStack(alignment: .leading, spacing: 0) {
                    // A sitting borrows its task's words.
                    Text(inlinePlain(event.displayTitle).isEmpty ? "Untitled" : inlinePlain(event.displayTitle))
                        .font(Face.display(15, .medium)).foregroundStyle(y.ink).lineLimit(1)
                    Text(HomeDay.nextCaption(event, now: Date())).font(Face.mono(11))
                        .foregroundStyle(accented ? y.accent : y.muted)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.next")
    }
}

struct HomeRow: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    let node: Node
    let isSmart: Bool
    let open: () -> Void
    var onLook: (Node) -> Void

    private var counts: (done: Int, total: Int) {
        let kids = isSmart ? model.smartListRows(node) + model.completedRows(node) : model.index.children(of: node.id).filter { $0.type == NodeType.task }
        return (kids.filter(\.done).count, kids.count)
    }

    var body: some View {
        let c = counts
        Button(action: open) {
            HStack(spacing: 14) {
                ListGlyph(icon: node.icon, color: node.color, smart: isSmart)
                VStack(alignment: .leading, spacing: 2) {
                    Text(inlinePlain(node.title ?? "").isEmpty ? "Untitled" : inlinePlain(node.title ?? "")).font(Face.display(15.5, .medium)).foregroundStyle(y.ink).lineLimit(1)
                    // Which repository a list is in is said once, by the section it sits under.
                    Text(c.total == 0 ? "Empty" : "\(c.done) of \(c.total) done").font(Face.text(12.5)).foregroundStyle(y.muted)
                }
                Spacer()
                if c.total > 0 { Compass(fraction: Double(c.done) / Double(c.total)) }
            }
            .padding(.vertical, 12)
            // Deliberately no `contentShape` here. It looks like the right thing — the row's
            // *accessibility* frame is only as tall as the text — but a Button is already tappable
            // across its whole area, and adding one makes the row a single hit target that swallows
            // the long press the context menu needs. Tried, and it broke "Icon & colour" on every
            // row; `ListLookUITests` is what caught it.
        }
        .buttonStyle(.plain)
        // Identifier only. A Button already reads as one control with its children folded into the
        // label; adding an explicit combine on top makes an element that reports as a button and
        // swallows the tap, which is a row that looks right in the tree and does nothing.
        .accessibilityIdentifier("home.row.\(inlinePlain(node.title ?? ""))")
        // How a list looks is a second thing you can do to a row, so it is the second gesture
        // rather than a control that would sit on every row for the once anybody uses it.
        .contextMenu {
            // The words alone. A menu item's label takes an `Image`, and a drawn mark in the icon
            // slot makes the whole menu fail to build — the row then long-presses to nothing, which
            // is exactly what putting one here did. The app draws its own marks everywhere it can;
            // a system menu is somewhere it cannot.
            Button(node.pinned ? "Unpin from top" : "Pin to top") {
                model.write { try model.writerFor(node.id).setPinned(node.id, !node.pinned) }
            }
            Button("Icon & colour…") { onLook(node) }
        }
        .overlay(alignment: .bottom) { Rectangle().fill(y.hairline).frame(height: 1) }
    }
}

/// A ring that closes as a list is finished; four cardinal ticks.
struct Compass: View {
    let fraction: Double
    @Environment(\.y) private var y
    var body: some View {
        ZStack {
            Circle().stroke(y.secondary.opacity(0.35), lineWidth: 1.5)
            Circle().trim(from: 0, to: fraction).stroke(y.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round)).rotationEffect(.degrees(-90))
            ForEach(0..<4, id: \.self) { i in
                Rectangle().fill(y.secondary.opacity(0.5)).frame(width: 1.2, height: 4).offset(y: -15).rotationEffect(.degrees(Double(i) * 90))
            }
        }.frame(width: 30, height: 30)
    }
}

struct ComposedEmpty: View {
    let line: String
    var action: String? = nil
    var onAction: () -> Void = {}
    @Environment(\.y) private var y
    var body: some View {
        VStack(spacing: 14) {
            BhupuraMark(size: 34)
            Text(line).font(Face.text(13, .medium)).foregroundStyle(y.muted)
            if let action {
                Button(action: onAction) {
                    Text(action).font(Face.text(12, .bold)).foregroundStyle(y.accentText).padding(.horizontal, 14).padding(.vertical, 8)
                        .background(Capsule().fill(y.accentFill))
                }.buttonStyle(.plain)
            }
        }.frame(maxWidth: .infinity).padding(.vertical, 40)
    }
}

struct HomeTabBar: View {
    let onCalendar: () -> Void, onCreate: () -> Void, onStats: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        HStack {
            // Calendar, not a home key: this *is* the home screen, so a key back to it does
            // nothing. The calendar is the other board, and it belongs where a thumb is.
            Button(action: onCalendar) {
                YantraIcon(mark: .calendar, size: YantraIcons.large, tint: y.secondary)
                    .frame(width: 44, height: 44)
            }
            .accessibilityIdentifier("home.calendar").accessibilityLabel("Calendar")
            Spacer()
            // The make-something key.
            //
            // It wore a gearshape while opening the create sheet, and settings sat in the top-right
            // circle wearing sliders — inverted from every other app on the phone, so a person
            // reaching for a cog found a new-list sheet. The Kotlin hit the same thing and records
            // the same fix; this is that fix, at the same 56 points and card radius so the key is
            // the same object as the calendar's, in size, shape and position.
            Button(action: onCreate) {
                YantraIcon(mark: .add, size: YantraIcons.large, tint: y.accent)
                    .frame(width: 56, height: 56)
                    .background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.accentFill))
                    .overlay(RoundedRectangle(cornerRadius: Layout.cardRadius).stroke(y.accentBorder, lineWidth: 1))
            }
            .accessibilityIdentifier("tab.create").accessibilityLabel("New")
            Spacer()
            Button(action: onStats) {
                YantraIcon(mark: .stats, size: YantraIcons.large, tint: y.secondary)
                    .frame(width: 44, height: 44)
            }
            .accessibilityIdentifier("tab.stats").accessibilityLabel("Stats")
        }
        .buttonStyle(.plain)
        // No ground of its own: it is a row inside the dock, which is what carries the surface and
        // the rounded top. A second background here would be the two-objects problem the dock fixed.
        .padding(.horizontal, 40).padding(.top, 10).padding(.bottom, 6)
    }
}

struct CreateSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    @Binding var path: NavigationPath
    /// The list a new task goes into — a list widget's "+" names its own. Nil is capture's answer:
    /// the Inbox.
    var into: String? = nil
    @State private var text = ""
    @State private var kind = 0
    @State private var smart = false
    @State private var builder = false
    /// Which workspace a new list or group is made in. Only asked when there is more than one, and
    /// only for the kinds that belong to a repository — a smart list is a view over all of them and
    /// always lives locally.
    @State private var workspace = ""
    @FocusState private var focused: Bool
    private let kinds = ["Task", "List", "Group"]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            TextField(kind == 0 ? "New task" : kind == 1 ? "New list" : "New group", text: $text)
                .font(Face.display(24)).foregroundStyle(y.ink).focused($focused).submitLabel(.done).onSubmit(create)
            HStack(spacing: 8) {
                ForEach(0..<3, id: \.self) { i in SelectChip(label: kinds[i], selected: kind == i, stretch: true) { kind = i } }
            }
            if kind != 0, !smart, model.allStores.count > 1 {
                // A list made in the wrong repository is a list the people it was meant for cannot
                // see, and moving it afterwards is not something this app offers yet. So it is
                // asked once, here, while it still costs a tap.
                SectionLabel(text: "In")
                HStack(spacing: 8) {
                    ForEach(model.allStores, id: \.id) { store in
                        SelectChip(label: model.workspaceName(store.id), selected: workspace == store.id, stretch: true) { workspace = store.id }
                    }
                }
            }
            if kind == 1 {
                Toggle(isOn: $smart) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Make this a smart list").font(Face.text(14, .semibold)).foregroundStyle(y.ink)
                        Text("Auto-updates from conditions you set, instead of a fixed set of tasks").font(Face.text(12)).foregroundStyle(y.muted)
                    }
                }.tint(y.accent)
            }
            YantraButton(label: kind == 0 ? "Create task" : kind == 1 ? (smart ? "Continue" : "Create list") : "Create group", tone: .soft, enabled: !text.trimmingCharacters(in: .whitespaces).isEmpty) {
                if kind == 1, smart { builder = true } else { create() }
            }
            Spacer()
        }
        .padding(22).padding(.top, 12)
        .background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .onAppear { focused = true }
        .sheet(isPresented: $builder, onDismiss: { dismiss() }) { SmartListBuilder(path: $path, initialName: text) }
    }

    private func create() {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        switch kind {
        case 0:
            model.capture(t, into: into.flatMap { model.index.nodes[$0] })
            if let home = into ?? model.index.node(systemKey: SystemKey.inbox)?.id { path.append(Route.node(home)) }
        case 1:
            var id = ""
            model.write { id = try model.writerIn(workspace).createTopLevel(type: NodeType.list, title: t) }
            if !id.isEmpty { path.append(Route.node(id)) }
        default:
            model.write { _ = try model.writerIn(workspace).createTopLevel(type: NodeType.group, title: t) }
        }
        dismiss()
    }
}

/// A group on Home: its name, how many lists are filed under it, and whether it is folded.
///
/// Drawn as a band rather than a row, because a group is not a thing you open — it is a heading with
/// a lid. Tapping it folds; the lists underneath are indented so the nesting is read from the shape
/// rather than from a line.
struct GroupBanner: View {
    let group: Node
    let count: Int
    let folded: Bool
    var onFold: () -> Void
    @Environment(\.y) private var y

    var body: some View {
        Button(action: onFold) {
            HStack(spacing: 10) {
                YantraIcon(mark: folded ? .forward : .down, size: YantraIcons.small, tint: y.muted)
                Text(inlinePlain(group.title ?? "").isEmpty ? "Untitled group" : inlinePlain(group.title ?? ""))
                    .font(Face.text(12.5, .bold)).tracking(1).textCase(.uppercase)
                    .foregroundStyle(y.muted)
                Spacer()
                Text("\(count)").font(Face.mono(12)).foregroundStyle(y.dim)
            }
            .padding(.vertical, 10)
            // The label is a glyph, a word and a count with a Spacer between them, so most of the
            // band is empty and a tap in the middle of it hits nothing. Safe here, unlike on a list
            // row: nothing on this band wants a long press, which is what a shape like this eats.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.group.\(inlinePlain(group.title ?? ""))")
        .accessibilityValue(folded ? "folded" : "open")
    }
}

/// Which groups are folded, on this device.
enum FoldedGroups {
    private static let key = "home_folded_groups"
    static func load() -> Set<String> {
        Set(AppGroup.defaults.stringArray(forKey: key) ?? [])
    }
    static func save(_ ids: Set<String>) {
        AppGroup.defaults.set(Array(ids), forKey: key)
    }
}
