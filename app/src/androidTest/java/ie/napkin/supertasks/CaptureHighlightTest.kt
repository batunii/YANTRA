package ie.napkin.supertasks

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import ie.napkin.supertasks.data.db.LabelEntity
import ie.napkin.supertasks.data.label.LabelPalette
import ie.napkin.supertasks.ui.components.chipStyleFor
import ie.napkin.supertasks.ui.components.rememberCaptureHighlight
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The tint in the field has to be the colour the chip will actually be.
 *
 * That is the whole claim of the feature — typing `#home` shows you the chip you are about to make —
 * and it is the kind of claim that quietly stops being true. Both sides resolve through
 * `chipStyleFor`, so this asserts they agree rather than asserting a particular colour, which would
 * only pin the palette rather than the promise.
 */
class CaptureHighlightTest {

    @get:Rule
    val compose = createComposeRule()

    private val existing = LabelEntity(
        id = ":label:work", workspaceId = "", name = "work",
        color = LabelPalette.swatches[3].light, createdAt = 0, updatedAt = 0,
    )
    private val neutral = LabelEntity(
        id = ":label:plain", workspaceId = "", name = "plain",
        color = null, createdAt = 0, updatedAt = 0,
    )

    /**
     * One composition for the whole class: `setContent` may be called only once per test, so the
     * input is driven from state and the transformation recomputed rather than rebuilt.
     */
    private val input = mutableStateOf("")
    private val known = mutableStateOf<List<LabelEntity>>(emptyList())
    private var tints: List<Color> = emptyList()
    private var chips: Map<String, Color> = emptyMap()

    private fun given(text: String, labels: List<LabelEntity>): List<Color> {
        compose.runOnUiThread {
            input.value = text
            known.value = labels
        }
        compose.waitForIdle()
        return tints
    }

    @Before
    fun setUp() {
        compose.setContent {
            SuperTasksTheme {
                Column {
                    val t: VisualTransformation = rememberCaptureHighlight(known.value)
                    tints = t.filter(AnnotatedString(input.value)).text.spanStyles.map { it.item.color }
                    // What the chips themselves would use, resolved the way the rows resolve it.
                    chips = known.value.associate { l ->
                        l.name to chipStyleFor(l.color?.let { Color(it) }).text
                    } + ("new" to chipStyleFor(Color(LabelPalette.defaultFor("brandnew"))).text)
                }
            }
        }
    }

    @Test
    fun anExistingLabelIsTintedItsOwnColour() {
        val tints = given("ship it #work", listOf(existing))
        assertEquals(1, tints.size)
        assertEquals("the field disagreed with the chip", chips["work"], tints.single())
    }

    @Test
    fun anExistingNeutralLabelStaysNeutral() {
        // A label deliberately left uncoloured must not be shown wearing the colour it would have
        // been given — that would promise a change the user already declined.
        val tints = given("ship it #plain", listOf(neutral))
        assertEquals(chips["plain"], tints.single())
    }

    @Test
    fun anUnknownLabelIsTintedTheColourItIsAboutToGet() {
        // Knowable before the label exists, because the default is derived from the name.
        val tints = given("ship it #brandnew", emptyList())
        assertEquals(chips["new"], tints.single())
    }

    @Test
    fun labelMatchingIgnoresCase() {
        // `#Work` and `#work` are one tag, as LabelRepository.getOrCreate decides, so they must not
        // be shown as two different colours.
        assertEquals(given("a #work", listOf(existing)), given("a #Work", listOf(existing)))
    }

    @Test
    fun aDateAndALabelAreDifferentColours() {
        val tints = given("ship it tomorrow #work", listOf(existing))
        assertEquals(2, tints.size)
        assertNotEquals("a date and a label read the same", tints[0], tints[1])
    }

    @Test
    fun twoDifferentNewLabelsGetDifferentColours() {
        // The palette derives from the name so a handful of new tags come out varied rather than all
        // landing on one swatch — worth holding, since it is what makes the preview informative.
        val a = given("x #alpha", emptyList()).single()
        val b = given("x #omega", emptyList()).single()
        assertNotEquals(a, b)
    }

    @Test
    fun plainTypingIsNotTintedAtAll() {
        assertTrue(given("think about the roadmap", emptyList()).isEmpty())
    }
}
