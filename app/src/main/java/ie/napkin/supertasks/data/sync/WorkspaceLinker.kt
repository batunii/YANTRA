package ie.napkin.supertasks.data.sync

import ie.napkin.supertasks.data.workspace.WorkspaceStore
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File

/** How linking went, in terms the setup screen can say out loud. */
sealed interface LinkResult {
    /** [adopted] means the branch was already there and we joined it rather than starting it. */
    data class Ok(val ref: RepoRef, val login: String, val adopted: Boolean) : LinkResult
    data class Refused(val reason: String) : LinkResult

    /**
     * The repository already holds tasks that did not come from here.
     *
     * Not a refusal — a fork in the road, and one only the person can take. This used to be a
     * [Refused] whose text told you to add the repository as a second workspace, which is sound
     * advice for someone else's project and precisely wrong for your own repository after a
     * reinstall: it is *your* task list, and being handed a duplicate workspace instead of it is
     * not what anyone meant. Reporting the situation rather than a verdict lets the caller offer
     * both readings and lets the person say which one is true.
     */
    data class HasTasks(val ref: RepoRef, val login: String) : LinkResult
}

/**
 * Points a workspace at a GitHub repository — GIT_WORKSPACES_PLAN.md §4.
 *
 * The important thing this does *not* do is clone the repository. Tasks live on their own orphan
 * branch, so linking fetches that one ref and nothing else: pointing a workspace at a ten-year-old
 * codebase downloads a few kilobytes of task files, not the code. It also means the code and the
 * tasks never meet — no task churn in anybody's diffs, no branch-filtered CI firing on a checkbox,
 * and nothing to refuse when the repo already has content in it.
 *
 * Two entry points, and the difference between them is which side already has the tasks. [link] is
 * for a workspace that does not exist yet — a project you are joining, or a repo you are starting.
 * [attach] is for one that does: the tasks you have been keeping on this phone, given somewhere to
 * live. Running the wrong one over the other is how a task list gets destroyed, so they are separate
 * functions rather than one function being clever.
 */
class WorkspaceLinker(
    private val api: GitHubApi = GitHubApi(),
    private val branch: String = "yantra-tasks",
) {

    /**
     * Links a *new* workspace directory to a repository.
     *
     * [scaffold] is called only when this device is the first to arrive, so seeding cannot run on a
     * machine that is joining an existing workspace — the mistake that would otherwise give every
     * new device its own second Inbox.
     */
    fun link(
        dir: File,
        workspaceId: String,
        name: String,
        urlOrSlug: String,
        token: String,
        scaffold: (WorkspaceStore) -> Unit = {},
    ): LinkResult {
        val (ref, login) = validate(urlOrSlug, token) ?: return refusal(urlOrSlug, token)
        val creds = provider(login, token)
        val repo = GitRepo(dir, branch)

        return try {
            dir.mkdirs()
            val git = if (repo.exists) repo.open() else repo.init()
            git.use { g ->
                if (g.repository.config.getSubsections("remote").isEmpty()) {
                    repo.addRemote(g, ref.httpsUrl)
                }
                if (fetchOne(g, creds) != null) {
                    adopt(g)
                    LinkResult.Ok(ref, login, adopted = true)
                } else {
                    start(g, repo, creds, workspaceId, name, dir, scaffold)
                    LinkResult.Ok(ref, login, adopted = false)
                }
            }
        } catch (e: Exception) {
            LinkResult.Refused(e.message ?: "could not reach ${ref.slug}")
        }
    }

    /**
     * Gives a workspace that already has tasks in it a remote to push to.
     *
     * This is the end of signing in: the user has been keeping tasks on one phone with no backup,
     * and now there is a private repository for them. The history is already here, so there is
     * nothing to adopt and nothing to scaffold — the remote is added and the existing commits are
     * pushed as they stand.
     *
     * **Stops when the remote branch already has tasks on it** and reports [LinkResult.HasTasks]
     * rather than deciding. Two populated task histories with no common ancestor may be two real
     * sets of work — rebasing one onto the other would interleave them into something nobody wrote,
     * and picking a winner silently would delete the loser. But they may equally be the same set of
     * work seen twice: your own repository, and a phone that has been reinstalled since it last saw
     * it. Those two look identical from here and are opposite in what they want, so this asks
     * instead of guessing, and [adopt] is the answer coming back.
     *
     * Pass [adopt] to take the remote as it stands. It is a hard reset onto `origin/$branch`: what
     * is on this device goes. That is the right and only meaning of "use the repository's tasks",
     * and it is why nothing here reaches that line without having been told to.
     */
    fun attach(
        store: WorkspaceStore,
        urlOrSlug: String,
        token: String,
        adopt: Boolean = false,
    ): LinkResult {
        val (ref, login) = validate(urlOrSlug, token) ?: return refusal(urlOrSlug, token)
        val creds = provider(login, token)
        val repo = GitRepo(store.root, branch)

        return try {
            val git = if (repo.exists) repo.open() else repo.init()
            git.use { g ->
                if (g.repository.config.getSubsections("remote").isEmpty()) {
                    repo.addRemote(g, ref.httpsUrl)
                }
                val remote = fetchOne(g, creds)
                if (remote != null) {
                    val local = g.repository.resolve("refs/heads/$branch")
                    // Tasks on the remote are only a problem if they are *someone else's*. Running
                    // this twice on the same workspace — a second tap, a screen that resumed twice —
                    // must not be an error, and telling the user to add their own repository as a
                    // separate workspace would split their tasks in half for no reason.
                    if (local == null || !related(g, local, remote)) {
                        if (!adopt) return LinkResult.HasTasks(ref, login)
                        // Told to take the remote: the same checkout-and-reset that joining an
                        // existing repository has always done, reached from the other door.
                        adopt(g)
                        return LinkResult.Ok(ref, login, adopted = true)
                    }
                }
                repo.commitAll(g, "Start a Yantra workspace", "Yantra", "yantra@napkin.ie")
                repo.push(g, creds)
                LinkResult.Ok(ref, login, adopted = false)
            }
        } catch (e: Exception) {
            LinkResult.Refused(e.message ?: "could not reach ${ref.slug}")
        }
    }

    /** Both API questions, asked before git is touched at all. */
    private fun validate(urlOrSlug: String, token: String): Pair<RepoRef, String>? {
        val ref = RepoRef.parse(urlOrSlug) ?: return null
        val login = api.viewer(token) ?: return null
        val check = api.check(ref, token)
        return if (check is RepoCheck.Ok && check.canPush) ref to login else null
    }

    /**
     * Says *which* of the checks failed.
     *
     * Split out from [validate] so the happy path stays one expression, and re-asks rather than
     * threading the reason through a nullable — this only runs when something already went wrong, so
     * a second round trip costs nothing anybody will notice.
     */
    private fun refusal(urlOrSlug: String, token: String): LinkResult.Refused {
        val ref = RepoRef.parse(urlOrSlug)
            ?: return LinkResult.Refused("That does not look like a GitHub repository")
        api.viewer(token) ?: return LinkResult.Refused("That token was rejected by GitHub")
        return LinkResult.Refused(
            when (val check = api.check(ref, token)) {
                // Finding this out now is worth a great deal: the alternative is discovering it
                // after a week of local commits that can never leave the device.
                is RepoCheck.Ok -> "You have read access to ${ref.slug} but cannot push to it"
                RepoCheck.NotFound -> "${ref.slug} does not exist, or this token cannot see it"
                RepoCheck.Unauthorized -> "That token cannot read ${ref.slug}"
                is RepoCheck.Failed -> check.message
            }
        )
    }

    private fun provider(login: String, token: String): CredentialsProvider =
        org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(login, token)

    /**
     * Fetches the task branch and nothing else, returning null when it is not there.
     *
     * The default refspec would drag down the entire history of the repository to reach a branch
     * that shares none of it. A missing branch is the normal case for a repo nobody has used for
     * tasks yet, and JGit reports it by throwing, so the throw is an answer rather than an error.
     */
    private fun fetchOne(git: Git, creds: CredentialsProvider): org.eclipse.jgit.lib.ObjectId? {
        runCatching {
            git.fetch().setRemote("origin")
                .setRefSpecs("+refs/heads/$branch:refs/remotes/origin/$branch")
                .setCredentialsProvider(creds)
                .call()
        }
        return git.repository.resolve("refs/remotes/origin/$branch")
    }

    /**
     * Whether two commits share any history at all.
     *
     * The question being asked is "are these the same task list, or two different ones?", and a merge
     * base answers it exactly: our own workspace pushed earlier is an ancestor of what came back,
     * while somebody else's orphan branch has no common commit with ours by construction.
     */
    private fun related(git: Git, a: ObjectId, b: ObjectId): Boolean =
        RevWalk(git.repository).use { walk ->
            walk.revFilter = RevFilter.MERGE_BASE
            walk.markStart(walk.parseCommit(a))
            walk.markStart(walk.parseCommit(b))
            walk.next() != null
        }

    /**
     * Someone has been here: take the branch as it stands.
     *
     * The no-local-branch case has two shapes and they need different handling. Reached from [link]
     * the directory was made moments ago and is empty. Reached from [attach] it is a workspace that
     * has been used offline: pages and a manifest on disk, and no branch holding any of them — so
     * checkout refuses, because writing the repository's files over them would destroy work it was
     * never told it could touch.
     *
     * Here it *has* been told. Adopting means the repository's copy replaces this device's, so the
     * working tree is emptied first rather than merged into: a leftover page from the old copy would
     * be indexed beside the ones that arrive, and a second Inbox is exactly the failure this whole
     * path exists to avoid. Only reachable behind an answered question — see [attach].
     */
    private fun adopt(git: Git) {
        val local = git.repository.resolve("refs/heads/$branch")
        if (local == null) {
            git.repository.workTree.listFiles()
                ?.filter { it.name != ".git" }
                ?.forEach { it.deleteRecursively() }
            git.checkout().setCreateBranch(true).setName(branch)
                .setStartPoint("origin/$branch").call()
        } else {
            git.checkout().setName(branch).call()
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/$branch").call()
        }
    }

    /**
     * Nobody has been here: make the branch, fill it, push it.
     *
     * The orphan checkout carries the index and working tree across, so both are cleared first —
     * otherwise a repository with code in it would have that code committed onto the task branch,
     * which is precisely what the orphan branch exists to prevent.
     *
     * It is also guarded on the directory not already being a workspace, because clearing the
     * working tree is exactly wrong when the tree *is* the tasks. That case belongs to [attach];
     * reaching it here would mean deleting someone's task list to make room for an empty one.
     */
    private fun start(
        git: Git,
        repo: GitRepo,
        creds: CredentialsProvider,
        workspaceId: String,
        name: String,
        dir: File,
        scaffold: (WorkspaceStore) -> Unit,
    ) {
        val existing = WorkspaceStore(dir, workspaceId)
        if (git.repository.resolve("HEAD") != null && !existing.exists) {
            git.checkout().setOrphan(true).setName(branch).call()
            val tracked = git.repository.readDirCache()
                .let { dc -> (0 until dc.entryCount).map { dc.getEntry(it).pathString } }
            if (tracked.isNotEmpty()) {
                git.rm().setCached(true).apply { tracked.forEach { addFilepattern(it) } }.call()
            }
            dir.listFiles()?.filter { it.name != ".git" }?.forEach { it.deleteRecursively() }
        }

        val store = WorkspaceStore(dir, workspaceId)
        val fresh = !store.exists
        if (fresh) store.scaffold(name, System.currentTimeMillis())
        // Only on a workspace this device is starting. The name reaches the manifest, which is what
        // the derived workspace label reads — every new workspace was called "Workspace" before.
        if (fresh) scaffold(store)

        repo.commitAll(git, "Start a Yantra workspace", "Yantra", "yantra@napkin.ie")
        git.push().setRemote("origin").add(branch).setCredentialsProvider(creds).call()
    }
}
