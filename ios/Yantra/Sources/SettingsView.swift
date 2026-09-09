import SwiftUI
import UIKit
import YantraCore

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var theme: ThemeController
    @Environment(\.y) private var y
    @Binding var path: NavigationPath
    @State private var archiveNote: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack { NavCircle(icon: "chevron.left") { path.removeLast() }; Spacer() }
                Text("Settings").font(Face.display(32)).tracking(-0.6).foregroundStyle(y.ink).padding(.top, 18).padding(.bottom, 26)

                SectionLabel(text: "Theme").padding(.bottom, 10)
                HStack(spacing: 8) { ForEach(ThemeMode.allCases, id: \.self) { m in SelectChip(label: m.rawValue, selected: theme.mode == m, stretch: true) { theme.modeRaw = m.rawValue } } }

                SectionLabel(text: "GitHub").padding(.top, 28).padding(.bottom, 10)
                Button { path.append(Route.github) } label: {
                    row(title: SyncSettings.login ?? "Not signed in",
                        subtitle: SyncSettings.login != nil ? "Signed in — tap to manage" : "Sync across devices, and share a list with other people", chevron: true)
                }.buttonStyle(.plain)

                SectionLabel(text: "Workspaces").padding(.top, 28)
                Text("Each one is a repository. Today spans all of them.").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 10)
                row(title: model.store.readManifest()?.name ?? "Workspace",
                    subtitle: model.store.isReadOnly ? "Read-only here — update Yantra to edit" : (SyncSettings.repo?.slug ?? "On this device only"), chevron: false)
                SectionLabel(text: "Sync").padding(.top, 28)
                Text(SyncSettings.lastStatus ?? "Every change is saved to a file and committed on its own").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 10)
                YantraButton(label: model.syncing ? "Syncing…" : "Sync now", tone: .quiet, enabled: SyncSettings.repo != nil && !model.syncing) { model.syncInBackground("asked to sync") }
                row(title: "Add a workspace", subtitle: "Join a repository, or start a shared one", chevron: true)

                SectionLabel(text: "Archive").padding(.top, 28)
                Text("Finished tasks leave your lists after a while. They stay in the repository and can be brought back — this is about keeping lists short, not deleting anything.")
                    .font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 10)
                let days = model.store.readManifest()?.archiveAfterDays ?? 0
                HStack(spacing: 8) {
                    ForEach([(0, "Never"), (30, "30 days"), (90, "90 days"), (365, "A year")], id: \.0) { d, label in
                        SelectChip(label: label, selected: days == d, stretch: true) { model.setArchiveAfterDays(d) }
                    }
                }
                if days > 0 {
                    YantraButton(label: archiveNote ?? "Archive finished tasks now", tone: .quiet) {
                        let n = model.sweepArchive(); archiveNote = n == 0 ? "Nothing was old enough yet" : "\(n) moved out of your lists"
                    }.padding(.top, 8)
                }
                let archived = model.writer.archivedCount()
                if archived > 0 {
                    Button { path.append(Route.archive) } label: { row(title: "\(archived) archived", subtitle: "See what left, and put any of it back", chevron: true) }.buttonStyle(.plain).padding(.top, 8)
                }

                SectionLabel(text: "Accent").padding(.top, 28)
                Text("The ink that means your effort").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 12)
                HStack(spacing: 14) {
                    ForEach(Accent.allCases, id: \.self) { a in
                        Button {
                            theme.accentRaw = a.rawValue
                            // The launcher icon follows the accent, as Android's activity-alias trick does.
                            UIApplication.shared.setAlternateIconName(a == .coral ? nil : "AppIcon-\(a.rawValue)")
                        } label: {
                            Circle().fill(a.ink(dark: y.dark)).frame(width: 44, height: 44)
                                .overlay(Circle().stroke(theme.accent == a ? y.ink : .clear, lineWidth: 2).padding(-4))
                        }.buttonStyle(.plain)
                    }
                }

                SectionLabel(text: "Ink").padding(.top, 28)
                Text("Each colour means one thing, so a glance is enough").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 10)
                legend("Structure", "frames, tracks, text", y.secondary)
                legend("Your effort", "focus sessions, what you finished", y.accent)
                legend("High priority", "the world asking", y.crimson)
                legend("Medium priority", "the world, quieter", y.amber)

                SectionLabel(text: "The task glyph").padding(.top, 28)
                Text("Tap to complete · swipe a task right to mark what you are on").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 12)
                HStack(spacing: 28) {
                    GlyphSample(label: "Open", initial: .open); GlyphSample(label: "On it", initial: .inProgress); GlyphSample(label: "Done", initial: .done)
                }

                SectionLabel(text: "Conformance").padding(.top, 28).padding(.bottom, 10)
                Button { path.append(Route.conformance) } label: { row(title: "Reading what Android wrote", subtitle: "The bytes both apps agree on", chevron: true) }.buttonStyle(.plain)
                Spacer().frame(height: 40)
            }
            .padding(.horizontal, Layout.pageMargin).padding(.top, 8)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    private func row(title: String, subtitle: String, chevron: Bool) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(Face.text(15.5, .semibold)).foregroundStyle(y.ink)
                Text(subtitle).font(Face.text(12.5)).foregroundStyle(y.muted)
            }
            Spacer()
            if chevron { Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(y.dim) }
        }
        .padding(16).background(RoundedRectangle(cornerRadius: Layout.cardRadius).fill(y.cardBg)).padding(.bottom, 8)
    }

    private func legend(_ name: String, _ meaning: String, _ color: Color) -> some View {
        HStack(spacing: 12) {
            Circle().fill(color).frame(width: 12, height: 12)
            Text(name).font(Face.text(14, .semibold)).foregroundStyle(y.ink)
            Text(meaning).font(Face.text(12.5)).foregroundStyle(y.muted)
            Spacer()
        }.padding(.vertical, 6)
    }
}

struct GlyphSample: View {
    let label: String
    @State var initial: TaskGlyphState
    @Environment(\.y) private var y
    var body: some View {
        VStack(spacing: 8) {
            YantraCheckbox(state: initial, size: 26) { initial = initial == .done ? .open : .done }
            Text(label).font(Face.text(12)).foregroundStyle(y.muted)
        }
    }
}

/// The seven-day review and the breakdown by task — `StatsScreen`, first cut.
struct StatsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Binding var path: NavigationPath

    var body: some View {
        let counted = model.sessions.filter(\.counts)
        let today = LocalDate.today()
        let week = counted.filter { LocalDate.of(Date(timeIntervalSince1970: TimeInterval($0.startedAt) / 1000)) >= today.adding(days: -6) }
        let days = Set(week.map { LocalDate.of(Date(timeIntervalSince1970: TimeInterval($0.startedAt) / 1000)) }).count
        let todayCount = counted.filter { LocalDate.of(Date(timeIntervalSince1970: TimeInterval($0.startedAt) / 1000)) == today }.count
        let weekSecs = week.reduce(0) { $0 + ($1.actualSecs ?? 0) }
        let byTask = Dictionary(grouping: week, by: \.nodeId).map { (id: $0.key, secs: $0.value.reduce(0) { $0 + ($1.actualSecs ?? 0) }, n: $0.value.count) }.sorted { $0.secs > $1.secs }.prefix(8)

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack { NavCircle(icon: "chevron.left") { path.removeLast() }; Spacer() }
                Text("Focus stats").font(Face.display(32)).tracking(-0.6).foregroundStyle(y.ink).padding(.top, 18)
                FocusGlyph(dayCounts: days == 0 ? [] : Array(repeating: 0, count: days - 1) + [todayCount], progress: model.timer.state.map(\.progress))
                    .frame(width: 208, height: 208).frame(maxWidth: .infinity).padding(.top, 18)
                Text(days == 0 ? "No focus in the last 7 days" : "Focused on \(days) of the last 7 days").font(Face.text(13)).foregroundStyle(y.muted).frame(maxWidth: .infinity).padding(.top, 8)
                HStack(spacing: 6) {
                    cell("Today", "\(todayCount)", "sessions", accent: true)
                    cell("Rhythm", "\(days)/7", "days")
                    cell("Week", "\(week.count)", durationLabel(weekSecs))
                    cell("Per day", days == 0 ? "—" : String(format: "%.1f", Double(week.count) / Double(days)), "a day on")
                }.padding(.top, 24)
                SectionLabel(text: "Breakdown").padding(.top, 28).padding(.bottom, 8)
                if byTask.isEmpty { Text("No focus in the last 7 days.").font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 18) }
                ForEach(Array(byTask), id: \.id) { row in
                    Button { path.append(Route.focus(row.id)) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(inlinePlain(model.index.nodes[row.id]?.title ?? "").isEmpty ? "Untitled task" : inlinePlain(model.index.nodes[row.id]?.title ?? "")).font(Face.text(14, .semibold)).foregroundStyle(y.ink).lineLimit(1)
                                Text("\(durationLabel(row.secs)) · \(row.n) session\(row.n == 1 ? "" : "s")").font(Face.text(11.5)).foregroundStyle(y.dim)
                            }
                            Spacer()
                            Image(systemName: "play.fill").font(.system(size: 15)).foregroundStyle(y.accent).frame(width: 40, height: 40)
                        }.padding(.vertical, 6)
                    }.buttonStyle(.plain)
                }
                Spacer().frame(height: 40)
            }.padding(.horizontal, Layout.pageMargin).padding(.top, 8)
        }
        .background(y.page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    private func cell(_ label: String, _ value: String, _ sub: String, accent: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionLabel(text: label, color: accent ? y.accentText : nil)
            Text(value).font(Face.display(25)).tracking(-0.5).foregroundStyle(accent ? y.accent : y.ink)
            Text(sub).font(Face.text(10.5)).foregroundStyle(y.dim)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
