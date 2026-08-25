package ie.napkin.supertasks.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ie.napkin.supertasks.data.sync.SyncResult
import ie.napkin.supertasks.ui.appContainer
import ie.napkin.supertasks.ui.theme.Yantra
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pull down to sync.
 *
 * Sync is otherwise something the app decides: a structural change goes out at once, a burst of
 * edits waits for you to stop, and a background pass runs every fifteen minutes *if Android feels
 * like it* — which on Samsung it frequently does not. This is the gesture for the moment someone
 * wants to know **now** whether the other device's work has arrived, and it is the only sync in the
 * app whose timing is entirely the user's.
 *
 * Two rules it keeps, both of which are about not lying:
 *
 * The spinner tracks the real pass — commit, fetch, rebase, resolve, push, reindex — rather than a
 * timer. It ends when the work ends. The one concession is [MIN_SPIN_MS]: a sync with nothing to do
 * finishes in a few milliseconds, and a spinner that appears and vanishes within one frame reads as
 * a broken gesture rather than a completed one.
 *
 * And it says something when there is something to say. Silence is the right answer to "you pulled,
 * nothing had changed" — the retracting spinner is that answer — but never to a failure, and never
 * to the case that would otherwise be quietly misleading: a pull on a device where no workspace has
 * a remote at all, which succeeds instantly having synced with nobody.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToSync(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val y = Yantra.colors

    var syncing by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val state = rememberPullToRefreshState()

    // The note is transient: it belongs to the pull that produced it, not to the screen.
    LaunchedEffect(said) {
        if (said != null) {
            delay(SAY_MS)
            said = null
        }
    }

    PullToRefreshBox(
        isRefreshing = syncing,
        state = state,
        onRefresh = {
            if (!syncing) {
                syncing = true
                said = null
                scope.launch {
                    val startedAt = System.currentTimeMillis()
                    val results = container.syncAwait("pulled down to sync")
                    val remote = container.anyRemote()
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed < MIN_SPIN_MS) delay(MIN_SPIN_MS - elapsed)
                    said = summarise(results, remote)
                    syncing = false
                }
            }
        },
        modifier = modifier,
        // Only the pull is gated, never the content: a screen that stopped scrolling because sync
        // is unavailable would be a much worse trade than a gesture that does nothing.
        indicator = {
            if (enabled) {
                PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = syncing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = y.cardBg,
                    color = y.accent,
                )
            }
        },
    ) {
        content()

        AnimatedVisibility(
            visible = said != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // A pill that sizes to its sentence, not a full-width bar. It floats over the list, and
            // a bar spanning the screen reads as a row that has gone wrong rather than as a message
            // laid on top of one.
            Text(
                said.orEmpty(),
                color = y.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 10.dp)
                    .background(y.band, RoundedCornerShape(24.dp))
                    .border(1.dp, y.tileBorder, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * One sentence for what a pass did, or null when the retracting spinner already said it.
 *
 * Errors first and unconditionally. A workspace that cannot reach its remote is the case where the
 * user most needs to know their tasks are only on this phone, and it is also the case where the
 * gesture otherwise looks identical to success.
 */
private fun summarise(results: List<SyncResult>, anyRemote: Boolean): String? {
    val failed = results.firstOrNull { it.error != null }
    if (failed != null) return "Not synced: ${failed.error}"

    // Nowhere to sync to. Succeeding silently here would read as "synced with GitHub", which is the
    // one thing that definitely did not happen.
    if (!anyRemote) return "No workspace is connected to GitHub yet"

    val conflicts = results.sumOf { it.conflicts.size }
    if (conflicts > 0) {
        return "Synced · ${conflicts} change${if (conflicts == 1) "" else "s"} merged from another device"
    }
    if (results.any { it.pulled }) return "Synced · new work arrived"
    // Committed-and-pushed, or nothing to do. Either way the list in front of them is now right,
    // and a banner saying so on every pull would be noise.
    return null
}

/**
 * The floor on how long the spinner is visible.
 *
 * Not padding for its own sake: a local-only sync takes a few milliseconds, and an indicator that
 * appears and disappears inside one frame looks like the gesture failed rather than finished.
 */
private const val MIN_SPIN_MS = 450L

/** How long a note stays before it fades. Long enough to read, short enough not to be dismissed. */
private const val SAY_MS = 3_500L
