package ie.shoonya.yantra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ie.shoonya.yantra.ui.appContainer
import ie.shoonya.yantra.ui.theme.Yantra
import kotlinx.coroutines.delay

/**
 * A quiet line saying the app is talking to GitHub — and nothing at all when it is not.
 *
 * **Why it is here.** Every network call this app makes it makes on its own initiative: a
 * structural change pushes at once, a burst of edits waits for you to stop typing, a token renews
 * itself, a background pass runs when Android permits. None of it was visible, so "is it saving
 * this?" had no answer on screen, and a slow push looked the same as a broken one and the same as
 * nothing happening.
 *
 * **It never reports failure.** A pass that fails is reported where a person can act on it — the
 * pull-to-sync message, the sync section in Settings — and a red mark in the chrome of every screen
 * over a push that will retry in ninety seconds is alarm without a remedy. This says *something is
 * happening*, which is the question it exists to answer, and stops there.
 */
@Composable
fun NetworkPulse(modifier: Modifier = Modifier) {
    val container = appContainer()
    val what by container.network.current.collectAsStateWithLifecycle()

    // Held briefly after the work ends.
    //
    // A sync with nothing to send finishes in milliseconds, and a label that appears and vanishes
    // inside two frames reads as a flicker in the interface rather than as a thing that happened.
    // Worse, it draws the eye without ever being readable. So what is shown lags what is true, on
    // purpose, and only in the direction of staying longer.
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(what) {
        if (what != null) {
            shown = what
        } else if (shown != null) {
            delay(MIN_VISIBLE_MS)
            shown = null
        }
    }

    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
        modifier = modifier,
    ) {
        val y = Yantra.colors
        // Breathing rather than spinning. A spinner is the shape of something you are waiting for,
        // and you are not waiting for this — it is work the app took on by itself and you are free
        // to keep typing through it.
        val pulse = rememberInfiniteTransition(label = "network")
        val glow by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "glow",
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(y.neutralChipBg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).alpha(glow).clip(CircleShape).background(y.accent))
            Spacer(Modifier.width(6.dp))
            Text(
                shown.orEmpty(),
                color = y.textMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.W600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Long enough to read three words, short enough not to outstay a sync that is already done. */
private const val MIN_VISIBLE_MS = 900L
