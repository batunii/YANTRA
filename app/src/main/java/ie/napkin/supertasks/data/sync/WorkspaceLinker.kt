package ie.napkin.supertasks.data.sync

import ie.napkin.supertasks.data.workspace.WorkspaceStore
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File

/** How linking went, in terms the setup screen can say out loud. */
sealed interface LinkResult {
    /** [adopted] means the branch was already there and we joined it rather than starting it. */
    data class Ok(val ref: RepoRef, val login: String, val adopted: Boolean) : LinkResult
    data class Refused(val reason: String) : LinkResult
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
     * **Refuses when the remote branch already has tasks on it**, and that refusal is the whole
     * reason this is not just [link]. Two populated task histories with no common ancestor are two
     * real sets of work; rebasing one onto the other would interleave them into something nobody
     * wrote, and picking a winner would silently delete the loser. Adding it as a second workspace
     * keeps both, which is what the user would have asked for if anyone had asked them.
     */
    fun attach(store: WorkspaceStore, urlOrSlug: String, token: String): LinkResult {
        val (ref, login) = validate(urlOrSlug, token) ?: return refusal(urlOrSlug, token)
        val creds = provider(login, token)
        val repo = GitRepo(store.root, branch)

        return try {
            val git = if (repo.exists) repo.open() else repo.init()
            git.use { g ->
                if (g.repository.config.getSubsections("remote").isEmpty()) {
                    repo.addRemote(g, ref.httpsUrl)
                }
                if (fetchOne(g, creds) != null) {
                    return LinkResult.Refused(
                        "${ref.slug} already has Yantra tasks on it. Add it as a separate workspace " +
                            "so both sets are kept."
                    )
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

    /** Someone has been here: take the branch as it stands. */
    private fun adopt(git: Git) {
        val local = git.repository.resolve("refs/heads/$branch")
        if (local == null) {
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
