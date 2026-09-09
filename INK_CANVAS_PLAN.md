# Ink canvas — coordinates, zoom, and aiming a selection

Three complaints, one shared cause and two of their own:

1. **Ink drawn on a tablet lands in the wrong place on a phone.**
2. **The canvas cannot zoom.**
3. **A small loop selects nothing, so it has to be made bigger — and then it catches too much.**

(1) and (2) are the same defect seen from two sides: the canvas has no coordinate space of its own,
so there is nothing for a camera to point at and nothing for a second device to agree with. (3) is
independent and is the cheapest of the three to fix.

---

## A. Why ink does not port between screens

`ARCHITECTURE.md` §A says ink "does the opposite and does it correctly — strokes are `.ink` sidecars
inside the workspace, committed with everything else." The *bytes* do sync correctly. The **units do
not exist**, so what syncs is a set of numbers whose meaning was left behind on the device that drew
them.

### The document space is device pixels

`StrokeCodec` writes a header of `{family, color, size, epsilon}` and then the raw
`StrokeInputBatch` (`StrokeCodec.kt:44`). There is no unit, no reference width, and no format
version. The numbers in the batch are whatever the view handed over, and the view hands over raw
`MotionEvent` pixels: `toDocumentSpace` shifts `y` by the scroll offset and passes `x` through
untouched (`InkCanvas.kt:537`).

So a stroke's `x` runs from 0 to the drawing device's width in physical pixels.

### The page is sized from the *viewing* device

```kotlin
private val pageHeight: Float get() = if (width > 0) width * PAGE_RATIO else 0f   // InkCanvas.kt:120
```

Page height is derived from the current view's width. The same document therefore has different
page boundaries on every device it is opened on.

### What follows, concretely

| Symptom | Mechanism |
|---|---|
| Ink runs off the right edge on the narrower device | A stroke at `x = 1800` (2000 px tablet) is outside a 1080 px phone's view — and there is **no horizontal pan**: `focusY`, `scrollDocTo` and `maxScroll` are y-only. The ink is not merely misplaced, it is unreachable, un-erasable and un-selectable. |
| Page numbers and separators disagree across devices | `contentPages()` divides document extent by a `pageHeight` computed from the local width. "Page 3 of 7" is a different 3 and a different 7 per device. |
| Previews are scaled wrong on any device that didn't draw them | `InkPreview` sets `pageCropDocWidth = displayMetrics.widthPixels` (`StrokeViews.kt:135`) — it *assumes* the document width equals this screen's width. |
| The pen is a different physical thickness per device | `PenSlot("PEN", …, 2.6f)` goes straight into `Brush.size`, which is in stroke-space units — i.e. px. Compare `eraserRadius = eraserSize.dp.toPx()`, which *is* density-converted. At 3× density a 2.6 px pen is 0.87 dp; at 2× it is 1.3 dp. |
| A shape gesture snaps on one device and not another | `ShapeRecognizer` is otherwise all ratios, but carries one absolute: `if (diag < 40f) return null` (`ShapeRecognizer.kt:90`). 40 px is a different gesture on each screen. |

**This is a file-format defect, not a rendering one.** No amount of transform work at read time fixes
it, because the information needed to scale a v1 stroke correctly — the width it was drawn at — was
never written down.

## B. Why there is no zoom

The camera is a translation, by construction, and its inverse is open-coded at every call site.

```kotlin
transform.reset()
transform.postTranslate(0f, -scrollOffset)   // InkCanvas.kt:692 — this is the whole camera
```

and the inverse, "add `scrollOffset` to y", appears separately in `lassoPoint`, `eraseAt`,
`commitShape`, `insideSelection` and `toDocumentSpace`. Five places each know the mapping; a scale
term would have to be threaded through all five and through the two callbacks that leak view
coordinates outward:

- `onLassoSelection(ids, cx, b - scrollOffset)` converts doc→view at the call site, so the selection
  bar's placement is wired to the assumption that the only transform is a y translation.
- `onMoveSelection(ids, dx, dy)` hands **view**-space deltas to `vm.moveStrokes` (`InkScreen.kt:569`
  → `:311`), which writes them as document units. Correct today only because the scale is 1.

Two-finger gestures are also already spoken for: `ACTION_POINTER_DOWN` cancels the active stroke and
sets `panning = true`, and `focusY` averages y only. There is no focal point and no span being
tracked, so there is nowhere for a pinch to be heard.

**The library is not the obstacle.** Verified against the cached `ink-authoring-1.0.0.aar` (`javap`):

```
InProgressStrokeId startStroke(MotionEvent, int pointerId, Brush,
                               Matrix motionEventToWorldTransform,
                               Matrix strokeToWorldTransform)
Matrix getMotionEventToViewTransform() / setMotionEventToViewTransform(Matrix)
```

The wet layer can be *told* the camera, and finished strokes then arrive already in document space —
which deletes `toDocumentSpace` rather than complicating it. Likewise
`CanvasStrokeRenderer.draw(canvas, stroke, strokeToScreen)` already takes the matrix it needs and is
already being passed the one concatenated into the canvas, so a scale term flows into stroke
geometry and anti-aliasing for free.

## C. Why a small loop does not select what is inside it

The question is not "how do we select one stroke" — it is why the loop does not simply mean the
region it encloses. Five reasons, compounding.

### 1. The rule is per-stroke majority, not per-region containment

```kotlin
return inside * 2 > n     // StrokeCodec.kt:276
```

More than half a stroke's input points must fall inside the loop. This is deliberate and documented
("Most of it, not any of it") and it is right for the case it was written for — a descender poking
into the circle should not come along.

But it makes the loop's required size a property of **the stroke**, not of the region you are
pointing at. Handwriting is long strokes: a whole word is frequently one stroke. To win a majority
of the word you must encircle the word — and a loop that large has already enclosed the neighbours,
which then win their own majorities. The loop grows, the catch grows with it, and there is no way
down. That is the reported behaviour, exactly: a small loop selects nothing, so you enlarge it, and
then it takes too much.

Circling two short strokes *does* work today, because both are ~100% contained. The rule fails the
moment the thing you are circling is longer than the loop you want to draw.

### 2. The vote is over sample points, so it is biased by drawing speed

`MotionEvent` arrives at a fixed rate, so spatial sample density is inversely proportional to how
fast the pen moved — and the code already notes that Ink "drops samples that do not advance the
stroke" (`InkCanvas.kt`, `holdTrack`). A stroke drawn slowly at the start and quickly at the end
holds most of its *points* in its first half while holding half its *length* there.

So "majority of points" is not "majority of the stroke". Circle the slow half and it selects; circle
the fast half, same size loop, same amount of ink, and it does not.

### 3. A stroke can cross the loop with no point inside it

`strokeInside` tests the sampled points and nothing else. A fast stroke's samples can be tens of du
apart, so a segment can pass clean through a small loop and contribute **zero** to the vote. The
loop visibly crosses the ink and the geometry never sees it. This is the same problem the eraser
avoids by testing *segments* (`strokeHit` uses point-to-segment distance) — the lasso just never got
the same treatment.

### 4. Nothing ranks the candidates

Every stroke that passes is taken, and every stroke that fails is dropped. There is no notion of the
stroke the loop was most obviously drawn around, so there is no way for a small loop to prefer the
one thing it is sitting on over a long stroke that happens to pass nearby.

### 5. You cannot see what the loop will catch until you let go

The catch is computed in `commitLasso`, on lift. While dragging there is no highlight, so growing
the loop is guesswork — and the drawn path is not even closed (`setLasso` does `moveTo` + `lineTo`
with no `close()`, `InkCanvas.kt:641`) while the geometry closes it implicitly in
`pointInPolygon`. The region being tested and the region being shown are not the same shape, and the
one being shown looks unfinished, which invites over-drawing.

Smaller siblings, worth fixing in passing:

- The test is on the **centreline**, not the drawn outline. A 9 du highlighter is aimed at by its
  body and tested by a line through its middle.
- `extraStrokes` — strokes finished but not yet round-tripped through the vm — are not in
  `dryLayer.items`, so `commitLasso` cannot catch a stroke drawn a moment ago.

---

## Plan

Phase 0 must precede Phase 1: a camera over a space with no units has nothing to be correct about.
**Phase 2 is independent of both and can ship first** — it is the smallest change and answers the
sharpest complaint.

### Phase 0 — give the document a unit  ·  *format change, breaking*

Define **document units (du)**: page width is exactly `1000 du`, page height `1000·√2 du`.
Independent of px, dp and device. A stroke's `x` is then a position on the page, which is what it was
always meant to be.

**This phase deletes every existing drawing.** See the decision below.

**On "v1" and "v2".** These are labels introduced by this document; neither appears in the code.
**v1 is the format on disk today** — `[int32 header length][header JSON][StrokeInputBatch]` with the
header being exactly `{family, color, size, epsilon}` (`StrokeCodec.kt:43`), carrying no version and
no statement of what unit its coordinates are in. **v2 is the same layout with those two facts
added.** The absence of a version field is what identifies v1.

### Decided: v1 ink is deleted, not migrated

Migration was considered and rejected. The reference width a v1 stroke was drawn at was **never
recorded**, so migrating means inventing it — and once coordinates are rewritten and stamped v2, a
wrong conversion is indistinguishable from a correct one. No test can catch it, because there is no
ground truth to compare against, and nothing revisits a stroke that already claims to be v2.

Two facts about this codebase made that risk unacceptable rather than merely untidy:

- **A workspace drawn on more than one device has two correct reference widths and one place to put
  them.** Pages record `device:` in front matter as the LWW tiebreak (`PageCodec.kt:245`), but on a
  linked workspace that field holds the GitHub *login*, not the device (`App.kt:332`) — so two of
  your own devices write the same value and nothing in the files says which drew a given page's ink.
  A phone-and-tablet workspace would migrate half its pages at 2× and there would be no way to tell
  which half.
- **Two devices migrating before they sync** both rewrite every `.ink` file with a different width,
  and ink is settled by last-writer-wins. Push order decides whose guess is imposed on whose
  drawings. Recording `ink_reference_width_px` in the manifest first narrows that window; it cannot
  close it, because the manifest is LWW too.

Deleting is lossy and says so. `GIT_WORKSPACES_PLAN.md` §0 already holds that local data is mock and
no migration path is owed to it, and the workspace is a git repo, so the bytes stay in history and a
single workspace can still be converted by hand later — by which point you would know which device
drew it, which is the fact migration needs and does not have.

**Deletion is a required step, not a default.** Because v1 carries no version field, a v2-only app
does not skip an old stroke — it misreads it, treating px as du: ~8% too large from a 1080 px phone,
2× and half off the page from a 2000 px tablet. So Phase 0 must actively remove them.

### Prerequisite — make `formatVersion` mean something

`Manifest.formatVersion` is declared and documented as "what makes an older app go read-only on a
newer repo" (`WorkspaceStore.kt:19`), but the only two references to it in the codebase are the
declaration and that comment. **Nothing reads it.** Until it does, an un-upgraded device syncing the
same workspace keeps writing px-coordinate strokes into a du workspace, with nothing marking them as
a different unit — which reintroduces the original bug through the back door and, this time, mixes
both units inside one drawing.

So, before anything else in Phase 0:

1. Read `formatVersion` on open. If it exceeds `WorkspaceStore.FORMAT_VERSION`, the workspace opens
   **read-only**, with a message saying the app is behind, not that the data is broken.
2. Bump `FORMAT_VERSION` to `2` as part of this phase. That is what makes the du switch safe: an old
   build meeting a du workspace declines to write to it instead of writing pixels into it.

### The change itself

1. `StrokeCodec.Header` gains `v: Int = 1` and `unit: String = "px"`; new strokes are written with
   `v = 2, unit = "du1000"`. Old headers decode at the defaults because
   `Json { ignoreUnknownKeys = true }` (`StrokeCodec.kt:52`) plus Kotlin defaults fill in what is
   missing — so `decode` can *recognise* a v1 stroke in order to discard it.

   ⚠️ Declare the defaults as **v1's** values, not v2's. kotlinx.serialization's
   `encodeDefaults = false` omits any field equal to its declared default, so `val v: Int = 2` would
   write no `v` at all and make new strokes indistinguishable from old ones — the exact thing the
   field exists to prevent.

   The version stays in the header even though nothing is being migrated. It costs a few bytes per
   stroke and it is the reason the *next* unit change will have the choice this one did not.

2. **Drop v1 strokes on read, delete the file on write.** `readInk` filters out any stroke whose
   header is v1; `writeInk` then rewrites the sidecar without them, which it already does whole on
   every change. A page whose ink was entirely v1 loses its `.ink` file and keeps its `![[ink:id]]`
   block — so the block should render as an empty canvas you can draw on, never as a broken
   reference.

3. Capture and render convert at the edges only: `du = px * 1000 / viewWidth`. Nothing in between
   knows about pixels.
4. `Brush.size` and `epsilon` move into du. `PenSlot`'s `2.6f` becomes a du width; physical
   thickness then falls out of the camera scale, identically on every device.
5. `ShapeRecognizer`'s `40f` becomes a du constant.
6. `InkPreview` stops reading `displayMetrics` and uses `1000f` — the assumption becomes true
   instead of being worked around.
7. `pageHeight` becomes `1000f * PAGE_RATIO` du, so page boundaries and page counts are properties
   of the document.

**Guards.**

- A codec test that encodes at one reference width, decodes at another, and asserts the geometry
  lands in the same place on the page — the regression test for the whole of §A.
- A test that no stroke's bbox exceeds page width, so nothing can be drawn where a narrower device
  could not reach it.
- A test that a v1 blob is dropped rather than decoded, and that a sidecar of only v1 strokes leaves
  no file behind.
- A test that a workspace whose manifest declares a higher `formatVersion` opens read-only.

### Phase 1 — one camera, in one place

1. Introduce a `Viewport` owning `scale`, `panX`, `panY` (du), and exposing `docToView` /
   `viewToDoc` matrices plus `toDoc(x, y)`. Every open-coded `+ scrollOffset` is replaced by a call
   to it — `lassoPoint`, `eraseAt`, `commitShape`, `insideSelection`.
2. `DocumentStrokesView.onDraw` concats `docToView` and passes it to `renderer.draw` (already the
   right shape). **Page furniture moves outside the transform** — separators, `textSize = 28f`, the
   `width - 40f` label inset are chrome in view px, and would otherwise shrink to nothing at low
   zoom.
3. Wet layer: `wetLayer.startStroke(event, pointerId, brush, viewToDoc, IDENTITY)`. Finished strokes
   arrive in document space and **`toDocumentSpace` is deleted**.
4. Two fingers become pan **and** pinch: track focal point and span, not just `focusY`. Keep the
   existing "second finger cancels the active stroke" rule — that part is already right.
5. Bounds and a way back: clamp scale to ~0.25×–8×, clamp pan so the page cannot be lost off screen,
   and double-tap to fit page width. Anyone who can zoom in needs one gesture back to normal.
6. Horizontal pan becomes both possible and required — `maxScroll()` grows an x sibling.
7. Fix the two leaks the scale would otherwise break: `onLassoSelection` reports document
   coordinates (the screen converts); `onMoveSelection` divides the view drag by `scale` at the
   canvas edge, so `vm.moveStrokes` keeps writing document units.
8. `eraserRadius` becomes du (`eraserSize.dp.toPx() / scale`), so the eraser stays the same size
   under the finger instead of eating more ink the further you zoom out.
9. The viewport lives on the canvas, not in Compose state: `setStrokeItems` runs on every `update`
   and resets `extraStrokes`, and a camera reset by an unrelated recomposition would be a zoom that
   snaps back when the theme changes.

### Phase 2 — make the loop mean the region  ·  *independent, ship first*

One predicate replaces the majority vote. No new gesture, no new mode, no tap special case — a loop
small enough is a tap, and falls out of the same rule.

1. **Measure by arc length, not by sample count.** Resample each candidate stroke at a uniform du
   interval (~2 du) before testing, so every sample stands for the same amount of ink. This is a
   short change to `strokeInside`'s internals and it removes causes §2 and §3 at once: the vote stops
   depending on drawing speed, and a segment can no longer cross the loop unnoticed. Resampled point
   sets are cacheable per stroke, keyed off the stroke instance.

2. **Score, don't vote.** Return a *contained fraction* per stroke — resampled points inside the
   closed loop over total resampled points — instead of a boolean.

3. **Select relative to the best-contained stroke.** Take every stroke scoring
   `>= 0.5 * bestScore`, subject to an absolute floor on contained length so an empty loop catches
   nothing. This one rule covers every case that is broken today:

   | Gesture | Scores | Result |
   |---|---|---|
   | Loop around two whole strokes | ~1.0, ~1.0 | both — the case you asked about |
   | …that also clips a passing third stroke | ~1.0, ~1.0, 0.08 | the two; the third is 12% of the winner |
   | Small loop on part of one long word-stroke | 0.3, and 0.04 for the neighbour | the word |
   | Loop the size of a fingertip on one stroke | 0.03, 0.0, 0.0 | that stroke — a tap, for free |
   | Loop drawn on blank paper | all ~0, floor not met | nothing |

   The loop keeps meaning "most of it, not any of it" — but *most of it relative to what else the
   loop caught*, which is what makes a small loop legitimate rather than a near-miss. It also
   removes the open-vs-closed crossing mode from the earlier draft: with relative scoring, a
   strike-through is just a thin loop whose best-contained stroke is the one struck through, so the
   extra rule buys nothing.

4. **Tie-break by z-order.** Where scores are within a few percent — a tiny loop over two
   overlapping strokes — prefer the topmost (last in `items`, since draw order is z-order).

5. **Show the catch during the drag.** Highlight candidates live and close the drawn path
   (`setLasso` gains `close()`), throttled and bbox-culled so the scoring stays cheap. This is the
   half of the fix that is interaction rather than geometry: you stop enlarging the loop when the
   right thing lights up, instead of finding out on lift. Cull with a stroke-bbox / lasso-bbox
   overlap test before scoring anything.

6. **Add and subtract.** With a selection live, a further loop toggles rather than replaces, so a
   rough catch is a starting point rather than a dead end.

7. Include `extraStrokes` in the candidates, and widen the test by the ink — `radius +
   brush.size / 2` — so a highlighter is as easy to catch as it is to see.

8. **Tests, on the geometry directly.** Each row of the table above becomes a case in
   `StrokeCodecTest`, plus the speed-bias regression: the same geometry sampled densely in one half
   and sparsely in the other must score the same either way.

---

## Decisions taken

| Decision | Choice |
|---|---|
| Document unit | **1000 du across the page width**, height `1000·√2`. Independent of px, dp and device |
| Existing v1 ink | **Deleted, not migrated.** The reference width was never recorded, so migration would invent it, and a wrong guess is undetectable afterwards. Bytes remain in git history |
| `formatVersion` gate | **Built first**, and `FORMAT_VERSION` bumped to 2 — otherwise an old build writes pixels into a du workspace |
| Version field | Kept in the stroke header despite nothing being migrated, so the next unit change has the choice this one did not |
| Selection rule | **Relative containment** by arc length, not a per-stroke majority vote. One predicate; tap and strike-through fall out of it |
| Page width | **Fixed.** Zooming out reveals more *pages*, never a wider page — a fixed page width is what makes du portable |

## Open questions

1. **Zoom range**, and whether pinch stays available while a stylus is in hand. It would, since
   stylus mode only makes a *single* finger pan — but worth confirming that a two-finger pinch
   mid-sketch is wanted rather than an accident to be rejected.
2. **What an emptied ink block should look like.** A page whose ink was entirely v1 keeps its
   `![[ink:id]]` block and loses its sidecar. Blank canvas you can draw on is the assumption; the
   alternative is removing the block, which edits someone's page on their behalf.
