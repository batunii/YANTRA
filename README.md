# SuperTasks

A Superlist-style task/notes app for Android — offline-first, sync-ready. Built on the
one-table node-tree data model (see the schema design doc): a single `node` table with a
`type` discriminator is the whole spine. A list's body is its child nodes; opening a task
"as a page" renders that task's children with the same code. Smart lists are nodes whose
children are *computed*, not stored.

## Stack

| Piece | Choice |
| --- | --- |
| Language / UI | Kotlin 2.2.21, Jetpack Compose (BOM 2026.06.01), Material 3 |
| Persistence | Room 2.8.4 (KSP), schema exported to `app/schemas` |
| Ink | androidx.ink **1.0.0** (pinned, stable) — authoring, rendering, storage |
| Filters | kotlinx.serialization JSON → SQL via a small query compiler |
| Build | AGP 8.13.2, Gradle 8.14.3, minSdk 26, compile/targetSdk 36 |

No Hilt, no fragments — a plain `AppContainer` in [`App.kt`](app/src/main/java/ie/napkin/supertasks/App.kt)
and one activity with Navigation Compose.

## Build & run

```
gradlew :app:assembleDebug          # needs JAVA_HOME -> a JDK 17+ (Android Studio's jbr works)
gradlew :app:testDebugUnitTest      # rank + filter-compiler unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Where things live

```
data/db/Entities.kt        node, property_def, property_value, pomodoro_session,
                           smart_list_def, ink_stroke — 1:1 with the schema doc
data/db/Daos.kt            children/subtree CTEs, counts, raw smart-list query hook
data/rank/Rank.kt          fractional (LexoRank-style) sibling ordering, base-36 strings
data/filter/Filter.kt      serializable filter tree + SortSpec + apply-on-create derivation
data/filter/FilterCompiler.kt  filter_json -> recursive-CTE SQL (EXISTS per property clause)
data/ink/StrokeCodec.kt    [header JSON][Ink StrokeInputBatch bytes] envelope; ink stays ink
data/repo/                 Node / Property / SmartList / Pomodoro / Ink repositories
data/seed/Seeder.kt        first-run defs (Priority, Due), Inbox, sample list, 2 smart lists
domain/PomodoroTimer.kt    app-scoped timer; every session persisted start-to-end
ui/                        home, node page (universal renderer), smart list, focus/stats, ink
```

## Design decisions carried through

- **Sync-ready from day one** — client-generated UUIDs, `updated_at` LWW clocks,
  `deleted_at` tombstones everywhere; deletes are always soft (whole subtree).
- **Fractional rank** — `Rank.between/after/before` generates base-36 strings that never
  end in `0`; reorder/indent/outdent never renumber siblings.
- **Global typed property registry** — property chips, the sheet editor, and smart-list
  filters all run off the same `property_def` / `property_value` tables.
- **Smart lists are the "real place"** — read side compiles `filter_json` to SQL
  (recursive CTE only when scoped); write side inserts into `home_parent_id` and applies
  `apply_on_create_json`, derived automatically from the filter's `=` clauses.
- **Ink stays ink** — each stroke row stores the Ink API's own serialized
  `StrokeInputBatch` plus a tiny brush header; strokes are rebuilt as `Stroke(brush, inputs)`
  and re-rendered with `CanvasStrokeRenderer`. Per-stroke bboxes are stored for the future
  canvas mode; the nullable `canvas_*` node columns are already in the schema.

## Deliberately deferred

- Sync transport (schema needs no change when it arrives)
- Canvas render mode (schema-ready: `canvas_*` + stroke bboxes)
- Arbitrary filter-editor UI (creation uses templates; the JSON model supports more)
- Foreground-service pomodoro (timer currently lives in the app process)
- Drag-and-drop reorder (menu-based move/indent/outdent for now)
