package ie.napkin.supertasks.data.sync

import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File

/** What one pass of the loop did, so the UI can say something true about it. */
data class SyncResult(
    val committed: Boolean = false,
    val pushed: Boolean = false,
    val pulled: Boolean = false,
    val conflicts: List<ConflictResolver.Resolution> = emptyList(),
    val problems: List<String> = emptyList(),
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * Fetch, rebase, resolve, push, reindex — GIT_WORKSPACES_PLAN.md §4.
 *
 * Two things this deliberately does not do. It never merges: local commits are *rebased* onto the
 * remote, so only work that has never left this device is ever rewritten, which makes the history
 * other clones already saw immovable by construction. And it never resolves anything git could have
 * resolved itself — [ConflictResolver] sees only files git gave up on.
 *
 * Failure is expected rather than exceptional. Offline is not an error, it is git's normal state:
 * commits pile up locally and the next pass sends them. What the caller gets back is a description
 * of what happened, not an exception, because "could not reach the remote" is not a reason to stop
 * the user typing.
 */
class SyncEngine(
    private val store: WorkspaceStore,
    private val indexer: Indexer,
    private val repo: GitRepo,
    private val device: String,
    private val credentials: CredentialsProvider? = null,
) {
    /**
     * One pass at a time per workspace.
     *
     * A rebase leaves the working tree half-applied while it runs. A second pass starting in the
     * middle of that would read files that belong to neither side, index them, and commit the
     * result as though someone had meant it.
     */
    private val mutex = Mutex()

    companion object {
        /** A push racing another device is ordinary; three rounds of losing it is a problem. */
        const val MAX_ATTEMPTS = 3
    }

    /**
     * **On the IO dispatcher, always.**
     *
     * Git and the network are blocking work, and putting this here rather than at each call site is
     * the difference between a rule and a hope. Every caller until now happened to arrive on a
     * background scope, so nothing complained; the first one that did not — pull-to-sync, which runs
     * on the composable's scope, and therefore on the main thread — got a
     * `NetworkOnMainThreadException` in place of a sync.
     */
    suspend fun sync(commitMessage: String = "sync"): SyncResult =
        withContext(Dispatchers.IO) { syncNow(commitMessage) }

    private suspend fun syncNow(commitMessage: String): SyncResult = mutex.withLock {
        if (!repo.exists) return SyncResult(error = "workspace is not a git repository")

        // Before anything else: a lock from a write that never finished would fail every step below,
        // and would go on failing forever.
        val unlocked = repo.clearStaleLock()

        val resolutions = ArrayList<ConflictResolver.Resolution>()
        var committed = false
        var pulled = false

        try {
            repo.open().use { git ->
                committed = repo.commitAll(git, commitMessage, "Yantra", "yantra@napkin.ie")

                if (!hasRemote(git)) {
                    // A workspace with nowhere to push is still a workspace; committing locally is
                    // the whole of sync for it, and saying "no remote" as an error would be wrong.
                    return SyncResult(committed = committed, problems = reindex() + listOfNotNull(unlocked))
                }

                var attempt = 0
                while (true) {
                    attempt++
                    repo.fetch(git, credentials)
                    val before = git.repository.resolve("HEAD")

                    val rebase = git.rebase().setUpstream("origin/${branchOf(git)}").call()
                    if (!rebase.status.isSuccessful) {
                        resolutions += resolveAll(git)
                        val stillStuck = repo.conflicts(git).isNotEmpty()
                        if (stillStuck) {
                            repo.abortRebase(git)
                            return SyncResult(
                                committed = committed,
                                conflicts = resolutions,
                                error = "could not resolve a conflict; local work is untouched",
                            )
                        }
                    }
                    pulled = before != git.repository.resolve("HEAD")

                    val pushed = runCatching { repo.push(git, credentials); true }.getOrElse { false }
                    if (pushed || !repo.hasUnpushed(git)) {
                        return SyncResult(committed, true, pulled, resolutions, reindex() + listOfNotNull(unlocked))
                    }
                    // Someone else pushed between our fetch and our push. Go round again.
                    if (attempt >= MAX_ATTEMPTS) {
                        return SyncResult(
                            committed = committed, pulled = pulled, conflicts = resolutions,
                            problems = reindex(),
                            error = "kept losing a race with another device; will try again later",
                        )
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") SyncResult(committed = committed)
        } catch (e: Exception) {
            // Offline, auth revoked, a protected branch: all of these leave local work intact and
            // are worth reporting rather than throwing, because none of them should stop editing.
            SyncResult(committed = committed, pulled = pulled, conflicts = resolutions, error = e.message ?: e.toString())
        }
    }

    private fun hasRemote(git: Git): Boolean =
        git.repository.config.getSubsections("remote").contains("origin")

    private fun branchOf(git: Git): String = git.repository.branch

    private suspend fun reindex(): List<String> = indexer.rebuild(store)

    /**
     * Decides every conflicted file and stages the answer, then continues the rebase.
     *
     * Resolution is per file rather than per hunk on purpose: a page whose lines genuinely disagree
     * has no honest half-way point, and stitching two versions of a task list together would
     * produce a page neither person wrote.
     */
    private fun resolveAll(git: Git): List<ConflictResolver.Resolution> {
        val out = ArrayList<ConflictResolver.Resolution>()
        var guard = 0
        while (repo.conflicts(git).isNotEmpty() && guard++ < 64) {
            repo.conflicts(git).forEach { path ->
                val sides = repo.conflictSides(git.repository, path)
                val decision =
                    ConflictResolver.resolve(path, sides.local, sides.remote, device, base = sides.base)
                out += decision
                val file = File(git.repository.workTree, path)
                if (decision.bytes == null) {
                    file.delete()
                    git.rm().addFilepattern(path).call()
                } else {
                    file.parentFile?.mkdirs()
                    file.writeBytes(decision.bytes)
                    git.add().addFilepattern(path).call()
                }
            }
            val res = git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE).call()
            if (res.status.isSuccessful) break
            // A rebase replays commits one at a time, so continuing can land straight in the next
            // one's conflicts. Loop rather than assume a single round settles it.
        }
        return out
    }
}
