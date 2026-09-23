import SwiftUI
import YantraCore

/// Where a task goes: any list, in any workspace.
///
/// Grouped by workspace and coloured by it, because that is the part of this choice that can go
/// quietly wrong. Two workspaces can hold a list with the same name, and moving work into the wrong
/// repository is not a mistake you notice — it is a task that stops being where the people who share
/// that repository are looking.
struct MoveToListSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    let nodeId: String
    @State private var search = ""

    var body: some View {
        let targets = model.moveTargets(for: nodeId)
        let shown = targets.filter { search.isEmpty || inlinePlain($0.list.title ?? "").localizedCaseInsensitiveContains(search) }
        let byWorkspace = Dictionary(grouping: shown) { $0.workspace }

        VStack(alignment: .leading, spacing: 0) {
            Text("Move to").font(Face.display(24)).foregroundStyle(y.ink).padding(.bottom, 4)
            Text(inlinePlain(model.index.nodes[nodeId]?.title ?? "")).font(Face.text(13)).foregroundStyle(y.muted)

            if targets.count > 8 {
                TextField("Search lists", text: $search).font(Face.text(14)).foregroundStyle(y.ink)
                    .autocorrectionDisabled()
                    .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
                    .padding(.top, 14)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if shown.isEmpty {
                        Text("Nowhere else to put it yet").font(Face.text(13)).foregroundStyle(y.dim).padding(.vertical, 24)
                    }
                    // Local first, then the linked ones, which is the order they are listed in
                    // everywhere else in the app.
                    ForEach(model.allStores.map(\.id).filter { byWorkspace[$0] != nil }, id: \.self) { workspace in
                        if model.allStores.count > 1 {
                            HStack(spacing: 7) {
                                Circle().fill(LabelPalette.swatchColor(model.workspaceColorName(workspace), dark: y.dark) ?? y.dim)
                                    .frame(width: 8, height: 8)
                                SectionLabel(text: model.workspaceName(workspace))
                            }.padding(.top, 18).padding(.bottom, 6)
                        }
                        ForEach(byWorkspace[workspace] ?? [], id: \.list.id) { target in
                            Button {
                                model.move(nodeId: nodeId, toList: target.list.id)
                                dismiss()
                            } label: {
                                HStack(spacing: 10) {
                                    ListGlyph(icon: target.list.icon, color: target.list.color, smart: false)
                                    Text(inlinePlain(target.list.title ?? "").isEmpty ? "Untitled" : inlinePlain(target.list.title ?? ""))
                                        .font(Face.text(15)).foregroundStyle(y.ink).lineLimit(1)
                                    Spacer(minLength: 0)
                                    YantraIcon(mark: .forward, size: YantraIcons.small, tint: y.dim)
                                }
                                .padding(.vertical, 11).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }
            // Said plainly, because a move between repositories is the one that has a consequence
            // beyond this device: the task leaves one repository and appears in another, and anyone
            // sharing the first will find it gone.
            if model.allStores.count > 1 {
                Text("Moving to another workspace moves the task, and everything filed under it, into that repository.")
                    .font(Face.text(11.5)).foregroundStyle(y.dim).padding(.top, 6)
            }
        }
        .padding(22).padding(.top, 14)
        .background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
    }
}
