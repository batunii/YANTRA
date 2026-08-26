package ie.napkin.supertasks.data.sync

import ie.napkin.supertasks.data.format.PageCodec
import ie.napkin.supertasks.data.workspace.WorkspaceStore

/**
 * What to do when git cannot decide — GIT_WORKSPACES_PLAN.md §4.
 *
 * This only ever runs on files git has already failed to merge. Anything it *could* merge is
 * already merged and never arrives here: two people editing different tasks on the same page is an
 * ordinary line merge, and that is the whole reason the format is line-oriented text. What is left
 * is genuine disagreement about the same lines, and something has to lose.
 *
 * Three rules, in order of how much they can be trusted:
 *
 *  - **A delete never beats an edit.** An unexpected extra task is a nuisance; a task deleted out
 *    from under someone's notes is data loss. The file comes back.
 *  - **An append-only log takes both sides.** Two devices that focused offline are not disagreeing,
 *    they each did something, and picking a winner would throw one person's afternoon away.
 *  - **Everything else is last-writer-wins on `modified_at`**, tiebroken by device so both sides
 *    reach the same answer without talking. That is a real loss, and it is why it is last.
 *
 * The discarded side is never actually gone — it is in git history, and the UI can offer it. Silent
 * LWW is how people stop trusting an app; recoverable LWW is a feature nobody else offers.
 */
object ConflictResolver {

    /** What a resolution decided, so the caller can report it rather than swallow it. */
    data class Resolution(val path: String, val bytes: ByteArray?, val reason: String) {
        override fun equals(other: Any?) = other is Resolution &&
            other.path == path && other.reason == reason && other.bytes.contentEquals(bytes)
        override fun hashCode() = path.hashCode() * 31 + reason.hashCode()
    }

    /**
     * [local] and [remote] are the two sides, either of which may be null when that side deleted
     * the file. A null result means the file should stay deleted.
     *
     * [device] and [otherDevice] break a timestamp tie. They must be compared the same way on both
     * machines or the two would resolve differently and diverge forever — which is why the rule is
     * a plain string comparison rather than anything cleverer like "prefer mine".
     */
    fun resolve(
        path: String,
        local: ByteArray?,
        remote: ByteArray?,
        device: String = "",
        otherDevice: String = "",
        /** The common ancestor, when git has one. Without it only last-writer-wins is available. */
        base: ByteArray? = null,
    ): Resolution {
        if (local == null && remote == null) return Resolution(path, null, "deleted on both sides")
        if (local == null) return Resolution(path, remote, "kept the edit over a delete")
        if (remote == null) return Resolution(path, local, "kept the edit over a delete")

        if (isLog(path)) return Resolution(path, mergeLog(local, remote), "merged an append-only log")

        if (path.endsWith(".md")) {
            mergePage(base, local, remote)?.let {
                return Resolution(path, it, "merged the page around the disagreement")
            }
        }

        if (!path.endsWith(".md")) {
            // Sidecars and registries have no readable clock, and a stroke set has no line
            // structure to merge. Keeping the local side is arbitrary but it is *consistently*
            // arbitrary, which is what stops two devices ping-ponging a file between them.
            return Resolution(path, local, "kept the local side of an unmergeable file")
        }

        val lt = modifiedAt(local)
        val rt = modifiedAt(remote)
        return when {
            lt > rt -> Resolution(path, local, "local page was newer")
            rt > lt -> Resolution(path, remote, "remote page was newer")
            device > otherDevice -> Resolution(path, local, "same timestamp, decided by device")
            else -> Resolution(path, remote, "same timestamp, decided by device")
        }
    }

    /**
     * Merges two versions of a page **block by block**, not line by line.
     *
     * Line merge is the wrong tool here and fails on the commonest case there is. Tasks are adjacent
     * lines, so two people ticking off two different tasks change two neighbouring lines with no
     * unchanged line between them — git sees one region touched by both sides and gives up. On a
     * short list that is *every* concurrent edit. And `modified_at` sits in the frontmatter and is
     * rewritten by both devices on every save, so even edits that are nowhere near each other
     * collide on that.
     *
     * Blocks are the honest unit. A task carries its own id, so "did this task change" is a question
     * with an answer, and two people editing different tasks are not in conflict at any level that
     * matters. Only a block both sides changed is a real disagreement, and only that block is
     * settled by last-writer-wins — the rest of the page survives intact.
     *
     * Blocks with no id of their own — prose, headings, bullets — are matched by position among
     * their own kind. That is cruder, but it is no worse than the line merge it replaces and it no
     * longer drags unrelated tasks down with it.
     */
    private fun mergePage(base: ByteArray?, local: ByteArray, remote: ByteArray): ByteArray? {
        val b = decode(base ?: return null) ?: return null
        val l = decode(local) ?: return null
        val r = decode(remote) ?: return null

        val localNewer = l.modifiedAt >= r.modifiedAt
        val bk = keyed(b.blocks)
        val lk = keyed(l.blocks)
        val rk = keyed(r.blocks)

        val order = lk.keys.toMutableList()
        rk.keys.forEach { if (it !in lk) order += it }

        val merged = order.mapNotNull { key ->
            val bb = bk[key]
            val ll = lk[key]
            val rr = rk[key]
            when {
                ll == null && rr == null -> null
                // A side dropping a block only wins if the other side left it alone. An edit
                // always outlives a delete.
                ll == null -> if (rr == bb) null else rr
                rr == null -> if (ll == bb) null else ll
                ll == rr -> ll
                bb == ll -> rr          // only the remote touched it
                bb == rr -> ll          // only the local touched it
                else -> if (localNewer) ll else rr   // both did; someone has to lose
            }
        }

        val header = if (localNewer) l else r
        return PageCodec.encode(
            header.copy(blocks = merged.map { stripRaw(it) })
        ).toByteArray()
    }

    /**
     * A stable name for a block.
     *
     * Tasks and ink carry ids, which is what makes this work at all. Everything else is identified
     * by its kind and how many of that kind came before it, so an untouched paragraph still matches
     * itself across all three versions.
     */
    private fun keyed(blocks: List<ie.napkin.supertasks.data.format.Block>):
        LinkedHashMap<String, ie.napkin.supertasks.data.format.Block> {
        val out = LinkedHashMap<String, ie.napkin.supertasks.data.format.Block>()
        val counts = HashMap<String, Int>()
        blocks.forEach { blk ->
            val key = when (blk) {
                is ie.napkin.supertasks.data.format.TaskRef ->
                    if (blk.id.isNotEmpty()) "task:${blk.id}" else null
                is ie.napkin.supertasks.data.format.InkRef -> "ink:${blk.id}"
                else -> null
            } ?: run {
                val kind = blk::class.simpleName.orEmpty()
                val n = counts.merge(kind, 1, Int::plus)!!
                "$kind:$n"
            }
            out[key] = blk
        }
        return out
    }

    /** Raw source belongs to the file it came from; a merged block has to be re-rendered. */
    private fun stripRaw(b: ie.napkin.supertasks.data.format.Block) = when (b) {
        is ie.napkin.supertasks.data.format.Prose -> b.copy(raw = null)
        is ie.napkin.supertasks.data.format.Heading -> b.copy(raw = null)
        is ie.napkin.supertasks.data.format.Bullet -> b.copy(raw = null)
        is ie.napkin.supertasks.data.format.Numbered -> b.copy(raw = null)
        is ie.napkin.supertasks.data.format.TaskRef -> b.copy(raw = null)
        is ie.napkin.supertasks.data.format.InkRef -> b.copy(raw = null)
        is ie.napkin.supertasks.data.format.ImageRef -> b.copy(raw = null)
    }

    private fun decode(bytes: ByteArray) =
        runCatching { PageCodec.decode(bytes.decodeToString()) }.getOrNull()

    // Both directory names: a device still on the older app pushes to "pomodoro/", and those lines
    // have to merge by union like any other focus log rather than be resolved as an ordinary file.
    private fun isLog(path: String) = path.endsWith(".log") &&
        (path.startsWith("${WorkspaceStore.FOCUS_DIR}/") || path.startsWith("${WorkspaceStore.LEGACY_FOCUS_DIR}/"))

    private fun modifiedAt(bytes: ByteArray): Long =
        runCatching { PageCodec.decode(bytes.decodeToString()).modifiedAt.toEpochMilli() }
            .getOrDefault(0L)

    /**
     * Both sides' lines, with the last line for any repeated id winning.
     *
     * A session is appended once when it starts and again when it ends, so "last wins" is what
     * turns two partial views into one complete one: the device that finished the session has the
     * closing line, the other only has the opening one, and taking both in order lands on the
     * closing one whichever way round they arrive.
     */
    private fun mergeLog(local: ByteArray, remote: ByteArray): ByteArray {
        val seen = LinkedHashMap<String, String>()
        (remote.decodeToString().lines() + local.decodeToString().lines())
            .filter { it.isNotBlank() && !it.startsWith("<<<") && !it.startsWith("===") && !it.startsWith(">>>") }
            .forEach { seen[it.substringBefore('\t')] = it }
        return (seen.values.joinToString("\n") + "\n").toByteArray()
    }
}
