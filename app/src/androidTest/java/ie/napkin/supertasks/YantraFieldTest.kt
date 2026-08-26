package ie.napkin.supertasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.percentOffset
import ie.napkin.supertasks.ui.components.YantraField
import ie.napkin.supertasks.ui.theme.SuperTasksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * That you can actually type in a text field.
 *
 * Which sounds too obvious to test until it is not. [YantraField] draws a full-width box, paints a
 * placeholder inside it, and puts a `BasicTextField` in the middle — and a `BasicTextField` with no
 * width modifier is only as wide as the text it holds. An empty one is a few pixels at the left
 * edge, so every field on the add-a-workspace screen looked normal and ignored being tapped.
 *
 * The test therefore taps where a finger lands rather than where the node happens to be: the usual
 * `performTextInput` focuses the field itself and would have passed throughout.
 */
class YantraFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(initial: String = "", onValue: (String) -> Unit = {}) {
        compose.setContent {
            SuperTasksTheme {
                Column(Modifier.fillMaxSize()) {
                    var text by androidx.compose.runtime.remember { mutableStateOf(initial) }
                    YantraField(
                        value = text,
                        onValue = { text = it; onValue(it) },
                        placeholder = "github.com/you/project",
                        modifier = Modifier.testTag("field"),
                    )
                }
            }
        }
    }

    @Test
    fun tappingTheFarSideOfAnEmptyFieldFocusesIt() {
        show()
        // The right-hand end, as far from the text cursor as the field goes. This is the tap that
        // did nothing at all before.
        compose.onNodeWithTag("field").performTouchInput { click(percentOffset(0.92f, 0.5f)) }
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun typingReachesTheCaller() {
        var last = ""
        show(onValue = { last = it })
        compose.onNodeWithTag("field").performTouchInput { click(percentOffset(0.92f, 0.5f)) }
        compose.onNode(hasSetTextAction()).performTextInput("batunii/yantra-tasks")
        assertEquals("batunii/yantra-tasks", last)
    }

    @Test
    fun thePlaceholderIsShownAndThenGetsOutOfTheWay() {
        show()
        compose.onNodeWithText("github.com/you/project").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("x")
        compose.onNodeWithText("github.com/you/project").assertDoesNotExist()
    }

    @Test
    fun aFieldWithTextInItIsStillTappableBeyondTheText() {
        // The bug hid here too: a short value makes a short target, so tapping past the end of
        // "yantra" to put the caret at the end missed.
        show(initial = "yantra")
        compose.onNodeWithTag("field").performTouchInput { click(percentOffset(0.92f, 0.5f)) }
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }
}
