import SwiftUI
import YantraCore

/// Finished tasks that left your lists, grouped by the list they came from, each one tap from coming
/// back exactly where it was — `ArchiveScreen`. Reads the archive files directly: archived tasks are
/// deliberately not indexed.
struct ArchiveView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    @State private var groups: [(pageId: String, title: String, tasks: [TaskRef])] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack { NavCircle(mark: .back) { path.removeLast() }; Spacer() }
                Text("Archive").font(Face.display(32)).tracking(-0.6).foregroundStyle(y.ink).padding(.top, 18)
                Text("Finished tasks that left your lists. They are still in the repository — putting one back returns it exactly where it was.")
                    .font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 6).padding(.bottom, 20)
                if groups.isEmpty { ComposedEmpty(line: "Nothing archived yet") }
                ForEach(groups, id: \.pageId) { g in
                    SectionLabel(text: g.title).padding(.bottom, 8)
                    ForEach(g.tasks, id: \.id) { t in
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(inlinePlain(t.title).isEmpty ? "Untitled task" : inlinePlain(t.title)).font(Face.text(14, .semibold)).foregroundStyle(y.secondary).lineLimit(2)
                                Text("Finished " + finishedLabel(t.doneAt)).font(Face.text(11.5)).foregroundStyle(y.dim)
                            }
                            Spacer()
                            NavCircle(mark: .undo, accent: true) {
                                model.write { _ = try model.writer.restoreArchived(pageId: g.pageId, taskIds: [t.id]) }
                                load()
                            }.frame(width: 34, height: 34)
                        }
                        .padding(.horizontal, 16).padding(.vertical, 13)
                        .background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.cardBg)).padding(.bottom, 8)
                    }
                    Spacer().frame(height: 12)
                }
                Spacer().frame(height: 40)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: load)
    }

    private func load() {
        groups = model.store.archivedPageIds().compactMap { pageId in
            let tasks = model.store.readArchivedLines(pageId).compactMap { line -> TaskRef? in if case let .task(t) = PageCodec.decodeBlock(line) { return t }; return nil }
            if tasks.isEmpty { return nil }
            return (pageId, inlinePlain(model.index.nodes[pageId]?.title ?? "Untitled list"), tasks)
        }
    }

    private func finishedLabel(_ d: LocalDate?) -> String {
        guard let d else { return "some time ago" }
        let days = d.days(until: .today())
        if days <= 0 { return "today" }; if days == 1 { return "yesterday" }
        if days < 30 { return "\(days) days ago" }; if days < 365 { return "\(days / 30) months ago" }
        let f = DateFormatter(); f.dateFormat = "MMM yyyy"; return f.string(from: d.startOfDay())
    }
}
