package ie.napkin.supertasks

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.db.NodeType
import ie.napkin.supertasks.data.db.SystemKey
import ie.napkin.supertasks.data.filter.Filter
import ie.napkin.supertasks.data.filter.FilterCompiler
import ie.napkin.supertasks.data.workspace.Indexer
import ie.napkin.supertasks.data.workspace.WorkspaceStore
import ie.napkin.supertasks.data.workspace.WorkspaceWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * One database holds every workspace, which is what lets a single Today span personal, project and
 * shared. The price is that scoping stops being optional: a query that forgets `workspace_id`
 * quietly mixes a work repo into a personal one, and nothing about the result *looks* wrong.
 *
 * These tests are the guard on that. Two workspaces are built with deliberately overlapping shapes —
 * both have an Inbox, both use `#sync`, both have a task called the same thing — and every read the
 * app performs is checked for bleed.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceLeakTest {

    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var work: WorkspaceStore
    private lateinit var personal: WorkspaceStore
    private lateinit var workW: WorkspaceWriter
    private lateinit var personalW: WorkspaceWriter

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(ctx.cacheDir, "leak-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val indexer = Indexer(db)

        work = WorkspaceStore(File(root, "work"), "ws-work").also { it.scaffold("Work", 1L) }
        personal = WorkspaceStore(File(root, "personal"), "ws-personal").also { it.scaffold("Personal", 1L) }
        workW = WorkspaceWriter(work, db, indexer, "test")
        personalW = WorkspaceWriter(personal, db, indexer, "test")

        // Deliberately identical shapes in both.
        listOf(workW to "Work", personalW to "Personal").forEach { (w, which) ->
            val inbox = w.createTopLevel(NodeType.LIST, "Inbox", systemKey = SystemKey.INBOX)
            val t = w.addBlock(inbox, NodeType.TASK, "Same name in both")
            w.editTask(t) { it.copy(labels = listOf("sync")) }
            w.addBlock(inbox, NodeType.TASK, "Only in $which")
        }
        Unit
    }

    @After
    fun tearDown() = db.close()

    private suspend fun nodesIn(ws: String) =
        db.nodeDao().rawNodeQuery(
            SimpleSQLiteQuery("SELECT * FROM node WHERE workspace_id = ?", arrayOf(ws))
        ).first()

    // ---- the constraints that were global ----

    @Test
    fun bothWorkspacesCanHaveTheirOwnInbox() = runBlocking {
        // A globally unique system_key rejected the second one on insert, which is the sort of
        // failure that only appears when someone adds their second repo.
        val inboxes = db.nodeDao().rawNodeQuery(
            SimpleSQLiteQuery("SELECT * FROM node WHERE system_key = ?", arrayOf(SystemKey.INBOX))
        ).first()
        assertEquals(2, inboxes.size)
        assertEquals(setOf("ws-work", "ws-personal"), inboxes.mapTo(HashSet()) { it.workspaceId })
    }

    @Test
    fun bothWorkspacesCanUseTheSameTagWithoutSharingIt() = runBlocking {
        val sync = db.labelDao().all().first().filter { it.name == "sync" }
        assertEquals(2, sync.size)
        assertEquals(2, sync.mapTo(HashSet()) { it.id }.size)
        assertEquals(setOf("ws-work", "ws-personal"), sync.mapTo(HashSet()) { it.workspaceId })
    }

    // ---- reads ----

    @Test
    fun reindexingOneWorkspaceLeavesTheOtherAlone() = runBlocking {
        val before = nodesIn("ws-personal").map { it.id }.sorted()
        workW.reindex()
        assertEquals(before, nodesIn("ws-personal").map { it.id }.sorted())
        assertTrue(nodesIn("ws-work").isNotEmpty())
    }

    @Test
    fun aSmartListSeesOnlyItsOwnWorkspace() = runBlocking {
        val q = FilterCompiler.compile(
            scopeRootId = null,
            filter = Filter.Type(NodeType.TASK),
            workspaceId = "ws-work",
        )
        val rows = db.nodeDao().rawNodeQuery(SimpleSQLiteQuery(q.sql, q.args.toTypedArray())).first()
        assertTrue(rows.isNotEmpty())
        assertTrue("a rule reached into another workspace", rows.all { it.workspaceId == "ws-work" })
        assertTrue(rows.none { it.title == "Only in Personal" })
    }

    @Test
    fun aRuleCanDeliberatelySpanWorkspaces() = runBlocking {
        // What the unified Today is for. Null scope is the explicit opt-in, never the default.
        val q = FilterCompiler.compile(null, Filter.Type(NodeType.TASK), workspaceId = null)
        val rows = db.nodeDao().rawNodeQuery(SimpleSQLiteQuery(q.sql, q.args.toTypedArray())).first()
        assertEquals(setOf("ws-work", "ws-personal"), rows.mapTo(HashSet()) { it.workspaceId })
    }

    @Test
    fun deletingInOneWorkspaceDoesNotTouchTheOther() = runBlocking {
        val target = nodesIn("ws-work").single { it.title == "Only in Work" }
        workW.removeBlock(target.id)
        assertTrue(nodesIn("ws-work").none { it.title == "Only in Work" })
        assertTrue(nodesIn("ws-personal").any { it.title == "Only in Personal" })
    }

    // ---- the derived label ----

    @Test
    fun everyTaskCarriesALabelForTheWorkspaceItCameFrom() = runBlocking {
        val labels = db.labelDao().all().first()
        val workLabel = labels.single { it.name == "Work" }
        val links = db.labelDao().allNodeLabels().first().filter { it.labelId == workLabel.id }

        val workTasks = nodesIn("ws-work").filter { it.type == NodeType.TASK }
        assertEquals(workTasks.map { it.id }.sorted(), links.map { it.nodeId }.sorted())
        // And it filters through the existing machinery, with no new syntax.
        val q = FilterCompiler.compile(null, Filter.HasLabel(workLabel.id), workspaceId = null)
        val rows = db.nodeDao().rawNodeQuery(SimpleSQLiteQuery(q.sql, q.args.toTypedArray())).first()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.workspaceId == "ws-work" })
    }

    @Test
    fun theWorkspaceLabelIsNotWrittenToAnyFile() = runBlocking {
        // Provenance is not content. A file claiming its own workspace would be lying the moment
        // the repo was cloned as a second one.
        val text = work.root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue("the derived label leaked into the repo", !text.contains("ws-work:workspace"))
        assertTrue(!work.readLabels().any { it.name == "Work" })
    }

    @Test
    fun reindexingOneWorkspaceKeepsAnothersProperties() = runBlocking {
        // property_def is the one table with no workspace column, and its clear was the one clear in
        // Indexer.apply that was not scoped — so rebuilding either workspace emptied the registry
        // for both and refilled it from one. Invisible while every workspace scaffolds the same
        // built-ins, and data loss the moment one has a property of its own.
        val mine = db.propertyDao().defsOnce().map { it.id }.toSet()
        assertTrue("no property definitions to test with", mine.isNotEmpty())

        personalW.reindex()

        assertTrue(
            "reindexing another workspace removed property definitions",
            db.propertyDao().defsOnce().map { it.id }.toSet().containsAll(mine),
        )
    }
}
