package ie.shoonya.yantra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.shoonya.yantra.data.label.LabelPalette
import ie.shoonya.yantra.ui.theme.Yantra

/**
 * What a list looks like: its mark or its emoji, wearing its colour.
 *
 * One composable because the answer has to be the same in both places it is asked. The row on Home
 * and the list's own page header used to have nothing in common — the page drew no glyph at all —
 * and two drawings of the same choice is how a list comes to look like one thing in the list of
 * lists and another thing when you open it.
 *
 * **The colour applies differently depending on whether there is an emoji, and that is the point
 * rather than a compromise.** A drawn mark is a single-colour shape, so the colour is its tint. An
 * emoji brings its own colours and cannot be tinted at all — so the colour becomes a disc behind
 * it. Without that, picking a colour for a list that has an emoji would be accepted, stored, and
 * invisible: the app would have taken a choice and done nothing with it, which is worse than not
 * offering it.
 */
@Composable
fun ListGlyph(
    icon: String?,
    color: String?,
    smart: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val y = Yantra.colors
    val mine = swatchColour(color)

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            // A disc only when there is an emoji to sit on. Behind a mark it would be a second
            // shape competing with the drawing it is meant to colour.
            .background(if (icon != null && mine != null) mine.copy(alpha = 0.18f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            // Sized off the box rather than fixed, so the same glyph works at 34dp on a row and
            // larger on a page header without a second set of numbers to keep in step.
            Text(icon, fontSize = (size.value * 0.58f).sp)
        } else {
            YantraIcon(
                if (smart) YantraMark.SmartList else YantraMark.List,
                tint = mine ?: y.checkOutline,
                contentDescription = null,
            )
        }
    }
}

/**
 * A stored palette name as the ink to actually draw with, or null for "no colour chosen".
 *
 * Null rather than a default, because "uncoloured" is a real state and is what makes a coloured
 * list mean something — see the note on the Home row.
 */
@Composable
fun swatchColour(name: String?): Color? {
    val dark = Yantra.colors.isDark
    return name
        ?.let { n -> LabelPalette.swatches.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        ?.let { Color(LabelPalette.display(it.light, dark)) }
}
