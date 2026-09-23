import SwiftUI
import YantraCore

/// Who a task is for.
///
/// **Anybody who can push can assign.** An assignee is a word on a line in a file — `@login` — not a
/// permission, so joining a shared repository you can push to is all it takes to give work to
/// somebody, including to the person who started it. The list below exists to offer the right names,
/// never to decide who may do the giving: a login typed by hand is accepted exactly as one picked.
///
/// The names come from three places and none of them has to work: the signed-in account, whoever is
/// already on a task in this workspace, and the repository's collaborators from GitHub. See
/// `People` for why that order matters on a train.
struct AssigneeSheet: View {
    let node: Node
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.dismiss) private var dismiss
    @StateObject private var people = People.shared
    @State private var typed = ""
    @State private var inviting = false

    private var candidates: [Person] {
        people.candidates(workspaceId: node.workspaceId, index: model.index)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Who is it for").font(Face.display(22)).foregroundStyle(y.ink)
            Text(model.allStores.count > 1
                 ? "In \(model.workspaceName(node.workspaceId)) · anyone who can push to it can be given a task"
                 : "Anyone who can push to this repository")
                .font(Face.text(12.5)).foregroundStyle(y.muted).padding(.top, 3)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(candidates) { person in
                        Button { assign(person.login) } label: {
                            HStack(spacing: 10) {
                                YantraIcon(mark: .person, size: YantraIcons.small, tint: y.secondary)
                                Text(person.login).font(Face.text(15)).foregroundStyle(y.ink)
                                if person.isYou { Text("you").font(Face.text(11)).foregroundStyle(y.dim) }
                                Spacer(minLength: 6)
                                // Only ever said when GitHub has actually answered — see
                                // `Person.onRepo`. A warning on every name of a repository whose
                                // roster has not arrived is a warning nobody reads.
                                if person.onRepo == false {
                                    Text("cannot push").font(Face.text(11)).foregroundStyle(y.warning)
                                }
                                if node.assignee?.caseInsensitiveCompare(person.login) == .orderedSame {
                                    YantraIcon(mark: .check, size: YantraIcons.small, tint: y.accent)
                                }
                            }
                            .padding(.vertical, 11).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
            }.frame(maxHeight: 220)

            SectionLabel(text: "Or type a login").padding(.top, 8)
            HStack(spacing: 8) {
                TextField("octocat", text: $typed).font(Face.mono(14)).foregroundStyle(y.ink)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                    .submitLabel(.done).onSubmit { assign(typed) }
                    .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
                YantraButton(label: "Give", tone: .soft, enabled: !typed.trimmingCharacters(in: .whitespaces).isEmpty) {
                    assign(typed)
                }.frame(width: 96)
            }.padding(.top, 6)

            if let note = people.note {
                Text(note).font(Face.text(11.5)).foregroundStyle(y.muted).padding(.top, 10)
            }
            HStack(spacing: 14) {
                Button(people.fetching ? "Asking GitHub…" : "Get the list from GitHub") {
                    Task { await people.refresh(workspaceId: node.workspaceId) }
                }
                .font(Face.text(12.5, .bold)).foregroundStyle(y.accentText).disabled(people.fetching)
                // The answer to "they are not in the list": put them on the repository.
                Button("Add someone") { inviting = true }
                    .font(Face.text(12.5, .bold)).foregroundStyle(y.accentText)
                if node.assignee != nil {
                    Button("Take it off") { assign(nil) }
                        .font(Face.text(12.5, .bold)).foregroundStyle(y.secondary)
                }
            }.padding(.top, 12)
            Spacer(minLength: 0)
        }
        .padding(22).padding(.top, 14)
        .background(y.cardBg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .sheet(isPresented: $inviting) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("People").font(Face.display(22)).foregroundStyle(y.ink).padding(.bottom, 10)
                    PeoplePanel(workspaceId: node.workspaceId)
                }.padding(22).padding(.top, 14)
            }
            .background(y.cardBg.ignoresSafeArea())
            .presentationDetents([.medium, .large])
        }
    }

    private func assign(_ login: String?) {
        let clean = login?.trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "@"))
        model.write { try model.writerFor(node.id).setAssignee(node.id, clean?.isEmpty == false ? clean : nil) }
        dismiss()
    }
}

/// Who can push to a workspace's repository, and the way to add somebody.
///
/// **Inviting happens here when the sign-in can do it, and on GitHub when it cannot.** The `repo`
/// scope carries admin on your own repositories, so the OAuth sign-in can add a collaborator
/// outright; a GitHub App's permissions are fixed at registration and never include it, so that
/// sign-in gets the link to GitHub's own access page instead. Which one is on offer is decided by
/// asking GitHub, not by guessing — a field that produces "you are not allowed" is worse than no
/// field, because the decision to invite somebody has already been made by the time it is pressed.
struct PeoplePanel: View {
    let workspaceId: String
    @EnvironmentObject var model: AppModel
    @Environment(\.y) private var y
    @Environment(\.openURL) private var openURL
    @StateObject private var people = People.shared
    @State private var typed = ""
    @State private var said: String?

    var body: some View {
        let roster = people.candidates(workspaceId: workspaceId, index: model.index)
        let mayInvite = people.canInvite[workspaceId] ?? false

        VStack(alignment: .leading, spacing: 0) {
            ForEach(roster) { person in
                HStack(spacing: 10) {
                    YantraIcon(mark: .person, size: YantraIcons.small, tint: y.secondary)
                    Text(person.login).font(Face.text(14.5)).foregroundStyle(y.ink)
                    if person.isYou { Text("you").font(Face.text(11)).foregroundStyle(y.dim) }
                    Spacer()
                }.padding(.vertical, 8)
            }
            if roster.count <= 1 {
                Text(people.note ?? "Only you so far.").font(Face.text(12.5)).foregroundStyle(y.muted)
                    .padding(.vertical, 6)
            }

            if mayInvite {
                SectionLabel(text: "Add someone").padding(.top, 14)
                Text("They get push access — enough to read and write tasks, and nothing else.")
                    .font(Face.text(12)).foregroundStyle(y.dim).padding(.bottom, 6)
                HStack(spacing: 8) {
                    TextField("their GitHub login", text: $typed).font(Face.mono(14)).foregroundStyle(y.ink)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                        .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(y.surfaceHigh))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(y.tileBorder, lineWidth: 1))
                    YantraButton(label: people.fetching ? "…" : "Invite", tone: .soft,
                                 enabled: !typed.trimmingCharacters(in: .whitespaces).isEmpty && !people.fetching) {
                        Task {
                            said = await people.invite(typed, workspaceId: workspaceId)
                            typed = ""
                        }
                    }.frame(width: 96)
                }
            } else if let repo = SyncSettings.repo(for: workspaceId) {
                Button { openURL(GitHubAuth.accessSettingsURL(repo.slug)) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Add someone on GitHub").font(Face.text(14, .semibold)).foregroundStyle(y.ink)
                            Text("This sign-in cannot add people — a GitHub App is never allowed to")
                                .font(Face.text(11.5)).foregroundStyle(y.muted)
                        }
                        Spacer()
                        YantraIcon(mark: .openOut, size: YantraIcons.small, tint: y.dim)
                    }.padding(.vertical, 10)
                }.buttonStyle(.plain)
            }

            if let said { Note(text: said).padding(.top, 10) }

            Button(people.fetching ? "Asking GitHub…" : "Refresh the list") {
                Task { await people.refresh(workspaceId: workspaceId) }
            }
            .font(Face.text(12.5, .bold)).foregroundStyle(y.accentText).disabled(people.fetching)
            .padding(.top, 12)
        }
        .task { await people.checkCanInvite(workspaceId: workspaceId) }
    }
}
