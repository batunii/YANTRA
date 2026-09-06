import { decode, encode } from '../format/pageCodec'
import type { Block, PageDoc, TaskStatus } from '../format/pageDoc'

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
}

/** Where page bytes come from. Abstract so the loader can be tested without a network. */
export interface PageSource {
  list(): Promise<Array<{ path: string; sha: string }>>
  read(path: string, sha: string): Promise<string>
}

const PAGE_RE = /^pages\/[^/]+\.md$/
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

  return { name, pages, byPath }
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
