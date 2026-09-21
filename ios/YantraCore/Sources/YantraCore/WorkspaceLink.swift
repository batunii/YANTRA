import Foundation

/// Joining a repository that may already hold a workspace — `data/sync/WorkspaceLinker.kt`.
///
/// The mistake this exists to prevent, in Android's own words: *"seeding cannot run on a machine
/// that is joining an existing workspace — the mistake that would otherwise give every new device
/// its own second Inbox."*
///
/// iOS reaches the same problem by a different road. It has no local git, so there is no clone to
/// skip: `AppGroup.openWorkspace` scaffolds and seeds a starter workspace the first time the app
/// runs, before anyone has connected anything. When that device is then pointed at a repository
/// that already has tasks, the ordinary merge sees two sets of pages with different ids and no
/// common ancestor, and — correctly, by its own rules — keeps both. The result is two Inboxes, two
/// Todays, two of everything, and both Inboxes claiming `system_key: inbox` so that
/// `node(systemKey:)` picks one arbitrarily.
///
/// The merge is not wrong. The question was asked too late.
public enum WorkspaceLink {

    /// What should happen when a device connects to a repository.
    public enum Decision: Equatable, Sendable {
        /// Take the repository's workspace as it stands. What is on this device is a starter set
        /// nobody has touched, so there is nothing to lose and everything to gain.
        case adopt
        /// Nothing is up there yet: this device is the first to arrive, so its workspace becomes
        /// the repository's.
        case push
        /// **Two real workspaces.** Not a refusal — a fork in the road, and one only the person can
        /// take. They may be two genuine sets of work, in which case merging would interleave them
        /// into something nobody wrote; or they may be the same work seen twice — your own
        /// repository, and a device that has been reinstalled since it last saw it. Those look
        /// identical from here and want opposite things, so this asks rather than guessing.
        case ask
    }

    /// Whether this workspace is still the untouched starter set.
    ///
    /// The test is the `device:` stamp, and it is exact rather than a heuristic: `WorkspaceSeeder`
    /// writes every page with no device, and `WorkspaceWriter` stamps one on **every** write. So a
    /// page carrying a device is a page a person caused, and a workspace with none has never been
    /// written to on this device — whatever it contains.
    ///
    /// Focus sessions count too. Someone who has run a timer but not yet edited a page has still
    /// done something this device would be throwing away.
    public static func isPristine(_ store: WorkspaceStore) -> Bool {
        if store.readPages().contains(where: { $0.device != nil }) { return false }
        if !FocusLedger.read(store).isEmpty { return false }
        return true
    }

    /// The decision, kept pure so the interesting part is testable without a network.
    public static func decide(localIsPristine: Bool, remoteHasWorkspace: Bool) -> Decision {
        switch (localIsPristine, remoteHasWorkspace) {
        case (_, false): return .push
        case (true, true): return .adopt
        case (false, true): return .ask
        }
    }

    /// Does a set of remote paths amount to a workspace, rather than an empty or unrelated branch?
    ///
    /// The manifest is the marker: every Yantra workspace has one, and nothing else writes it.
    public static func looksLikeAWorkspace(_ paths: some Collection<String>) -> Bool {
        paths.contains(WorkspaceStore.manifestPath)
    }
}

public extension SyncEngine {

    /// Takes the repository's workspace wholesale, discarding what is here.
    ///
    /// Every remote file is written locally and every local file the remote does not have is
    /// removed, so the device ends up holding exactly the repository's workspace and a base that
    /// says so. The next ordinary sync then has a common ancestor and behaves like any other.
    ///
    /// **This deletes local files**, which is why nothing calls it without either having checked
    /// `WorkspaceLink.isPristine` or having been told to by the person.
    func adoptRemote() async throws -> SyncResult {
        var result = SyncResult()
        guard let remote = try await transport.head(branch: Self.branch) else {
            result.error = "there is no workspace up there to adopt"
            return result
        }

        var files: [String: Data] = [:]
        for (path, sha) in remote.tree {
            guard let data = try await transport.blob(sha: sha) as Data? else { continue }
            files[path] = data
            writeLocal(path, data)
        }
        // Anything here that the repository does not have is part of the starter set being replaced.
        for path in localFiles().keys where files[path] == nil {
            writeLocal(path, nil)
        }
        try saveBase(commit: remote.commit, files: files)
        result.pulled = true
        return result
    }

    /// What connecting to this repository should do, asked before anything is written.
    func linkDecision() async throws -> WorkspaceLink.Decision {
        let remote = try await transport.head(branch: Self.branch)
        let hasWorkspace = remote.map { WorkspaceLink.looksLikeAWorkspace($0.tree.keys) } ?? false
        return WorkspaceLink.decide(localIsPristine: WorkspaceLink.isPristine(store),
                                    remoteHasWorkspace: hasWorkspace)
    }
}
