package ie.napkin.supertasks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * The one text field.
 *
 * Same reasoning as [SelectChip]: a control redrawn differently on every screen is a control the
 * user has to learn twice. This is the warm tile, the hairline border, the accent caret — the field
 * the rule builder already uses, given a name so the next screen does not invent a fourth one.
 *
 * [secret] exists for exactly one caller and is not a style choice: a personal access token pasted
 * into a field is readable by anything looking at the screen, and on Android that includes the
 * screenshot the system takes of the app when it goes into the background.
 */
@Composable
fun YantraField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    enabled: Boolean = true,
) {
    val y = Yantra.colors
    val shape = RoundedCornerShape(12.dp)
    val style = TextStyle(
        fontSize = 15.sp,
        color = if (enabled) y.textPrimary else y.textMuted,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    )
    Box(
        modifier
            .fillMaxWidth()
            .background(y.tileWarm2, shape)
            .border(1.dp, y.tileBorder, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            enabled = enabled,
            // Without this the field is only as wide as the text in it, which for an empty one is a
            // sliver at the left edge. The box around it fills the width and the placeholder is
            // painted by the decoration, so it *looks* like a field across the screen while every tap
            // outside those few pixels lands on nothing — a field that cannot be typed into.
            modifier = Modifier.fillMaxWidth(),
            textStyle = style,
            cursorBrush = SolidColor(y.accent),
            visualTransformation =
                if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboard,
                // A token or a URL is one long opaque string. Autocorrect would mangle it and
                // capitalisation would break a case-sensitive slug.
                autoCorrectEnabled = false,
                imeAction = imeAction,
            ),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 15.sp, color = y.textDim, style = style.copy(color = y.textDim))
                    }
                    inner()
                }
            },
        )
    }
}
