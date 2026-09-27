package ie.shoonya.yantra.data.sync

import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.OriginScanEntity
import ie.shoonya.yantra.data.db.TaskOriginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream

/**
 * Who added each task, read out of the repository's history — for a task whose line has no `by:`.
 *
 * The rule is **first sighting**: the commit that first put a task's `^id` into any page added the
 * task, and its author is who did it. History is walked oldest first and a row, once written, is
 * never replaced, so a later commit that renames, ticks or moves the line cannot claim it.
 *
 * What it can say is limited by what the history recorded. Until commits were signed with the login
 * (see [SyncEngine]'s `author`), every commit the app made was signed "Yantra" from every device, so
 * a task first seen in one of those is recorded with no login — seen, so nothing later can claim
 * it, but unattributed, because the history genuinely does not know. The phone model in a page's
 * `device:` is not used in its place: two people with the same phone would be one person.
 *
 * Incremental: the last commit read is kept per workspace, and each pass reads only what came after
 * it. A rebase that rewrites commits already read makes them new to the walk; reading them again
 * costs a little and changes nothing, since every id they add is already recorded.
 */
class TaskOrigins(
    private val db: AppDatabase,
    /** Where a scan runs — never the sync's own coroutine. See [request]. */
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Reads what is new in [workspaceId]'s history, in the background, and returns at once.
     *
     * Off the sync's path on purpose. The first read of a long history is the one expensive pass
     * this ever makes, and inside the sync's lock it would hold pull-to-sync's spinner for as long
     * as it took — for a line of text on a task page. A request while one is already reading that
     * workspace is dropped: the one running will reach HEAD, and the next sync asks again.
     */
    fun request(workspaceId: String, repo: GitRepo) {
        if (!running.add(workspaceId)) return
        scope.launch(Dispatchers.IO) {
            try {
                repo.open().use { git -> scan(workspaceId, git.repository) }
            } catch (e: Exception) {
                ie.shoonya.yantra.Trace.error("sync", "could not read who added what", e)
            } finally {
                running.remove(workspaceId)
            }
        }
    }

    internal suspend fun scan(workspaceId: String, repository: Repository) {
        val head = repository.resolve("HEAD") ?: return
        val dao = db.originDao()
        val stored = dao.head(workspaceId)
        // Read under different rules, the rows are wrong in a way reading more cannot fix — first
        // sighting is never replaced — so they go, and the history is read again from the start.
        val last = stored?.takeIf { it.startsWith(RULES) }?.removePrefix(RULES)
        if (stored != null && last == null) dao.forget(workspaceId)
        if (last == head.name) return

        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(head))
            // An id the repository no longer has — a rebase dropped it, or it was never here — is
            // simply not a floor, and the walk reads from the beginning.
            last?.let { ObjectId.fromString(it) }
                ?.let { runCatching { walk.parseCommit(it) }.getOrNull() }
                ?.let { walk.markUninteresting(it) }
            walk.sort(RevSort.TOPO, true)
            walk.sort(RevSort.REVERSE, true)

            DiffFormatter(DisabledOutputStream.INSTANCE).use { diffs ->
                diffs.setRepository(repository)
                diffs.isDetectRenames = false
                val batch = ArrayList<TaskOriginEntity>()
                var lastRead: String? = null
                for (commit in walk) {
                    val login = loginOf(commit.authorIdent)
                    val parent = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)).tree else null
                    for (entry in diffs.scan(parent, commit.tree)) {
                        if (entry.changeType == DiffEntry.ChangeType.DELETE) continue
                        if (!entry.newPath.endsWith(".md")) continue
                        val now = idsIn(textOf(repository, entry.newId.toObjectId()))
                        val before =
                            if (entry.changeType == DiffEntry.ChangeType.ADD) emptySet()
                            else idsIn(textOf(repository, entry.oldId.toObjectId()))
                        (now - before).forEach { batch += TaskOriginEntity(workspaceId, it, login) }
                    }
                    lastRead = commit.name
                    // Written as it goes, so a first read of a long history that is interrupted
                    // resumes rather than starting again.
                    if (batch.size >= FLUSH_AT) {
                        dao.insertAll(batch)
                        batch.clear()
                        dao.setHead(OriginScanEntity(workspaceId, RULES + commit.name))
                    }
                }
                if (batch.isNotEmpty()) dao.insertAll(batch)
                dao.setHead(OriginScanEntity(workspaceId, RULES + (lastRead ?: head.name)))
            }
        }
    }

    private fun textOf(repository: Repository, id: ObjectId): String =
        runCatching { String(repository.open(id).bytes, Charsets.UTF_8) }.getOrDefault("")

    companion object {
        private const val FLUSH_AT = 500

        /**
         * Which rules the stored rows were read under, kept with the position. Bumped whenever
         * [loginOf] or [idsIn] would read the same history differently.
         */
        private const val RULES = "2:"

        /** The id of every task line in a page — `- [ ] … ^id …`. */
        private val TASK_LINE = Regex("""^\s*- \[[ xX~]]""")

        /**
         * `^id` as its own word. Preceded by whitespace so the `|^abc` inside a link — which is a
         * *reference* to a task, not the task — is not read as one.
         */
        private val ID = Regex("""(?:^|\s)\^([A-Za-z0-9_-]+)""")

        /** The task ids a page's text holds, read the way the codec reads a task line's `^id`. */
        fun idsIn(text: String): Set<String> =
            text.lineSequence()
                .filter { TASK_LINE.containsMatchIn(it) }
                .mapNotNull { line -> ID.findAll(line).lastOrNull()?.groupValues?.get(1) }
                .toSet()

        private val APP_DOMAINS = listOf("@shoonya.ie", "@napkin.ie")

        private val NOREPLY = Regex("""^(?:\d+\+)?([^@]+)@users\.noreply\.github\.com$""", RegexOption.IGNORE_CASE)

        /**
         * Who a commit names, as a login where it can say one.
         *
         * The app signs with GitHub's no-reply address, so the login is read back out of that. A
         * commit made anywhere else — GitHub's own editor, a laptop — is taken at its author name,
         * which is the best the history has. The app's old signature — "Yantra" at its own domain,
         * from every phone — names nobody.
         */
        fun loginOf(name: String?, email: String?): String? {
            // Every address the app has ever signed with — `napkin.ie` from before the rename.
            // Matched on the domain, not the name: the name was the product's, and it changed.
            if (email != null && APP_DOMAINS.any { email.endsWith(it, ignoreCase = true) }) return null
            email?.let { NOREPLY.find(it) }?.let { return it.groupValues[1] }
            return name?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun loginOf(ident: PersonIdent?): String? = loginOf(ident?.name, ident?.emailAddress)
    }
}
