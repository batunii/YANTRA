# Adversarial QA report (2026-10-16)

Static read of the Android and iOS apps, web, scripts and CI. Nothing was run on a device.
`[V]` = the lead re-read the cited lines and confirmed it. `[A]` = a read-only audit agent reported it from the code, not re-checked. `[A+run]` = the agent also reproduced it by running code.
Paths are under `YANTRA/`. Android is `app/src/main/java/ie/shoonya/yantra/` (abbreviated `A/`). iOS is `ios/`.
Handoff: see `H-1` in `YANTRA.clan`.

## 1. Bugs, in fix order

### P1. Crash loops (a bad synced file bricks every device)
1. [V] `ios/YantraCore/.../Rank.swift:20-47`, `A/data/rank/Rank.kt:25-50`: a page with 769 or more blocks hits the 128-char cap in `Rank.between`. iOS `preconditionFailure`, Android `IllegalStateException`, on every index read. Fix: fixed-width index ranks (ranks are never written to files).
2. [A+run] `InkBlob.swift:465-466`: a gzip-valid payload with a length varint of `FF×9 01` traps in `Int(try varint())`. `try?` can't catch it, so a synced `.ink` crashes the app on page open. Fix: `Int(exactly:)` plus a bounds check.
3. [A+run] `CaptureParse.swift:258-269`: full-width or Arabic-Indic digits (`２０２６-０１-０１`) match ICU `\d` and trap on `Int(...)!`. Fix: use `[0-9]`.
4. [A+run] `CaptureParse.swift:305`: a resolved `[[Fix #bug]]` link (or `!high`, `9:30`, `@x` inside a link) leaves overlapping spans. Replacement goes out of bounds and throws, or deletes the wrong text. Android twin: `CaptureParse.kt:571-582` loses trailing text. Fix: drop any span that intersects an existing one.
5. [A+run] `PageCodec.swift:28,80`, `CalendarModel.swift:266`: `@ 2026-09-11/+999999999-12-31` traps in `startOfDay`'s force unwrap. A duration of `PT99999999999H` makes `emit` loop forever. Fix: clamp years to about ±9999 and clamp the loop to the visible window.
6. [V] `Workspace.swift:681`, `AppModel.descendants:587`, `WorkspaceMove.copyTree`, Android `reparent` and the `listTaskCounts` CTE: no cycle guard. Two offline devices each moving a task under the other hang the UI or kill the widget. Fix: visited sets.
7. [A+run] `FocusView.swift:133`: 18 digits in the minutes field overflows `minutes * 60`. Fix: clamp to 1…600.

### P2. Silent data loss
8. [V] iOS `SyncEngine.swift:122-132, 238-242`: `localFiles()` drops a file whose read fails (`try?`). It then looks deleted and the pass pushes the deletion. Fix: abort on read error. Refuse to push when the manifest is missing or more than about 20% of base paths vanished.
9. [V] iOS `WorkspaceWriter.swift`: `deleteLabel`, `archiveFinished`, `restoreArchived` and `createTopLevel` write outside the `WorkspaceLock` (see `CLAUDE.md` §5). A pull can be reverted by the writer's stale copy. Fix: hold the lock across the read-modify-write.
10. [A] iOS `WorkspaceRegistry.swift:44-100`, `Workspace.swift:236-243`, Android `WorkspaceStore.kt:697` and `WorkspaceWriter.kt:610`: an unreadable `registry.json`, `properties.json` or `labels.json` reads as `[]` and is then overwritten. Fix: distinguish unreadable from empty and refuse to write over a file that exists but cannot be parsed.
11. [A] Android `res/xml/data_extraction_rules.xml:20,24`: `files/workspaces` (including the Personal workspace and `registry.json`) is excluded from backup. A restore purges the restored Room rows. Fix: keep `workspaces/local` and the registry in backup.
12. [V] iOS `TaskPageView.swift:80,203,238`: no `onDisappear` and no `scenePhase` handling. Text in a block or title is lost on back-swipe or kill. Fix: commit on disappear, focus loss and scene change.
13. [V] iOS `TaskPageView.swift:204` (`editBlockText(index:)`): a sync pull that shifts blocks makes a draft overwrite a different block, or a blank Return delete a different one. Fix: address blocks by id.
14. [A] Android `ui/node/NodePageScreen.kt:2175`: non-task blocks are keyed `page~index`, so `remember(child.id)` outlives the line after an Enter, Backspace or remote edit. Fix: key by content identity.
15. [A] iOS ink: `InkView.swift:212-213` (write errors swallowed, ledger advances), `:122-125,200-203` (rotation reloads over unsaved strokes), `InkBlob.swift:324-325` (tilt within about 2e-4 of π/2 makes `transform` throw and `InkView.swift:644` drops the stroke). Android: `InkScreen.kt:206-220` (drawing before first decode wipes the sidecar), `:210-211` (undecodable strokes deleted on first edit).
16. [V] iOS `Keychain.swift:54-61`: delete before add, status ignored. A failed add loses the rotated refresh token. Fix: `SecItemUpdate` first, check the status. Related [A]: `method`/`login` live in UserDefaults and don't survive a reinstall while the token does.
17. [V] iOS `ConflictResolver.swift:129-151` and Android `ConflictResolver.kt:131-170` (`mergePage`): non-task blocks are keyed `Kind:n`. A delete or insert above shifts keys, duplicating or dropping an edit. Fix: stable block ids or hash matching, on both platforms together.
18. [V] iOS and Android `ConflictResolver`: non-page files keep only the local side (labels, properties, smart lists, `.ink`). Android `:167-170` takes frontmatter and order from the newer side wholesale.
19. [V] iOS `WorkspaceWriter.swift:411-429`, Android `reparent`/`putLine`: a move removes the source line before the destination is written. Fix: write the destination first. [A] `archiveFinished` has the same order problem on both platforms.
20. [A] Android `A/data/workspace/WorkspaceWriter.kt:778-784`: `blockIdOf` uses `indexOf`, so equal non-task blocks share an id.
21. [A] Android `Daos.kt:213,216`/`Repos.kt:110,127` (queries ignore workspace), `Repos.kt:126-133` (duplicate Inbox race), `FilterCompiler.kt:21`/`HomeViewModel.kt:114` (Today bounds frozen across midnight).

### P3. Android sync
22. [V] `A/data/sync/CommitScheduler.kt:75-92`: `flush()` cancels its own timer job, so `engine.sync` throws `CancellationException` that `launch` swallows. Debounced commits never push.
23. [V] `A/data/sync/SyncWorker.kt:35`: `syncNow` launches and returns, so the worker reports success before any work. Fix: `syncAwait`, `Result.retry()`.
24. [A] `SyncEngine.kt:154-168`, `GitRepo.kt:143-146`: a kill mid-rebase is never recovered (detached HEAD, `setUpstream` throws forever). Fix: abort any rebase at the start of each pass.
25. [A] No JGit network timeouts (`SyncEngine.kt:183,205`, `GitRepo.kt:149,167`, `WorkspaceLinker.kt:193`). A stall holds the engine mutex forever.
26. [A] `GitHubDeviceAuth.kt:419`: a refresh retried after GitHub rotated the token signs the user out. No single flight across workspaces.
27. [A] `WorkspaceLinker.kt:191-199`: a failed fetch is read as "no remote branch", so it scaffolds a fresh workspace and pushes unchecked.

### P4. Security
28. [V] Token exfiltration, both platforms: `RepoRef.parse` keeps any host from a pasted URL. Android `WorkspaceLinker.kt:182` attaches the account token as git credentials. iOS `GitHubTransport` sends `Authorization: Bearer` to `https://<host>/api/v3`. Fix: accept only github.com, or allowlist, before attaching credentials.
29. [V] iOS `Workspace.swift:267,320,329`, `PageCodec.swift:626,731`: ids like `^../../x` or `![[ink:../../x]]` reach `pageFile`/`inkFile`/`imageFile` unsanitised. Fix: reject `/`, `..`, `\0` at decode.
30. [A] iOS `MeetingText.swift:46-56`: `notzoom.us` or a query containing `meet.google.com` produce a trusted "Join Zoom/Meet" label.
31. [A] `.github/workflows/apk.yml`: the keystore and passwords are unpacked before branch code runs; secrets in an unquoted heredoc. `scripts/stage-key-backup.sh:30` uses `\s` in BSD sed.

### P5. Free-signed build (no App Group)
32. [V] Share extension: `ShareViewController.swift:87-90` gets an unopened store, so the Inbox is nil and `finish()` runs silently. `:120-126` downscale uses screen scale (about 6144 px, over 100 MB, killed). `:63,101` auto-dismiss at 3.2 s and a swallowed error leave "Added to Inbox".
33. [A, device check] `AppGroup.swift:190-281`, `Intents.swift:15-37`: `LiveSession` is in process-local defaults, so the Focus widget and Live Activity buttons do nothing, and `FocusActivity.sync()` can end the activity.
34. [V] `AppGroup.swift:234-235`: starting a session on a task in workspace B closes the old session through B's writer.

### P6. UI and other (see the full list in the conversation)
- Android `ui/calendar/TimelineView.kt:1071,1097,131,154`: frozen `pointerInput(Unit)` callbacks and stale `hourHeight`. [A]
- Android `NodePageScreen.kt:1102-1104`: rotation prunes trailing blanks. `SmartListScreen.kt:241,335`: duplicate-key crash. `NodePageScreen.kt:368-374`: drag-reorder off by the hidden-block count. [A]
- Android ink: `InkCanvas.kt:414-426` (palm cancels the pen stroke), `InkScreen.kt:295-363` (undo pops only the newest stroke). [A]
- iOS: `PageCodec.swift:67-89` uses `Calendar.current` (wrong dates on non-Gregorian calendars) [A+run]. `FocusActivity.swift:46` (Live Activity never ends) [A]. `SignInView.swift:91,137-179` (poll continues after leaving) [A]. `SmartListRule.swift:134,126` (edit rewrites the rule) [A].
- Android `FocusTimer.kt:156-176,178-186` (tick count not wall-clock; pause lost on death), `ReminderManager.kt:133-135` (missed-while-off reminders). [V] for the ticker; [A] for the rest.
- Android widgets [A]: Today "DONE" filters on page-level `updatedAt`; no row cap on `ListWidget`; paging arrows use the stored view; quick-add to a deleted list loses the task.
- iOS `ListWidgets.swift:196-207`: `shown` mutated inside a view body (device check). [V]

## 2. Stress points
Rank cliff at 769 blocks. iOS full-workspace reindex per write on main. Per-row index scans and `registry.json` reads. Sequential, non-resumable blob uploads. No sync coalescing. Android `taskOrigins()` CTE re-run on every `node` write. Home counts load full rows. Missing indexes on `node.in_progress`/`system_key` and `property_value(def_id, v_date)`. Eraser and lasso without a bounding-box cull. Widget row cap. Ink decompression with no cap. 20,000 numbered items take 16.5 s to encode. No JGit timeouts, no rate-limit backoff.

## 3. Fragility map (short)
Sync (GitHub REST on iOS, JGit on Android), device-flow refresh rotation, the byte-identical format contract (`conformance/` is the only guard, and `oracle.sh` swallows compile errors), swallowed `try?` throughout the iOS store, process-local defaults on a free-signed build, `Calendar.current`, JGit/Room/androidx.ink/AGP upgrades (`gradle.properties` opt-out flags). CI runs unit tests only, so `MigrationTest` and the instrumented suite never run.

## 4. Redundancy
Duplicate: the pure `epochDay` and `Calendar`-based date math; `two()` and the 9-digit fraction parser (3 copies each); `KotlinFloat` and `javaDouble`; Android Flow/Once DAO query pairs (the reminder pair can drift); the swipe-to-start gesture; day counting. Derived-but-stored: `property_value.v_number`, `done`+`in_progress`, `ext_uid`/`ext_start`, `created_at == updated_at`. Dead: about 30 Android DAO methods, `Indexer.kt:116-169`, the `justCreatedId`/`autoFocus` chain, `inlineChips`/`pomoCount`, `JSONValue.swift`, `AppModel.makeTaskForMeeting`, `-calmode`/`-calrail` hooks in release. Thin wrappers: Android repositories over a single DAO.

## 5. Optimization (hot paths)
Coalesce and background the iOS reindex. Build `childrenByParent` and open counts once. Cache `allStores`/`workspaceName`. Share the Android `taskOrigins` collector. `COUNT/SUM` for smart-list counts. Add the missing indexes. Cull in the eraser. Persist `hourHeight` on gesture end. See the report text for the rest.

## Limits
Nothing was run on a device. Device checks needed: the Today widget layout overflow, free-signed Focus and Live Activity behaviour, and the Android widget row-cap threshold. Test code was only read for time-dependent flakiness.
