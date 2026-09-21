import SwiftUI
import YantraCore

enum Route: Hashable {
    case node(String)
    case smart(String)
    case focus(String?)
    case stats
    case settings
    case conformance
    case ink(String)
    case github
    case archive
    /// The calendar, optionally landing on a day — how the widget points at one.
    case calendar(String?)
    /// Every mark at once, for checking the drawings. `-route marks`.
    case marks
}

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    @State private var creating = false
    /// The list whose appearance is being chosen, if any.
    @State private var look: Node?

    private var lists: [Node] { model.index.children(of: nil).filter { $0.type == NodeType.list } }
    private var smart: [Node] { model.index.children(of: nil).filter { $0.type == NodeType.smartList } }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        switch h { case 5...11: return "Morning"; case 12...16: return "Afternoon"; case 17...21: return "Evening"; default: return "Late one" }
    }
    private var tally: String {
        // Regular lists only — smart lists re-count the same tasks.
        let open = lists.reduce(0) { $0 + model.index.openChildCount($1.id) }
        if open == 0 { return "Nothing open. Breathe." }
        return "\(open) open across \(lists.count) list\(lists.count == 1 ? "" : "s")"
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(Date(), format: .dateTime.weekday(.wide)).font(Face.text(12.5, .medium)).foregroundStyle(y.muted)
                                + Text(" · ").font(Face.text(12.5, .medium)).foregroundStyle(y.muted)
                                + Text(Date(), format: .dateTime.day().month(.abbreviated)).font(Face.text(12.5, .medium)).foregroundStyle(y.muted)
                            Text(greeting).font(Face.display(24)).tracking(-0.3).foregroundStyle(y.ink)
                                .accessibilityIdentifier("home.greeting")
                            Text(tally).font(Face.text(13.5)).foregroundStyle(y.secondary)
                        }
                        Spacer()
                        NavCircle(mark: .settings) { path.append(Route.settings) }
                            .accessibilityIdentifier("home.settings").accessibilityLabel("Settings")
                    }
                    .padding(.top, 14).padding(.bottom, 22)

                    if !smart.isEmpty {
                        SectionLabel(text: "Pinned").padding(.bottom, 6)
                        ForEach(smart) { n in HomeRow(node: n, isSmart: true, open: { path.append(Route.smart(n.id)) }, look: $look) }
                        Spacer().frame(height: 18)
                    }
                    SectionLabel(text: "Lists").padding(.bottom, 6)
                    if lists.isEmpty {
                        ComposedEmpty(line: "Nothing here yet", action: "Make a list") { creating = true }
                    }
                    ForEach(lists) { n in HomeRow(node: n, isSmart: false, open: { path.append(Route.node(n.id)) }, look: $look) }
                    Spacer().frame(height: 120)
                }
                .padding(.horizontal, Layout.pageMargin)
            }
            HomeTabBar(onCalendar: { path.append(Route.calendar(nil)) },
                       onCreate: { creating = true },
                       onStats: { path.append(Route.stats) })
        }
        .background(y.page.ignoresSafeArea())
        .sheet(isPresented: $creating) { CreateSheet(path: $path) }
        .sheet(item: $look) { n in
            ListLookSheet(nodeId: n.id, title: inlinePlain(n.title ?? ""), smart: n.type == NodeType.smartList)
        }
    }
}

struct HomeRow: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    let node: Node
    let isSmart: Bool
    let open: () -> Void
    @Binding var look: Node?

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
                    Text(c.total == 0 ? "Empty" : "\(c.done) of \(c.total) done").font(Face.text(12.5)).foregroundStyle(y.muted)
                }
                Spacer()
                if c.total > 0 { Compass(fraction: Double(c.done) / Double(c.total)) }
            }
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        // Identifier only. A Button already reads as one control with its children folded into the
        // label; adding an explicit combine on top makes an element that reports as a button and
        // swallows the tap, which is a row that looks right in the tree and does nothing.
        .accessibilityIdentifier("home.row.\(inlinePlain(node.title ?? ""))")
        // How a list looks is a second thing you can do to a row, so it is the second gesture
        // rather than a control that would sit on every row for the once anybody uses it.
        .contextMenu {
            Button { look = node } label: { Label("How it looks", systemImage: "paintpalette") }
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
        .padding(.horizontal, 40).padding(.top, 10).padding(.bottom, 6)
        .background(y.page)
    }
}

struct CreateSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    @Binding var path: NavigationPath
    @State private var text = ""
    @State private var kind = 0
    @State private var smart = false
    @State private var builder = false
    @FocusState private var focused: Bool
    private let kinds = ["Task", "List", "Group"]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            TextField(kind == 0 ? "New task" : kind == 1 ? "New list" : "New group", text: $text)
                .font(Face.display(24)).foregroundStyle(y.ink).focused($focused).submitLabel(.done).onSubmit(create)
            HStack(spacing: 8) {
                ForEach(0..<3, id: \.self) { i in SelectChip(label: kinds[i], selected: kind == i, stretch: true) { kind = i } }
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
            model.capture(t, into: nil)
            if let inbox = model.index.node(systemKey: SystemKey.inbox) { path.append(Route.node(inbox.id)) }
        case 1:
            var id = ""
            model.write { id = try model.writer.createTopLevel(type: NodeType.list, title: t) }
            if !id.isEmpty { path.append(Route.node(id)) }
        default:
            model.write { _ = try model.writer.createTopLevel(type: NodeType.group, title: t) }
        }
        dismiss()
    }
}
