import SwiftUI
import YantraCore

/// A list page or a smart list: task rows in the two-line grammar, a capture bar at the bottom.
struct ListView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    let nodeId: String
    let isSmart: Bool
    @State private var capture = ""
    @State private var showDone = false
    @State private var editingRules = false
    /// Tasks that were open when this list was opened, and stay in place for as long as it is.
    ///
    /// Ticking one used to take it out from under your finger the same instant: the row vanished
    /// into the Done section and everything below jumped up a line. That is the wrong feedback for
    /// the commonest action in the app — you cannot see what you just did, and if it was the wrong
    /// row you cannot see that either.
    ///
    /// So a finished task is struck through **where it is**, and the list tidies itself the next
    /// time you come to it. Held by id rather than by copying the rows, so everything else about
    /// them — the title, the labels, the ring — stays live.
    @State private var standing: Set<String> = []

    private var node: Node? { model.index.nodes[nodeId] }

    /// What this view has already said. A plain list page has no rule — its rule is "children of
    /// this page", which pins the list by construction; a smart list hands over its own filter.
    private var grammar: RowGrammar {
        Salience.grammar(ViewContext(filter: isSmart ? model.index.smartLists[nodeId]?.filter : nil,
                                     singleWorkspace: model.allStores.count <= 1))
    }
    /// Every task in the list, however it is gathered.
    private var allRows: [Node] {
        guard let n = node else { return [] }
        if isSmart { return model.smartListRows(n) + model.completedRows(n) }
        return model.index.children(of: n.id).filter { $0.type == NodeType.task }
    }
    private var openRows: [Node] {
        allRows.filter { !$0.done || standing.contains($0.id) }
            .sorted { a, b in a.inProgress != b.inProgress ? a.inProgress : a.rank < b.rank }
    }
    private var doneRows: [Node] {
        allRows.filter { $0.done && !standing.contains($0.id) }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 3) {
                    if openRows.isEmpty && doneRows.isEmpty {
                        ComposedEmpty(line: isSmart ? "Nothing matches right now" : "Nothing here yet")
                    } else if openRows.isEmpty {
                        ComposedEmpty(line: "All done here")
                    }
                    ForEach(openRows) { row in
                        TaskRow(node: row, showOrigin: isSmart, grammar: grammar,
                                onOpen: { path.append(Route.node(row.id)) })
                    }
                    if !doneRows.isEmpty {
                        HStack {
                            SectionLabel(text: "Done · \(doneRows.count)")
                            Spacer()
                            Button(showDone ? "Hide" : "Show") { withAnimation { showDone.toggle() } }.font(Face.text(12, .bold)).foregroundStyle(y.accentText)
                        }.padding(.top, 18).padding(.bottom, 4)
                        if showDone { ForEach(doneRows) { row in TaskRow(node: row, showOrigin: isSmart, grammar: grammar, onOpen: { path.append(Route.node(row.id)) }) } }
                    }
                    Spacer().frame(height: 20)
                }
                .padding(.horizontal, Layout.pageMargin).padding(.top, 10).readableColumn()
            }
            // Capture is always open — writing something down should never cost a mode — and the
            // player slots in above it, so the field is pinned to the screen edge whatever is or is
            // not running.
            BottomBar(onOpenNow: { path.append(Route.focus($0.nodeId)) }) {
                if !isSmart || model.index.smartLists[nodeId]?.homeParentId != nil {
                    QuickAddBar(text: $capture, placeholder: "Add a task…",
                                workspaceId: node?.workspaceId ?? "") {
                        model.capture(capture, into: node); capture = ""
                    }
                }
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
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            // What was open when you arrived. Recomputed on every appearance, which is what makes
            // "the next time you open it" the moment the list tidies up — including coming back to
            // it from a task page.
            standing = Set(allRows.filter { !$0.done }.map(\.id))
            if LaunchRoute.rulesFor == nodeId { editingRules = true }
        }
        .sheet(isPresented: $editingRules) { if let n = node { SmartListBuilder(editing: n, path: $path) } }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                NavCircle(mark: .back) { path.removeLast() }.accessibilityIdentifier("nav.back")
                Spacer()
                // No focus control here. A session is time given to **one thing**, and the file it
                // is written to names a task — `NodePageScreen` gates the same control on
                // `isTask`. A list offering to be focused on was offering to start a clock against
                // a container, which the stats screen would then have to report as work done on
                // nothing in particular.
                if isSmart {
                    Menu { Button("Edit rules") { editingRules = true } } label: {
                        YantraIcon(mark: .more, size: YantraIcons.medium, tint: y.secondary).frame(width: 38, height: 38).background(Circle().fill(y.ink.opacity(0.05)))
                    }
                }
            }
            if isSmart {
                HStack(spacing: 6) { YantraIcon(mark: .smartList, size: YantraIcons.small, tint: y.accent); Text("SMART VIEW").font(Face.text(11, .semibold)).kerning(1.5) }.foregroundStyle(y.accent).padding(.top, 8)
            }
            Text(inlinePlain(node?.title ?? "").isEmpty ? "Untitled" : inlinePlain(node?.title ?? "")).font(Face.display(isSmart ? 22 : 32)).tracking(-0.6).foregroundStyle(y.ink).lineLimit(3)
            if isSmart, let def = model.index.smartLists[nodeId] {
                let rules = def.filter.map { countRules($0) } ?? 0
                let home = def.homeParentId.flatMap { model.index.nodes[$0]?.title }
                HStack(spacing: 8) {
                    Circle().fill(y.accent).frame(width: 6, height: 6)
                    Text("\(rules) rule\(rules == 1 ? "" : "s")" + (home.map { " · lands in \($0)" } ?? "")).font(Face.text(11.5, .semibold)).foregroundStyle(y.secondary)
                }
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(y.page)).overlay(Capsule().stroke(y.tileBorder, lineWidth: 1))
            } else if let n = node {
                let kids = model.index.children(of: n.id).filter { $0.type == NodeType.task }
                Text(kids.isEmpty ? "Empty" : "\(kids.filter(\.done).count) of \(kids.count) done").font(Face.text(13)).foregroundStyle(y.muted)
            }
        }
        .padding(.horizontal, Layout.pageMargin).padding(.top, 8).padding(.bottom, 16)
        // The band runs the full width of the glass; the words in it line up with the rows below,
        // so a wide window does not read as two different columns stacked on each other.
        .readableColumn()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(y.band.clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 20, bottomTrailingRadius: 20)).ignoresSafeArea(edges: .top))
    }

    private func countRules(_ f: Filter) -> Int {
        switch f {
        case let .all(fs): return fs.filter { if case .type = $0 { return false }; if case .done = $0 { return false }; return true }.reduce(0) { $0 + max(countRules($1), 1) }
        case let .anyOf(fs): return fs.isEmpty ? 0 : 1
        case .type, .done: return 0
        default: return 1
        }
    }
}

/// A task row on a list is exactly two lines: the title with due at its end, and one line of meta
/// in the instrument voice beneath it.
struct TaskRow: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    let node: Node
    var showOrigin: Bool = false
    /// What this view has already said, so the row does not say it again — `ROW_SALIENCE.md`.
    /// Nil on a surface that has not been taught its own grammar yet, which reads as a list page.
    var grammar: RowGrammar?
    let onOpen: () -> Void
    @State private var swipe: CGFloat = 0

    private var state: TaskGlyphState { node.done ? .done : node.inProgress ? .inProgress : .open }
    private var originName: String? {
        guard showOrigin else { return nil }
        return model.index.ancestors(of: node.id).last { $0.type == NodeType.list }?.title
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            YantraCheckbox(state: state, size: 23, frameTint: node.done ? nil : y.priority(node.priority),
                           swipeProgress: swipe) { model.toggleDone(node) }
                .padding(.top, 1)
                .accessibilityIdentifier("task.check.\(inlinePlain(node.title ?? ""))")
                .accessibilityLabel(node.done ? "Mark not done" : "Mark done")
            // Not a Button. A Button is only as big as its label, so the row's padding, the gap
            // beside the due date and everything right of the words were dead: the task opened if
            // you hit the title and did nothing if you hit the card an inch away. The whole row is
            // the target now, with the checkbox keeping its own — see the tap gesture below.
            VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(inlinePlain(node.title ?? "").isEmpty ? "Untitled" : inlinePlain(node.title ?? ""))
                            .font(Face.text(15, .medium)).foregroundStyle(node.done ? y.dim : y.ink).lineLimit(1)
                            .overlay(alignment: .leading) { if node.done { InkStrike(seed: node.id.hashValue).allowsHitTesting(false) } }
                        Spacer(minLength: 4)
                        // The title slot carries the date the view has not already fixed. On Today
                        // an on-time due date says nothing; overdue always speaks, in the alert
                        // voice, because that is the exception the rules are allowed to have.
                        if let due = node.due, !node.done, !silent(.prop(BuiltIns.due)) {
                            Text(dueLabel(due)).font(Face.mono(11, bold: true)).foregroundStyle(isOverdue(node) ? y.overdue : isDueToday(node) ? y.due : y.secondary)
                        }
                        let kids = model.index.openChildCount(node.id)
                        if kids > 0 { Text("\(kids)").font(Face.mono(11)).foregroundStyle(y.dim) }
                        YantraIcon(mark: .forward, size: YantraIcons.small, tint: y.dim)
                    }
                meta
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Combined into one element, and only *this* half of the row.
            //
            // A plain stack publishes no element of its own, so the identifier went nowhere and the
            // row stopped being findable at all — by a test, and by anybody navigating with
            // VoiceOver. Combining fixes that. It is deliberately not applied to the whole row:
            // the checkbox is a control in its own right and folding it in would leave no way to
            // tick a task without opening it.
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("task.row.\(inlinePlain(node.title ?? ""))")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { onOpen() }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 16).fill(node.inProgress ? y.accentFill.opacity(0.5) : y.cardBg))
        // Everything the card covers, including its padding, opens the task.
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        // The drag used to track a distance nothing drew, so a swipe looked like nothing at all
        // until it committed — and a task already finished still ran the gesture to no effect.
        .swipeToProgress($swipe, enabled: !node.done) { model.toggleInProgress(node) }
    }

    /// The row's grammar, defaulting to a list page's when nobody has said otherwise.
    private var says: RowGrammar {
        grammar ?? Salience.grammar(ViewContext(filter: nil, singleWorkspace: model.allStores.count <= 1))
    }

    /// Whether this view has already said a field, so the row can stay quiet about it.
    private func silent(_ field: Field) -> Bool {
        // An overdue date is an exception and beats every rule: `ROW_SALIENCE.md` §3 allows exactly
        // two overrides and this is the first. Keep that list boring.
        if field == .prop(BuiltIns.due), isOverdue(node) { return false }
        if field == .prop(BuiltIns.deadline), let d = node.deadline, d < .today() { return false }
        return says.pinned.contains(field)
    }

    private var meta: some View {
        // Tags lead the line — `DESIGN.md` §6 took the assignee out of the title to give them the
        // room, and the line ellipsises from the tail, so the tags cannot be what pays. The matched
        // tag leads the run: it is the reason this row is on this screen.
        let branchedFirst = node.labels.sorted { a, b in
            let ba = says.branched.contains(.label(a)), bb = says.branched.contains(.label(b))
            return ba != bb ? ba : a < b
        }
        let tags = branchedFirst.filter { !silent(.label($0)) }.prefix(3)
        // The assignee is yours to skip: on your own tasks the name is the one thing you already
        // know. `Expected` shipped with this single field on Android, for the same reason.
        let mine = node.assignee.map { $0.caseInsensitiveCompare(SyncSettings.login ?? "\u{0}") == .orderedSame } ?? false
        let showWho = !silent(.prop(BuiltIns.assignee)) && node.assignee != nil && !mine
        let showDeadline = !silent(.prop(BuiltIns.deadline))
        let showOriginNow = !silent(.originList)

        let parts: [(String, Color)] = (showWho ? [("@\(node.assignee!)", y.secondary)] : [])
            + tags.map { ("#\($0)", labelColor($0)) }
            + ((showDeadline ? node.deadline : nil).map { [(deadlineLabel($0), $0 < .today() ? y.overdue : y.secondary)] } ?? [])
            + ((showOriginNow ? originName : nil).map { [($0, y.muted)] } ?? [])
        return HStack(spacing: 6) {
            if parts.isEmpty { Text("\u{2009}").font(Face.mono(10.5)) }
            ForEach(Array(parts.enumerated()), id: \.offset) { i, p in
                if i > 0 { Text("·").font(Face.mono(10.5)).foregroundStyle(y.dim) }
                Text(p.0).font(Face.mono(10.5)).foregroundStyle(p.1)
            }
            Spacer(minLength: 0)
        }.lineLimit(1)
    }

    private func labelColor(_ name: String) -> Color { LabelPalette.color(name, registry: model.index.labels, dark: y.dark) }
}

struct QuickAddBar: View {
    @Binding var text: String
    let placeholder: String
    /// Which workspace the capture lands in, so `@` offers the people who can push *there*.
    /// A roster is per repository: the names on a shared project are not the names on your own.
    var workspaceId: String = ""
    let submit: () -> Void
    @EnvironmentObject private var model: AppModel
    @Environment(\.y) private var y
    /// Where the caret is, so `[[` and `~` know what is being typed *now* rather than what the line
    /// happens to end with.
    @State private var caret = 0
    /// What the field says it needs, so the bar is one line until the words need two.
    @State private var captureHeight: CGFloat = 22

    private var lists: [String] {
        model.index.children(of: nil).filter { $0.type == NodeType.list }.map { inlinePlain($0.title ?? "") }
    }
    private var people: [String] { People.shared.logins(workspaceId: workspaceId, index: model.index) }

    var body: some View {
        VStack(spacing: 0) {
            CaptureSuggestions(text: $text, caret: caret, lists: lists, workspaceId: workspaceId,
                               onCaret: { caret = $0 })
            bar
        }
    }

    private var bar: some View {
        HStack(spacing: 10) {
            // Tinted as it is understood — see `CaptureField`. The words that will leave the title
            // say so before the task is made, not after.
            CaptureField(text: $text, caret: $caret, placeholder: placeholder, onSubmit: submit,
                         onHeight: { captureHeight = $0 },
                         lists: lists, people: people,
                         labelColor: { UIColor(LabelPalette.color($0, registry: model.index.labels, dark: y.dark)) },
                         palette: .init(ink: UIColor(y.ink), dim: UIColor(y.dim), date: UIColor(y.due),
                                        priority: UIColor(y.crimson), list: UIColor(y.accentText),
                                        link: UIColor(y.accentText), assignee: UIColor(y.secondary)))
                // The height of one line, growing only as far as a few — the bar was a slab before
                // this, because a non-scrolling text view takes whatever it is offered.
                .frame(height: max(22, min(captureHeight, 88)))
            Button(action: submit) {
                YantraIcon(mark: .send, size: YantraIcons.medium, tint: y.onAccent)
                    .foregroundStyle(text.isEmpty ? y.dim : y.accent).frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 12).fill(text.isEmpty ? y.surfaceHigh : y.accentFill))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(text.isEmpty ? y.tileBorder : y.accentBorder, lineWidth: 1))
            }.buttonStyle(.plain)
        }
        .padding(.leading, 18).padding(.trailing, 8).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: Layout.barRadius).fill(y.cardBg))
        .overlay(RoundedRectangle(cornerRadius: Layout.barRadius).stroke(y.tileBorder, lineWidth: 1))
        // The safe area supplies the rest of the gap below, so this is the breathing room and not a
        // guess at the home indicator's height on top of it.
        .padding(.horizontal, Layout.pageMargin).padding(.bottom, 10).padding(.top, 10)
        .readableColumn()
    }
}
