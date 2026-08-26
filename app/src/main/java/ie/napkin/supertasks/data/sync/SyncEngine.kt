package ie.napkin.supertasks.data.sync

import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseResult
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
    /**
     * Resolved once per pass rather than held, and suspending so it can renew first.
     *
     * It used to be a provider captured at construction. That made a refreshed — or re-entered —
     * token invisible until the app was restarted: the engine went on presenting the credential it
     * was built with, which by then was the one that had stopped working.
     */
    private val credentials: suspend () -> CredentialsProvider? = { null },
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

        private const val TAG = "YantraSync"
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

        // Before the first request, so an expiring token is renewed rather than discovered dead.
        val creds = runCatching { credentials() }.getOrNull()

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
                    repo.fetch(git, creds)
                    val before = git.repository.resolve("HEAD")

                    val rebase = git.rebase().setUpstream("origin/${branchOf(git)}").call()
                    if (!rebase.status.isSuccessful) {
                        resolutions += resolveAll(git)
                        // Still rebasing counts as stuck, not just still conflicted. A rebase left
                        // open leaves HEAD detached, and pushing from there is refused by the remote
                        // — which used to be reported as a clean sync, so the work simply stopped
                        // leaving the device with nothing on screen to say so.
                        val stillStuck = repo.conflicts(git).isNotEmpty() || repo.isRebasing(git)
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

                    val push = repo.push(git, creds)
                    if (push.accepted || !repo.hasUnpushed(git)) {
                        return SyncResult(committed, true, pulled, resolutions, reindex() + listOfNotNull(unlocked))
                    }
                    // Refused for a reason going round again cannot fix — a protected branch, a
                    // token that no longer carries write access. Retrying would spend the attempts
                    // and then report losing a race that never happened, so it is reported as what
                    // it is. This is the case that used to be indistinguishable from success.
                    if (!push.retryable) {
                        Log.w(TAG, "push refused: ${push.reason}")
                        return SyncResult(
                            committed = committed, pulled = pulled, conflicts = resolutions,
                            problems = reindex(),
                            error = "the remote refused the push: ${push.reason}",
                        )
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
            //
            // Logged as well as returned. The message on screen has to be short enough to read at a
            // glance, which makes it useless for working out *why* — and a sync that has been
            // failing for a day is exactly when the underlying cause is worth having somewhere.
            Log.w(TAG, "sync failed", e)
            SyncResult(
                committed = committed, pulled = pulled, conflicts = resolutions,
                error = readable(e.message ?: e.toString()),
            )
        }
    }

    /**
     * Git's wording, turned into something the reader can act on.
     *
     * "not authorized" is what JGit says for a token that expired, was revoked, or never had write
     * access — accurate, and no help at all to someone holding a phone. The remedy is the same in
     * every case and worth stating.
     */
    private fun readable(message: String): String =
        if (message.contains("not authorized", ignoreCase = true) ||
            message.contains("Authentication is required", ignoreCase = true)
        ) "GitHub would not accept the sign-in — sign in again in Settings"
        else message

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
        // Driven by whether a rebase is still open, not by whether anything is conflicted. Those
        // came apart in the case that mattered: resolving a page to exactly what upstream already
        // had makes the replayed commit empty, so `continue` answers NOTHING_TO_COMMIT with a clean
        // index. Looping on conflicts alone exited right there and left the rebase open forever.
        while (repo.isRebasing(git) && guard++ < 64) {
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
            when {
                res.status.isSuccessful -> break
                // Our answer matched upstream, so there is nothing left to replay for this commit.
                // Git wants it skipped; without that the rebase sits here and never finishes.
                res.status == RebaseResult.Status.NOTHING_TO_COMMIT ->
                    git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.SKIP).call()
                // A rebase replays commits one at a time, so continuing can land straight in the
                // next one's conflicts. Loop rather than assume a single round settles it.
                repo.conflicts(git).isNotEmpty() -> Unit
                // Not successful, nothing conflicted, nothing to skip: this is not a state we know
                // how to drive, and guessing at it would be worse than handing it back. The caller
                // aborts, and the local commits are untouched.
                else -> break
            }
        }
        return out
    }
}
