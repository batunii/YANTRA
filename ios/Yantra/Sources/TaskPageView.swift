import PhotosUI
import SwiftUI
import YantraCore

/// A task's page: the universal renderer's document mode. Blocks are lines; tapping a textual block
/// edits it in place; the type bar converts or inserts.
struct TaskPageView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    let nodeId: String
    @State private var editing: Int? = nil
    @State private var draft = ""
    @State private var titleDraft = ""
    /// Deleting takes the page and everything inside it, and none of it comes back. The title is
    /// read back in the question because that is the one thing that stops the wrong page going.
    @State private var confirmingDelete = false
    @State private var movingIt = false
    /// The time editor, for an event of your own.
    @State private var editingTime = false
    @FocusState private var focus: Int?

    private var node: Node? { model.index.nodes[nodeId] }
    private var page: PageDoc? { model.storeFor(nodeId).readPage(nodeId) }
    private var crumbs: [Node] { model.index.ancestors(of: nodeId) }

    var body: some View {
        GeometryReader { g in
            // A width test, not a device test: a tablet in split screen behaves like a phone.
            let wide = g.size.width >= 840
            let siblings = node?.parentId.map { model.index.children(of: $0).filter { $0.type == NodeType.task } } ?? []
            HStack(spacing: 0) {
                if wide, siblings.count > 1 { TaskRail(siblings: siblings, currentId: nodeId) { path.append(Route.node($0)) }; Rectangle().fill(y.hairline).frame(width: 1) }
                document.frame(maxWidth: wide ? 720 : .infinity).frame(maxWidth: .infinity)
            }
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .alert("Delete \u{201C}\(inlinePlain(node?.title ?? "Untitled"))\u{201D}?",
               isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) { delete() }
            Button("Keep it", role: .cancel) {}
        } message: {
            Text("This page and everything inside it will be deleted.")
        }
        .sheet(isPresented: $movingIt) { MoveToListSheet(nodeId: nodeId) }
        .sheet(isPresented: $editingTime) {
            if let n = node, n.event != nil { EventSheet(target: .editing(n.id), cal: CalendarModel()) {} }
        }
        .onAppear { titleDraft = node?.title ?? "" }
    }

    private var document: some View {
        VStack(spacing: 0) {
            band
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    let blocks = page?.blocks ?? []
                    if blocks.isEmpty {
                        Button { addBlock(NodeType.paragraph) } label: {
                            Text("Write something…").font(Face.text(14.5)).foregroundStyle(y.dim).padding(.vertical, 12)
                        }.buttonStyle(.plain)
                    }
                    ForEach(Array(blocks.enumerated()), id: \.offset) { i, b in
                        BlockRow(pageId: nodeId, index: i, block: b, ordinal: ordinal(blocks, i), isEditing: editing == i, draft: $draft,
                                 focus: $focus, onBegin: { begin(i, b) }, onCommit: { commit(i) }, onOpen: { id in path.append(Route.node(id)) },
                            onOpenCalendar: { day in path.append(Route.calendar(day.description)) })
                    }
                    Color.clear.frame(height: 160).contentShape(Rectangle()).onTapGesture { addBlock(NodeType.paragraph) }
                }
                .padding(.leading, Layout.pageMargin).padding(.trailing, 20).padding(.top, 8)
            }
            typeBar
        }
    }

    private var band: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                NavCircle(mark: .back) { path.removeLast() }.accessibilityIdentifier("nav.back")
                Spacer()
                if let n = node, n.type == NodeType.task {
                    if let s = model.timer.state, s.nodeId == n.id, !s.isFinished {
                        Button { path.append(Route.focus(nil)) } label: {
                            HStack(spacing: 6) { YantraIcon(mark: .focus, size: YantraIcons.small, tint: y.accent); Text(sessionClock(s.isOpen ? s.elapsedSecs : s.remainingSecs)).font(Face.mono(13, bold: true)) }
                                .foregroundStyle(y.accentText).padding(.horizontal, 12).padding(.vertical, 8)
                                .background(RoundedRectangle(cornerRadius: 14).fill(y.accentFill)).overlay(RoundedRectangle(cornerRadius: 14).stroke(y.accentBorder, lineWidth: 1))
                        }.buttonStyle(.plain)
                    } else {
                        NavCircle(mark: .focus, accent: true) { path.append(Route.focus(n.id)) }
                            .accessibilityIdentifier("page.focus")
                    }
                }
                Menu {
                    Button("Move to…") { movingIt = true }
                    Button("Delete…", role: .destructive) { confirmingDelete = true }
                } label: {
                    YantraIcon(mark: .more, size: YantraIcons.medium, tint: y.secondary).frame(width: 38, height: 38).background(Circle().fill(y.ink.opacity(0.05)))
                }
            }
            if !crumbs.isEmpty {
                Text(crumbs.map { inlinePlain($0.title ?? "Untitled") }.joined(separator: "  /  ")).font(Face.text(12)).foregroundStyle(y.muted).lineLimit(1)
            }
            HStack(alignment: .top, spacing: 12) {
                if let n = node, n.type == NodeType.task {
                    YantraCheckbox(state: n.done ? .done : n.inProgress ? .inProgress : .open, size: 30) { model.toggleDone(n) }.padding(.top, 6)
                }
                TextField("Untitled", text: $titleDraft, axis: .vertical)
                    .font(Face.display(32)).foregroundStyle(y.ink).lineLimit(3)
                    .onSubmit(commitTitle)
                    .onChange(of: titleDraft) { _, v in if v.contains("\n") { titleDraft = v.replacingOccurrences(of: "\n", with: ""); commitTitle() } }
            }
            if let n = node, n.type == NodeType.task { PropertyPills(node: n) }
            // An event is a node like any other, and what makes it an event — when it is, where it
            // is, whose calendar it came from — belongs at the top of its page rather than in a card
            // you have to dismiss before you can write anything under it.
            if let n = node, n.type == NodeType.event {
                MeetingHeader(node: n, onEdit: { editingTime = true })
            }
        }
        .padding(.horizontal, Layout.pageMargin).padding(.top, 8).padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(y.band.clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 18, bottomTrailingRadius: 18)).ignoresSafeArea(edges: .top))
    }

    /// The photo being picked, if the Image key was tapped.
    @State private var picked: PhotosPickerItem?

    private var typeBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                // The kinds, in the Kotlin's order and wearing the Kotlin's marks. Task and Note
                // carry none on purpose: a task's mark is the checkbox its row already draws, and a
                // note is the absence of a kind rather than a kind of its own.
                ForEach([("Task", NodeType.task, YantraMark?.none),
                         ("Note", NodeType.paragraph, nil),
                         ("Heading", NodeType.heading, .heading),
                         ("Bullet", NodeType.bullet, .list),
                         ("Numbered", NodeType.numbered, .numbered)], id: \.1) { label, type, mark in
                    SelectChip(label: label,
                               selected: editing.flatMap { i in page?.blocks[safe: i]?.nodeType } == type,
                               mark: mark) {
                        if let i = editing { convert(i, to: type) } else { addBlock(type) }
                    }
                }
                Rectangle().fill(y.hairline).frame(width: 1, height: 22)
                SelectChip(label: "Ink", selected: false, mark: .ink) { addBlock(NodeType.ink) }
                imageChip
                // Indent is *visual*, not nesting — a guillemet on the front of the line, which
                // markdown reads as nothing. It only means something with a line in hand.
                if let i = editing {
                    Rectangle().fill(y.hairline).frame(width: 1, height: 22)
                    SelectChip(label: "Outdent", selected: false, mark: .indentOut) {
                        model.write { try model.writerFor(nodeId).indentBlock(pageId: nodeId, index: i, by: -1) }
                    }
                    SelectChip(label: "Indent", selected: false, mark: .indentIn) {
                        model.write { try model.writerFor(nodeId).indentBlock(pageId: nodeId, index: i, by: 1) }
                    }
                }
                if let i = editing {
                    Rectangle().fill(y.hairline).frame(width: 1, height: 22)
                    Button { model.write { try model.writerFor(nodeId).removeBlock(pageId: nodeId, index: i) }; editing = nil } label: {
                        Text("Delete").font(Face.text(13, .bold)).foregroundStyle(y.overdue).padding(.horizontal, 14).padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: Layout.chipRadius).fill(y.overdue.opacity(0.14)))
                    }.buttonStyle(.plain)
                }
            }.padding(.horizontal, Layout.pageMargin).padding(.vertical, 10)
        }
        .background(y.page)
    }

    // MARK: actions

    private func ordinal(_ blocks: [Block], _ i: Int) -> Int {
        guard case .numbered = blocks[i] else { return 0 }
        var n = 1, j = i - 1
        while j >= 0, case .numbered = blocks[j], blocks[j].indent == blocks[i].indent { n += 1; j -= 1 }
        return n
    }

    private func begin(_ i: Int, _ b: Block) {
        if let e = editing, e != i { commit(e) }
        editing = i; draft = b.text ?? ""; focus = i
    }

    private func commit(_ i: Int) {
        guard editing == i else { return }
        model.write { try model.writerFor(nodeId).editBlockText(pageId: nodeId, index: i, text: draft) }
        editing = nil; focus = nil
    }

    private func commitTitle() {
        guard let n = node else { return }
        let t = titleDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        if n.type == NodeType.task { model.write { try model.writerFor(n.id).setTitle(n.id, t) } } else { model.write { try model.writerFor(n.id).renamePage(n.id, t) } }
    }

    /// The Image key. A `PhotosPicker` rather than a button opening one, so what appears is the
    /// system's own sheet and the app never asks for the photo library as a whole — it is handed
    /// one picked item and nothing else.
    private var imageChip: some View {
        PhotosPicker(selection: $picked, matching: .images, photoLibrary: .shared()) {
            HStack(spacing: 6) {
                YantraIcon(mark: .image, size: YantraIcons.small, tint: y.secondary)
                Text("Image").font(Face.text(13, .medium))
            }
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: Layout.chipRadius).fill(y.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: Layout.chipRadius).stroke(y.tileBorder, lineWidth: 1))
            .foregroundStyle(y.secondary)
        }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            Task {
                defer { picked = nil }
                guard let raw = try? await item.loadTransferable(type: Data.self),
                      let bytes = ImageImport.prepare(raw) else { return }
                await MainActor.run { addImage(bytes) }
            }
        }
    }

    /// Writes the photo beside the page and puts a line in for it.
    private func addImage(_ bytes: Data) {
        if let e = editing { commit(e) }
        let name = UUID().uuidString.lowercased() + ".jpg"
        model.write {
            model.storeFor(nodeId).writeImage(String(name.dropLast(4)), bytes)
            _ = try model.writerFor(nodeId).addBlock(to: nodeId, type: NodeType.image, text: name, afterIndex: editing)
        }
    }

    private func addBlock(_ type: String) {
        if let e = editing { commit(e) }
        var newIndex = (page?.blocks.count ?? 0)
        model.write {
            _ = try model.writerFor(nodeId).addBlock(to: nodeId, type: type, text: "", afterIndex: editing)
            newIndex = editing.map { $0 + 1 } ?? newIndex
        }
        if type == NodeType.ink {
            if let inkId = model.storeFor(nodeId).readPage(nodeId)?.blocks[safe: newIndex], case let .ink(id, _, _) = inkId { path.append(Route.ink(id)) }
        } else {
            editing = newIndex; draft = ""; focus = newIndex
        }
    }

    private func convert(_ i: Int, to type: String) {
        let text = draft
        model.write {
            try model.writerFor(nodeId).editPage(nodeId, change: .structural) { page in
                var p = page
                guard i < p.blocks.count else { return p }
                let indent = p.blocks[i].indent
                switch type {
                case NodeType.task: p.blocks[i] = .task(TaskRef(id: UUID().uuidString.lowercased(), title: text, indent: indent))
                case NodeType.heading: p.blocks[i] = .heading(text, indent: indent)
                case NodeType.bullet: p.blocks[i] = .bullet(text, indent: indent)
                case NodeType.numbered: p.blocks[i] = .numbered(text, indent: indent)
                default: p.blocks[i] = .prose(text, indent: indent)
                }
                return p
            }
        }
    }

    private func delete() {
        guard let n = node else { return }
        model.write {
            let w = model.writerFor(n.id)
            if let (home, i) = w.locate(taskId: n.id) { try w.removeBlock(pageId: home, index: i) } else { try w.deletePage(n.id) }
        }
        path.removeLast()
    }
}

extension Array { subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil } }

struct BlockRow: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    let pageId: String
    let index: Int
    let block: Block
    let ordinal: Int
    let isEditing: Bool
    @Binding var draft: String
    var focus: FocusState<Int?>.Binding
    let onBegin: () -> Void
    let onCommit: () -> Void
    let onOpen: (String) -> Void
    /// An event row goes to the calendar on its own day, not to a page: an event has no page.
    let onOpenCalendar: (LocalDate) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Spacer().frame(width: CGFloat(block.indent) * 20)
            switch block {
            case let .task(t):
                let n = model.index.nodes[t.id]
                YantraCheckbox(state: t.status == .done ? .done : t.status == .inProgress ? .inProgress : .open, size: 23) {
                    if let n { model.toggleDone(n) }
                }.padding(.top, 10)
                field(placeholder: "New task", font: Face.text(15, .medium), color: t.status == .done ? y.dim : y.ink, padding: 10)
                if !t.id.isEmpty {
                    Button { onOpen(t.id) } label: {
                        HStack(spacing: 4) {
                            let kids = model.index.openChildCount(t.id)
                            if kids > 0 { Text("\(kids)").font(Face.mono(11)).foregroundStyle(y.dim) }
                            YantraIcon(mark: .forward, size: YantraIcons.small, tint: y.dim)
                        }.padding(.top, 12)
                    }.buttonStyle(.plain)
                }
            case .heading:
                field(placeholder: "Heading", font: Face.text(16, .heavy), color: y.ink, padding: 14)
            case .bullet:
                Text("•").font(Face.text(15)).foregroundStyle(y.secondary).frame(width: 22, alignment: .trailing).padding(.top, 6)
                field(placeholder: "List item", font: Face.text(14.5), color: y.ink, padding: 6)
            case .numbered:
                Text("\(ordinal).").font(Face.text(14.5)).foregroundStyle(y.secondary).frame(width: 22, alignment: .trailing).padding(.top, 6)
                field(placeholder: "List item", font: Face.text(14.5), color: y.ink, padding: 6)
            case .prose:
                field(placeholder: "Write something…", font: Face.text(14.5), color: y.ink, padding: 9)
            case let .ink(id, _, _):
                Button { onOpen(id) } label: {
                    InkBlockPreview(inkId: id).frame(maxWidth: .infinity).frame(minHeight: 64)
                        .background(RoundedRectangle(cornerRadius: 14).fill(y.surface)).overlay(RoundedRectangle(cornerRadius: 14).stroke(y.tileBorder, lineWidth: 1))
                }.buttonStyle(.plain).padding(.vertical, 6)
            case let .image(uri, _, _):
                ImageBlock(uri: uri).padding(.vertical, 6)
            case let .event(e):
                // An event has no box to tick — it is not finished, it simply passes — so the row
                // leads with when rather than with a checkbox, and opens the calendar rather than a
                // page of its own.
                Button { onOpenCalendar(e.time.start.date) } label: {
                    HStack(alignment: .top, spacing: 10) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(LabelPalette.swatchColor(e.color, dark: y.dark) ?? y.accent)
                            .frame(width: 3).padding(.vertical, 2)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.title.isEmpty ? "Event" : e.title)
                                .font(Face.text(14.5, .medium)).foregroundStyle(y.ink)
                                .multilineTextAlignment(.leading)
                            Text(eventWhen(e.time)).font(Face.mono(11)).foregroundStyle(y.muted)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 8)
                }.buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 6)
        .background(RoundedRectangle(cornerRadius: 10).fill(isEditing ? y.accent.opacity(0.05) : .clear))
    }

    @ViewBuilder
    private func field(placeholder: String, font: Font, color: Color, padding: CGFloat) -> some View {
        if isEditing {
            TextField(placeholder, text: $draft, axis: .vertical)
                .font(font).foregroundStyle(color).focused(focus, equals: index)
                .onSubmit(onCommit).submitLabel(.done)
                .onChange(of: draft) { _, v in if v.hasSuffix("\n") { draft = String(v.dropLast()); onCommit() } }
                .padding(.vertical, padding)
        } else {
            let text = block.text ?? ""
            // A line carrying links is drawn by `LinkedText`, which makes each one tappable and
            // renders it as the target's *current* title. A line without them stays a plain Text
            // inside a Button — the ordinary case, and the one where a tap anywhere should start
            // editing rather than navigate.
            if Links.hasLink(text) {
                LinkedText(text: text, font: font, color: color, open: onOpen)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, padding)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onBegin)
            } else {
                Button(action: onBegin) {
                    Text(text.isEmpty ? placeholder : inlinePlain(text)).font(font).foregroundStyle(text.isEmpty ? y.dim : color)
                        .frame(maxWidth: .infinity, alignment: .leading).multilineTextAlignment(.leading)
                        .overlay(alignment: .leading) { if case let .task(t) = block, t.status == .done { InkStrike(seed: t.id.hashValue).frame(height: 20).allowsHitTesting(false) } }
                        .padding(.vertical, padding)
                }.buttonStyle(.plain)
            }
        }
    }
}

/// The set values first at full strength, unset as ghost pills.
struct PropertyPills: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    let node: Node
    /// One sheet, chosen by what was tapped.
    ///
    /// Two `.sheet` modifiers on one view is not two sheets: SwiftUI honours one and silently drops
    /// the rest, so "+ Label" opened nothing while Due worked, and the pill looked broken rather
    /// than unimplemented. One presentation, one modifier, and adding a third kind cannot
    /// reintroduce it.
    private enum PillSheet: Identifiable {
        case due, label, assignee
        var id: Int {
            switch self { case .due: return 0; case .label: return 1; case .assignee: return 2 }
        }
    }
    @State private var presented: PillSheet?
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let due = node.due { pill("Due · " + dueLabel(due) + (due.reminders.isEmpty ? "" : (due.reminders.count == 1 ? " · 🔔" : " · 🔔\(due.reminders.count)")), color: isOverdue(node) ? y.overdue : y.due, ghost: false, id: "task.due") { presented = .due } }
                if let p = node.priority { pill("Priority · " + p, color: y.priority(p) ?? y.secondary, ghost: false) { cyclePriority() } }
                ForEach(node.labels, id: \.self) { l in
                    // Tap detaches, as on Android.
                    pill("#\(l)", color: LabelPalette.color(l, registry: model.index.labels, dark: y.dark), ghost: false) {
                        model.write { try model.writerFor(node.id).editTask(node.id) { t in var x = t; x.labels.removeAll { $0 == l }; return x } }
                    }
                }
                if let who = node.assignee {
                    pill("@\(who)", color: y.accentText, ghost: false, id: "task.assignee") { presented = .assignee }
                }
                if node.due == nil { pill("+ Due", color: y.muted, ghost: true) { presented = .due } }
                if node.priority == nil { pill("+ Priority", color: y.muted, ghost: true) { cyclePriority() } }
                pill("+ Label", color: y.muted, ghost: true) { presented = .label }
                if node.assignee == nil { pill("+ Who", color: y.muted, ghost: true, id: "task.assign") { presented = .assignee } }
            }
        }
        .sheet(item: $presented) { which in
            switch which {
            case .due: DueSheet(node: node)
            case .label: LabelPicker(node: node)
            case .assignee: AssigneeSheet(node: node)
            }
        }
    }
    private func cyclePriority() {
        let order: [String?] = ["High", "Medium", "Low", nil]
        let i = order.firstIndex(of: node.priority) ?? 3
        model.write { try model.writerFor(node.id).setPriority(node.id, order[(i + 1) % order.count]) }
    }
    private func pill(_ text: String, color: Color, ghost: Bool, id: String? = nil,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(Face.text(11.5, .semibold)).foregroundStyle(color).padding(.horizontal, 9).padding(.vertical, 5)
                .background(RoundedRectangle(cornerRadius: 5).fill(ghost ? .clear : color.opacity(0.14)))
                .overlay(RoundedRectangle(cornerRadius: 5).stroke(color.opacity(ghost ? 0.55 : 0), style: StrokeStyle(lineWidth: 1, dash: ghost ? [6, 4] : [])))
                .opacity(ghost ? 0.7 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id ?? "")
    }
}

/// Read-only preview of an ink block's strokes, drawn from the sidecar.
struct InkBlockPreview: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    let inkId: String
    var body: some View {
        let strokes = model.storeFor(inkId).readInk(inkId).compactMap { try? StrokeEnvelope.decode($0) }
        if strokes.isEmpty {
            HStack(spacing: 8) { YantraIcon(mark: .ink, size: YantraIcons.medium, tint: y.dim); Text("Tap to sketch").font(Face.text(13)) }.foregroundStyle(y.dim).padding(20)
        } else {
            StrokeCanvas(strokes: strokes, dark: y.dark).frame(height: 180).padding(8)
        }
    }
}

struct StrokeCanvas: View {
    let strokes: [StrokeEnvelope.Envelope]
    let dark: Bool
    var body: some View {
        Canvas { ctx, size in
            let pts = strokes.flatMap(\.points)
            guard let minX = pts.map(\.x).min(), let maxX = pts.map(\.x).max(), let minY = pts.map(\.y).min(), let maxY = pts.map(\.y).max() else { return }
            let w = max(maxX - minX, 1), h = max(maxY - minY, 1)
            let scale = min((size.width - 16) / CGFloat(w), (size.height - 16) / CGFloat(h), 1.5)
            let ox = (size.width - CGFloat(w) * scale) / 2, oy = (size.height - CGFloat(h) * scale) / 2
            for s in strokes where !s.points.isEmpty {
                var path = Path()
                for (i, p) in s.points.enumerated() {
                    let pt = CGPoint(x: ox + CGFloat(p.x - minX) * scale, y: oy + CGFloat(p.y - minY) * scale)
                    if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
                }
                var color = Color(argb: s.header.color)
                // The two theme-native inks swap with the theme, as on Android.
                if s.header.color == 0xFF23211C, dark { color = Color(argb: 0xFFF1EEE7) }
                if s.header.color == 0xFFF1EEE7, !dark { color = Color(argb: 0xFF23211C) }
                ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: CGFloat(s.header.size) * scale, lineCap: .round, lineJoin: .round))
            }
        }
    }
}

/// The list beside the page on a wide window — `TaskRail`. Marked, not selected: the current row
/// wears the started wash. The glyphs are informational; the page is where you act.
struct TaskRail: View {
    @Environment(\.y) private var y
    let siblings: [Node]
    let currentId: String
    let open: (String) -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                SectionLabel(text: "In this list").padding(.horizontal, 16).padding(.top, 60).padding(.bottom, 8)
                ForEach(siblings) { n in
                    Button { open(n.id) } label: {
                        HStack(alignment: .top, spacing: 10) {
                            YantraCheckbox(state: n.done ? .done : n.inProgress ? .inProgress : .open, size: 23) {}.allowsHitTesting(false)
                            Text(inlinePlain(n.title ?? "").isEmpty ? "Untitled" : inlinePlain(n.title ?? "")).font(Face.text(14.5, n.id == currentId ? .bold : .medium))
                                .foregroundStyle(n.done ? y.dim : y.ink).lineLimit(2).multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: 12).fill(n.id == currentId ? y.accentFill.opacity(0.6) : .clear))
                    }.buttonStyle(.plain)
                }
            }.padding(.horizontal, 6)
        }
        .frame(width: 340)
        .background(y.rail)
    }
}

/// When an event is, in words — the reading a row wants rather than the bytes the file keeps.
func eventWhen(_ t: EventTime) -> String {
    let day = dateLabel(t.start.date)
    if t.allDay {
        // Exclusive in the model, inclusive in the reading: "the 11th to the 13th" is what somebody
        // who wrote that line meant.
        let last = t.end.date.adding(days: -1)
        return last <= t.start.date ? day : "\(day) – \(dateLabel(last))"
    }
    let f = DateFormatter(); f.dateFormat = "HH:mm"
    let from = f.string(from: t.start.instant())
    if t.isInstantaneous { return "\(day) \(from)" }
    return "\(day) \(from)–\(f.string(from: t.end.instant()))"
}

/// A photo that lives beside the page.
///
/// Read from the workspace rather than from a bundle or a cache: the file *is* the picture, and a
/// block that drew from anywhere else would show something the repository does not contain.
struct ImageBlock: View {
    let uri: String
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y

    private var image: UIImage? {
        // The line names a file; the bytes sit next to the page under the same stem.
        let stem = uri.hasSuffix(".jpg") ? String(uri.dropLast(4)) : uri
        // The block knows its file's name and not which workspace it is in, so the workspaces are
        // asked in turn. The stem is a uuid, so the first one holding it is the one that wrote it.
        return model.allStores.lazy
            .compactMap { try? Data(contentsOf: $0.imageFile(stem)) }
            .first
            .flatMap(UIImage.init(data:))
    }

    var body: some View {
        if let image {
            Image(uiImage: image)
                .resizable().scaledToFit()
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: Layout.cardRadius))
                .overlay(RoundedRectangle(cornerRadius: Layout.cardRadius).stroke(y.tileBorder, lineWidth: 1))
                .accessibilityLabel("Image")
        } else {
            // A line whose file is missing says so. Drawing nothing would make a block that is
            // plainly in the file look like a blank in the page.
            RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.surfaceHigh)
                .frame(height: 96)
                .overlay(
                    VStack(spacing: 6) {
                        YantraIcon(mark: .image, size: YantraIcons.medium, tint: y.dim)
                        Text("Picture not in this workspace").font(Face.text(11.5)).foregroundStyle(y.dim)
                    }
                )
        }
    }
}
