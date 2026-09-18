package ie.shoonya.yantra

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The registry is not the list of workspaces.**
 *
 * `WorkspaceRegistry` holds *linked repositories*, and says so at the top of its own file: "The
 * local workspace is not in here. It has the empty id, it is always present, and it is the one
 * thing that must exist before any of this does."
 *
 * So `registry.entries()` answers "which repositories have I linked". Anything that shows a person
 * their workspaces, counts them, names one, or files something into one wants a different question
 * — and every time that difference was forgotten, the workspace most of the work lives in went
 * missing. It went missing seven times before this test was written:
 *
 *  - `setColor("")` looked the workspace up in the registry and returned when it found nothing, so
 *    the colour chosen for Personal in Settings was never saved. The row updated optimistically, so
 *    it looked like it had worked until the screen was reopened.
 *  - `HomeViewModel.workspaces` never contained Personal, so `defaultWorkspaceId` fell through its
 *    own first branch — `firstOrNull { it.id.isEmpty() }` can never match a list with no local
 *    entry in it — and every new list and group defaulted to the first *linked* repo instead. With
 *    exactly one repo linked the picker never appeared either, because one entry is not "more than
 *    one", so there was no way to correct it. A group made on Home landed in a repository nobody
 *    had chosen.
 *  - The widget counted the same list to decide whether to draw its spine, so a device with
 *    Personal and one repo counted *one* workspace and drew none — on the one surface that has
 *    nothing but the spine to say where a row came from.
 *  - Four more places named a workspace from it, so anything in Personal came back unnamed. One of
 *    them carried a comment asserting that Personal *was* in the registry, which is the belief this
 *    whole class of bug is made of.
 *
 * The rule, checked here rather than remembered:
 *
 * > **No screen and no widget may call `registry.entries()`.** Ask [ie.shoonya.yantra.AppContainer]
 * > — `openWorkspaces()` for the list, `workspaceNames()` / `workspaceColours()` for the two gated
 * > on there being more than one to tell apart.
 *
 * The data layer is exempt on purpose: linking, syncing and naming a *repository* genuinely mean
 * the registry, and `AppContainer` is where the local workspace is added back.
 */
class LocalWorkspaceIsAWorkspaceTest {

    /** Where a person meets their workspaces. Everything here must go through the container. */
    private val surfaces = listOf("ui", "widget")

    private val forbidden = Regex("""\bregistry\s*\.\s*entries\s*\(""")

    @Test
    fun `no screen builds its own list of workspaces`() {
        val root = sequenceOf(File("src/main/java/ie/shoonya/yantra"), File("app/src/main/java/ie/shoonya/yantra"))
            .firstOrNull { it.isDirectory }
        assertTrue("Source root moved — this test would pass by finding nothing", root != null)

        val offenders = surfaces.flatMap { dir ->
            val here = File(root, dir)
            if (!here.isDirectory) emptyList()
            else here.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> forbidden.containsMatchIn(line) && !line.trimStart().startsWith("*") }
                    .map { (i, line) -> "${file.name}:${i + 1}  ${line.trim()}" }
            }.toList()
        }

        assertTrue(
            buildString {
                appendLine("A screen or widget is asking the registry for the workspaces.")
                appendLine("The registry holds linked repositories only — the local workspace is")
                appendLine("not in it — so this answer is missing Personal, and whatever it feeds")
                appendLine("will silently skip it, miscount it, or fail to name it.")
                appendLine()
                appendLine("Use AppContainer.openWorkspaces(), or workspaceNames() /")
                appendLine("workspaceColours() where the answer should be empty below two.")
                appendLine()
                offenders.forEach { appendLine("  $it") }
            },
            offenders.isEmpty(),
        )
    }

    /**
     * The local workspace's id is the empty string, and that is load-bearing in both directions: it
     * is what `node.workspace_id` carries for local rows, and what `firstOrNull { it.id.isEmpty() }`
     * is looking for when something asks "is Personal in this list". If one end ever changes, this
     * fails and the other end has to move with it.
     */
    @Test
    fun `the container puts the local workspace back into the list`() {
        val root = sequenceOf(File("src/main/java/ie/shoonya/yantra"), File("app/src/main/java/ie/shoonya/yantra"))
            .firstOrNull { it.isDirectory }
        assertTrue("Source root moved", root != null)
        val app = File(root, "App.kt").readText()
        // The whole function, not a fixed window: it grew a comment once and this assertion went
        // looking past the end of what it had read.
        val body = app.substringAfter("fun openWorkspaces()", "").substringBefore("\n    /**")
        assertTrue(
            "AppContainer.openWorkspaces() no longer names the local workspace by its empty id, " +
                "which is the one thing it exists to do",
            body.contains("WorkspaceEntry(") && body.contains("id = \"\"") && body.contains("isEmpty()"),
        )
    }
}
