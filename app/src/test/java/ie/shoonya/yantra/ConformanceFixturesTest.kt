package ie.shoonya.yantra

import ie.shoonya.yantra.data.ink.StrokeEnvelope
import ie.shoonya.yantra.data.rank.Rank
import ie.shoonya.yantra.data.sync.ConflictResolver
import ie.shoonya.yantra.data.workspace.WorkspaceStore
import ie.shoonya.yantra.domain.sessionClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The contract between this app and any other that shares its repositories.
 *
 * `conformance/` at the repository root holds golden files: inputs and the bytes this code produces
 * for them. This test **checks** the Kotlin against those files on every run, and **writes** them
 * when run with `YANTRA_WRITE_FIXTURES=1` — the only way the fixtures change is by deciding to
 * change them. The iOS core runs the same files through its own implementation; when both pass,
 * two devices agree on bytes without ever talking.
 *
 * Add a fixture here the moment a second platform needs to reproduce a behaviour, and before that
 * behaviour is changed.
 */
class ConformanceFixturesTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private lateinit var dir: File
    private val writing = System.getenv("YANTRA_WRITE_FIXTURES") == "1"

    @Before
    fun locate() {
        // Gradle runs unit tests with the module as the working directory.
        dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "conformance") }
            .firstOrNull { it.isDirectory || writing && File(it.parentFile, "settings.gradle.kts").exists() }
            ?: error("conformance/ not found above ${File("").absolutePath}")
        if (writing) dir.mkdirs()
        Locale.setDefault(Locale.US)
    }

    private inline fun <reified T> golden(name: String, produce: () -> T): T {
        val f = File(dir, name)
        val value = produce()
        if (writing) {
            f.parentFile.mkdirs()
            f.writeText(json.encodeToString(value))
        } else {
            assertTrue("missing fixture $name — run with YANTRA_WRITE_FIXTURES=1", f.exists())
            assertEquals("fixture $name drifted", json.decodeFromString<T>(f.readText()), value)
        }
        return value
    }

    private fun goldenBytes(name: String, bytes: ByteArray) {
        val f = File(dir, name)
        if (writing) { f.parentFile.mkdirs(); f.writeBytes(bytes) } else {
            assertTrue("missing fixture $name", f.exists())
            assertArrayEquals("fixture $name drifted", f.readBytes(), bytes)
        }
    }

    // ---- rank ----

    @Serializable data class RankCase(val a: String?, val b: String?, val result: String)
    @Serializable data class RankFixture(val alphabet: String, val first: String, val cases: List<RankCase>, val neverEndsInZero: Boolean = true)

    @Test
    fun rank() {
        val bounds = listOf(
            null to null, null to "i", "i" to null, "i" to "j", "i" to "i1", "a" to "b", "az" to "b",
            "zz" to null, null to "1", "0z" to "1", "i" to "i01", "hz" to "i", "ii" to "ij",
        )
        // Also a chain: twenty ranks appended after one another, then ten inserted between the
        // first two — the way a list is actually built.
        val chain = ArrayList<Pair<Pair<String?, String?>, String>>()
        var last: String? = null
        repeat(20) { val r = Rank.after(last); chain += (last to null) to r; last = r }
        var lo = chain[0].second; val hi = chain[1].second
        repeat(10) { val r = Rank.between(lo, hi); chain += (lo to hi) to r; lo = r }
        golden("rank/vectors.json") {
            RankFixture(
                alphabet = "0123456789abcdefghijklmnopqrstuvwxyz",
                first = Rank.FIRST,
                cases = bounds.map { (a, b) -> RankCase(a, b, Rank.between(a, b)) } +
                    chain.map { (ab, r) -> RankCase(ab.first, ab.second, r) },
            )
        }
    }

    // ---- stroke envelope ----

    @Serializable data class PointFixture(val x: Float, val y: Float, val elapsedMillis: Int, val pressure: Float, val tiltRadians: Float, val orientationRadians: Float, val strokeUnitLengthCm: Float)
    @Serializable data class StrokeFixture(val family: String, val color: Long, val size: Float, val epsilon: Float, val tool: Int, val points: List<PointFixture>, val file: String)

    @Test
    fun strokeEnvelope() {
        val header = StrokeEnvelope.Header("pressure_pen", 0xFF23211CL, 2.6f, 0.1f)
        val points = (0 until 6).map { i ->
            StrokeEnvelope.Point(10f + i * 3.5f, 20f - i * 1.25f, i * 8, 0.4f + i * 0.05f, if (i == 0) -1f else 0.3f, if (i == 0) -1f else 1.2f, 0f)
        }
        val env = StrokeEnvelope.Envelope(header, StrokeEnvelope.TOOL_STYLUS, points)
        val bytes = StrokeEnvelope.encode(env)
        goldenBytes("ink/pen.ynk1", bytes)
        golden("ink/pen.json") {
            StrokeFixture(header.family, header.color, header.size, header.epsilon, env.tool,
                points.map { PointFixture(it.x, it.y, it.elapsedMillis, it.pressure, it.tiltRadians, it.orientationRadians, it.strokeUnitLengthCm) },
                file = "pen.ynk1")
        }
        // An empty highlighter stroke, and the whole-sidecar container around both.
        val empty = StrokeEnvelope.Envelope(StrokeEnvelope.Header("highlighter", 0x59E0A83EL, 9f, 0.1f), StrokeEnvelope.TOOL_TOUCH, emptyList())
        goldenBytes("ink/empty-highlighter.ynk1", StrokeEnvelope.encode(empty))
        goldenBytes("ink/sidecar.ink", WorkspaceStore.encodeInk(listOf(bytes, StrokeEnvelope.encode(empty))))
        assertEquals(env, StrokeEnvelope.decode(bytes))
    }

    // ---- session clock ----

    @Serializable data class ClockFixture(val cases: Map<Int, String>)

    @Test
    fun sessionClock() {
        golden("focus/session-clock.json") {
            ClockFixture(listOf(0, 7, 59, 60, 545, 3599, 3600, 7384, 36000, -30).associateWith { sessionClock(it) })
        }
    }

    // ---- capture grammar ----

    @Serializable data class CaptureCase(val input: String, val lists: List<String> = emptyList(), val people: List<String> = emptyList(),
        val title: String, val date: String?, val time: String?, val labels: List<String>, val priority: String?, val assignee: String?,
        val list: String?, val listIsNew: Boolean, val spans: List<String>)
    @Serializable data class CaptureFixture(val today: String, val cases: List<CaptureCase>)

    @Test
    fun capture() {
        // A fixed Wednesday, so weekday arithmetic is the same on every machine that runs this.
        val today = java.time.LocalDate.of(2026, 9, 9)
        val inputs: List<Triple<String, List<String>, List<String>>> = listOf(
            Triple("buy milk today", emptyList(), emptyList()),
            Triple("call the vet tomorrow", emptyList(), emptyList()),
            Triple("standup wednesday", emptyList(), emptyList()),
            Triple("review next monday", emptyList(), emptyList()),
            Triple("dinner 6pm", emptyList(), emptyList()),
            Triple("dinner 6:30pm", emptyList(), emptyList()),
            Triple("standup 9 am", emptyList(), emptyList()),
            Triple("shift 12am", emptyList(), emptyList()),
            Triple("lunch 12pm", emptyList(), emptyList()),
            Triple("dinner 18:30", emptyList(), emptyList()),
            Triple("fix the sink #home #urgent", emptyList(), emptyList()),
            Triple("ship it !high", emptyList(), emptyList()),
            Triple("ship it !med", emptyList(), emptyList()),
            Triple("buy milk tomorrow 6pm #home !high", emptyList(), emptyList()),
            Triple("presents 25/12", emptyList(), emptyList()),
            Triple("thing 2027-01-03", emptyList(), emptyList()),
            Triple("thing 4 sep", emptyList(), emptyList()),
            Triple("thing sep 4", emptyList(), emptyList()),
            Triple("taxes 4 march", emptyList(), emptyList()),
            Triple("think about the roadmap", emptyList(), emptyList()),
            Triple("today", emptyList(), emptyList()),
            Triple("#home", emptyList(), emptyList()),
            Triple("issue C#100 is open", emptyList(), emptyList()),
            Triple("do it !soon", emptyList(), emptyList()),
            Triple("buy 18 eggs", emptyList(), emptyList()),
            Triple("call 25:99", emptyList(), emptyList()),
            Triple("thing 31/2", emptyList(), emptyList()),
            Triple("~ Errands tomorrow", emptyList(), emptyList()),
            Triple("get bread ~groceries", listOf("Groceries", "Work trips"), emptyList()),
            Triple("plan the trip ~ work trips ~ friday", listOf("Groceries", "Work trips"), emptyList()),
            Triple("ask @batunii about it", emptyList(), listOf("batunii")),
            Triple("ask @nobody about it", emptyList(), listOf("batunii")),
            Triple("~5 mins of stretching", emptyList(), emptyList()),
        )
        golden("capture/cases.json") {
            CaptureFixture(today.toString(), inputs.map { (input, lists, people) ->
                val c = ie.shoonya.yantra.data.capture.CaptureParse.parse(input, today, lists, people)
                CaptureCase(input, lists, people, c.title, c.date?.toString(), c.time?.toString(), c.labels, c.priority, c.assignee,
                    c.list, c.listIsNew, c.spans.map { "${it.kind.name.lowercase()}:${it.range.first}-${it.range.last + 1}" })
            })
        }
    }

    // ---- manifest merge ----

    @Serializable data class MergeCase(val name: String, val base: String?, val local: String, val remote: String, val device: String, val otherDevice: String, val merged: String, val reason: String)
    @Serializable data class MergeFixture(val path: String, val cases: List<MergeCase>)

    @Test
    fun manifestMerge() {
        val base = """{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}"""
        val inputs = listOf(
            Triple("independent-edits", """{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":30}""", """{"formatVersion":2,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}"""),
            Triple("both-raise-version", """{"formatVersion":3,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}""", """{"formatVersion":2,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}"""),
            Triple("both-change-same-field", """{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":7}""", """{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":30}"""),
            Triple("new-field-each-side", """{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0,"inkFormat":"ynk1"}""", """{"formatVersion":1,"name":"renamed","createdAt":1,"epoch":2,"archive_after_days":0,"colour":"coral"}"""),
            Triple("one-side-removes-field", """{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1}""", """{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}"""),
        )
        val cases = inputs.flatMap { (name, l, r) ->
            listOf("a" to "b", "b" to "a").map { (d, o) ->
                val res = ConflictResolver.resolve(WorkspaceStore.MANIFEST_PATH, l.toByteArray(), r.toByteArray(), d, o, base.toByteArray())
                MergeCase("$name/$d", base, l, r, d, o, res.bytes!!.decodeToString(), res.reason)
            }
        } + run {
            val res = ConflictResolver.resolve(WorkspaceStore.MANIFEST_PATH, inputs[0].second.toByteArray(), inputs[0].third.toByteArray(), "a", "b", base = null)
            listOf(MergeCase("no-base/a", null, inputs[0].second, inputs[0].third, "a", "b", res.bytes!!.decodeToString(), res.reason))
        }
        golden("sync/manifest-merge.json") { MergeFixture(WorkspaceStore.MANIFEST_PATH, cases) }
    }

    // ---- the page format ----

    /**
     * One source line, what it parses to, and the bytes it renders back to once its `raw` is gone.
     *
     * Dropping `raw` is the point. With it the emitter returns the source untouched, which pins
     * nothing: every parser that can read a line would pass. Without it the line has to be rebuilt
     * from the model, so the fixture catches a token written in the wrong order, a duration printed
     * differently, or a value whose spaces were not put back.
     */
    @Serializable data class LineCase(val source: String, val kind: String, val rendered: String)

    private fun stripRaw(b: ie.shoonya.yantra.data.format.Block): ie.shoonya.yantra.data.format.Block = when (b) {
        is ie.shoonya.yantra.data.format.Prose -> b.copy(raw = null)
        is ie.shoonya.yantra.data.format.Heading -> b.copy(raw = null)
        is ie.shoonya.yantra.data.format.Bullet -> b.copy(raw = null)
        is ie.shoonya.yantra.data.format.Numbered -> b.copy(raw = null)
        is ie.shoonya.yantra.data.format.TaskRef -> b.copy(raw = null)
        is ie.shoonya.yantra.data.format.InkRef -> b.copy(raw = null)
        is ie.shoonya.yantra.data.format.ImageRef -> b.copy(raw = null)
        is ie.shoonya.yantra.data.format.EventRef -> b.copy(raw = null)
    }

    @Test
    fun pageLines() {
        val sources = listOf(
            // the plain kinds, including the two empties that used to change shape on a round trip
            "Just some prose",
            "# A heading",
            "- a bullet",
            "3. a numbered item",
            "» » indented prose",
            "- [ ]",
            "- [ ] Open task",
            "- [x] Done task ^t1 done:2026-09-11",
            "- [~] Started ^t2",
            "![[ink:i1]]",
            "![[image:img1.jpg]]",
            // tasks: every token, and the right-to-left scan's edge cases
            "- [ ] Buy #2 pencils",
            "- [ ] Buy milk #groceries #urgent !High @sam",
            "- [ ] Blocked out ^t3 due:2026-09-11T14:00:00Z/PT1H+r15",
            "- [ ] All day ^t4 due:2026-09-11 deadline:2026-09-20",
            "- [ ] About a meeting ^t5 ext:abc123@google.com",
            "- [ ] One occurrence ^t6 ext:abc123@google.com@2026-10-28T09:00",
            "- [ ] See [[Buy milk #2|^abc]] today",
            // events
            "@ 2026-09-11 Birthday ^e1",
            "@ 2026-09-11/2026-09-13 Conference ^e2",
            "@ 2026-09-11T14:00 Reminder ^e3",
            "@ 2026-09-11T14:00/PT1H Standup ^e4 rrule:FREQ=WEEKLY;BYDAY=MO",
            "@ 2026-09-11T14:00/2026-09-11T15:30 Review ^e5",
            "@ 2026-09-11T09:00[Europe/Dublin]/PT30M Call ^e6",
            "@ 2026-09-11T14:00/PT1H Sitting ^e7 for:t1",
            "@ 2026-09-11T14:00/PT1H Moved ^e8 series:s1@2026-10-28T09:00",
            "@ 2026-09-11T14:00/PT1H Skipped ^e9 series:s1 cancelled",
            "@ 2026-09-11T14:00/PT1H Lunch ^e10 loc:The%20Old%20Spot col:teal remind:10 !High #social @sam @alex",
            "@ 2026-09-11T14:00/PT1H Theirs ^e11 ext:abc123@google.com@2026-09-11T14:00",
            "@ 2026-09-11T14:00 Coffee with #2",
            // a `@ ` line whose when-slot will not parse is somebody's text, not an event
            "@ not-a-time at all",
        )
        val cases = sources.map { src ->
            val b = ie.shoonya.yantra.data.format.PageCodec.decodeBlock(src)
            LineCase(src, b::class.simpleName.orEmpty(), ie.shoonya.yantra.data.format.PageCodec.encodeBlock(stripRaw(b)))
        }
        golden("pages/lines.json") { cases }
    }

    /**
     * A whole page file, emitted from a model built in code.
     *
     * Pins what no per-line fixture can: the frontmatter's key order, which blocks get a blank line
     * between them, how a numbered run counts, and that an empty block still occupies a line.
     */
    @Test
    fun pageFile() {
        val page = ie.shoonya.yantra.data.format.PageDoc(
            id = "p1",
            type = ie.shoonya.yantra.data.db.NodeType.LIST,
            parent = null,
            title = "Sample",
            systemKey = ie.shoonya.yantra.data.db.SystemKey.INBOX,
            modifiedAt = java.time.Instant.parse("2026-09-11T14:22:31.402Z"),
            device = "android-a",
            blocks = listOf(
                ie.shoonya.yantra.data.format.Heading("Welcome"),
                ie.shoonya.yantra.data.format.Prose("A list holds tasks."),
                ie.shoonya.yantra.data.format.Prose(""),
                ie.shoonya.yantra.data.format.TaskRef(id = "t1", title = "First", due = ie.shoonya.yantra.data.format.DueSpec(
                    ie.shoonya.yantra.data.format.DueValue.AllDay(java.time.LocalDate.parse("2026-09-11")))),
                ie.shoonya.yantra.data.format.TaskRef(id = "t2", title = "Second", indent = 1),
                ie.shoonya.yantra.data.format.EventRef(id = "e1", title = "Standup", time = ie.shoonya.yantra.data.format.EventTime(
                    start = java.time.LocalDateTime.parse("2026-09-11T09:00"),
                    end = java.time.LocalDateTime.parse("2026-09-11T09:15"))),
                ie.shoonya.yantra.data.format.Numbered("one"),
                ie.shoonya.yantra.data.format.Numbered("two"),
                ie.shoonya.yantra.data.format.Bullet("a bullet"),
                ie.shoonya.yantra.data.format.InkRef("i1"),
            ),
            color = "teal",
        )
        val text = ie.shoonya.yantra.data.format.PageCodec.encode(page)
        goldenBytes("pages/sample.md", text.toByteArray())
        // And it reads back as what it was, which is the half a byte fixture cannot state.
        assertEquals(page.blocks.map { stripRaw(it) },
            ie.shoonya.yantra.data.format.PageCodec.decode(text).blocks.map { stripRaw(it) })
    }

    // ---- the label palette ----

    /**
     * The colour a name seeds to.
     *
     * A contract, not a detail: a list coloured by its name on one platform and read on the other
     * has to come out the same colour, or the same repository would look like two different ones.
     * The seed is `String.hashCode` over the lowercased name, which is a *specified* function —
     * 32-bit and wrapping — and therefore reproducible rather than merely "some hash".
     */
    @Serializable data class SwatchFixture(val name: String, val light: String, val dark: String)
    @Serializable data class SeedCase(val name: String, val swatch: String, val light: String)
    @Serializable data class PaletteFixture(val swatches: List<SwatchFixture>, val seeds: List<SeedCase>)

    @Test
    fun labelPalette() {
        fun hex(v: Long) = "%08X".format(v)
        val names = listOf(
            "", "a", "work", "Work", " work ", "groceries", "urgent", "Inbox", "Today",
            "personal", "home", "café", "ñ", "日本語", "a very long label name indeed",
        )
        golden("labels/palette.json") {
            PaletteFixture(
                swatches = ie.shoonya.yantra.data.label.LabelPalette.swatches.map {
                    SwatchFixture(it.name, hex(it.light), hex(it.dark))
                },
                seeds = names.map {
                    SeedCase(it, ie.shoonya.yantra.data.label.LabelPalette.defaultNameFor(it),
                             hex(ie.shoonya.yantra.data.label.LabelPalette.defaultFor(it)))
                },
            )
        }
    }
}
