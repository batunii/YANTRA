# Row salience — what a task row says, depending on where it's read

## Goal

A row shows a metadata field only when it carries information the view hasn't already
implied. Today this is hardcoded in `TextualBlockRow` (`ui/node/NodePageScreen.kt`, the
`isTask && !editable` branch, ~L2363–2470): due is always pinned to the title slot, the
sub-line is always `labels · deadline · origin list`, and assignee/workspace are filtered out
by name. That recipe was written for Today and is wrong on every other view — e.g. on a
list page a task assigned to someone else shows nothing at all.

Replace the recipe with three composable rules:

1. **View weight** (pure, per view) — walk the `Filter` and rank each field. Done: `Salience.kt`.
2. **Expected value** (per row, needs live context) — hide a field whose value is what the
   reader would assume anyway.
3. **Overrides** (per task, tiny list) — alert states that force a field back regardless.

Overrides beat expected-value beats view weight.

## 1. View weight — `data/filter/Salience.kt` (written, untested)

Types: `Field` (`Prop(defId)`, `Label(labelId)`, `OriginList`, `Workspace`), `Weight`
(`Pinned < Bounded < Branched < Free` by informativeness), `ViewContext(filter, singleWorkspace)`,
`RowGrammar(titleSlot, subLine, spine, pinned)`.

Walk rules:
- Under `All`: `EQ`/`IS_SET`/`NOT_SET`/`HasLabel`/`InWorkspace` → **Pinned** (dropped);
  range ops → **Bounded** (shown, tail).
- Under `AnyOf` with >1 arm → **Branched** (front). One-armed `AnyOf` behaves as `All`.
- `Not(IS_SET)` → Pinned; other negations → Bounded.
- Field mentioned twice keeps the more informative weight.
- `filter == null` (plain list page) pins `OriginList`. `singleWorkspace` pins `Workspace`.
- `Done`/`InProgress`/`Type` are ignored — that's the glyph's business.

Output: `titleSlot` = first surviving date field; `spine` = Workspace if not pinned;
`subLine` = the rest, ordered Branched → Free (default order: due, deadline, assignee, list,
workspace) → Bounded. Labels are not in the default order; the row appends unpinned labels
after props and before the list.

Tests: `app/src/test/java/ie/shoonya/yantra/SalienceTest.kt`. **Run them first**; fix the
engine, not the test, unless a test contradicts this doc.

## 2. Expected values — new, small

```kotlin
data class Expected(
    val me: String?,          // viewer's login for this workspace, from Credentials
    val hereWorkspace: String?, // workspace the page lives in; null on cross-workspace smart lists
    val hereList: String?,    // the page id on a list page; null on smart lists
    val today: LocalDate,
    val sharedWorkspace: Boolean, // >1 known collaborator in this workspace
)
```

Per row, after the grammar has said a field is eligible, drop the chip if its value equals
the expected one:

| Field | Expected | Effect |
|---|---|---|
| Assignee | `me` | `@batunii` silent on my own tasks; `@saieeshward` shown |
| Assignee (null) | — | silent in personal workspaces; dim "unassigned" mark when `sharedWorkspace` |
| Due | `today` | Today: on-time tasks say nothing, "5 Aug" in crimson stays |
| Workspace | `hereWorkspace` | list pages silent; smart lists keep the hued spine |
| Origin list | `hereList` | same result as Pinned, different reason — keep both |

Where it lives: compute `Expected` once in the page/smart-list view model (they already have
credentials, workspace, and the visible set). Pass down with the grammar via one
`CompositionLocal` (`LocalRowContext` holding `RowGrammar + Expected`) so `TextualBlockRow`
doesn't grow five parameters.

`sharedWorkspace`: cheapest correct signal is "more than one distinct assignee value across
the visible set, or any assignee ≠ me." Don't hit the network for it.

## 3. Overrides — keep this list boring

- `ChipStatus.Overdue` on due or deadline → force into the title slot even if Pinned/expected.
- Deadline within 24h → same (if `ChipStatus.Due`/`Warn` already encodes this, reuse it).

That's it for now. Render an override chip in the alert voice (crimson, as overdue already
is), never as a normal metadata chip, so the reader can tell it's an exception. Do not add
override number three without a written reason in a comment.

## Integration points

- `ui/node/NodePageScreen.kt` `TextualBlockRow` non-editable branch: replace the two
  hardcoded filters with `grammar.subLine.mapNotNull { field -> resolve(field) }` + expected
  filter + overrides. Title slot likewise. Keep the sub-line's mono/dim voice; this changes
  *which* chips, not how chips look.
- `ui/node/NodePageViewModel.kt`: `ViewContext(filter = null, singleWorkspace = …)`, build `Expected`.
- `ui/smart/SmartListViewModel.kt`: `ViewContext(filter = node.filter, …)`; `hereWorkspace`
  = the single workspace if `workspacesNamed()` yields exactly one, else null.
- `widget/` `WidgetRowDetail`: second hand-rolled grammar. Make it consume `Salience.grammar`
  + `Expected` too, or at minimum delete the duplicate reasoning and call the same function.
  Widget rows have a budget of one field — take `titleSlot ?: subLine.firstOrNull()`.
- Origin-list chip: still rendered from `Origin` as today; the engine only decides its rank.
- Spine hue: already keyed off workspace on smart lists — now keyed off `grammar.spine != null`.

## Situations (acceptance)

> **Amended on implementation.** Five rows of the table below describe a row this build does not
> draw, and the difference is deliberate in each case — read this first or correct behaviour will
> look like a bug:
>
> - **Tags lead the line**, they do not trail it. `DESIGN.md` §6 removed the assignee *to give the
>   tags the room*, and the line ellipsises from the tail, so the tags cannot be what pays.
> - **The workspace is never a word**, on any row, in any view. It is the spine or it is nothing.
> - The spine is on **whenever two or more repositories are open**, not "always" on an OR of
>   workspaces: with one open, `ws A OR ws B` can only ever match one of them.
> - A **plain list page has no spine at all** — it builds no `Origin`, so there is no hue to paint.
> - Row 1's `@other-or-unassigned` is **`@other`**. `@?` is drawn only where the assignee is
>   Branched — see §2's amendment below.
>
> `Expected` shipped with **one** field, `logins`. `today`, `hereWorkspace`, `hereList` and
> `sharedWorkspace` were each a signal something cheaper already carried: the chip's own
> `ChipStatus`, `origin.workspaceHue`, the fact that a list page's rule pins its own list, and —
> for `sharedWorkspace` — nothing, because a signal derived from the visible set makes one row's
> content depend on the others.


| View | Title slot | Sub-line | Spine |
|---|---|---|---|
| Plain list page (shared ws) | due | deadline · @other-or-unassigned · #tags | — |
| Plain list page (personal) | due | deadline · #tags | — |
| Today (due≤today OR deadline≤today) | due/deadline, silent if today | the other date · @other · #tags · list | ws hue |
| Due = today (strict) | deadline | @other · #tags · list | ws hue |
| `#work` AND ws A | due | deadline · @other · other tags · list | — |
| ws A OR ws B | due | deadline · @other · #tags · list | ws hue, always |
| `#urgent` OR `#blocked` | due | matched tag first · deadline · @other · list | ws hue |
| Assigned to me | due | deadline · #tags · list | ws hue |
| Any view, task overdue | **due (crimson)** | as above | — |

Concretely, the screenshot that motivated this: Napkin Tasks list page, "Rag optimization"
assigned to `@saieeshward`, no dates → row reads `@saieeshward` on the sub-line.
"Architecture Complete", overdue Sept 18 → unchanged.

## Out of scope

- Data-driven pinning ("every visible task shares this value") beyond the assignee case
  covered by `Expected`. Note it as a follow-up; the `pinned` set in `RowGrammar` is where it
  would land.
- Any change to chip visuals, the editable (document) branch, or the filter builder UI.
- User-configurable column choices. The engine is the opinion; don't add a settings screen.

## Definition of done

- `SalienceTest` green; add a test for `Expected` covering me/other/unassigned × personal/shared.
- No field name appears as a string literal inside `TextualBlockRow`'s meta logic.
- `WidgetRowDetail` no longer contains its own field-ranking `when`.
- Rows on a list page, Today, and one OR-filter smart list checked by eye against the table.
