# Yantra on the web

The same workspaces, in a browser. Not a mirror of the Android app and not a separate product: both
clients read and write the *same* Markdown files in the *same* git repository, so the thing that
matters most here is not the UI — it is agreeing, byte for byte, about what a page file looks like.

## The format is the contract

`src/format/` is a port of `data/format/` from the Android app. It exists because the phone will
read what the browser wrote on the very next sync, and a client that emits subtly different
Markdown does not produce a bug you notice — it produces a diff on every file, every time, and a
merge conflict on anything two devices touched.

Two layers of test hold that line:

- **`src/format/pageCodec.test.ts`** — ported case for case from `PageCodecTest.kt`. Where a case
  here disagrees with the Kotlin suite, this port is wrong.
- **`scripts/roundtrip.ts`** — a differential test against real files. Point it at a workspace clone
  and it decodes and re-encodes every page, expecting bytes identical to what Kotlin wrote:

      npx vite-node scripts/roundtrip.ts ~/path/to/a/workspace/clone

  The unit suite pins the behaviour this port was *written* to have; this pins the behaviour it must
  actually have. It currently passes on all 17 pages of a real two-workspace install.

## Why there is a server at all

There is exactly one thing a browser cannot do, and it decides the architecture: `api.github.com`
sends `access-control-allow-origin: *`, so a browser can read and write repositories directly with a
token — but `github.com/login/oauth/access_token` refuses CORS entirely, so a browser can never
*obtain* one. The sign-in button therefore needs a server-side token exchange; everything after it
is static.

## What works so far

The daily loop: read a workspace, walk its lists, open a task as a page, add tasks, retitle them and
tick them off. Emphasis is rendered rather than shown as asterisks.

Writes go straight to GitHub's Contents API with the blob sha as a precondition, so a write that
would clobber something the phone committed in the meantime is refused rather than applied — the
client has no merge machinery and should not pretend to. Every mutation goes through one path that
applies the edit on screen first, then the file, and puts the old page back if the write fails: a
tick left showing after a failed save claims something the repository does not say.

Capture and retitle both hand the typed text to the page codec's own parser rather than
re-implementing the tokens, so `Buy milk #shop !high` is split exactly as a line in a file would be
— including the rule that keeps the hash in `Buy #2 pencils`.

Smart lists are evaluated rather than compiled. Android turns a rule into SQL over Room; there is no
SQL here, so `src/filter/` walks the pages already in memory. Same semantics, different machine —
which is exactly the kind of second implementation that drifts, so its tests use the rule JSON
copied verbatim out of a live workspace rather than rules invented to be passed.

**A clause this client cannot answer does not evaluate to false.** Android's own note on smart lists
says why: a Today quietly missing half your tasks is worse than no Today. Unsupported clauses — a
user-defined property, a cross-workspace rule — are collected and reported above the list, which is
shown short rather than shown wrong.

Reordering is a drag on the grip, or Alt+↑/↓ and Tab from the keyboard. It moves **one line**, not a
subtree: indentation on a page is layout, not parentage — a task's children live on its own page —
and every structural change re-clamps the run so the first line sits flush left and no line is more
than one step deeper than the one above it. That is `WorkspaceWriter.normalizeIndents`, and skipping
it is how dragging a block to the top leaves an indent with nothing to be indented under.

Not here yet: ink, images, the focus ledger, and the sign-in button.

## Running it

    npm install
    npm test              # the format, filter and workspace suites
    npm run dev           # then open /?demo to look around without a token
    npm run test:ui       # drag and keyboard, in a real browser, against /?demo

`?demo` opens a synthetic workspace — no token, no network — which is also how the UI is tested. A
list is deep-linkable as `#<page-id>`.
