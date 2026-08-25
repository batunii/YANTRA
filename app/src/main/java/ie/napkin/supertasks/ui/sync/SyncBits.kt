package ie.napkin.supertasks.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.ui.theme.Yantra

/**
 * The two small things every setup screen needs: a sentence said back, and a way out to the web.
 *
 * These screens are mostly prose. Half of what they do is explain what is about to happen to
 * somebody's repository, and the other half is repeating what GitHub just said — so the sentence
 * *is* the interface here in a way it is not anywhere else in this app.
 */
@Composable
internal fun Note(text: String, bad: Boolean = false, good: Boolean = false) {
    val y = Yantra.colors
    Text(
        text,
        color = if (bad) y.overdue else y.textSecondary,
        fontSize = 12.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    bad -> y.overdueChipBg
                    good -> y.successChipBg
                    else -> y.neutralChipBg
                },
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** Leaves the app. Underlined because that is the one convention every user already knows. */
@Composable
internal fun Link(label: String, onClick: () -> Unit) {
    val y = Yantra.colors
    Text(
        label,
        color = y.accentText,
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}
