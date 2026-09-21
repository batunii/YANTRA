package ie.shoonya.yantra

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A placed widget is remembered by a class name, so the class name may not move.**
 *
 * `GlanceAppWidgetManager` keeps a DataStore of receiver → `GlanceAppWidget` class name, written
 * the first time a receiver updates and read back by `updateAll()` to decide which placed widgets a
 * class owns. That file survives an app update. R8 renames the classes and is under no obligation
 * to pick the same names twice.
 *
 * Adding one widget was enough to break two. On the update that introduced the calendar widget,
 * `FocusWidget` was given the short name the calendar widget had held in the build before it — and
 * the stale entry still pointed at `CalendarWidgetReceiver`. The launcher drew the focus timer,
 * "Start 25m", inside a widget that was, and remained, bound to the calendar. `dumpsys appwidget`
 * said the binding was correct, because it was: the wrong content had been pushed into the right
 * widget.
 *
 * It is invisible in debug, which is not minified, and it only bites on the build *after* a set of
 * widgets changes — so the one variant it can be found in is the one nobody runs while developing,
 * on the one occasion nobody repeats. Hence a test rather than a memory.
 *
 * Checked by reading the rules file for the same reason [FoldableDialogOwnershipTest] reads source:
 * the thing that must be true is a line in a configuration file, and the alternative — building a
 * release, mapping it, updating, and looking at a launcher — is not a unit test.
 */
class GlanceWidgetNamesTest {

    @Test
    fun `R8 may not rename a GlanceAppWidget`() {
        val rules = sequenceOf(File("proguard-rules.pro"), File("app/proguard-rules.pro"))
            .firstOrNull { it.isFile }
        assertTrue("proguard-rules.pro not found — has the module moved?", rules != null)

        val text = rules!!.readText().lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        // -keep also keeps the name, so either spelling is a correct answer; -keepnames is the
        // narrower one and what the file actually says.
        val keeps = Regex("""-keep(names|classeswithmembernames)?\b[^\n]*\bextends\s+androidx\.glance\.appwidget\.GlanceAppWidget\b(?!Receiver)""")
        assertTrue(
            """
            Nothing keeps the names of this app's GlanceAppWidget classes.

            Glance stores the obfuscated class name of a widget in a DataStore that outlives the
            install, and looks placed widgets up by it. Let R8 rename them and an update can hand
            one widget's stored name to another — the launcher then draws the wrong widget's
            content into a correctly-bound slot, which reads as the new widget being broken.

            Add to app/proguard-rules.pro:
              -keepnames class * extends androidx.glance.appwidget.GlanceAppWidget
            """.trimIndent(),
            keeps.containsMatchIn(text),
        )
    }
}
