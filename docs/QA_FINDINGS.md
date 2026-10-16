# Findings register for Shrey (H-1)

Generated 2026-10-16 from the adversarial QA review. Static read; nothing was run on a device. Priorities P1..P7 are the fix order and supersede the numbering in `QA_REPORT.md`.

V = re-read and confirmed by the lead; A = reported by a read-only audit agent, NOT re-checked (reproduce first); A+run = the agent also reproduced it by running code. Device check = needs a real device.

Work one finding at a time: reproduce, write a failing test, fix, run tests, then set the status in `YANTRA.clan` (`findings`). Android is frozen: ask Sai before editing `app/` or `web/`.

## P1. Crash loops

### F-01 [CRITICAL] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/Rank.swift:20-47; app/src/main/java/ie/shoonya/yantra/data/rank/Rank.kt:25-50`
- **Trigger:** Any page with 769+ blocks (done tasks pile up; archive defaults to never). Each rank grows ~1 char per 6 blocks and hits the 128 cap in Rank.between.
- **Breaks:** iOS preconditionFailure, Android IllegalStateException on every index read; synced, so every device crash-loops.
- **Fix:** Assign ranks by fixed-width index (ranks are never written to files; both apps just must agree). Add a 1000-block test on both.

### F-02 [HIGH] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/InkBlob.swift:465-466`
- **Trigger:** Synced .ink with gzip-valid payload whose length varint is FF x9 01 (UInt64.max).
- **Breaks:** Int(try varint()) traps; try? cannot catch a trap; app crashes on every open of that page.
- **Fix:** Int(exactly:) plus bounds check against remaining bytes; also cap inflated size (see F-61).

### F-03 [HIGH] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/CaptureParse.swift:258-269`
- **Trigger:** Quick-add text with full-width or Arabic-Indic digits, e.g. '２０２６-０１-０１'.
- **Breaks:** ICU \d matches them, Int(group)! does not: force-unwrap trap.
- **Fix:** Use [0-9] in the regexes or guard let each Int.

### F-04 [HIGH] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/CaptureParse.swift:305 (spans 82-99); app/src/main/java/ie/shoonya/yantra/data/capture/CaptureParse.kt:571-582`
- **Trigger:** Resolved link containing another token: '[[Fix #bug]]', '!high', '9:30' or '@x' inside a link.
- **Breaks:** Spans overlap; strip replaces highest first so the inner span shrinks the string and the link range goes out of bounds (iOS throws; Android deletes trailing text, 'see [[Issue #12]] now' loses ' now').
- **Fix:** Drop any candidate span that intersects an existing span (all kinds, not only dates).

### F-05 [MED-HIGH] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/PageCodec.swift:28,80; ios/YantraCore/Sources/YantraCore/CalendarModel.swift:266-273`
- **Trigger:** Synced line '@ 2026-09-11/+999999999-12-31' (trap in startOfDay force unwrap) or '@ 2026-09-11T14:00/PT99999999999H' (emit loop never ends: date saturates and maps to itself).
- **Breaks:** Crash loop on page load, or infinite loop.
- **Fix:** Clamp LocalDate years to ~±9999; clamp the emit loop to [from,toExclusive); implement adding(days:) via epochDay.

### F-06 [MED] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/Workspace.swift:681-685; ios/Yantra/Sources/AppModel.swift:587; ios/YantraCore/Sources/YantraCore/WorkspaceMove.swift:67-86; app/src/main/java/ie/shoonya/yantra/data/workspace/WorkspaceWriter.kt (reparent); db/Daos.kt (listTaskCounts CTE)`
- **Trigger:** Two offline devices each move a task under the other (or any page naming itself): A's line on B's page and B's line on A's page.
- **Breaks:** ancestors(of:) loops forever and grows memory (TaskPageView crumbs, widgets); descendants/copyTree recurse to stack overflow; Android CTE UNION ALL loops.
- **Fix:** Visited sets in every tree walker; cycle check inside move/reparent, not only in the UI.

### F-07 [MED] (A+run)
- **Where:** `ios/Yantra/Sources/FocusView.swift:133`
- **Trigger:** Type 18 digits in the minutes field (smaller huge values make a session of years).
- **Breaks:** minutes * 60 overflows and traps.
- **Fix:** Clamp to 1...600.

## P2. Silent data loss / corruption

### F-08 [HIGH] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/SyncEngine.swift:122-132,238-242`
- **Trigger:** Any read error in localFiles() (locked file before first unlock, I/O error). try? drops the file, so it looks deleted; remote unchanged.
- **Breaks:** The pass pushes a deletion to GitHub; no mass-delete guard; same if workspace dir is empty but sync base survives.
- **Fix:** Abort the pass on read error. Refuse to push if the manifest is missing locally or >~20% of base paths vanished.

### F-09 [HIGH] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/WorkspaceWriter.swift (deleteLabel, archiveFinished, restoreArchived, createTopLevel, ensurePage); CLAUDE.md section 5`
- **Trigger:** Writer reads a page, a sync pull swaps it under the lock and advances the base, writer then writes its stale copy.
- **Breaks:** Next pass pushes the stale copy as a local change: another device's edit is reverted everywhere with no conflict notice.
- **Fix:** Hold WorkspaceLock across every read-modify-write.

### F-10 [MED-HIGH] (A)
- **Where:** `ios/YantraCore/Sources/YantraCore/WorkspaceRegistry.swift:44-100; ios/YantraCore/Sources/YantraCore/Workspace.swift:236-243; app/src/main/java/ie/shoonya/yantra/data/workspace/WorkspaceStore.kt:697; WorkspaceWriter.kt:610`
- **Trigger:** registry.json/properties.json/labels.json unreadable, truncated, or one entry fails to decode (e.g. type changed by a newer build).
- **Breaks:** Read returns []; next add/remove/upsert/ensureBuiltInProperties writes [] + new: forgets every linked workspace, or wipes custom properties and labels; synced.
- **Fix:** Distinguish unreadable from empty; refuse to write over a file that exists but cannot be parsed; decode per entry.

### F-11 [HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/../../../../res/xml/data_extraction_rules.xml:20,24 (app/src/main/res/xml)`
- **Trigger:** Phone migration/restore for a user who never signed in to GitHub.
- **Breaks:** files/workspaces (Personal workspace, registry.json) is excluded; Room restores, then the orphan sweep (App.kt:413-416) purges rows: all local tasks and ink lost.
- **Fix:** Keep workspaces/local and registry.json in backup (or move git clones to noBackupFilesDir).

### F-12 [HIGH] (V)
- **Where:** `ios/Yantra/Sources/TaskPageView.swift:80,203,238`
- **Trigger:** Type in a block or the title, then back-swipe or background and get killed. No onDisappear/scenePhase handling (grep: 0 matches).
- **Breaks:** Text lost. onAppear also resets an uncommitted title edit.
- **Fix:** commit(editing)/commitTitle() on disappear, on focus -> nil, and on scenePhase != .active.

### F-13 [HIGH] (V)
- **Where:** `ios/Yantra/Sources/TaskPageView.swift:204,231,244; ios/YantraCore/Sources/YantraCore/WorkspaceWriter.swift:286-301`
- **Trigger:** A sync pull inserts/removes a block above the one being edited; commit(i) uses the old integer index.
- **Breaks:** Draft overwrites a different block, or blank Return deletes a different block. Negative index also traps (index < count only).
- **Fix:** Address blocks by id or verify content before write; guard index >= 0.

### F-14 [HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/node/NodePageScreen.kt:2175 (rows 885,898); data/workspace/PageMapper.kt:74`
- **Trigger:** Enter mid-note, Backspace on a blank line, or a remote edit shifts non-task ids (page~index).
- **Breaks:** remember(child.id) keeps the old line's text; typing overwrites a different line.
- **Fix:** Key rows by content identity or add a LaunchedEffect that adopts external changes.

### F-15 [HIGH] (A)
- **Where:** `ios/Yantra/Sources/InkView.swift:212-213`
- **Trigger:** Workspace read-only or disk full while editing a sketch.
- **Breaks:** AppModel.write swallows errors, ledger advances, save becomes a no-op: strokes never retried, lost on leave.
- **Fix:** Make write return Bool; advance the ledger only on success.

### F-16 [HIGH] (A)
- **Where:** `ios/Yantra/Sources/InkView.swift:122-125,200-203`
- **Trigger:** Rotation / iPad resize within ~900 ms of lifting the pen, or while unsaved.
- **Breaks:** redraw() reloads the file over unsaved strokes; undo history left in old-width coordinates.
- **Fix:** Save with the previous width before updating canvasWidth, or rescale the live drawing; reset history.

### F-17 [HIGH] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/InkBlob.swift:324-325; ios/Yantra/Sources/InkView.swift:644`
- **Trigger:** Lasso-move a Pencil stroke with tilt within ~2e-4 of pi/2 (~1% of strokes) or orientation near 2pi.
- **Breaks:** decodeBatch yields a value one ulp out of range, validate throws, 'try? InkBlob.transform' drops the stroke from the saved page.
- **Fix:** Clamp tilt, map orientation >=2pi to 0 in decodeBatch; fall back to entry.base when transform throws.

### F-18 [HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/ink/InkScreen.kt:206-220,380,391`
- **Trigger:** Start drawing before the first Room emission/decode finishes (~200 ms on a large sketch).
- **Breaks:** Existing strokes never load; next write replaces the sidecar with only the new strokes.
- **Fix:** A 'loaded' flag blocking input/writes until the first decode lands, or merge by id.

### F-19 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/ink/InkScreen.kt:210-211`
- **Trigger:** Edit a sketch containing a stroke decodeOrNull rejects (v1, iOS or newer format).
- **Breaks:** Those strokes are deleted from the repo on first edit (iOS keeps the bytes).
- **Fix:** Keep unreadable blobs as opaque entries that are written back but not drawn.

### F-20 [MED-HIGH] (V)
- **Where:** `ios/Shared/Keychain.swift:54-61,75,105-116`
- **Trigger:** A failed SecItemAdd after token refresh (device locked, keychain busy); also reinstall.
- **Breaks:** Old item already deleted, GitHub rotated the refresh token: user silently signed out; readers see nil between delete and add. method/login in UserDefaults are wiped on reinstall while the token survives, so refresh uses the wrong client id.
- **Fix:** SecItemUpdate first (as setShared does), check status; keep method/login inside the keychain item.

### F-21 [MED] (A)
- **Where:** `ios/Shared/AppGroup.swift:272-280; ios/YantraCore/Sources/YantraCore/WorkspaceWriter.swift:380`
- **Trigger:** Disk full or workspace read-only at session end.
- **Breaks:** try? swallows appendFocus failure; LiveSession is cleared; the focus time is lost with no signal.
- **Fix:** Surface the failure; clear LiveSession only after a successful append.

### F-22 [MED] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/WorkspaceWriter.swift:411-429; app/src/main/java/ie/shoonya/yantra/data/workspace/WorkspaceWriter.kt:396,415-446 (reparent/takeLine/putLine); Workspaces.kt:146-149`
- **Trigger:** App killed, or sync swaps the destination page, between removing the line and adding it.
- **Breaks:** The task line is deleted and never re-added; title lives only in the line.
- **Fix:** Write the destination first, then remove; check editPage's return value.

### F-23 [MED] (A)
- **Where:** `ios/YantraCore/Sources/YantraCore/WorkspaceWriter.swift:507-527; app/src/main/java/ie/shoonya/yantra/data/workspace/WorkspaceWriter.kt:680-717 (archiveFinished/restoreArchived)`
- **Trigger:** Crash between appending archive lines and rewriting the page.
- **Breaks:** Task in both places; next run appends it again; restore appends a duplicate id.
- **Fix:** Make idempotent: dedupe by id; order writes so a crash leaves it recoverable.

### F-24 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/workspace/WorkspaceWriter.kt:778-784 (blockIdOf)`
- **Trigger:** Page with two equal non-task blocks (e.g. two empty bullets).
- **Breaks:** indexOf gives both the same id: addBlock(afterId=second) appends at end, moveBlock no-op, takeLine can remove both.
- **Fix:** Pass the index everywhere.

### F-25 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/db/Daos.kt:213,216; data/repo/Repos.kt:110,127`
- **Trigger:** Two or more workspaces open, each with its own Inbox/Today.
- **Breaks:** bySystemKey/byTypeAndTitle have no workspace filter or ORDER BY: quick-add can file into the wrong repo; also a table scan.
- **Fix:** Filter by workspace_id; add an index on system_key.

### F-26 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/repo/Repos.kt:126-133; data/workspace/WorkspaceReconciler.kt:154-170`
- **Trigger:** Two concurrent inboxList() calls, or a second device joining.
- **Breaks:** Two pages claim system_key inbox; loser's child tasks become parentless and invisible.
- **Fix:** Lock the check-and-create; reparent the loser's children.

### F-27 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/db/FilterCompiler.kt:21; ui/home/HomeViewModel.kt:114; data/repo/Repos.kt:~775`
- **Trigger:** App stays open across midnight on Today/Home.
- **Breaks:** TODAY_START/END bound once at flow creation: Today, Home counts and booked minutes stay on yesterday.
- **Fix:** Key the flow on a date-change tick and recompile.

### F-28 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/workspace/WorkspaceStore.kt:111-113; WorkspaceReconciler.kt:308`
- **Trigger:** One corrupt/truncated .ink sidecar.
- **Breaks:** decodeInk throws (EOF, negative size, OOM): rebuild throws on every edit, reindexAll leaves the workspace unindexed.
- **Fix:** Guard readInk (return empty, add to problems); bound lengths against remaining bytes.

### F-29 [MED] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/PageCodec.swift:876,893-912; app/src/main/java/ie/shoonya/yantra/data/format/PageCodec.kt:580-594`
- **Trigger:** Task titled 'Fix bug #12', trailing '@bob'/'!x'/'due:' in a title, text starting '1. ', '- ', '# ', or a title containing newlines.
- **Breaks:** iOS: rebuilt line re-parses as a different block ('Fix bug' + label 12). Android: title with 2+ newlines loses its ^id and gets a positional id; page title with newline breaks the frontmatter fence.
- **Fix:** Re-parse rebuilt lines to confirm round trip; escape or UI-guard; replace newlines in single-line renderers. Format-level decision: both platforms.

### F-30 [MED] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/PageCodec.swift:1061`
- **Trigger:** Type '[[' after an emoji (list icons are emoji): 'Call [[Bo'.
- **Breaks:** Character offset used as UTF-16 offset: picker never appears, or replace range eats text.
- **Fix:** Use utf16Offset or NSString range.

### F-31 [MED] (A)
- **Where:** `ios/YantraCore/Sources/YantraCore/SmartListRule.swift:126,134`
- **Trigger:** Open the rule builder on a rule with an epoch-ms date clause or a non-task type() filter.
- **Breaks:** date is discarded (written as nil) and type becomes task: the rule changes meaning on edit.
- **Fix:** Send such clauses to extras or carry the field through.

### F-32 [LOW-MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/workspace/WorkspaceStore.kt:405 vs 442`
- **Trigger:** Delete a page that has event children.
- **Breaks:** deletePage does not follow EventRef children but subtreeFiles does: event pages orphaned.
- **Fix:** Make both follow the same set.

### F-33 [LOW] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/Workspace.swift:458-472`
- **Trigger:** rename fails (rare) and copyItem then fails.
- **Breaks:** Target already removed, tmp removed in catch: file lost.
- **Fix:** Copy to a temp name and rename; never remove the target first.

## P3. Android sync

### F-34 [HIGH] (V)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/CommitScheduler.kt:75-92`
- **Trigger:** Any EDIT/INK change then the quiet period (20 s / 8 s).
- **Breaks:** flush() cancels its own timer job; engine.sync enters withContext(IO) and throws CancellationException which launch swallows: debounced commits never push.
- **Fix:** Run flush in a separate scope.launch, or cancel the timer only when it is not the current job. Add a CommitScheduler test (none exists).

### F-35 [HIGH] (V)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/SyncWorker.kt:35; App.kt:296`
- **Trigger:** The 15-min periodic sync with the app backgrounded.
- **Breaks:** syncNow launches and returns; worker reports success before any work; process can be frozen mid-push.
- **Fix:** Call syncAwait and return Result.retry() on failure.

### F-36 [HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/SyncEngine.kt:154-168; GitRepo.kt:143-146`
- **Trigger:** Process killed mid-rebase/resolveAll, or abortRebase fails inside runCatching.
- **Breaks:** Later passes commit onto a detached HEAD (possibly with conflict markers); setUpstream('origin/<sha>') throws every pass: sync dead.
- **Fix:** At pass start, abort any rebase in progress and surface a failed abort.

### F-37 [HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/SyncEngine.kt:183,205; GitRepo.kt:149,167; WorkspaceLinker.kt:193`
- **Trigger:** Half-dead Wi-Fi: TCP connects, data stalls.
- **Breaks:** JGit default timeout 0 = infinite; the engine mutex is held forever; every later sync queues behind it.
- **Fix:** setTimeout on fetch/push and withTimeout around the pass.

### F-38 [MED-HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/GitHubDeviceAuth.kt:419-421; TokenRenewal.kt:49`
- **Trigger:** Refresh response times out after GitHub already rotated the token; post() retries with the spent token. Two workspaces syncing together spend one token twice.
- **Breaks:** Refused -> NeedsSignIn: user signed out. App.kt credentials lambda discards the Outcome.
- **Fix:** Never retry refresh; one mutex around renewal.

### F-39 [MED-HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/SyncEngine.kt:186-203,195; GitRepo.kt:145`
- **Trigger:** User saves a page after commitAll while the rebase runs.
- **Breaks:** Writer and sync hold different mutexes: abortRebase's reset --hard can discard the edit; UNCOMMITTED_CHANGES mislabelled 'kept losing a race'.
- **Fix:** One lock shared by WorkspaceWriter.locked and syncNow; drop reset --hard.

### F-40 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/WorkspaceLinker.kt:191-199,283,142`
- **Trigger:** Transient network error during link()/attach().
- **Breaks:** fetchOne ignores the failure and returns 'no branch': start() scaffolds and pushes unchecked; later unrelated histories, add/add conflicts, duplicate Inbox.
- **Fix:** Distinguish fetch exception from no-such-ref; check the push result.

### F-41 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/ConflictResolver.kt:234-236`
- **Trigger:** Focus log conflict: remote has the closing line of session X, local only the opening line.
- **Breaks:** remote+local concatenation keeps the last line per id, so the opening overwrites the closing: session never ended. Same ordering in iOS mergeLog.
- **Fix:** Keep the line with the later ended field when ids collide.

### F-42 [MED-LOW] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/App.kt:285,296,314,442,703; data/workspace/Workspaces.kt:24-25`
- **Trigger:** Tap sync while addWorkspace/attachRemote/forgetWorkspace mutates the maps.
- **Breaks:** ConcurrentModificationException crashes the app.
- **Fix:** ConcurrentHashMap or snapshot under a lock.

### F-43 [LOW] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/SyncEngine.kt:164,232,207`
- **Trigger:** Cancelled pass; reindex throws after a successful push.
- **Breaks:** runCatching/catch(Exception) swallow CancellationException; pushed=true lost; failed pull not reindexed.
- **Fix:** Rethrow CancellationException; keep pushed flag.

## P4. Security

### F-44 [MED-HIGH] (V)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/GitHubApi.kt:22,63; WorkspaceLinker.kt:75,151-155,182; Credentials.kt:458-462; ios/YantraCore/Sources/YantraCore/GitHub.swift:240-257,294`
- **Trigger:** User pastes https://evil.host/<owner>/<repo> where owner/repo is a github.com repo they can push to.
- **Breaks:** RepoRef.parse keeps the host; validate() checks api.github.com only; Android adds the remote at that host with the account token as credentials; iOS sends Authorization: Bearer to https://<host>/api/v3.
- **Fix:** Accept only github.com (or an explicit allowlist); attach credentials only when the remote host matches.

### F-45 [MED] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/Workspace.swift:267,320,329; ios/YantraCore/Sources/YantraCore/PageCodec.swift:626,731,524-540; ios/YantraCore/Sources/YantraCore/WorkspaceMove.swift:77-81`
- **Trigger:** A collaborator in a shared repo plants '^../../x' as a task id, '![[ink:../../x]]' or an image uri.
- **Breaks:** pageFile/inkFile/imageFile append the id unsanitised; deletePage/writeInk/copyTree/ImageBlock touch .md/.ink/.jpg outside pages/.
- **Fix:** Reject ids containing '/', '..', '\0' at decode (both platforms).

### F-46 [MED] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/MeetingText.swift:46-56`
- **Trigger:** Calendar text with https://notzoom.us/j/1, https://a.zoom.evil.com/x or ?r=meet.google.com.
- **Breaks:** Host is text before the first '/', suffix check lacks a dot boundary: a stranger's link gets a 'Join Zoom/Meet' button.
- **Fix:** Parse with URLComponents; require host == d or hasSuffix('.'+d).

### F-47 [MED] (A)
- **Where:** `.github/workflows/apk.yml:4-8,13,27-29,74-83`
- **Trigger:** Push to feature/**, fix/**, release/** or a same-repo PR.
- **Breaks:** Keystore and passwords are unpacked before arbitrary branch code (gradle) runs; job has contents: write; unquoted heredoc mangles passwords containing $ ` or \.
- **Fix:** Unpack the key only on tags in a separate environment-gated job; contents: read elsewhere; printf '%s' / env vars; pin actions to SHAs.

### F-48 [LOW] (A)
- **Where:** `scripts/stage-key-backup.sh:29-30; scripts/set-ci-secrets.sh`
- **Trigger:** Run on macOS.
- **Breaks:** BSD sed has no \s, so the SHA-256 in the encrypted README is empty; -storepass and --body visible in ps.
- **Fix:** Use [[:space:]]*, -storepass:env or stdin.

### F-49 [LOW-MED] (A)
- **Where:** `ios/Yantra/Sources/People.swift:136-137; Diagnostics.swift:65; AppModel.swift:332`
- **Trigger:** Invite a collaborator then export the log via ShareLink.
- **Breaks:** "\(outcome)" writes third-party logins into the shareable log; write.failed dumps raw errors with paths; Diagnostics JSON of non-JSON values can raise an uncatchable NSException.
- **Fix:** Log the case name only; domain+code for errors; isValidJSONObject guard.

## P5. Merge keying and conflict rules (both platforms, one change)

### F-50 [HIGH] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/ConflictResolver.swift:129-151,164-181; app/src/main/java/ie/shoonya/yantra/data/sync/ConflictResolver.kt:131-170`
- **Trigger:** Device A inserts or deletes a paragraph/heading/bullet; B edits a later block of the same kind (keys are 'Kind:n').
- **Breaks:** A deletes P1, B edits P3 -> [P2,P3,P3'] (old and edited both present). A inserts N on top, B edits P2 -> B's edit lost or duplicated.
- **Fix:** Stable per-block ids or content-hash matching; change both platforms in one change and regenerate conformance fixtures.

### F-51 [MED-HIGH] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/ConflictResolver.swift:41; app/src/main/java/ie/shoonya/yantra/data/sync/ConflictResolver.kt:64-69`
- **Trigger:** Two devices change labels.json, properties.json, a smart-list JSON, manifest, .ink or .jpg between syncs.
- **Breaks:** Local side wins on both devices; the other's labels, properties and strokes dropped, then overwritten by the next push (kept only in git history).
- **Fix:** Union-merge JSON registries by id; per-stroke merge for .ink. Also exclude archive/<id>.md (no frontmatter) from the page merge, which injects ---/id:/type: lines.

### F-52 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/ConflictResolver.kt:167-170,131-133`
- **Trigger:** A changes title/color/icon/pinned/parent or reorders blocks; B edits a task later (both rewrite modified_at).
- **Breaks:** mergePage takes frontmatter and order wholesale from the newer side: A's changes silently lost; parent: vs line can disagree.
- **Fix:** 3-way merge frontmatter fields against the base; use base block order.

### F-53 [LOW] (V)
- **Where:** `ios/YantraCore/Sources/YantraCore/ConflictResolver.swift:25; ios/YantraCore/Sources/YantraCore/SyncEngine.swift:256`
- **Trigger:** No-base conflict with equal modified_at.
- **Breaks:** otherDevice: "" so device > "" is always true: both devices keep local and the file can ping-pong.
- **Fix:** Pass the remote page's device.

## P6. Free-signed build (no App Group)

### F-54 [HIGH (this build)] (V)
- **Where:** `ios/Share/ShareViewController.swift:87-90; ios/Shared/AppGroup.swift:71`
- **Trigger:** Free-signed device build (no App Group): share anything.
- **Breaks:** Extension gets an unopened store, Inbox is nil, finish() runs silently: nothing created, nothing said.
- **Fix:** Show an error; or queue the share (as PendingTicks does) for the app to apply.

### F-55 [HIGH] (V)
- **Where:** `ios/Share/ShareViewController.swift:120-126`
- **Trigger:** Share a large photo.
- **Breaks:** UIGraphicsImageRenderer(size:) uses screen scale: '2048' is ~6144 px, >100 MB bitmap in an ~120 MB extension: killed; committed JPEGs are several MB.
- **Fix:** Use a renderer format with scale = 1 (also in the app's ImageImport use).

### F-56 [MED] (V)
- **Where:** `ios/Share/ShareViewController.swift:63,101,114`
- **Trigger:** Slow attachment (>3.2 s) or a failed write.
- **Breaks:** Auto-dismiss completes the request before capture() writes; catch {} leaves 'Added to Inbox' after a failure; unsupported types dismiss silently; finish() can run twice.
- **Fix:** Dismiss only after capture finishes; report failures; guard double finish.

### F-57 [MED (device check)] (A)
- **Where:** `ios/Shared/AppGroup.swift:190-281; ios/Shared/Intents.swift:15-37; ios/Shared/FocusActivity.swift:40-58`
- **Trigger:** Free-signed build: tap Pause/Stop/Done on the Focus widget or Live Activity.
- **Breaks:** LiveSession is in AppGroup.defaults (process-local here): the extension sees no session, does nothing, then FocusActivity.sync() ends the activity.
- **Fix:** Add an isShared guard + pending-command queue like ToggleDoneIntent; do not end the activity when the session is merely invisible.

### F-58 [MED] (V)
- **Where:** `ios/Shared/AppGroup.swift:231-242`
- **Trigger:** Start a focus session on a task in workspace B while one runs in A.
- **Breaks:** Old session is closed via B's writer: closing line lands in B's log, A's stays open.
- **Fix:** Close with the old session's own workspace writer.

### F-59 [LOW-MED] (V)
- **Where:** `ios/Shared/WidgetSnapshotStore.swift:74-84`
- **Trigger:** Widget tick queued while the app applies the queue.
- **Breaks:** removeShared(key) before applying: a tick added between read and remove is lost; a kill mid-apply loses the rest; add is an unlocked read-modify-write.
- **Fix:** Remove after applying; use update with a lock.

### F-60 [LOW-MED] (V)
- **Where:** `ios/Shared/AppGroup.swift:86-96,104-117; ios/Shared/Intents.swift:47-62`
- **Trigger:** Every widget render / intent in the extension.
- **Breaks:** openWorkspaces() runs upkeep() (writes, dir walk, sweep) and owning() re-reads the full index; 3 index reads per tick in a memory-limited extension; first-run extension could scaffold/seed.
- **Fix:** Read-only open for widgets; pass a node->workspace map.

## P7. UI, ink, widget, calendar and other bugs

### F-61 [MED] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/InkBlob.swift:516-541,342-381`
- **Trigger:** A tiny gzip blob that inflates to ~1 GB (ratio ~1000:1).
- **Breaks:** No decompression cap; ~6 more copies in decodeBatch; jetsam kill = crash loop on page open.
- **Fix:** Abort at ~16 MB inflated / 1M inputs.

### F-62 [HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/calendar/TimelineView.kt:1071,1097-1100,346`
- **Trigger:** Swipe from Mon to Tue then tap a free hour; or tap with a rail task armed.
- **Breaks:** pointerInput(Unit) freezes onTap/onDraft/onCommit at first composition: tap selects the old day, armed rail task ignored, drawn range goes to the old Editing.
- **Fix:** rememberUpdatedState inside the gesture; key(day) around the lane.

### F-63 [HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/calendar/TimelineView.kt:131,154-155,732,911,691,898`
- **Trigger:** Pinch on the day/week timeline.
- **Breaks:** hourHeight captured at first composition: zoom never compounds; block drag/resize use pre-zoom minutes-per-pixel.
- **Fix:** rememberUpdatedState(hourHeight); add it to the pointerInput keys.

### F-64 [MED-HIGH] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/node/NodePageScreen.kt:1102-1104; ui/node/NodePageViewModel.kt:614-639`
- **Trigger:** Rotate, fold, change font scale or theme while a page is open.
- **Breaks:** onDispose prunes trailing blank blocks (and any blank task) as the activity is recreated.
- **Fix:** Prune only on real navigation away (ON_STOP and !isChangingConfigurations).

### F-65 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/smart/SmartListScreen.kt:241,335`
- **Trigger:** Complete a task on a rule that excludes done tasks when the 'completed' flow lands first.
- **Breaks:** Two StateFlows merge to a duplicate LazyColumn key: IllegalArgumentException.
- **Fix:** distinctBy { it.id } or combine in the ViewModel.

### F-66 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/node/NodePageScreen.kt:368-374,636-638; WorkspaceWriter.kt:311`
- **Trigger:** Drag-reorder on a list page whose file holds hidden blocks (sittings, prose, ink).
- **Breaks:** Index into the filtered list applied to the full block list: drop lands off by the number of hidden blocks.
- **Fix:** Map to the full-list index.

### F-67 [HIGH (stylus)] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/ink/InkCanvas.kt:414-426,346`
- **Trigger:** Pen drawing and a palm/finger lands; or finger touches before the first pen touch.
- **Breaks:** ACTION_POINTER_DOWN ignores tool type: stroke cancelled, pen+palm drive a pinch; FLAG_CANCELED ignored.
- **Fix:** Ignore FINGER/PALM pointers during an active pen gesture; honour FLAG_CANCELED.

### F-68 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/ink/InkScreen.kt:295-363`
- **Trigger:** Draw several strokes, erase/move one, then Undo.
- **Breaks:** undo() only pops the newest stroke: deletes an unrelated stroke, cannot undo an erase or revert a move; redo changes z-order.
- **Fix:** History as a stack of invertible operations.

### F-69 [MED-LOW] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/ink/InkScreen.kt:379-397`
- **Trigger:** Timer write in flight, user draws once more, onPause flushNow.
- **Breaks:** Two ink.replace calls race to the mutex; older snapshot can land last; disk-full IOException uncaught crashes; isAhead refusal still marks clean.
- **Fix:** Serialise writes with a sequence number; catch and surface errors.

### F-70 [MED] (A)
- **Where:** `ios/Yantra/Sources/InkView.swift:372-376,702`
- **Trigger:** Use the shape tool or snap a stroke.
- **Breaks:** Tip width omits InkBridge.tipPerWidth (3.14x pen / 1.47x marker): shapes ~3x thinner than freehand and saved that way (reaches Android).
- **Fix:** Multiply w by tipPerWidth(inkType).

### F-71 [MED-HIGH] (V)
- **Where:** `app/src/main/java/ie/shoonya/yantra/domain/FocusTimer.kt:156-176,93-123; widget/FocusFinalizeWorker.kt:31`
- **Trigger:** Start a 25-min session and lock the phone.
- **Breaks:** Elapsed is a tick count (delay stops in suspend); FocusFinalizeWorker wakes but restoreIfNeeded returns Nothing (state != null): session ends late with the wrong elapsed, no bell.
- **Fix:** Derive elapsed/remaining from a wall-clock anchor; worker reconciles and finishes if the end has passed.

### F-72 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/domain/FocusTimer.kt:178-186,112-120,131-151`
- **Trigger:** Pause then the process dies; or double-tap Start.
- **Breaks:** Pause is in-memory: restore counts paused time (25-min promise closed as RAN_OUT); start has no mutex: orphaned session row.
- **Fix:** Persist paused-at; share the restore Mutex with start/end.

### F-73 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/reminders/ReminderManager.kt:133-135,97-104`
- **Trigger:** Phone off at reminder time (08:00), reboot at 09:00.
- **Breaks:** rescheduleAll arms only future instants: missed reminder never shown.
- **Fix:** Post immediately for rows within a grace window that have not fired (persist a fired marker).

### F-74 [MED] (A)
- **Where:** `ios/Yantra/Sources/Notifications.swift:25-27,92-103; ios/Yantra/Sources/Sheets.swift:71; ios/Yantra/Sources/EventSheet.swift:104-110,206`
- **Trigger:** Fresh install; start a focus session without setting a due reminder; or set 'Remind: 15m' on an event.
- **Breaks:** requestPermission has only two callers, so the focus bell never rings; event reminderMin is saved but syncReminders only handles tasks.
- **Fix:** Request on first timer.start; show 'notifications off'; schedule events or hide the chips.

### F-75 [MED-HIGH] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/PageCodec.swift:67,72,83-85`
- **Trigger:** Device region/calendar set to Buddhist, Japanese, Islamic or Hebrew (e.g. Thai).
- **Breaks:** Calendar.current returns year 2569 / 8: today, month grid, stats window, bucketing and startOfDay are wrong.
- **Fix:** Calendar(identifier: .gregorian) with an explicit zone, or pure epochDay arithmetic.

### F-76 [MED] (A)
- **Where:** `ios/Yantra/Sources/SignInView.swift:91,137-179`
- **Trigger:** Press Sign in then leave the screen, or press it twice.
- **Breaks:** Unstructured poll loop keeps running ~15 min: a late approval silently stores the token while the UI shows signed out; second loop with a second code; try? sleep swallows cancellation.
- **Fix:** Store and cancel the Task on disappear/restart; try await sleep; add a Cancel button.

### F-77 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/sync/SignInScreen.kt:137,302-431,335-338,350`
- **Trigger:** Rotate or kill the process while approving in the browser; or account() fails right after a good poll.
- **Breaks:** Device code lost, approval never collected, each retry burns a token slot; token discarded after a transient account() failure; Keystore exception inside LaunchedEffect crashes.
- **Fix:** Persist DeviceCode; retry account(); runCatching around signIn.

### F-78 [MED] (A)
- **Where:** `ios/Yantra/Sources/SignInView.swift:165-172,296,444,411-414`
- **Trigger:** Transient viewer() failure after a token; fast double-tap on connect/createRepo/keepBoth.
- **Breaks:** Token and method stored before viewer() succeeds; busy set after an await: workspace added twice / .exists. GitHub.swift:149 slow_down never accumulates; :154 a 5xx body ends the flow.
- **Fix:** Store after success; set busy first; treat 5xx as offline; accumulate interval.

### F-79 [MED] (A)
- **Where:** `ios/Shared/FocusActivity.swift:46; ios/Widgets/FocusLiveActivity.swift`
- **Trigger:** A committed countdown runs out while the app is not running.
- **Breaks:** Nothing updates/ends the activity: frozen 0:00 with live Pause/Stop/Done for hours; isStale never read; two quick sync() calls request two activities.
- **Fix:** Use staleDate + an isStale view branch; end from the bell handler; serialise sync().

### F-80 [MED-LOW] (A)
- **Where:** `ios/Yantra/Sources/CalendarView.swift:282; DeviceEvents.swift:79,113`
- **Trigger:** Calendar open while a pull changes content without changing node count; edit in the system Calendar app; open a later occurrence of a recurring device meeting.
- **Breaks:** Buckets stale (.onChange(nodes.count)); EKEventStoreChanged never observed; 'Open in Calendar' opens the series' first date.
- **Fix:** Trigger on a revision counter; observe EKEventStoreChanged; prefer event?.time.start.

### F-81 [LOW-MED] (A)
- **Where:** `ios/Yantra/Sources/DayTimeline.swift:31-32; ios/Yantra/Sources/DayTimeline.swift:293-296,279-282`
- **Trigger:** Drop a task on 23:00; 5+ concurrent items at 3 days across; calendar left open.
- **Breaks:** Zero-length sitting written; blocks overlap (24 pt min width); now-line frozen and 'today' stale past midnight.
- **Fix:** End at 00:00 next day; cap columns with '+N'; TimelineView(.everyMinute).

### F-82 [LOW-MED] (A)
- **Where:** `ios/Yantra/Sources/SettingsView.swift:192`
- **Trigger:** Open Stats on a week with sessions on other days.
- **Breaks:** dayCounts is zeros except today: earlier days always show 0 rings.
- **Fix:** Share FocusView.dayCounts.

### F-83 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/widget/ListWidget.kt:427-431,458-460`
- **Trigger:** Complete a task in list L Monday; edit any line on L's page Tuesday.
- **Breaks:** Today 'DONE' filters updatedAt >= midnight and updatedAt is the page's modified time: old completed tasks reappear (device check).
- **Fix:** Index doneAt and filter on doneAt == today (setDone already stamps it, Repos.kt:451).

### F-84 [HIGH (device check)] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/widget/ListWidget.kt:435-441,448,656-696,319`
- **Trigger:** Bind the list widget to a list/smart list with a few hundred open tasks.
- **Breaks:** No row cap: Glance packs items into one RemoteCollectionItems payload (doubled by Responsive); ~1 MB gives TransactionTooLargeException, widget blank on every emission.
- **Fix:** rows.take(50-100) + '+n more'; cap the unbounded done query.

### F-85 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/widget/CalendarWidgetActions.kt:25-26,43; CalendarWidget.kt:179`
- **Trigger:** Set Month on a large widget, then resize below 300x260 dp.
- **Breaks:** Paging keys use the stored view while the render uses the effective one: each tap jumps a month on a single-day view.
- **Fix:** Pass the effective view (m.allow) to the action or write it to state on resize.

### F-86 [MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/widget/QuickAddActivity.kt:99-101,114; WorkspaceWriter.kt:236-237`
- **Trigger:** Bind a widget to list L, delete L (or forget its workspace), tap '+'.
- **Breaks:** writer returns '' which is treated as success: task lost with no toast. Also double-submit creates two tasks (no saved flag).
- **Fix:** Treat blank id as failure and fall back to the Inbox with a toast; add a saved flag.

### F-87 [MED-LOW] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/widget/ListWidget.kt:335-336,364-405,719; widget/ListWidget.kt:361-366; CalendarWidget.kt:228-234`
- **Trigger:** Bound list deleted/forgotten; cold-woken widget before the index/defs exist.
- **Breaks:** Widget says 'All clear' with live '+'; Today says 'Tap and hold to set up'; Calendar reports ready with 'Nothing on' while tasks exist.
- **Fix:** Emit a 'List removed - tap to choose' state; retry/observe lookups; keep ready=false while missing.

### F-88 [LOW-MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/widget/actions/ToggleDoneAction.kt:21-34; widget/WidgetRefresh.kt:9-12`
- **Trigger:** Tap the checkbox on a stale row; complete a task from the list widget while the calendar widget is shown.
- **Breaks:** Toast says 'completed' though nothing was written; done is the render-time value; calendar widget not refreshed.
- **Fix:** Read current done from the DB; confirm the node exists; update the calendar widget too.

### F-89 [MED (device check)] (V)
- **Where:** `ios/Widgets/ListWidgets.swift:185-207`
- **Trigger:** Today widget with overdue + today + done sections.
- **Breaks:** 'shown' is mutated inside a view body and the inner ForEach closure runs after the outer check: row limit can be exceeded, pushing the TODAY section out of view.
- **Fix:** Compute the visible rows before the view builder (pure function).

### F-90 [LOW] (A)
- **Where:** `ios/Yantra/Sources/PageSwipe.swift:148; ios/Yantra/Sources/CaptureField.swift:82-94,117-133; ios/Yantra/Sources/People.swift:133-157; ios/Yantra/Sources/YantraApp.swift:99,102-131; ios/Yantra/Sources/ListView.swift:96; ios/Yantra/Sources/TaskPageView.swift:275,283`
- **Trigger:** Various.
- **Breaks:** pageOnSwipe leaks one UIPanGestureRecognizer per visit; restyle cancels IME marked text; invite never re-fetches (fetching still true); toast timer not keyed; quick-add clears text when capture refused; Image/Ink chip appends at end (editing already nil); repeated yantra://focus pushes duplicate screens; open/<paragraph-id> opens a blank page.
- **Fix:** Per-item fixes in the cited lines (dismantleUIView, markedTextRange guard, clear fetching, .task(id:), Bool return, capture editing before commit, skip if top route equal, only allow types with a page).

### F-91 [LOW-MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/share/ShareTargetActivity.kt:71-107; ui/ink/InkCanvas.kt:704-706; widget/WidgetConfigActivity.kt:62-63; widget/WidgetSettingsActivity.kt:185-192; ui/node/PropertySheet.kt:217`
- **Trigger:** Spannable EXTRA_TEXT; rotation within 3.2 s; every ink screen open; every widget config open; fast typing in a number property.
- **Breaks:** Share dropped / duplicated / crash in appScope; one executor leaked per open and RenderNodes not discarded; eager stateIn collectors never cancelled; number field flickers back.
- **Fix:** getCharSequenceExtra, skip capture when savedInstanceState != null, runCatching; shut down executor; WhileSubscribed; keep local text, write on blur.

### F-92 [LOW-MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/ui/calendar/CalendarViewModel.kt:139-142,254-270,37,52,79,452; reminders/BootReceiver.kt:34`
- **Trigger:** Open the calendar / device-calendar change / travel across zones / leave open past midnight.
- **Breaks:** Blocking ContentResolver queries on Main (jank/ANR); all-day task rewritten as timed 00:00; zone captured once; rail stale after midnight; timezone change does not reindex.
- **Fix:** flowOn(IO); respect d.allDay in movedTo; read the zone per query; day-tick flow; reindex on ACTION_TIMEZONE_CHANGED.

### F-93 [LOW-MED] (A)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/capture/CaptureParse.kt:115,502-509,514,385-399; ui/smart/SmartListBuilder.kt:558-560; ui/node/NodePageViewModel.kt:152-156`
- **Trigger:** 'Buy 1.5 litres' / 'Read 3.5 hours'; '29 feb'; 'I sat down'; typing '1.' in a number field; people refresh throws.
- **Breaks:** 1.5 read as 1 May and stripped from the title; 29 feb dropped; lone 'sat/sun/mon/wed' becomes a date and is stripped; decimals/negatives cannot be typed; spinner sticks and exception crashes.
- **Fix:** Accept '.' only with a 4-digit year; roll leap day; require a day-word context; keep raw text; try/finally.

### F-94 [LOW] (V)
- **Where:** `app/src/main/java/ie/shoonya/yantra/data/sync/GitHubApi.kt (and iOS GitHub.swift:326,334)`
- **Trigger:** GitHub 403/429 on ref/commit reads.
- **Breaks:** SyncError.http carries no message for ref/commit, so the screen says 'GitHub answered 403 reading ref'; no backoff or Retry-After; iOS repositories()/collaborators() read one page of 100.
- **Fix:** Include GitHub's message; honour Retry-After; paginate or say 'showing first 100'.

### F-95 [LOW] (A+run)
- **Where:** `ios/YantraCore/Sources/YantraCore/InkPages.swift:27,44,48; TimelineLayout.swift:47,152; DateTime.swift:115-116; KotlinJSON.swift:20; PageCodec.swift:1097-1111; MeetingText.swift`
- **Trigger:** Callers passing NaN/inf/negative counts; huge hours/minutes; encoder errors; nested bold; &amp;lt;.
- **Breaks:** Int(Double) and (0..<count) trap; m*60 overflow; KotlinJSON.encode returns 'null' and callers write it to the file; Markdown.plain leaves inner markers; &amp; decoded before &lt;.
- **Fix:** Guard inputs; throw instead of returning 'null'; decode &amp; last.

### F-96 [LOW] (A)
- **Where:** `conformance/tools/oracle.sh:20,25; conformance/tools/roundtrip.sh:14,29`
- **Trigger:** Kotlin/serialization version bump or compile error.
- **Breaks:** Compile failures swallowed, OUT never cleaned: stale classes regenerate the golden fixtures both apps test against; roundtrip prints 'identical: 0 files' when both sides are empty.
- **Fix:** rm -rf OUT first, drop '|| true', fail on empty dirs/classpath segments.

### F-97 [LOW] (A)
- **Where:** `gradle.properties:15-16; .github/workflows/apk.yml:69; .github/workflows/ios.yml`
- **Trigger:** AGP/Kotlin major upgrade; any release.
- **Breaks:** AGP 9 opt-out flags break the build at the next major; CI runs testDebugUnitTest only (MigrationTest, IndexCostTest, instrumented tests never run); ios.yml has no permissions block and an unpinned brew install; proguard keeps JGit classes by name.
- **Fix:** Migrate the flags one at a time; add an emulator/managed-device job on tags and main; pin and set permissions.

## Stress / degradation points

- **S-01** Rank cliff at 769 blocks per page (see F-01).
- **S-02** iOS AppModel.reindex (AppModel.swift:158-165): every write queues a full WorkspaceIndex.read + whole focus ledger on main; capture makes 3+ changes; a pull reindexes twice (:100,:247).
- **S-03** iOS per-row scans: openChildCount/children(of:) filter+sort all nodes per row (ListView.swift:31-32,73,83,219; HomeView.swift:112,268,364); grammar reads registry.json per row; Move-to sort reads ~4 files per comparison (AppModel.swift:583).
- **S-04** iOS sync reads and SHA-1s every file each pass and saveBase writes every blob (SyncEngine.swift:122-132,75-90); blob uploads are sequential and not resumable (GitHub.swift:428-436): a bulk import can restart from zero each background window.
- **S-05** No sync coalescing on either platform: each structural change starts its own full pass (iOS CommitScheduler.record, Android CommitScheduler.kt:46); on iOS they queue serially in SyncGate.
- **S-06** Android taskOrigins() (Daos.kt:318-333): recursive CTE over every task in every workspace, collected 2-4 times at once (App.kt:345,357; CalendarViewModel:146; CalendarWidget:244); re-runs on every node write.
- **S-07** Android Home counts (HomeViewModel.kt:79-110) load full NodeEntity rows twice per smart list per keystroke; SmartListViewModel chips (:221-223) load whole property_value and node_label tables; IN(:ids) can hit SQLite's 32,766-variable limit.
- **S-08** Android indexes: idx_pv_def has v_date 4th (Entities.kt:204) so date-range queries scan; no index on node.in_progress/type/system_key or focus_session.started_at; event inRange ORs rrule IS NOT NULL.
- **S-09** Android Indexer: every process start takes the wholesale wipe-and-reinsert path (Indexer.kt:108); sameInk compares by reference (Indexer.kt:351) so a cache miss rewrites every ink blob; rebuild is O(workspace) per write.
- **S-10** Android ink: eraser tests every stroke with no bounding-box cull and allocates per point (InkCanvas.kt:852-863, ~600k allocations/event at 2,000 strokes); lasso pan is O(N*K) per frame (:845-850,773-788); camera change re-records the whole node (:1168-1172); every pause rewrites the whole sidecar.
- **S-11** iOS ink: 50 history snapshots at ~2.2 MB each at 5,000 strokes (~110 MB); save runs on main; shape drag copies the drawing per touch move (~14 ms at 5,000 strokes).
- **S-12** Widgets: Android ListWidget has no row cap (F-84), focus widget recomposes every second (FocusWidget.kt:99, BhupuraWidget.kt:102); iOS widgets call ancestors per row and read the whole index per render.
- **S-13** Pathological pages: 20,000 numbered items take 16.5 s to encode (PageCodec.swift:904-910 ordinalOf O(n^2)); 5,000 unclosed ** take 2.9 s in Markdown.plain (:1088-1090); encode re-parses every block (~40 us/line); decode frontmatter lookup is O(k^2).
- **S-14** Per-keystroke work on a long Android page: NodePageViewModel.linkedNodes re-queries without distinctUntilChanged (:303-316); linkDraft and liveHere invalidate the whole screen scope (NodePageScreen.kt:236,738,1038; :516,1367 + VM :166-169).
- **S-15** iOS TaskPageView body: readPage + decode per keystroke (x6 in the type bar) and ImageBlock decodes the JPEG in body per publish (TaskPageView.swift:24,58,158,783-792).
- **S-16** No timeouts / rate limits: Android JGit none (F-37); iOS requests 30 s each but the pass has no deadline; no backoff on 403/429 (F-94).
- **S-17** Unbounded growth: iOS focus ledger (re-read every reindex), ArchiveView loads every archive file on appear, InkDecodeCache never evicts (TaskPageView.swift:205); Android StatsScreen focus.all() on every ledger change (:241).
- **S-18** iOS launch: AppModel.init runs openAll, reindex, sweepArchive (writes) and timer.wake synchronously and blocks the first frame; SettingsView body reads archives and the whole diagnostics log per render (71 ms at 4 MB, measured).

## Fragility map

| Subsystem | Dependency | Failure mode | Fallback / blast radius | Assumption that could become false |
|---|---|---|---|---|
| iOS sync | GitHub REST (Git Data + Contents) | Loud on 401/offline, opaque on 403/429, partial on timeout | 3 attempts only on non-fast-forward; whole workspace at risk (F-08) | 409 = empty repo; 'fast forward' in the 422 message |
| Android sync | JGit + HTTPS | Stall silent and infinite; kill mid-rebase permanent | SyncWorker every 15 min but it returns before the work | JGit's default timeout is acceptable |
| Auth (both) | GitHub device flow, refresh rotation | Refused refresh clears the token; lost save after rotation signs the user out | iOS single-flight + offline-safe; Android one blind retry | A refresh with client_id alone works (CLAUDE.md section 4: unproven) |
| Format + merge | Plain markdown shared by both apps | Wrong merge is silent; malformed line can crash on load | Git history only | Block keys positionally stable; decode never traps; ids contain no path separators |
| iOS workspace store | Local filesystem / App Group | try? swallows write failures | None | Unreadable file == deleted file; failed decode == empty |
| Android workspace store | Filesystem + Room index | Write then index; a throw after the write skips onChange | reconciler.problems | Sidecars are well-formed |
| Widgets/extensions | App Group or shared keychain snapshot | Free-signed build: no data, Focus does nothing, ticks queued in keychain | Snapshot capped at 90 KB; PendingTicks for ticks only | AppGroup.defaults is shared; extension memory limit is not reachable |
| Reminders/focus (Android) | AlarmManager, FGS, process lifetime | Silent drift; missed-while-off reminders lost | Finalize worker (cannot fix drift) | Process stays alive; tick time == wall time |
| Calendar (iOS) | EventKit, Calendar.current | Wrong dates on non-Gregorian calendars; stale after external edits | None | Device calendar is Gregorian; EKEventStoreChanged unneeded |
| Calendar (Android) | CalendarProvider on Main | Jank/ANR; swallowed error shows an empty list | runCatching | Provider is fast; zone/date constant |
| Ink | androidx.ink 1.0.0, PencilKit | Native decode failure drops the stroke; float details pinned to 1.0.0 | conformance/ink fixtures | RestrictedApi internals stay; creationDate unique per stroke |
| Android Room | Schema and queries | Migrations fine (21 registered); a filter reading an unlisted table goes stale silently | MigrationTest not run in CI | New filter clauses only read tables in observedEntities |
| Build/CI | AGP, Kotlin, KSP, Compose BOM, JGit | AGP 9 opt-out flags break at next major; JGit rename breaks release only | Manual 'install the release APK' step (RELEASE.md) | ksp 2.3.2 stays compatible with Kotlin 2.2.21 |

## Redundancy and duplication

- **R-01** Duplicate: pure epochDay arithmetic and Calendar-based LocalDate math (PageCodec.swift:67-89). Only the Calendar one can trap, saturate or go wrong on non-Gregorian calendars. Keep the epochDay one.
- **R-02** Duplicate: two() parser x3 (PageCodec.swift:29, DateTime.swift:250,369); 9-digit fraction parse x3 (DateTime.swift:161,258,382); KotlinFloat.shortest (InkBlob.swift:551-589) vs KotlinJSON.javaDouble (:59-83).
- **R-03** Duplicate: Android Flow/Once DAO query pairs with copy-pasted SQL (Daos.kt defs/defsOnce :431/434, builtInDefs :438/441, valuesForNode :447/450, textValuesInUse :466/477, observeActiveReminders/activeRemindersOnce :492/504 (reminders can disagree), builtInDefIdByName :560/563, observeEventReminders/eventRemindersOnce :989/999, LabelDao.all/allOnce :758/761); EventWithTitle projection x3 (:899,946,959). Implement Once as flow.first() or share the SQL.
- **R-04** Duplicate: swipe-to-start gesture + handOverRing (NodePageScreen.kt:1444-1483 and :2420-2468); TextualBlockRow(...) call spelled twice (:1859,:1874); InlineStyle remember (:1507,:2190); resolveLink remember (:454, SmartListScreen.kt:211).
- **R-05** Duplicate: day counting (FocusView.dayCounts :162 and SettingsView.swift:192); end-time calc (DayTimeline.place :31-32 and MonthGrid.place CalendarView.swift:467-470); Monday-first weekday x3 (TimelineLayout.swift:149, CalendarModel.swift:348, CaptureParse.swift:298); device-local id sets in UserDefaults x3 (FoldedGroups, InkFolds, TaskPageView ~:720).
- **R-06** Redundant state: property_value.v_number == v_reminders[0] (PageMapper.kt:229-230); v_bool derivable from DueValue kind; done+in_progress encode 3 states in 2 booleans; ext_uid/ext_start on both node and event; created_at == updated_at == page modified time (PageMapper.kt:116-149; makes default 'newest first' ordering arbitrary); iOS running/sessions/HomeRow.counts re-derived every reindex; NodePageViewModel observes the same node 3x (:78,:94,:187).
- **R-07** Redundant I/O: iOS AppGroup.owning() = openWorkspaces() (upkeep + writes) + full index read per command (AppGroup.swift:113-115); widgetIndex() per render; WorkspaceRegistry.entry/color re-read registry.json per call; InkBlockPreview loads twice per appearance (TaskPageView.swift:575-577); CalendarView.select triggers two refresh()es (:132-135,283-284); WidgetSnapshot.encode re-runs make per halving.
- **R-08** Dead (Android DAO methods, zero callers): NodeDao countAll lastRank lastRankTopLevel nextRank setTitle move subtreeIds softDelete softDeleteSubtree insert; InkDao strokeById softDeleteLast softDeleteAll softDeleteById lastRank insert; PropertyDao deleteValue upsertDef upsertValue; EventDao overridesOf sittingsFor; FocusDao totalBetweenIn perNodeBetween insert; SmartListDao upsert. deleted_at is never written yet 'deleted_at IS NULL' sits in ~40 queries.
- **R-09** Dead columns: node.canvas_x/canvas_y, ink_stroke.bbox_x/y/w/h (never read or written), event.series_original (written PageMapper.kt:208, never read). node.collapsed is reset at every cold start.
- **R-10** Dead code: Android Indexer.kt:116-169 is unreachable (returns early when was != null, so was == null below; leavingDependents and the PRAGMA branch can never run).
- **R-11** Dead code Android NodePageScreen: justCreatedId never assigned (autoFocus always false: autoFocusConsumed, LaunchedEffect(autoFocus) :2203-2208, params :1808,:1842,:2138, SmartListScreen.kt:290-291); inlineChips constant false (:2356; branch :2668-2673, shownChips :2335, FocusCount, pomoCount plumbing :225,824,1663,1803,1837,1861,2135, vm.pomoCounts); NodePageViewModel.moveUp/moveDown :536-541; unused params InkBlockRow active/vm :2782,2784, ImageBlockRow active :2864, BlockRow origin :1838; orphan KDoc :1890-1893, :3010-3015; PropertySheet CHECKBOX/NUMBER/free-text branches :210-246 probably dead.
- **R-12** Dead code iOS: JSONValue.swift (157 lines incl. JSONParser; test-only), Reminders (ListIcon.swift:62-82), PageCodec.tokenStart (:654), Links.targets (:1045), CaptureParse.linkNames (:57), CalendarBucketer.externalKey (CalendarModel.swift:186), TimelineLayout.weekOf (:138), ISODuration.hours (DateTime.swift:116), InkBlob.isPortable/version (:98-103), railShelves zone: parameter, AppModel.makeTaskForMeeting (:635), DeviceCalendars.ownerURL and CalendarInfo.visibleInOwner, SignInView.phase (:9) and copied never reset (:19), ledger_changed_at (AppGroup.swift:279), SyncGate.isBusy, InkPages canZoomIn/canZoomOut; WorkspaceWriter methods with no callers (moveBlock setDeadline setLabels setEventTime setEventTitle cancelOccurrence archivedCount) are deliberate pre-ports for UI listed in CLAUDE.md section 5; confirm before deleting.
- **R-13** Release hygiene: -calmode and -calrail launch-argument hooks ship outside #if DEBUG (CalendarView.swift:269-276).
- **R-14** Over-engineering: Android NodeRepository, PropertyRepository.defs, FocusRepository.*, InkRepository are 1:1 DAO forwards and the UI also calls DAOs directly (App.kt:341-357, CalendarViewModel:146): enforce the layer or inline it. rememberRowActions (NodePageScreen.kt:1621-1645) re-declares 14 interface methods to forward through rememberUpdatedState; PageRowActions has one implementation.

## Optimization (hot paths only)

- **O-01** iOS reindex: coalesce with a pending flag, read off main, re-read only the changed page (Change should carry the page id). Gain: ~10x at 5,000 tasks. (`AppModel.swift:158-165,73`)
- **O-02** iOS WorkspaceIndex: build childrenByParent and open counts once in read(); hoist allRows/viewIds/allLists out of body. Gain: removes O(rows*N log N) per publish. (`Workspace.swift:675-687; ListView.swift; HomeView.swift:97-112`)
- **O-03** iOS: cache allStores/workspaceName/workspaceColorName; resolve names once before sorting; invalidate in workspacesChanged. Gain: removes thousands of file reads at 100 lists. (`AppModel.swift:583,129; WorkspaceRegistry`)
- **O-04** iOS: replace AppGroup.owning/openWorkspaces per command and per widget render with AppModel.writerFor(id) or a node->workspace map; read-only open for widgets. (`AppGroup.swift:113-117; WidgetSnapshotStore.swift`)
- **O-05** iOS rebuildRunning: read titles from the index; assign running only when changed (removes a global redraw every minute). (`AppModel.swift:419-443`)
- **O-06** iOS TaskPageView: derive blocks once per index change; cache UIImage in @State loaded with .task. (`TaskPageView.swift:24,58,158,783-792`)
- **O-07** iOS calendar pinch: persist hourHeight on gesture end, cache TimelineLayout.forDay in CalendarModel.rebuild, reuse DateFormatters (7 per frame today). (`CalendarView.swift:35-37; DayTimeline.swift:156-163,242`)
- **O-08** iOS ink shape drag: preview in a CAShapeLayer, commit once on .ended; cap history by bytes; move InkView.save off main. SettingsView: compute archive/diagnostics counts in .task. (`InkView.swift:349-351,166-170,187-213; SettingsView.swift:69,77`)
- **O-09** iOS PageCodec: running counter instead of ordinalOf backwards walk; re-check only dirty blocks in rawStillDescribes. Gain: 16.5 s -> linear at 20,000 items; -80% encode at 7,000 lines. (`PageCodec.swift:904-910,876,893-902`)
- **O-10** iOS sync: hash only changed files (stat + cached sha), keep created blob shas across attempts, fetch with limited parallelism. Gain: bulk-import passes complete within background windows. (`SyncEngine.swift:122-132; GitHub.swift:428-436`)
- **O-11** Android taskOrigins: one shared StateFlow, restrict the base case to in-progress/on-screen tasks or filter by id. Gain: large at 100x. (`Daos.kt:318-333; App.kt:345,357`)
- **O-12** Android Home counts: SELECT COUNT(*), SUM(done) variant from FilterCompiler; scope chip value/label queries to matched ids. (`HomeViewModel.kt:79-110; SmartListViewModel.kt:221-223; Daos.kt:580,785`)
- **O-13** Android indexes: Index(def_id, v_date), Index(in_progress, done), Index(system_key), Index(started_at) (needs a Room migration). (`Entities.kt:204; Daos.kt:305,213,536,502`)
- **O-14** Android Indexer: persist a per-workspace content hash to skip the cold-start wipe; compare ink by id+size/hash. (`Indexer.kt:108,351`)
- **O-15** Android StatsScreen: query the last N days or aggregate in SQL instead of focus.all(). (`StatsScreen.kt:241`)
- **O-16** Android NodePageViewModel: distinctUntilChanged() before flatMapLatest in linkedNodes; move linkDraft/liveHere reads into small composables. (`NodePageViewModel.kt:303-316; NodePageScreen.kt:236,738,1038`)
- **O-17** Android ink: cull on cached itemBoxes before strokeHit and test the cached StrokePath, one indexed pass for selection bounds, translate the cached RenderNode on pan, early-return in setStrokeItems. (`InkCanvas.kt:852-863,845-850,773-788,1168-1172,227-264`)
- **O-18** Widgets: row caps (Android), precompute origin per row, sort once in WidgetSnapshot (decorate-sort), read-only open. (`ListWidget.kt:435-448; WidgetSnapshot.swift:29-58`)
- **O-19** Decorate-sort railShelves (startOfDay/lowercased inside the comparator; 48 ms per evaluation at 3,000 tasks); hoist day.adding(days:1) out of spanOf; cache DateFormatter in EventWords.day. (`TimelineLayout.swift:128,209-216; EventWords.swift:21`)

No optimization needed: iOS FocusTimer 1 s tick, scheduleFollowUp 0.6 s debounce, SmartListQuery.run single pass, InkView.pages, ShapeRecognizer, BackgroundSync/flushOnLeaving, Android HomeScreen filters over tens of lists.
