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

                CalendarSetting()

                SectionLabel(text: "Privacy").padding(.top, 28)
                Text("Yantra collects nothing. There is no server, no analytics and no account with us — your files are yours, and sync pushes them to a repository you own.")
                    .font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 10)
                // Reachable from inside the app, not only from the store listing: 5.1.1 asks for
                // both, and the listing is the one place somebody who already installed it will
                // never look.
                Link(destination: URL(string: AppLinks.privacyPolicy)!) {
                    row(title: "Privacy policy", subtitle: "What is stored, and where it goes", chevron: true)
                }.buttonStyle(.plain)

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
            if chevron { Image(systemName: "chevron.right").icon(14, .semibold).foregroundStyle(y.dim) }
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
                            Image(systemName: "play.fill").icon(15).foregroundStyle(y.accent).frame(width: 40, height: 40)
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

/// Whether to draw the phone's own calendars, and which of them.
///
/// Off until somebody turns it on. Reading a person's meetings is not something to start doing
/// because an app was updated, and the permission prompt is asked for by this switch rather than
/// thrown at the first launch of a screen.
struct CalendarSetting: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @StateObject private var calendars = DeviceCalendars.shared
    @State private var on = CalendarChoice.enabled
    @State private var denied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionLabel(text: "Your calendars").padding(.top, 28)
            Text("Meetings from this device, drawn beside your tasks. Yantra only ever reads them — nothing is added to or changed in your calendars.")
                .font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 2).padding(.bottom, 10)

            Toggle(isOn: Binding(get: { on }, set: { want in
                if want {
                    Task {
                        let ok = await calendars.requestAccess()
                        // A refusal is a refusal: the switch goes back rather than sitting on while
                        // nothing is drawn, which would read as the feature being broken.
                        on = ok; CalendarChoice.enabled = ok; denied = !ok
                    }
                } else {
                    on = false; CalendarChoice.enabled = false; denied = false
                }
            })) {
                Text("Show my calendars").font(Face.text(14.5)).foregroundStyle(y.ink)
            }
            .tint(y.accent)

            if denied {
                Text("Yantra was not given access. You can turn it on in Settings › Privacy › Calendars.")
                    .font(Face.text(12)).foregroundStyle(y.warning).padding(.top, 8)
            }

            if on, let available = calendars.available, !available.isEmpty {
                Text("Which ones").font(Face.text(12.5, .bold)).foregroundStyle(y.secondary).padding(.top, 14).padding(.bottom, 6)
                // No stored choice is not the same as choosing none: until somebody ticks something,
                // everything the owning app shows is what "my calendar" means.
                let chosen = CalendarChoice.chosen
                ForEach(available) { c in
                    let picked = chosen?.contains(c.id) ?? true
                    Button {
                        var next = chosen ?? Set(available.map(\.id))
                        if picked { next.remove(c.id) } else { next.insert(c.id) }
                        CalendarChoice.chosen = next
                    } label: {
                        HStack(spacing: 10) {
                            Circle().fill(deviceColor(c.color, y: y)).frame(width: 10, height: 10)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(c.title).font(Face.text(14)).foregroundStyle(y.ink)
                                if !c.account.isEmpty {
                                    Text(c.account).font(Face.text(11.5)).foregroundStyle(y.dim)
                                }
                            }
                            Spacer()
                            Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                                .icon(17).foregroundStyle(picked ? y.accent : y.dim)
                        }
                        .padding(.vertical, 9)
                    }.buttonStyle(.plain)
                }
            }
        }
        .task {
            calendars.refreshAuthorization()
            if CalendarChoice.enabled { calendars.loadCalendars() }
            // A permission revoked in Settings has to be noticed here, or the switch would claim
            // something the app can no longer do.
            if CalendarChoice.enabled, !calendars.authorized { on = false; CalendarChoice.enabled = false }
        }
    }
}

/// The addresses the app points at. One place, so the store listing and the app cannot drift.
enum AppLinks {
    /// Must stay reachable: App Store Connect requires the same URL, and a policy that 404s is a
    /// rejection under 5.1.1 whatever the app does.
    static let privacyPolicy = "https://github.com/batunii/YANTRA/blob/main/docs/PRIVACY.md"
}
