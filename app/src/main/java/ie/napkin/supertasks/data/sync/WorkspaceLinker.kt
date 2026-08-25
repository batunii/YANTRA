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
 * Two paths from there. If the branch already exists someone has been here before, so we adopt it
 * and take what is there. If it does not, we make it, scaffold it, and push — which is also the
 * only order that works, since JGit will not clone a ref that does not exist yet.
 */
class WorkspaceLinker(
    private val api: GitHubApi = GitHubApi(),
    private val branch: String = "yantra-tasks",
) {

    /**
     * [scaffold] is called only when this device is the first to arrive, so seeding cannot run on a
     * machine that is joining an existing workspace — the mistake that would otherwise give every
     * new device its own second Inbox.
     */
    fun link(
        dir: File,
        workspaceId: String,
        urlOrSlug: String,
        token: String,
        scaffold: (WorkspaceStore) -> Unit,
    ): LinkResult {
        val ref = RepoRef.parse(urlOrSlug)
            ?: return LinkResult.Refused("That does not look like a GitHub repository")

        val login = api.viewer(token)
            ?: return LinkResult.Refused("That token was rejected by GitHub")

        when (val check = api.check(ref, token)) {
            is RepoCheck.Ok ->
                if (!check.canPush) {
                    // Finding this out now is worth a great deal: the alternative is discovering it
                    // after a week of local commits that can never leave the device.
                    return LinkResult.Refused("You have read access to ${ref.slug} but cannot push to it")
                }
            RepoCheck.NotFound ->
                return LinkResult.Refused("${ref.slug} does not exist, or this token cannot see it")
            RepoCheck.Unauthorized -> return LinkResult.Refused("That token cannot read ${ref.slug}")
            is RepoCheck.Failed -> return LinkResult.Refused(check.message)
        }

        val creds: CredentialsProvider =
            org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(login, token)
        val repo = GitRepo(dir, branch)

        return try {
            dir.mkdirs()
            val git = if (repo.exists) repo.open() else repo.init()
            git.use { g ->
                if (g.repository.config.getSubsections("remote").isEmpty()) {
                    repo.addRemote(g, ref.httpsUrl)
                }

                // One ref, explicitly. Fetching the default refspec would drag down the entire
                // history of the repository to reach a branch that shares none of it.
                val fetched = runCatching {
                    g.fetch().setRemote("origin")
                        .setRefSpecs("+refs/heads/$branch:refs/remotes/origin/$branch")
                        .setCredentialsProvider(creds)
                        .call()
                }.getOrNull()

                val remoteRef = g.repository.resolve("refs/remotes/origin/$branch")
                if (fetched != null && remoteRef != null) {
                    adopt(g, creds)
                    LinkResult.Ok(ref, login, adopted = true)
                } else {
                    start(g, repo, creds, workspaceId, dir, scaffold)
                    LinkResult.Ok(ref, login, adopted = false)
                }
            }
        } catch (e: Exception) {
            LinkResult.Refused(e.message ?: "could not reach ${ref.slug}")
        }
    }

    /** Someone has been here: take the branch as it stands. */
    private fun adopt(git: Git, creds: CredentialsProvider) {
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
     */
    private fun start(
        git: Git,
        repo: GitRepo,
        creds: CredentialsProvider,
        workspaceId: String,
        dir: File,
        scaffold: (WorkspaceStore) -> Unit,
    ) {
        if (git.repository.resolve("HEAD") != null) {
            git.checkout().setOrphan(true).setName(branch).call()
            val tracked = git.repository.readDirCache()
                .let { dc -> (0 until dc.entryCount).map { dc.getEntry(it).pathString } }
            if (tracked.isNotEmpty()) {
                git.rm().setCached(true).apply { tracked.forEach { addFilepattern(it) } }.call()
            }
            dir.listFiles()?.filter { it.name != ".git" }?.forEach { it.deleteRecursively() }
        }

        val store = WorkspaceStore(dir, workspaceId)
        if (!store.exists) store.scaffold("Workspace", System.currentTimeMillis())
        scaffold(store)

        repo.commitAll(git, "Start a Yantra workspace", "Yantra", "yantra@napkin.ie")
        git.push().setRemote("origin").add(branch).setCredentialsProvider(creds).call()
    }
}
