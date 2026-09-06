import { decode, encode, parseBlock } from '../format/pageCodec'
import type { Block, PageDoc, TaskStatus } from '../format/pageDoc'
import { parseSmartList } from '../filter/filter'
import type { SmartListDef } from '../filter/filter'
import { clockFor, evaluate, sortMatches } from '../filter/evaluate'
import type { Candidate } from '../filter/evaluate'

/**
 * A workspace as this client holds it: every page file, indexed by the id in its frontmatter.
 *
 * The shape of a list is not stored on the list's children — it *is* the parent page's block order.
 * A task line carries the title, the status and the tokens; the child page of the same id carries
 * that task's own body and its own children. So ticking a task is a single-file edit to the parent,
 * and opening a task as a page is a lookup by the id on the line.
 */

export interface LoadedPage {
  id: string
  path: string
  /** The blob sha this was read at — the precondition for writing it back. */
  sha: string
  doc: PageDoc
}

export interface Workspace {
  name: string
  pages: Map<string, LoadedPage>
  byPath: Map<string, LoadedPage>
  /** Smart-list rules, by the id of the page that presents them. */
  smartLists: Map<string, SmartListDef>
}

/** Where page bytes come from. Abstract so the loader can be tested without a network. */
export interface PageSource {
  list(): Promise<Array<{ path: string; sha: string }>>
  read(path: string, sha: string): Promise<string>
}

const PAGE_RE = /^pages\/[^/]+\.md$/
const SMART_RE = /^\.yantra\/meta\/smartlists\/[^/]+\.json$/
const MANIFEST = '.yantra/manifest.json'

export async function loadWorkspace(source: PageSource): Promise<Workspace> {
  const entries = await source.list()

  let name = 'Workspace'
  const manifest = entries.find((e) => e.path === MANIFEST)
  if (manifest) {
    try {
      const j = JSON.parse(await source.read(manifest.path, manifest.sha))
      if (typeof j.name === 'string' && j.name) name = j.name
    } catch {
      // A manifest we cannot read costs the workspace its name and nothing else. Refusing to open
      // the whole thing over one unparseable metadata file would be the wrong trade.
    }
  }

  const pageFiles = entries.filter((e) => PAGE_RE.test(e.path))
  const texts = await Promise.all(pageFiles.map((e) => source.read(e.path, e.sha)))

  const pages = new Map<string, LoadedPage>()
  const byPath = new Map<string, LoadedPage>()
  pageFiles.forEach((e, i) => {
    const doc = decode(texts[i]!)
    // A page whose frontmatter has no id cannot be referenced by anything and cannot be written
    // back safely; it is kept addressable by path so it is at least visible rather than vanished.
    const loaded: LoadedPage = { id: doc.id, path: e.path, sha: e.sha, doc }
    if (doc.id) pages.set(doc.id, loaded)
    byPath.set(e.path, loaded)
  })

  // A rule that will not parse is dropped with its list left empty rather than taking the whole
  // workspace down — but it is the caller's job to notice, which is what `unsupported` is for on
  // the resolve side.
  const smartLists = new Map<string, SmartListDef>()
  const smartFiles = entries.filter((e) => SMART_RE.test(e.path))
  const smartTexts = await Promise.all(smartFiles.map((e) => source.read(e.path, e.sha)))
  smartTexts.forEach((text) => {
    try {
      const def = parseSmartList(text)
      smartLists.set(def.nodeId, def)
    } catch {
      /* see above */
    }
  })

  return { name, pages, byPath, smartLists }
}

/**
 * Every task line in the workspace, in a stable order.
 *
 * Stable matters: `created` has no field in the file format, so a smart list sorted by it falls
 * back to this order — and an order that shuffled between loads would reorder someone's Today for
 * no reason. Pages are walked by path, blocks in the order they appear.
 */
export function allTasks(ws: Workspace): Candidate[] {
  const out: Candidate[] = []
  let ordinal = 0
  for (const path of [...ws.byPath.keys()].sort()) {
    const page = ws.byPath.get(path)!
    for (const b of page.doc.blocks) {
      if (b.kind === 'task') out.push({ task: b, pageId: page.id, ordinal: ordinal++ })
    }
  }
  return out
}

export interface Resolved {
  candidates: Candidate[]
  /** Clause kinds this client could not evaluate. Non-empty means the list is not the whole answer. */
  unsupported: string[]
  /** False when the page is a smart list whose rule is missing or would not parse. */
  ruleFound: boolean
}

/** What a smart list currently contains. Computed, never stored — that is the whole idea. */
export function resolveSmartList(ws: Workspace, nodeId: string, now: Date): Resolved {
  const def = ws.smartLists.get(nodeId)
  if (!def) return { candidates: [], unsupported: [], ruleFound: false }
  const clock = clockFor(now)
  const unsupported = new Set<string>()
  const hits = allTasks(ws).filter((c) => {
    const r = evaluate(def.filter, c, clock)
    r.unsupported.forEach((u) => unsupported.add(u))
    return r.matched
  })
  return {
    candidates: sortMatches(hits, def.sort, clock),
    unsupported: [...unsupported],
    ruleFound: true,
  }
}

/** The pages a workspace opens at: lists and smart lists that are nobody's child. */
export function topLevel(ws: Workspace): LoadedPage[] {
  const out = [...ws.pages.values()].filter(
    (p) => !p.doc.parent && (p.doc.type === 'list' || p.doc.type === 'smart_list'),
  )
  // Inbox first — it is where capture lands and the one list that is always there — then by title,
  // so the order does not shift with whatever the tree read happened to return.
  return out.sort((a, b) => {
    if (a.doc.systemKey === 'inbox') return -1
    if (b.doc.systemKey === 'inbox') return 1
    return (a.doc.title ?? '').localeCompare(b.doc.title ?? '')
  })
}

export type TaskBlock = Extract<Block, { kind: 'task' }>

/** The task lines on a page, with their index so an edit can address one. */
export function tasksOf(page: LoadedPage): Array<{ index: number; task: TaskBlock }> {
  const out: Array<{ index: number; task: TaskBlock }> = []
  page.doc.blocks.forEach((b, index) => {
    if (b.kind === 'task') out.push({ index, task: b })
  })
  return out
}

/**
 * A stable id for this browser, used only to break a timestamp tie.
 *
 * The conflict resolver compares `device` when two edits share a `modified_at`, and the comparison
 * has to be *asymmetric* — whichever side runs it must reach the same verdict. Two writers sharing
 * a device string tie, both conclude the other should win, and ping-pong the file between them. So
 * every browser gets its own.
 */
export function deviceId(): string {
  const KEY = 'yantra.device'
  try {
    const held = localStorage.getItem(KEY)
    if (held) return held
    const made = 'web-' + Math.random().toString(36).slice(2, 8)
    localStorage.setItem(KEY, made)
    return made
  } catch {
    // Private mode, or storage blocked. A per-session id still breaks ties correctly; it just does
    // not persist, which costs nothing since it is only ever compared to a *different* device.
    return 'web-ephemeral'
  }
}

/**
 * The edited page, ready to write.
 *
 * `raw` is dropped from the block being changed so the emitter re-renders it rather than writing
 * the line it was parsed from — the codec re-parses and checks, so this is belt and braces, but the
 * intent should be visible at the call site rather than inferred from a validation two files away.
 */
export function withTaskStatus(doc: PageDoc, index: number, status: TaskStatus, now: Date, device: string): PageDoc {
  const blocks = [...doc.blocks]
  const b = blocks[index]
  if (!b || b.kind !== 'task') throw new Error(`block ${index} is not a task`)

  const next: TaskBlock = { ...b, status, raw: undefined }
  // A finished task records the day it finished, and un-finishing clears it — a task must not claim
  // to have been completed on a day it was not.
  if (status === 'done') next.doneAt = localDay(now)
  else delete next.doneAt

  blocks[index] = next
  return { ...doc, blocks, modifiedAt: now.toISOString(), device }
}

/** `YYYY-MM-DD` in the viewer's own timezone, which is the day they would say it is. */
function localDay(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function pageText(doc: PageDoc): string {
  return encode(doc)
}

/**
 * A typed line, captured as a task.
 *
 * The tokens are not re-implemented here: the line is assembled and handed to the page codec's own
 * parser, so `Buy milk #shop !high` gets exactly the right-to-left scan a file on disk would get,
 * including the rule that keeps the hash in "Buy #2 pencils". One parser, one set of surprises.
 *
 * `raw` is dropped so the block renders canonically rather than keeping the line it was assembled
 * from. Both forms round-trip, but only one of them is the shape the Android app writes, and two
 * spellings of the same task is a diff waiting to happen.
 */
export function withNewTask(doc: PageDoc, typed: string, now: Date, device: string, id: string = crypto.randomUUID()): { doc: PageDoc; id: string } {
  const parsed = parseBlock(`- [ ] ${typed.trim()} ^${id}`)
  if (parsed.kind !== 'task') throw new Error('capture did not produce a task')
  const block: Block = { ...parsed, raw: undefined }
  return {
    doc: { ...doc, blocks: [...doc.blocks, block], modifiedAt: now.toISOString(), device },
    id,
  }
}

/**
 * Retitle one task.
 *
 * The new text goes back through the parser for the same reason capture does — someone editing a
 * title to add `#reading` on the end means it as a label, and the file would read it as one on the
 * next load whatever this function decided.
 */
export function withTaskTitle(doc: PageDoc, index: number, typed: string, now: Date, device: string): PageDoc {
  const blocks = [...doc.blocks]
  const b = blocks[index]
  if (!b || b.kind !== 'task') throw new Error(`block ${index} is not a task`)
  const parsed = parseBlock(`- [ ] ${typed.trim()} ^${b.id}`)
  if (parsed.kind !== 'task') throw new Error('edit did not produce a task')
  // Status, and anything the typed text did not mention, stay as they were.
  blocks[index] = {
    ...parsed,
    id: b.id,
    status: b.status,
    indent: b.indent,
    doneAt: b.doneAt,
    raw: undefined,
  }
  return { ...doc, blocks, modifiedAt: now.toISOString(), device }
}

/**
 * The one rule indentation obeys: the first line of a page sits flush left, and no line is more
 * than one step deeper than the line above it. Anything else cannot be read as structure.
 *
 * Ported from `WorkspaceWriter.normalizeIndents`. Indent is stored rather than derived, so it does
 * not stay true on its own — dragging a block to the top, or deleting the line above one, leaves an
 * indent with nothing to be indented under. Every operation that changes the run ends here.
 */
export function normalizeIndents(blocks: Block[]): Block[] {
  let ceiling = 0
  return blocks.map((b) => {
    const fixed = Math.min(Math.max(b.indent, 0), ceiling)
    ceiling = fixed + 1
    return fixed === b.indent ? b : { ...b, indent: fixed, raw: undefined }
  })
}

/**
 * Move one block to a new position.
 *
 * **One block, not a subtree.** Indentation on this page is layout, not parentage — the Android
 * note is explicit that indenting "shifts the line on this page and never moves the block into the
 * one above it", and a task's children live on its own page rather than under it here. So a move
 * carries exactly the line it was given, and the clamp afterwards puts any indent that has been
 * left dangling back to a legal depth.
 */
export function withBlockMoved(doc: PageDoc, from: number, to: number, now: Date, device: string): PageDoc {
  if (from === to || from < 0 || from >= doc.blocks.length) return doc
  const blocks = [...doc.blocks]
  const [moved] = blocks.splice(from, 1)
  blocks.splice(Math.min(Math.max(to, 0), blocks.length), 0, moved!)
  return { ...doc, blocks: normalizeIndents(blocks), modifiedAt: now.toISOString(), device }
}

/** Indent or outdent one line, then re-clamp the run around it. */
export function withIndent(doc: PageDoc, index: number, delta: number, now: Date, device: string): PageDoc {
  const b = doc.blocks[index]
  if (!b) return doc
  const blocks = [...doc.blocks]
  blocks[index] = { ...b, indent: Math.max(0, b.indent + delta), raw: undefined }
  return { ...doc, blocks: normalizeIndents(blocks), modifiedAt: now.toISOString(), device }
}
