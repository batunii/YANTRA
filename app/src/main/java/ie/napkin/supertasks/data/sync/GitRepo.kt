package ie.napkin.supertasks.data.sync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import java.io.File

/**
 * The git operations a workspace needs, and nothing about tasks.
 *
 * Kept deliberately ignorant: the conflict policy lives in [ConflictResolver] and the orchestration
 * in [SyncEngine], so both can be tested against plain files with no notion of a repository. What is
 * here is only the parts that are awkward — reading both sides of a conflict out of the index, and
 * the fact that a rebase means the opposite of what you expect by "ours".
 */
open class GitRepo(private val dir: File, private val branch: String) {

    fun open(): Git = Git.open(dir)

    val exists: Boolean get() = File(dir, ".git").exists()

    /**
     * Starts a repo on an orphan branch, so tasks share no history with whatever else is in the
     * remote. Safe to call on a directory that already has files in it — they become the first
     * commit.
     */
    fun init(): Git = Git.init().setDirectory(dir).setInitialBranch(branch).call()

    /**
     * Points the repo at a remote, and **narrows what that remote means** to the task branch alone.
     *
     * JGit's `remoteAdd` writes the usual wildcard refspec, mapping every remote head into
     * `refs/remotes/origin`, which would make every later fetch drag down the entire repository —
     * all the code, all its history — to reach a branch that shares none of it. [WorkspaceLinker] is careful to fetch one refspec explicitly at
     * link time; without this, the first background sync afterwards would quietly undo that and a
     * workspace pointed at a large project would cost the user a clone over mobile data.
     */
    fun addRemote(git: Git, url: String) {
        git.remoteAdd().setName("origin").setUri(URIish(url)).call()
        git.repository.config.apply {
            setString("remote", "origin", "fetch", "+refs/heads/$branch:refs/remotes/origin/$branch")
            save()
        }
    }

    /** Everything, including deletions — `addFilepattern` alone silently ignores removed files. */
    fun commitAll(git: Git, message: String, author: String, email: String): Boolean {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        if (git.status().call().isClean) return false
        git.commit().setMessage(message).setAuthor(author, email).setCommitter(author, email).call()
        return true
    }

    fun hasUnpushed(git: Git): Boolean {
        val local = git.repository.resolve("refs/heads/$branch") ?: return false
        val remote = git.repository.resolve("refs/remotes/origin/$branch") ?: return true
        return local != remote
    }

    /**
     * Both sides of a conflicted path, straight out of the index.
     *
     * Stage 2 is "ours" and stage 3 is "theirs", and during a **rebase those mean the reverse of
     * what they mean during a merge**: the commit being rebased *onto* is ours, and the local commit
     * being replayed is theirs. Getting this backwards resolves every conflict the wrong way round
     * and looks exactly like working code, which is why the caller names them local and remote and
     * this is the only place that knows the mapping.
     */
    data class Sides(val local: ByteArray?, val remote: ByteArray?, val base: ByteArray?)

    fun conflictSides(repo: Repository, path: String): Sides {
        var base: ByteArray? = null     // stage 1 — the common ancestor, when there is one
        var remote: ByteArray? = null   // stage 2 — upstream, the commit we are replaying onto
        var local: ByteArray? = null    // stage 3 — the local commit being replayed
        val dc = repo.readDirCache()
        for (i in 0 until dc.entryCount) {
            val e = dc.getEntry(i)
            if (e.pathString != path) continue
            val bytes = runCatching { repo.open(e.objectId).bytes }.getOrNull()
            when (e.stage) {
                1 -> base = bytes
                2 -> remote = bytes
                3 -> local = bytes
            }
        }
        return Sides(local, remote, base)
    }

    fun conflicts(git: Git): Set<String> = git.status().call().conflicting

    /**
     * Whether a rebase is still open.
     *
     * Not the same question as "are there conflicts". A rebase can be mid-flight with a perfectly
     * clean index — when the commit being replayed turned out to be empty, for one — and in that
     * state HEAD is detached. Anything that pushes without asking this pushes a detached HEAD, which
     * the remote refuses.
     */
    fun isRebasing(git: Git): Boolean = git.repository.repositoryState.isRebasing

    /**
     * Clears a lock file left behind by an operation that never finished.
     *
     * Git takes `.git/index.lock` for the duration of a write and deletes it afterwards. If the
     * process dies in between — force-stopped, killed for memory, battery pulled — the file stays,
     * and every future write fails against it. Nothing in git removes it, so without this a single
     * badly-timed death breaks sync **permanently**, with no remedy inside the app and no hint on
     * screen beyond a failure the user cannot act on. That is exactly what happened here.
     *
     * Safe because all git work in this app is serialised through [SyncEngine]'s mutex in a single
     * process: a lock still present when a pass begins cannot belong to anything running. The age
     * check is belt and braces for a future caller that does not go through that mutex — [linking]
     * being the one that already does not.
     *
     * Returns a description if it removed one, so the pass can report it rather than fixing things
     * silently. A user whose sync failed for an hour deserves to see why it started working again.
     */
    fun clearStaleLock(staleAfterMs: Long = 60_000, now: Long = System.currentTimeMillis()): String? {
        val lock = File(File(dir, ".git"), "index.lock")
        if (!lock.exists()) return null
        val age = now - lock.lastModified()
        if (age < staleAfterMs) return null
        return if (lock.delete()) "cleared a stale git lock left by an interrupted write" else null
    }

    fun continueRebase(git: Git): RebaseCommand.Operation? =
        git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
            .let { if (it.status.isSuccessful) null else RebaseCommand.Operation.CONTINUE }

    /** Last resort: put the working tree back the way it was and leave the local commits alone. */
    fun abortRebase(git: Git) {
        runCatching { git.rebase().setOperation(RebaseCommand.Operation.ABORT).call() }
        runCatching { git.reset().setMode(ResetCommand.ResetType.HARD).call() }
    }

    fun fetch(git: Git, creds: CredentialsProvider?) {
        git.fetch().setRemote("origin").apply { creds?.let { setCredentialsProvider(it) } }.call()
    }

    /**
     * Pushes, and reports what the remote actually did with it.
     *
     * **JGit does not throw when a push is rejected.** `call()` succeeds and hands back a status per
     * ref, so a push refused for a protected branch, a revoked token or a stale ref looked exactly
     * like a push that worked. Sync took that silence for success: it reported a clean sync, and the
     * local commits piled up behind a remote that had never heard of them — which is the worst shape
     * this bug could take, because the one signal that something is wrong is the one that was
     * suppressed.
     *
     * A non-fast-forward is different in kind from the rest. It means someone else pushed first,
     * which is ordinary, expected, and fixed by fetching and rebasing — so it is reported as
     * retryable rather than as a failure, and the caller goes round again.
     */
    open fun push(git: Git, creds: CredentialsProvider?): PushOutcome {
        val results = git.push().setRemote("origin").add(branch)
            .apply { creds?.let { setCredentialsProvider(it) } }
            .call()

        val updates = results.flatMap { it.remoteUpdates }
        // No update at all means the remote was never told anything — not a success.
        if (updates.isEmpty()) return PushOutcome(false, retryable = false, reason = "the remote refused the connection")

        val bad = updates.filter {
            it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
        }
        if (bad.isEmpty()) return PushOutcome(accepted = true, retryable = false, reason = null)

        val retryable = bad.all { it.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD }
        val reason = bad.joinToString("; ") { u ->
            u.message?.takeIf { it.isNotBlank() } ?: describe(u.status)
        }
        return PushOutcome(accepted = false, retryable = retryable, reason = reason)
    }

    /** What each rejection means, said the way someone using the app would need to hear it. */
    private fun describe(status: RemoteRefUpdate.Status): String = when (status) {
        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> "the remote has work this does not have yet"
        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> "the remote moved while pushing"
        RemoteRefUpdate.Status.REJECTED_NODELETE -> "the remote does not allow deleting that branch"
        RemoteRefUpdate.Status.REJECTED_OTHER_REASON -> "the remote refused the push"
        RemoteRefUpdate.Status.NON_EXISTING -> "that branch does not exist on the remote"
        RemoteRefUpdate.Status.NOT_ATTEMPTED -> "the push was never attempted"
        RemoteRefUpdate.Status.AWAITING_REPORT -> "the remote never said whether it took the push"
        else -> status.name
    }

    /** What the remote did with a push: took it, wants a rebase first, or refused it outright. */
    data class PushOutcome(val accepted: Boolean, val retryable: Boolean, val reason: String?)
}
