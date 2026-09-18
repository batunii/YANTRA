package ie.shoonya.yantra

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A dialog does not belong to the thing that opened it.**
 *
 * The page's header band folds — `collapsed || (imeVisible && !titleFocused)` — and the fold is
 * real composition: `properties()` sits inside `AnimatedVisibility(visible = !collapsed)` and
 * `collapsedExtra()` inside `if (collapsed)`. Anything reachable from either **leaves composition**
 * when that flips, taking every `remember` in it.
 *
 * That is fine for a chip. It is fatal for a dialog you type into: tapping the label picker's
 * "Search or create…" raised the keyboard, the band folded, the row that owned the picker's
 * open-flag was disposed, and the dialog vanished mid-keystroke along with what had been typed. The
 * app never crashed; it just looked exactly like it had, and was reported as a crash.
 *
 * It was not one bug. When this test was first written it found **six** — the label picker, the
 * label recolour swatch, the date picker, the due sheet, the assignee sheet and the text-value
 * dialog — all one keystroke apart, because they were all hosted in the same folding row.
 *
 * So the rule, checked here rather than remembered:
 *
 * > No composable reachable from the folding band may own the flag that opens a dialog or a sheet.
 *
 * It asks the page instead ([ie.shoonya.yantra.ui.node.PillRequest]), and the page draws it
 * somewhere the fold cannot reach.
 *
 * **What is deliberately not flagged.** A `DropdownMenu` may stay: it is anchored to the chip that
 * opened it, raises no keyboard, and closing when its anchor folds away is a menu behaving
 * correctly. And a composable that *is* a sheet may own its own internals — the fault is owning the
 * flag that decides whether a dialog exists, not having state inside one.
 *
 * This reads source rather than running a UI: the failure is structural, one grep answers it for
 * every component at once, and a Compose test that drives a soft keyboard would be the flakiest
 * thing in the suite for a weaker guarantee.
 */
class FoldableDialogOwnershipTest {

    /** Slots the page composes conditionally. Add to this if the band grows another. */
    private val foldingSlots = listOf("properties", "collapsedExtra")

    private val dialogCall = Regex(
        """\b(AlertDialog|BasicAlertDialog|ModalBottomSheet|DatePickerDialog|[A-Za-z]*Dialog|[A-Za-z]*Sheet)\s*\("""
    )
    private val composableDecl = Regex("""@Composable[\s\S]{0,200}?\bfun\s+([A-Za-z0-9_]+)\s*\(""")
    private val callee = Regex("""\b([A-Z][A-Za-z0-9_]*)\s*\(""")
    private val ownedFlag = Regex("""\bvar\s+([A-Za-z0-9_]+)\s+by\s+remember[\s\S]{0,80}?mutableStateOf""")

    @Test
    fun `nothing in the folding band owns a dialog`() {
        val sources = uiSources()
        // A guard on the guard: a wrong path here would make this test pass by finding nothing,
        // which is the one way a structural check fails silently.
        assertTrue("No UI sources found — the source root moved", sources.size > 20)

        val declarations = HashMap<String, MutableList<Pair<File, Int>>>()
        for ((file, text) in sources) {
            composableDecl.findAll(text).forEach {
                declarations.getOrPut(it.groupValues[1]) { mutableListOf() } += file to it.range.first
            }
        }

        // Seed: every composable named inside a slot the band composes conditionally.
        val seeds = buildSet {
            for ((_, text) in sources) {
                for (slot in foldingSlots) {
                    Regex("""\b$slot\s*=\s*\{""").findAll(text).forEach { m ->
                        addAll(callee.findAll(braceBlock(text, m.range.first)).map { it.groupValues[1] })
                    }
                }
            }
        }
        assertTrue("No band slots found — the header stopped using $foldingSlots", seeds.isNotEmpty())

        val offenders = mutableListOf<String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            for ((file, at) in declarations[name].orEmpty()) {
                val body = functionBody(sources.getValue(file), at) ?: continue
                for (flag in ownedFlag.findAll(body).map { it.groupValues[1] }) {
                    for (guard in guardsOn(body, flag)) {
                        val opened = dialogCall.find(guard) ?: continue
                        offenders += "$name (${file.name}) — `$flag` gates ${opened.groupValues[1]}"
                    }
                }
                // Reachability is transitive: the label picker was two hops from the band.
                queue += callee.findAll(body).map { it.groupValues[1] }.filter { it in declarations }
            }
        }

        assertTrue(
            buildString {
                appendLine("A composable in the page's folding band owns a dialog.")
                appendLine("The band is disposed when the keyboard opens, so the dialog will vanish")
                appendLine("mid-keystroke and look like a crash. Hoist the flag to the screen and")
                appendLine("draw the dialog there — see PillRequest / PillDialogHost.")
                appendLine()
                offenders.distinct().sorted().forEach { appendLine("  $it") }
            },
            offenders.isEmpty(),
        )
    }

    // ---- reading Kotlin without parsing it ----

    private fun uiSources(): Map<File, String> {
        val root = sequenceOf(
            File("src/main/java/ie/shoonya/yantra/ui"),
            File("app/src/main/java/ie/shoonya/yantra/ui"),
        ).firstOrNull { it.isDirectory } ?: return emptyMap()
        return root.walkTopDown().filter { it.extension == "kt" }.associateWith { it.readText() }
    }

    /** The brace-balanced block opening at the first `{` at or after [from]. */
    private fun braceBlock(text: String, from: Int): String {
        val open = text.indexOf('{', from).takeIf { it >= 0 } ?: return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }

    /** The body of the function whose `@Composable` annotation starts at [at]. */
    private fun functionBody(text: String, at: Int): String? {
        var i = text.indexOf('(', at).takeIf { it >= 0 } ?: return null
        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            i++
        }
        val brace = text.indexOf('{', i).takeIf { it >= 0 } ?: return null
        return braceBlock(text, brace)
    }

    /** Every `if (flag)` / `flag?.let` / `if (flag != null)` block in [body]. */
    private fun guardsOn(body: String, flag: String): List<String> =
        Regex("""\bif\s*\(\s*$flag\s*[)&]|\b$flag\s*\?\.let\s*\{|\bif\s*\(\s*$flag\s*!=\s*null""")
            .findAll(body)
            .map { braceBlock(body, it.range.first) }
            .toList()
}
