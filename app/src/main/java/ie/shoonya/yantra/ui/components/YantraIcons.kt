package ie.shoonya.yantra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ie.shoonya.yantra.ui.theme.Yantra
import ie.shoonya.yantra.ui.components.YantraIcon
import ie.shoonya.yantra.ui.components.YantraMark

/**
 * The functional icon set.
 *
 * Every mark is drawn in the same 28-unit space as [bhupuraPath] at the same 1.6-unit stroke, so a
 * row of icons and a task glyph beside them are demonstrably one family. This file is the single
 * source of truth: nothing else in the app may redefine one of these marks locally, and nothing
 * here carries a colour.
 *
 * **Monochrome by construction.** A mark is a path; its ink arrives as [tint] at the call site.
 * Only three tints sit on the accent layer and move with the accent picker — the play key while a
 * session runs, the ring that marks the timed card, and the focus arc. [Priority] takes crimson or
 * amber from the task's own data and [Delete] takes crimson while pressed; neither is the effort
 * layer, so neither ever wears the accent.
 *
 * **The two composed glyphs are not here.** The task glyph and the focus glyph carry two inks at
 * once because their state lives inside the mark; they stay in YantraCheckbox and YantraFocus.
 *
 * **Fill is the exception, not the default.** There are exactly two, and both are written down
 * here so a third cannot arrive quietly.
 *
 * 1. **The bindu.** [More] is three of them and [RingLive] carries one; the bindu is a filled point
 *    everywhere in this language, so drawing it hollow would make it a different mark.
 * 2. **The transport keys.** [Play] and [Pause] are solid. ICONS.md §4 left this open and asked for
 *    the reason to be recorded if it was ever taken, so: the play key is the *only* control in the
 *    now player, and a hairline triangle at the one place the bar asks to be pressed read as
 *    decoration beside its own filled bindu counter. Weight here is the difference between a bar
 *    you read and a bar you use. It is the control, not the mark, that earns this — nothing else
 *    in the set may fill on the grounds of being important.
 *
 * Note what is *not* the exception: [Add], which is the primary action on two board screens and
 * stays hairline, because it sits on a filled key that is already carrying the weight.
 */
enum class YantraMark {
    Task,
    Focus,
    SmartList,
    List,
    Properties,
    Priority,
    Play,
    Pause,
    Add,
    More,
    Ink,
    Image,
    Delete,
    Calendar,
    Stats,
    Settings,
    /** A task you have taken up. Used by the now player's deck counter and the strata. */
    Ring,
    /** The same ring with the centre point in it: the card the clock is on. */
    RingLive,
    /** This tap leaves Yantra. Replaces every use of Icons.AutoMirrored.Filled.OpenInNew. */
    OpenOut,
    Back,
    Forward,
    /** Up and down. The same wedge as [Back]; a chevron is one drawing at four rotations. */
    Up,
    Down,

    // ---- The rest of the app's marks — ICONS.md §7, drawn rather than borrowed.
    //
    // §7 sent everything outside the first twenty-two to Material Symbols Sharp 300, on the grounds
    // that its weight "lands within a hair" of the 1.6/28 ratio. Within a hair is still not the same
    // stroke, and it is exactly the difference you see when two marks sit in one row — which is what
    // a half-migrated block bar demonstrated: a hairline Bullet beside a filled Heading.
    //
    // Drawing them keeps one stroke, one space and one component, and costs no font in the APK.

    /** A tick. Confirmation and the "this is chosen" state on a chip. */
    Check,
    /** A cross. Dismiss, and remove-this-one. Never destructive — that is [Delete]. */
    Close,
    /** Transport, with [Play] and [Pause]; filled for the same reason they are. */
    Stop,
    Undo,
    Redo,
    /** A reminder that will speak. A bell, not a clock: the point is that it comes to you. */
    Alarm,
    /**
     * A time.
     *
     * One mark for both a due time and a deadline. The two chips already say which they are in
     * words, and two clock faces differing by the angle of a hand is a distinction nobody reads.
     */
    Clock,
    /** A label. The tag shape, matching the `#name` form a label takes in the file. */
    Label,
    /** A heading block. A rule with a taller stem — the letterform argument, without a letter. */
    Heading,
    /** A numbered list: [List]'s rules, with the ordinal stroke before them. */
    Numbered,
    /** The grip on a block you can move. Two short rules; the same pair the kit uses. */
    Drag,
    /** Copy — one card behind another. */
    Copy,
    /** Send. The quick-add bar's key. */
    Send,
    /** Try again. An open ring with a head on it. */
    Refresh,
    /** This repeats — ICONS.md §8 left it undrawn. Two arcs chasing each other, not a cycle arrow. */
    Repeat,
    Expand,
    Collapse,
    IndentIn,
    IndentOut,
    /** Shapes, in the ink kit: a square and a circle overlapping. */
    Shapes,
    /**
     * Somebody. A bindu for the head over an arc for the shoulders.
     *
     * The first figure in this language, which is why it is drawn from the two forms already in it
     * rather than as a silhouette. [PersonOff] is the same figure struck through — the stroke runs
     * corner to corner so it reads as negation and not as part of the body.
     */
    Person,
    PersonOff,
}

object YantraIcons {
    /** The design space every path is written in. */
    const val SPACE = 28f

    /** Stroke width in design units. 1.6 / 28 is the bhupura's ratio. */
    const val STROKE = 1.6f

    /** The only three sizes. In a chip, in a row or circle, on a key. */
    val Small: Dp = 16.dp
    val Medium: Dp = 20.dp
    val Large: Dp = 24.dp
}

@Composable
fun YantraIcon(
    mark: YantraMark,
    modifier: Modifier = Modifier,
    size: Dp = YantraIcons.Medium,
    tint: Color = Yantra.colors.checkOutline,
    contentDescription: String? = null,
) {
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier.then(semantics).size(size)) {
        drawMark(mark, tint)
    }
}

/**
 * For call sites that already own a Canvas — a row background, a block, a glyph composition.
 * Draws into the current [DrawScope] at its full size.
 */
fun DrawScope.drawMark(mark: YantraMark, color: Color) {
    val u = size.minDimension / YantraIcons.SPACE
    val stroke = Stroke(YantraIcons.STROKE * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
        color = color,
        start = Offset(x1 * u, y1 * u),
        end = Offset(x2 * u, y2 * u),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )

    fun dot(cx: Float, cy: Float, r: Float) =
        drawCircle(color, r * u, Offset(cx * u, cy * u))

    fun ring(cx: Float, cy: Float, r: Float) =
        drawCircle(color, r * u, Offset(cx * u, cy * u), style = stroke)

    fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f, fill: Boolean = false) {
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = x * u, top = y * u, right = (x + w) * u, bottom = (y + h) * u,
                    radiusX = radius * u, radiusY = radius * u,
                )
            )
        }
        if (fill) drawPath(path, color) else drawPath(path, color, style = stroke)
    }

    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = stroke)

    fun Path.at(x: Float, y: Float) = moveTo(x * u, y * u)
    fun Path.to(x: Float, y: Float) = lineTo(x * u, y * u)

    when (mark) {
        YantraMark.Task -> drawPath(bhupuraPath(size.minDimension), color, style = stroke)

        YantraMark.Focus -> {
            ring(14f, 14f, 9f)
            // The elapsed arc, drawn rather than pathed. `arcTo` on a Rect needs a subpath already
            // started and inherits its current point, which is one more thing to get wrong than a
            // quarter turn is worth; `drawArc` with `useCenter = false` says exactly this and no
            // more. A quarter from twelve o'clock, because the mark is a ledger showing that some
            // of the circle is spent — not a clock hand, and not a progress bar to be read off.
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(5f * u, 5f * u),
                size = Size(18f * u, 18f * u),
                style = stroke,
            )
            dot(14f, 14f, 2.2f)
        }

        YantraMark.SmartList -> {
            line(4f, 8f, 24f, 8f)
            line(8f, 14f, 20f, 14f)
            line(12f, 20f, 16f, 20f)
            dot(14f, 25f, 1.6f)
        }

        YantraMark.List -> {
            line(5f, 8f, 23f, 8f)
            line(5f, 14f, 23f, 14f)
            line(5f, 20f, 23f, 20f)
        }

        YantraMark.Properties -> {
            box(3f, 7f, 22f, 6f, radius = 3f)
            box(3f, 16f, 14f, 6f, radius = 3f)
        }

        YantraMark.Priority -> {
            line(7f, 4f, 7f, 25f)
            path { at(8f, 5f); to(22f, 5f); to(18f, 10f); to(22f, 15f); to(8f, 15f) }
        }

        // Solid — the second and last fill exception; see the note in the file header.
        YantraMark.Play -> drawPath(
            Path().apply {
                moveTo(9f * u, 6f * u); lineTo(23f * u, 14f * u); lineTo(9f * u, 22f * u); close()
            },
            color,
        )

        YantraMark.Pause -> {
            // Bars rather than two fat strokes: a filled rectangle is a shape with a width, where a
            // thickened line is a stroke pretending to be one, and the two do not scale alike.
            box(10f, 7f, 3.2f, 14f, radius = 0.8f, fill = true)
            box(16.8f, 7f, 3.2f, 14f, radius = 0.8f, fill = true)
        }

        YantraMark.Add -> {
            line(14f, 5f, 14f, 23f)
            line(5f, 14f, 23f, 14f)
        }

        YantraMark.More -> {
            dot(14f, 6f, 1.9f)
            dot(14f, 14f, 1.9f)
            dot(14f, 22f, 1.9f)
        }

        YantraMark.Ink -> {
            path { at(19f, 4f); to(24f, 9f); to(11f, 22f); to(5f, 24f); to(7f, 18f); close() }
            line(7f, 18f, 11f, 22f)
        }

        YantraMark.Image -> {
            box(4f, 6f, 20f, 17f, radius = 3f)
            path { at(6f, 20f); to(12f, 13f); to(16f, 17f); to(19f, 14f); to(22f, 17f) }
            dot(10f, 11f, 1.5f)
        }

        YantraMark.Delete -> {
            line(6f, 9f, 22f, 9f)
            line(6f, 19f, 22f, 19f)
            line(4f, 23f, 24f, 5f)
        }

        YantraMark.Calendar -> {
            box(4f, 6f, 20f, 18f, radius = 3f)
            line(10f, 3f, 10f, 8f)
            line(18f, 3f, 18f, 8f)
            line(4f, 12f, 24f, 12f)
            dot(14f, 18f, 2f)
        }

        YantraMark.Stats -> {
            box(6f, 16f, 4.5f, 8f, radius = 2f)
            box(12f, 11f, 4.5f, 13f, radius = 2f)
            box(18f, 6f, 4.5f, 18f, radius = 2f)
        }

        YantraMark.Settings -> {
            line(5f, 8f, 23f, 8f)
            line(5f, 14f, 23f, 14f)
            line(5f, 20f, 23f, 20f)
            dot(18f, 8f, 2.4f)
            dot(10f, 14f, 2.4f)
            dot(19f, 20f, 2.4f)
        }

        YantraMark.Ring -> ring(14f, 14f, 9f)

        YantraMark.RingLive -> {
            ring(14f, 14f, 9f)
            dot(14f, 14f, 2.9f)
        }

        YantraMark.OpenOut -> {
            path { at(14f, 5f); to(5f, 5f); to(5f, 23f); to(23f, 23f); to(23f, 14f) }
            line(14f, 14f, 24f, 4f)
            path { at(18f, 4f); to(24f, 4f); to(24f, 10f) }
        }

        YantraMark.Back -> path { at(17f, 5f); to(8f, 14f); to(17f, 23f) }

        YantraMark.Forward -> path { at(11f, 5f); to(20f, 14f); to(11f, 23f) }

        YantraMark.Up -> path { at(5f, 18f); to(14f, 9f); to(23f, 18f) }

        YantraMark.Down -> path { at(5f, 10f); to(14f, 19f); to(23f, 10f) }

        YantraMark.Check -> path { at(6f, 15f); to(11.5f, 20.5f); to(22f, 8f) }

        YantraMark.Close -> { line(7f, 7f, 21f, 21f); line(21f, 7f, 7f, 21f) }

        // Filled, with Play and Pause — see the fill note in the file header.
        YantraMark.Stop -> box(9f, 9f, 10f, 10f, radius = 1.2f, fill = true)

        // An arrow turning back on itself: the arc carries the travel, the head says which way.
        YantraMark.Undo -> {
            path { at(9f, 10f); to(5f, 14f); to(9f, 18f) }
            drawArc(
                color = color, startAngle = 180f, sweepAngle = -150f, useCenter = false,
                topLeft = Offset(5f * u, 6f * u), size = Size(18f * u, 16f * u), style = stroke,
            )
        }

        YantraMark.Redo -> {
            path { at(19f, 10f); to(23f, 14f); to(19f, 18f) }
            drawArc(
                color = color, startAngle = 0f, sweepAngle = 150f, useCenter = false,
                topLeft = Offset(5f * u, 6f * u), size = Size(18f * u, 16f * u), style = stroke,
            )
        }

        // A bell. Not a clock: a reminder is the one that comes to you.
        YantraMark.Alarm -> {
            path {
                at(8f, 18f)
                to(8f, 13f)
                cubicTo(8f * u, 9f * u, 10.5f * u, 7f * u, 14f * u, 7f * u)
                cubicTo(17.5f * u, 7f * u, 20f * u, 9f * u, 20f * u, 13f * u)
                to(20f, 18f)
                close()
            }
            line(6f, 18f, 22f, 18f)
            line(12.5f, 21.5f, 15.5f, 21.5f)
        }

        YantraMark.Clock -> {
            ring(14f, 14f, 9f)
            line(14f, 8.5f, 14f, 14f)
            line(14f, 14f, 18f, 16.5f)
        }

        // The tag the `#name` form names. The hole is a bindu, filled like every other.
        YantraMark.Label -> {
            path {
                at(14f, 5f); to(23f, 5f); to(23f, 14f); to(12f, 25f); to(3f, 16f); close()
            }
            dot(19f, 9f, 1.5f)
        }

        YantraMark.Heading -> {
            line(6f, 6f, 6f, 20f)
            line(14f, 6f, 14f, 20f)
            line(6f, 13f, 14f, 13f)
            line(18f, 13f, 23f, 13f)
        }

        YantraMark.Numbered -> {
            line(12f, 8f, 23f, 8f)
            line(12f, 14f, 23f, 14f)
            line(12f, 20f, 23f, 20f)
            line(6f, 6f, 6f, 10f)
            line(4.5f, 7.5f, 6f, 6f)
            path { at(4.5f, 12.5f); to(7.5f, 12.5f); to(4.5f, 16f); to(7.5f, 16f) }
            path { at(4.5f, 18.5f); to(7.5f, 18.5f); to(7.5f, 21.5f); to(4.5f, 21.5f) }
        }

        YantraMark.Drag -> { line(9f, 11f, 19f, 11f); line(9f, 17f, 19f, 17f) }

        YantraMark.Copy -> {
            box(4f, 4f, 16f, 16f, radius = 2f)
            box(9f, 9f, 16f, 16f, radius = 2f)
        }

        YantraMark.Send -> {
            path { at(4f, 14f); to(24f, 5f); to(15f, 24f); to(12.5f, 15.5f); close() }
        }

        YantraMark.Refresh -> {
            drawArc(
                color = color, startAngle = 60f, sweepAngle = 280f, useCenter = false,
                topLeft = Offset(5f * u, 5f * u), size = Size(18f * u, 18f * u), style = stroke,
            )
            path { at(17f, 4f); to(22.5f, 8.5f); to(17f, 11.5f) }
        }

        // Two arcs chasing each other. Not a closed cycle: what repeats is the next one, not a loop
        // you are inside.
        YantraMark.Repeat -> {
            drawArc(
                color = color, startAngle = 150f, sweepAngle = 150f, useCenter = false,
                topLeft = Offset(5f * u, 6f * u), size = Size(18f * u, 16f * u), style = stroke,
            )
            path { at(18f, 4.5f); to(22.5f, 8f); to(17.5f, 10.5f) }
            drawArc(
                color = color, startAngle = -30f, sweepAngle = 150f, useCenter = false,
                topLeft = Offset(5f * u, 6f * u), size = Size(18f * u, 16f * u), style = stroke,
            )
            path { at(10f, 23.5f); to(5.5f, 20f); to(10.5f, 17.5f) }
        }

        YantraMark.Expand -> {
            path { at(12f, 5f); to(23f, 5f); to(23f, 16f) }
            path { at(16f, 23f); to(5f, 23f); to(5f, 12f) }
            line(23f, 5f, 15f, 13f)
            line(5f, 23f, 13f, 15f)
        }

        YantraMark.Collapse -> {
            path { at(23f, 12f); to(16f, 12f); to(16f, 5f) }
            path { at(5f, 16f); to(12f, 16f); to(12f, 23f) }
            line(16f, 12f, 23f, 5f)
            line(12f, 16f, 5f, 23f)
        }

        YantraMark.IndentIn -> {
            line(11f, 7f, 23f, 7f)
            line(11f, 14f, 23f, 14f)
            line(11f, 21f, 23f, 21f)
            path { at(4f, 10f); to(8f, 14f); to(4f, 18f) }
        }

        YantraMark.IndentOut -> {
            line(11f, 7f, 23f, 7f)
            line(11f, 14f, 23f, 14f)
            line(11f, 21f, 23f, 21f)
            path { at(8f, 10f); to(4f, 14f); to(8f, 18f) }
        }

        YantraMark.Shapes -> {
            box(4f, 4f, 13f, 13f, radius = 1.5f)
            ring(18f, 18f, 6f)
        }

        // The head is a bindu, the shoulders an arc — the two forms this language already has.
        YantraMark.Person -> {
            dot(14f, 9.5f, 3.6f)
            drawArc(
                color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(6f * u, 15f * u), size = Size(16f * u, 16f * u), style = stroke,
            )
        }

        YantraMark.PersonOff -> {
            dot(14f, 9.5f, 3.6f)
            drawArc(
                color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(6f * u, 15f * u), size = Size(16f * u, 16f * u), style = stroke,
            )
            // Corner to corner, so it reads as negation rather than as part of the figure.
            line(5f, 5f, 23f, 23f)
        }
    }
}
