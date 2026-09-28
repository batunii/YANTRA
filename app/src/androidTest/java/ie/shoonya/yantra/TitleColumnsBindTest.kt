package ie.shoonya.yantra

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.db.AppDatabase
import ie.shoonya.yantra.data.db.NodeEntity
import ie.shoonya.yantra.data.db.PropertyDefEntity
import ie.shoonya.yantra.data.db.PropertyValueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every query that returns a title actually delivers one.
 *
 * **Room binds by column name and says nothing when a name does not match.** A projection aliased
 * to something the data class does not declare compiles without an error, without a warning, and
 * reads back as null — so the app builds, installs, runs, and draws "Untitled" everywhere that
 * column was meant to appear.
 *
 * That is not hypothetical. Renaming `EventWithTitle.title` to `ownTitle` was done with a global
 * replace on the alias, which hit five queries instead of the three that needed it. `RailTask` and
 * `DueRow` went on declaring `title`, their queries started returning `ownTitle`, and the calendar
 * filled with "Untitled" — shipped to a phone, reported from the phone, and invisible to the entire
 * toolchain in between.
 *
 * These are dull assertions on purpose. The value is not in what they check but in *that* they run:
 * nothing else in the build will ever notice this class of mistake.
 */
@RunWith(AndroidJUnit4::class)
class TitleColumnsBindTest {

    private lateinit var db: AppDatabase
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val due = PropertyDefEntity(
        id = "def-due", name = "Due", kind = "date", config = null, isBuiltIn = true,
        createdAt = 1, updatedAt = 1,
    )
    private val deadline = PropertyDefEntity(
        id = "def-deadline", name = "Deadline", kind = "date", config = null, isBuiltIn = true,
        createdAt = 1, updatedAt = 1,
    )

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        db.propertyDao().insertDefs(listOf(due, deadline))
        db.nodeDao().insertAll(
            listOf(
                NodeEntity(
                    id = "t1", type = "task", parentId = null, title = "Write the deck",
                    rank = "a", createdAt = 1, updatedAt = 1, workspaceId = "",
                )
            )
        )
        db.propertyDao().insertValues(
            listOf(
                PropertyValueEntity(
                    nodeId = "t1", defId = due.id, workspaceId = "",
                    vDate = 1_787_000_000_000L, updatedAt = 1,
                )
            )
        )
    }

    @After
    fun tearDown() = db.close()

    /** The rail draws this straight; a null here is a shelf full of "Untitled". */
    @Test
    fun railTasksCarryTheirTitle() = runBlocking {
        val rows = db.propertyDao().railTasks(due.id, deadline.id).first()
        assertEquals(1, rows.size)
        assertEquals("Write the deck", rows.single().title)
    }

    /** The same for the day's due tasks, which the calendar draws on the timeline. */
    @Test
    fun dueRowsCarryTheirTitle() = runBlocking {
        val rows = db.propertyDao()
            .observeDueInRange(due.id, 1_786_000_000_000L, 1_788_000_000_000L)
            .first()
        assertEquals(1, rows.size)
        assertEquals("Write the deck", rows.single().title)
    }
}
