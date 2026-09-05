package ie.napkin.supertasks.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide the window is, in the only two answers this app has a different layout for.
 *
 * Deliberately a width test and not a device test. A tablet in split screen is a phone-shaped
 * window, a phone unfolded is not, and neither of those is answerable by asking what the hardware
 * is — the layout has to follow the glass it actually has.
 *
 * Two values because there are two layouts. Material's three-way size class would let a middle case
 * exist that nothing on this side of the app knows how to draw.
 */
enum class PaneWidth {
    /** One pane. Phones, and any window narrow enough to be one. */
    SINGLE,

    /** Room for a rail beside the page. */
    WIDE;

    val isWide: Boolean get() = this == WIDE
}

/**
 * The breakpoint, at 840dp.
 *
 * That is Material's own expanded threshold and it is close to right for the wrong reason: what
 * actually matters here is whether a 340dp rail can sit beside a page and still leave the page a
 * readable measure. 340 + 720 is 1060, more than the threshold — so at the low end of WIDE the page
 * gets less than its ideal measure but still far more than the rail costs it, and the alternative
 * is a single column with 500dp of empty margin either side.
 */
private val WIDE_AT: Dp = 840.dp

/**
 * The margin every screen keeps down its sides, and so the width every list of tasks shares.
 *
 * One number because a task row that is 14dp from the edge on a list, 18dp on Home and 20dp on the
 * stats page is the same row drawn three widths, and walking between those screens makes the
 * content appear to breathe. `DESIGN.md` and the handoff both settle on 22dp for the phone; this is
 * that number, in one place, so the next screen does not pick a fourth.
 *
 * The document on a task's page is deliberately not this: its text sits inside a drag gutter that
 * belongs to the blocks, and that gutter is the margin. See `NodePageScreen`.
 */
val PAGE_MARGIN: Dp = 22.dp

/** The rail: wide enough for a task row with its glyph and two lines, and no wider. */
val RAIL_WIDTH: Dp = 340.dp

/**
 * The measure a column of prose is allowed.
 *
 * **The column stays a column.** Text set across the full width of a tablet is text nobody finishes
 * a paragraph of — the eye loses the line on the way back. So the page is centred at this width and
 * the rest of the glass is margin, which looks like waste and is the entire reason the page is
 * readable.
 */
val PAGE_MEASURE: Dp = 720.dp

@Composable
fun paneWidth(): PaneWidth =
    if (LocalConfiguration.current.screenWidthDp.dp >= WIDE_AT) PaneWidth.WIDE else PaneWidth.SINGLE
