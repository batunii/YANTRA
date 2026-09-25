package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeType
import ie.shoonya.yantra.data.db.SystemKey
import ie.shoonya.yantra.data.repo.LabelRepository
import ie.shoonya.yantra.data.workspace.Indexer
import ie.shoonya.yantra.data.workspace.LabelDef
import ie.shoonya.yantra.data.workspace.Workspaces
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Recolouring a tag on a task in another repo does not take the tag off the task.
 *
 * **What happened on the phone.** Long-press the `#question` chip on a task in `v2-tasks`, tap a
 * swatch, and the chip is gone — from that task and from every other task in that repo carrying it.
 * It stays gone. Only a cold start, which rebuilds every workspace from its files, brings it back.
 *
 * **Why.** `LabelRepository.setColor` wrote through `primary()` whatever it was recolouring, so
 * v2-tasks' label id landed in Personal's registry. Personal's next rebuild read it as Personal's
 * own definition of that name. `label`'s primary key is the id and its insert is REPLACE, so the
 * real v2-tasks row was **deleted** to make room — and `node_label` has a cascading foreign key to
 * `label`, so every attachment went with it. v2-tasks had not changed, so nothing was going to
 * rebuild it and put them back.
 *
 * Nothing was ever lost: the tag is a word on a line in the page, and no page was touched. That is
 * the whole of the damage and also the whole of the reason it was hard to see.
 *
 * [LabelChipSurvivesTest] covers the same cascading key within one workspace, where `linksChanged`
 * can see the label change and rewrite the links. Across workspaces it cannot: a rebuild only
 * writes its own tables, and the workspace being harmed is not the one being rebuilt.
 */
@RunWith(AndroidJUnit4::class)
class RecolourStaysInItsWorkspaceTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var ws: Workspaces
    private lateinit var labels: LabelRepository

    private val work = "ws-work"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "recolour-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        ws = Workspaces(db, Indexer(db), "test-device")
        // Personal first, so it is `primary()` — which is where every recolour used to go.
        ws.open("", File(root, "personal"), "Personal")
        ws.open(work, File(root, "work"), "Work")
        ws.reindexAll()
        labels = LabelRepository(db, ws)
    }

    @After
    fun tearDown() = db.close()

    /** A tagged task in Work, and the id its workspace minted for the tag. */
    private suspend fun taggedTaskInWork(): Pair<String, String> {
        val w = ws.writer(work)!!
        val inbox = w.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        val task = w.addBlock(inbox, NodeType.TASK, "Laurance questions")
        w.editTask(task) { it.copy(labels = listOf("question")) }
        val row = db.labelDao().allOnce().single { it.name == "question" }
        assertEquals("the tag should belong to Work", work, row.workspaceId)
        return task to row.id
    }

    private suspend fun attachmentsOf(labelId: String) = db.labelDao().countUsage(listOf(labelId))

    @Test
    fun `recolouring a tag leaves it on its tasks`() = runBlocking {
        val (task, labelId) = taggedTaskInWork()
        assertEquals(1, attachmentsOf(labelId))

        labels.setColor(labelId, 4282222776L)

        assertEquals("the tag came off the task", 1, attachmentsOf(labelId))
        assertEquals(listOf(labelId), db.labelDao().forNode(task).first().map { it.labelId })
    }

    @Test
    fun `the colour is written to the workspace that owns the tag`() = runBlocking {
        val (_, labelId) = taggedTaskInWork()

        labels.setColor(labelId, 4282222776L)

        val theirs = ws.store(work)!!.readLabels()
        assertEquals(
            "the colour belongs in Work's registry",
            listOf(LabelDef(id = labelId, name = "question", color = 4282222776L)),
            theirs,
        )
        assertTrue(
            "Personal's registry must not carry another workspace's label: ${ws.store("")!!.readLabels()}",
            ws.store("")!!.readLabels().none { it.id == labelId },
        )
        assertEquals(4282222776L, db.labelDao().allOnce().single { it.name == "question" }.color)
    }

    /**
     * One tag in two repos is one tag: one entry to pick, one colour, one count. Each workspace
     * still keeps its own row and gets the colour in its own registry, under its own id.
     */
    @Test
    fun `a tag typed in two workspaces is one tag with one colour`() = runBlocking {
        val (_, workId) = taggedTaskInWork()
        val p = ws.writer("")!!
        val inbox = p.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
        p.editTask(p.addBlock(inbox, NodeType.TASK, "Ask Laurance")) { it.copy(labels = listOf("Question")) }
        val personalId = db.labelDao().allOnce().single { it.workspaceId == "" && it.name.equals("question", true) }.id

        assertEquals("the picker lists the tag once", 1, labels.all().first().count { it.name.equals("question", true) })
        assertEquals("the tag is on two tasks", 2, labels.usageCount(workId))

        labels.setColor(workId, 4282222776L)

        assertEquals(listOf(LabelDef(id = workId, name = "question", color = 4282222776L)), ws.store(work)!!.readLabels())
        assertEquals(4282222776L, ws.store("")!!.readLabels().single { it.id == personalId }.color)
        val byId = labels.byAnyId().first()
        assertEquals("both attachments resolve to one label", byId[workId], byId[personalId])
    }

    /**
     * The floor under the write side. Even handed a poisoned registry — which every device that ran
     * the old build has on disk — rebuilding Personal must not reach into Work's rows.
     */
    @Test
    fun `a foreign label id already in the primary registry cannot cascade`() = runBlocking {
        val (_, labelId) = taggedTaskInWork()
        val personal = ws.writer("")!!
        // Exactly what the old setColor wrote: Work's id, in Personal's file.
        personal.upsertLabel(LabelDef(id = labelId, name = "question", color = 4282222776L))
        ws.reindexAll()

        assertNotNull("Work's row was deleted", db.labelDao().allOnce().firstOrNull { it.id == labelId })
        assertEquals(
            "Work's row changed hands",
            work,
            db.labelDao().allOnce().single { it.id == labelId }.workspaceId,
        )
        assertEquals("Work's attachment was cascaded away", 1, attachmentsOf(labelId))
    }
}
