# Git-backed workspaces — implementation plan

Adapted from `~/Downloads/git-task-app-implementation-plan.md`, which was written for a greenfield
app. This one is written for YANTRA as it actually exists.

**Concept unchanged:** a workspace is a git repo. Files are the source of truth; Room becomes a
disposable index rebuilt from a checkout. Sync is fetch → rebase → push, with per-file
last-writer-wins only on genuine conflicts.

---

## 0. What differs from the original plan, and why

| Original | Here | Reason |
|---|---|---|
| Phase 1 builds a task app | Phase 1 is already done | YANTRA is a working offline task manager. What's missing is the file format and the inversion. |
| One file per task, free-form body | One file per **page**; blocks are lines in it | A YANTRA page is a run of typed nodes (`paragraph`/`heading`/`bullet`/`numbered`/`ink`/`image`/nested `task`), not a notes blob. |
| Ink as SVG sidecars | Ink as the existing `StrokeCodec` blob | SVG discards pressure, tilt, orientation, per-point timing, tool type and brush family. The plan wanted SVG for diffability, then declared ink whole-file LWW — so mergeability buys nothing and the loss is paid for nothing. |
| Tasks share a branch with code, or the repo must be empty | Tasks live on a dedicated **orphan branch** | Disjoint history: never in a code diff, never merged by accident, doesn't trigger branch-filtered CI, unaffected by `main`'s branch protection. Also deletes the plan's "repo must be empty or a task workspace" rule — we never touch their content. |
| ULID filenames | Existing UUIDv4 | Filename sort order buys nothing; order is line position (see §2). |
| `archive/` for done tasks | Deferred | YANTRA has no archive concept. Revisit if the hot directory gets slow. |
| Hierarchy via `parent` pointers | Hierarchy via **file containment + line position** | A page's children are its lines. Nothing to point at. |

Local data is mock and is not being preserved. No migration path is owed to it.

## 1. Locked decisions

| Decision | Choice |
|---|---|
| Workspace | 1 workspace = 1 repo + 1 branch (default `yantra-tasks`, orphan) |
| Git library | JGit 7.3.0 — **verified on device**, see Phase 0 |
| Auth | Fine-grained PAT over HTTPS first (fewer failure modes), Ed25519 SSH later |
| Source of truth | Files. Room is an index, rebuilt from the working tree |
| Conflict policy | git merge first; per-file LWW on `modified_at` only on genuine conflict |
| Delete vs edit | **Edit wins — resurrect the file.** An extra task is a nuisance; a deleted task holding someone's notes is data loss |
| Identity | GitHub login, obtained at auth. Used for `assignee` and as the LWW tiebreak |
| Ordering | Line position. `rank` becomes an index-only detail, regenerated on import |

## 2. The file format

The contract everything else layers on. Freeze this before writing anything else.

### What is a file

**A page is a file.** Lists, groups, smart lists, and any task that has page content or properties.
A plain task with neither is just a line on its parent's page and has no file of its own.

**A task is a line on its parent's page, and its page is a separate document.** That mirrors the
data model exactly — `title`, `done`, `indent` and position belong to the line; the page is what the
chevron opens. No field is stored twice, so nothing can diverge.

### Layout

```
repo-root/                      (orphan branch: yantra-tasks)
├── .yantra/
│   ├── manifest.json           # format_version, workspace name, created_at, epoch
│   └── meta/
│       ├── properties.json     # property defs, ids stable and shared (see §3)
│       ├── labels.json         # label registry: name -> colour
│       └── smartlists/<id>.json
├── pages/
│   ├── <uuid>.md               # a page
│   └── <uuid>.ink              # StrokeCodec blob sidecar
└── pomodoro/
    └── <yyyy-mm>.log           # append-only session lines
```

### A page file

```markdown
---
id: 7c3f...
type: task
parent: 1a2b...
title: Wire up the sync worker
modified_at: 2026-08-25T14:22:31.402Z
device: sm-s921b
---
# Welcome

A list holds tasks. A task's own page holds anything.

- [ ] Tasks can nest ^9f1e... due:2026-08-26 !high #sync @batunii
>> - [x] An indented, finished subtask ^4d2c...
>> Prose indented under it, still prose.

![[ink:5b8a...]]
```

Rules:

- **Body lines are blocks, in order.** Order is line position — there is no `rank` in the format.
  Reordering rewrites the file; the index regenerates ranks on import.
- **`>>` prefixes visual indent**, one per level. Deliberately not nesting: YANTRA separates how a
  line is laid out from where it lives, and a markdown-native format would conflate them.
- **`- [ ]` / `- [x]` / `- [~]`** are open / done / in progress.
- **`^<id>`** on a task line is the id its page file and its ink sidecars are keyed by.
- **Inline properties** on a task line: `due:`, `deadline:`, `!priority`, `#label`, `@assignee`.
  `due:2026-08-26` is all-day; `due:2026-08-26T09:00Z` is timed — the date/datetime distinction
  carries `hasTime` for free. Reminder offset is `due:...+r-540`.
- **`![[ink:<id>]]`** and **`![[image:<uri>]]`** are the two blocks that own external data and so
  are the only blocks needing stable ids. Every other block's identity is positional.
- **`modified_at`** is the LWW clock, ms precision, tiebroken by GitHub login. Never commit
  metadata — rebase rewrites commit timestamps, file content survives.

### What deliberately does not go in a file

- **`collapsed`** — device-local UI state. Syncing it would collapse a section on the laptop because
  you collapsed it on the phone.
- **`rank`** — regenerated from line order.
- **`deleted_at`** — git is the tombstone. Deleting a page deletes its descendants' files and
  sidecars in the same commit.
- **`canvas_*`** — all null today.

## 3. Property defs: the per-install id trap

`SmartListDefEntity.filterJson` embeds `defId`, and `BuiltIns` says Due keeps a *per-install random
UUID*, looked up by name. Written to a shared repo, that id means nothing on another device and the
smart list silently matches nothing.

Two fixes, both required:

1. **Stable ids for the built-ins** — `builtin-due`, `builtin-priority`, beside the
   `builtin-deadline` that already exists. Mock data means no migration; just seed them stable.
2. **The registry lives in the repo** (`.yantra/meta/properties.json`), so any user-created def is
   shared by construction rather than translated at the boundary.

**And the trap this creates:** `Seeder.seedIfEmpty(db)` gates on `countAll() > 0`. In the file world
the DB is empty on *every* device that clones an existing workspace — so seeding would fire and
create a second Inbox, a second Today, and a duplicate set of defs on every device that joins.
Seeding must gate on "did I just scaffold this workspace", never on "is the index empty".

## 4. Sync engine

### Commit cadence

| Event | When it commits and pushes |
|---|---|
| Task created / deleted / completed | Immediately | 
| Task or prose edited | Batched — ~15 min or ~5 commits, whichever is later |
| Ink | **Once per drawing session** (leaving the canvas, or a few seconds of no strokes), not per stroke |
| Manual | "Sync now" — always available |

Strokes land in bursts of dozens; committing per stroke would bury the history and bloat the repo.
Numbers are tunable, the shape isn't: the events other people are waiting on go immediately,
everything else batches.

### Loop (single-flight per workspace, mutex-guarded)

```
fetch origin/<branch>
if nothing local and nothing remote -> done
rebase local commits onto origin/<branch>
on conflict per file:
    both sides parse -> newer modified_at wins wholesale
    equal -> higher GitHub login wins (deterministic on both devices)
    delete vs edit -> edit wins, file resurrected
push
on non-fast-forward -> retry from fetch (max 3, then backoff)
reconcile: diff HEAD against last-indexed commit -> update Room
```

### Triggers

App foreground (fetch for freshness), app background (flush pending, push), after a debounced
commit (expedited), WorkManager periodic at 15 min with `NetworkType.CONNECTED`, and manual.

## 5. Graceful failure

The principle: **local editing never fails, never blocks on sync, and nothing typed is ever silently
discarded.** Sync is best-effort and always tells the truth about its own state.

| Failure | Behaviour |
|---|---|
| Offline | Invisible. Commits accumulate — git's native behaviour. Quiet "n pending". |
| Auth revoked / PAT expired | Editing continues untouched. Persistent notification + workspace banner. Never read-only the local data. |
| Push rejected (non-fast-forward) | Fetch, rebase, retry, backoff. Invisible unless it persists. |
| Push denied (protected branch) | Terminal until fixed, with a *specific* message naming repo and branch, and a "use a different branch" action. |
| Task branch deleted remotely | Offer to recreate from local. Never silently — someone may have meant it. |
| Repo deleted / access revoked | Workspace marked broken; local files stay readable and editable indefinitely. |
| Killed mid-rebase / corrupt repo | Reclone — but **never while unpushed commits exist**. Salvage the working tree first or refuse and offer an export. |
| Unparseable file | Forgiving parser; surface it as a raw-text block, never skip it. A silently omitted task is indistinguishable from data loss. |
| Format version newer than app | That workspace goes read-only. |
| Clock skew | ms precision + login tiebreak. More likely between people than between one person's devices. |

**Conflicts are recoverable, so say so.** When LWW discards a side it is still in git history. Show
"2 conflicts resolved" with a way to see what was replaced. Silent LWW on a shared branch is how
people stop trusting an app.

## 6. Phases

**Phase 0 — JGit spike. DONE.** `JGitSpikeTest`, 5 tests green on the S24 (API 36, `minSdk` 26).
JGit 7.3.0 works with no desugaring, no packaging excludes and no Android-specific fork: init, bare
repos, clone, add, commit, push, fetch, rebase, orphan branches and HTTPS to github.com all work.
The risk the original plan asserted away is retired.

Four things it taught us, all of which change the implementation:

1. **The app had no `INTERNET` permission.** It has never touched the network. Added, with
   `ACCESS_NETWORK_STATE` for the sync constraints.
2. **JGit refuses to clone an empty bare repo** — "Remote branch 'HEAD' not found in upstream
   origin", where real git only warns. So scaffolding a fresh workspace cannot be clone-then-commit;
   it must be init locally, add the remote, push.
3. **`checkout --orphan` carries the index and work tree across.** Both must be cleared explicitly
   or the first task commit contains the entire codebase — precisely what the orphan branch exists
   to prevent.
4. **`RebaseResult.getConflicts()` returns empty even when the rebase stopped on a conflict.** The
   reliable source is `git.status().call().conflicting`. The LWW resolver must read that.

**Phase 1 — Format + serializer.** Writer and parser for §2 with round-trip property tests: parse →
edit one field → write must leave every untouched byte alone. Stable built-in def ids. *Exit: the
whole mock workspace serializes to files and back with no loss.*

**Phase 2 — Index inversion.** Directory scan → diff → Room. Seeding gated on scaffold, not on empty.
Repositories write files first, index second. *Exit: YANTRA runs entirely off a local directory;
deleting the DB and reopening loses nothing.*

**Phase 3 — Local git.** Commit scheduler with the §4 cadence. Sync loop against a local bare repo,
no network. Conflict matrix as pure-JVM tests: both-edited, delete-vs-edit, equal timestamps,
clock skew, rebase-after-push race. *Exit: two instances converge through a bare repo on disk.*

**Phase 4 — Remote + auth.** PAT over HTTPS, then SSH. Add-workspace: validate, create the orphan
branch, scaffold, index. WorkManager triggers, sync status UI. *Exit: phone and desktop clone
converge within one cycle on a private repo.*

**Phase 5 — Multi-workspace + sharing.** Workspace switcher, `workspace_id`, per-workspace sync
state. Assignee, and notification on assignment. Collaborator invites via API or deep link.

**Phase 6 — Polish.** Unmetered-only, reclone recovery, conflict viewer, history compaction.

Phases 1–3 are the single-user multi-device product and are a strict prefix of the collaboration
half. If Phase 5 never earns its keep, everything before it still stands on its own.
