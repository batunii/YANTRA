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

    private var node: Node? { model.index.nodes[nodeId] }
    private var openRows: [Node] {
        guard let n = node else { return [] }
        if isSmart { return model.smartListRows(n) }
        return model.index.children(of: n.id).filter { $0.type == NodeType.task && !$0.done }.sorted { a, b in a.inProgress != b.inProgress ? a.inProgress : a.rank < b.rank }
    }
    private var doneRows: [Node] {
        guard let n = node else { return [] }
        if isSmart { return model.completedRows(n) }
        return model.index.children(of: n.id).filter { $0.type == NodeType.task && $0.done }
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
                        TaskRow(node: row, showOrigin: isSmart, onOpen: { path.append(Route.node(row.id)) })
                    }
                    if !doneRows.isEmpty {
                        HStack {
                            SectionLabel(text: "Done · \(doneRows.count)")
                            Spacer()
                            Button(showDone ? "Hide" : "Show") { withAnimation { showDone.toggle() } }.font(Face.text(12, .bold)).foregroundStyle(y.accentText)
                        }.padding(.top, 18).padding(.bottom, 4)
                        if showDone { ForEach(doneRows) { row in TaskRow(node: row, showOrigin: isSmart, onOpen: { path.append(Route.node(row.id)) }) } }
                    }
                    Spacer().frame(height: 20)
                }
                .padding(.horizontal, Layout.pageMargin).padding(.top, 10)
            }
            if !isSmart || model.index.smartLists[nodeId]?.homeParentId != nil {
                QuickAddBar(text: $capture, placeholder: "Add a task…") {
                    model.capture(capture, into: node); capture = ""
                }
            }
        }
        .background(y.page.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $editingRules) { if let n = node { SmartListBuilder(editing: n, path: $path) } }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                NavCircle(icon: "chevron.left") { path.removeLast() }
                Spacer()
                if !isSmart, let n = node {
                    NavCircle(icon: "timer", accent: true) { path.append(Route.focus(n.id)) }
                } else if isSmart {
                    Menu { Button("Edit rules") { editingRules = true } } label: {
                        Image(systemName: "ellipsis").font(.system(size: 17, weight: .semibold)).foregroundStyle(y.secondary).frame(width: 38, height: 38).background(Circle().fill(y.ink.opacity(0.05)))
                    }
                }
            }
            if isSmart {
                HStack(spacing: 6) { Image(systemName: "sparkles").font(.system(size: 12, weight: .semibold)); Text("SMART VIEW").font(Face.text(11, .semibold)).kerning(1.5) }.foregroundStyle(y.accent).padding(.top, 8)
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
    let onOpen: () -> Void
    @State private var swipe: CGFloat = 0

    private var state: TaskGlyphState { node.done ? .done : node.inProgress ? .inProgress : .open }
    private var originName: String? {
        guard showOrigin else { return nil }
        return model.index.ancestors(of: node.id).last { $0.type == NodeType.list }?.title
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            YantraCheckbox(state: state, size: 23, frameTint: node.done ? nil : y.priority(node.priority)) { model.toggleDone(node) }
                .padding(.top, 1)
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(inlinePlain(node.title ?? "").isEmpty ? "Untitled" : inlinePlain(node.title ?? ""))
                            .font(Face.text(15, .medium)).foregroundStyle(node.done ? y.dim : y.ink).lineLimit(1)
                            .overlay(alignment: .leading) { if node.done { InkStrike(seed: node.id.hashValue).allowsHitTesting(false) } }
                        Spacer(minLength: 4)
                        if let due = node.due, !node.done {
                            Text(dueLabel(due)).font(Face.mono(11, bold: true)).foregroundStyle(isOverdue(node) ? y.overdue : isDueToday(node) ? y.due : y.secondary)
                        }
                        let kids = model.index.openChildCount(node.id)
                        if kids > 0 { Text("\(kids)").font(Face.mono(11)).foregroundStyle(y.dim) }
                        Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(y.dim)
                    }
                    meta
                }
            }.buttonStyle(.plain)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 16).fill(node.inProgress ? y.accentFill.opacity(0.5) : y.cardBg))
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 24, coordinateSpace: .local)
                .onChanged { v in if v.translation.width > 0 { swipe = min(v.translation.width, 90) } }
                .onEnded { v in
                    if v.translation.width >= 72, !node.done {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.6)
                        model.toggleInProgress(node)
                    }
                    swipe = 0
                }
        )
    }

    private var meta: some View {
        let parts: [(String, Color)] = node.labels.prefix(3).map { ("#\($0)", labelColor($0)) }
            + (node.deadline.map { [(deadlineLabel($0), $0 < .today() ? y.overdue : y.secondary)] } ?? [])
            + (originName.map { [($0, y.muted)] } ?? [])
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
    let submit: () -> Void
    @Environment(\.y) private var y
    var body: some View {
        HStack(spacing: 10) {
            TextField(placeholder, text: $text).font(Face.text(15.5, .medium)).foregroundStyle(y.ink).submitLabel(.done).onSubmit(submit)
                .autocorrectionDisabled()
            Button(action: submit) {
                Image(systemName: "paperplane.fill").font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(text.isEmpty ? y.dim : y.accent).frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 12).fill(text.isEmpty ? y.surfaceHigh : y.accentFill))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(text.isEmpty ? y.tileBorder : y.accentBorder, lineWidth: 1))
            }.buttonStyle(.plain)
        }
        .padding(.leading, 18).padding(.trailing, 8).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: Layout.barRadius).fill(y.cardBg))
        .overlay(RoundedRectangle(cornerRadius: Layout.barRadius).stroke(y.tileBorder, lineWidth: 1))
        .padding(.horizontal, Layout.pageMargin).padding(.bottom, 10).padding(.top, 6)
        .background(y.page)
    }
}
