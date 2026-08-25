package ie.napkin.supertasks.data.format

import java.time.Instant
import java.time.LocalDate

/**
 * A page, as it exists in the repo — see GIT_WORKSPACES_PLAN.md §2.
 *
 * The shape follows from one observation: **a task is a line on its parent's page, and its page is
 * a separate document.** Title, status, indent and position belong to the line; this document holds
 * what the chevron opens. Nothing is stored in both places, so nothing can drift out of agreement.
 *
 * Ordering is line position. There is no `rank` here — the index regenerates one on import, which
 * means reordering rewrites a file rather than renaming anything, and git merges it as a text edit.
 */
data class PageDoc(
    val id: String,
    val type: String,                 // ie.napkin.supertasks.data.db.NodeType
    val parent: String?,
    /**
     * Authoritative **only when [parent] is null**.
     *
     * A task's title belongs to its line on the parent's page, not to its own file — that is what
     * "nothing is stored twice" means. A top-level list or group has no line anywhere, so its name
     * has to live here. A title found on a parented page is kept (nothing unrecognised is dropped)
     * but the line wins, and the app does not write one.
     */
    val title: String?,
    /** [ie.napkin.supertasks.data.db.SystemKey] — `today`, `inbox`. Must survive the round trip. */
    val systemKey: String? = null,
    val modifiedAt: Instant,
    val device: String?,
    val blocks: List<Block>,
    /**
     * Frontmatter keys this version does not understand, in file order.
     *
     * Kept so a page written by a newer app survives a round-trip through an older one. Dropping
     * them silently is how "it worked on my phone and lost a field on my laptop" happens.
     */
    val unknownKeys: Map<String, String> = emptyMap(),
)

/** How a task line renders its glyph. Mirrors `done` + `in_progress`, which are never both set. */
enum class TaskStatus { OPEN, IN_PROGRESS, DONE }

/** All-day means a calendar date; timed means an exact instant. The distinction is `hasTime`. */
sealed interface DueValue {
    data class AllDay(val date: LocalDate) : DueValue
    data class At(val instant: Instant) : DueValue
}

/** [reminderMin] is minutes *before* the due moment; negative means after. Null is no reminder. */
data class DueSpec(val value: DueValue, val reminderMin: Int? = null)

/**
 * One line of a page.
 *
 * [raw] is the source text this block was parsed from, and the emitter prefers it over re-rendering
 * — but only after checking that re-parsing it still yields this exact block. That is what lets a
 * hand-edited file survive the app touching a different line, without anyone having to remember to
 * clear [raw] when they change something.
 */
sealed interface Block {
    val indent: Int
    val raw: String?
}


data class Prose(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

data class Heading(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

data class Bullet(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

/** The ordinal is positional and recomputed on render, so it is deliberately not stored. */
data class Numbered(val text: String, override val indent: Int = 0, override val raw: String? = null) : Block

/**
 * A task on this page. Its own page, if it has one, is the file named by [id].
 *
 * Unrecognised trailing words stay in [title] rather than being extracted into a bag of extras:
 * a token this version cannot read is, as far as it is concerned, part of what the line says, and
 * leaving it in the title means it is written back exactly as it arrived.
 */
data class TaskRef(
    val id: String,
    val title: String,
    val status: TaskStatus = TaskStatus.OPEN,
    override val indent: Int = 0,
    val due: DueSpec? = null,
    val deadline: LocalDate? = null,
    val priority: String? = null,
    val labels: List<String> = emptyList(),
    val assignee: String? = null,
    override val raw: String? = null,
) : Block

/** Strokes live in `<id>.ink`, the same StrokeCodec blob the database holds today. */
data class InkRef(val id: String, override val indent: Int = 0, override val raw: String? = null) : Block

data class ImageRef(val uri: String, override val indent: Int = 0, override val raw: String? = null) : Block
