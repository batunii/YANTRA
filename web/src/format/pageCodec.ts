import type { Block, DueSpec, DueValue, PageDoc, TaskStatus } from './pageDoc'
import { EPOCH } from './pageDoc'

/**
 * Reads and writes a page file — the TypeScript half of `data/format/PageCodec.kt`.
 *
 * ## The round-trip contract
 *
 * **A block's own text survives byte-exact unless this client edits that block.** Each block keeps
 * the line it was parsed from and the emitter prefers it, so ticking one task cannot reformat the
 * paragraph above it. That property is what lets the same file be edited on a phone, in a browser
 * and in a text editor without any of them churning the other two's work into a diff.
 *
 * **Blank-line layout between blocks is canonical, not preserved.** Blank lines belong to no block,
 * so there is nowhere honest to hang them.
 *
 * ## Forgiving on the way in
 *
 * A line this parser cannot classify becomes prose holding the original text, and a frontmatter key
 * it does not know is kept in `unknownKeys`. Nothing is dropped for being unrecognised — the file
 * may well have been written by a newer client, and a silently vanished task is indistinguishable
 * from data loss.
 */

/**
 * One level of visual indent. Deliberately not markdown nesting.
 *
 * A guillemet rather than `>>` because `>>` is a nested blockquote, and rather than leading
 * whitespace because markdown reads indentation as list nesting — which is the conflation this
 * format exists to avoid. This character means nothing to markdown, so blockquotes and callouts
 * pass through as the prose they are.
 */
export const INDENT = '»'

const FENCE = '---'
const LINK_CLOSE = ']]'

const KNOWN_KEYS = new Set([
  'id', 'type', 'parent', 'title', 'system_key', 'modified_at', 'device',
])

/** `- [ ]`, `- [x]`, `- [~]`, each with or without a following space. */
const TASK_MARKER = /^- \[([ xX~])] ?/
const NUMBERED = /^\d+\.\s+(.*)$/
const NUMBERED_PREFIX = /^(\d+)\./
const INK = /^!\[\[ink:([^\]]+)]]$/
const IMAGE = /^!\[\[image:([^\]]+)]]$/
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

// ---- decode ----

export function decode(text: string): PageDoc {
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let i = 0
  const front = new Map<string, string>()

  if (lines[0]?.trim() === FENCE) {
    i = 1
    while (i < lines.length && lines[i]!.trim() !== FENCE) {
      const line = lines[i]!
      const colon = line.indexOf(':')
      if (colon > 0) front.set(line.slice(0, colon).trim(), line.slice(colon + 1).trim())
      i++
    }
    i++ // closing fence
  }

  const blocks: Block[] = []
  for (; i < lines.length; i++) {
    // Empty, not blank. A truly empty line is the separator the emitter puts between blocks; a line
    // holding only whitespace is an empty *block*, which the editor needs to exist because it makes
    // one and then types into it.
    if (lines[i] !== '') blocks.push(parseBlock(lines[i]!))
  }

  const nonBlank = (k: string) => {
    const v = front.get(k)
    return v && v.trim() !== '' ? v : undefined
  }
  const unknownKeys: Record<string, string> = {}
  for (const [k, v] of front) if (!KNOWN_KEYS.has(k)) unknownKeys[k] = v

  return {
    id: front.get('id') ?? '',
    type: front.get('type') ?? '',
    parent: nonBlank('parent'),
    title: nonBlank('title'),
    systemKey: nonBlank('system_key'),
    // Kept as the string it arrived as rather than re-serialised. Java's Instant.toString drops a
    // zero fraction and JavaScript's toISOString does not, so parsing and re-emitting would rewrite
    // `...:14Z` as `...:14.000Z` and turn every read into a diff.
    modifiedAt: front.get('modified_at')?.trim() || EPOCH,
    device: nonBlank('device'),
    blocks,
    unknownKeys,
  }
}

/** Peels indent markers off the front, returning the depth and what is left. */
function splitIndent(line: string): [number, string] {
  let rest = line
  let depth = 0
  while (rest.startsWith(INDENT)) {
    depth++
    rest = rest.slice(INDENT.length)
    if (rest.startsWith(' ')) rest = rest.slice(1)
  }
  return [depth, rest]
}

export function parseBlock(raw: string): Block {
  const [indent, rest] = splitIndent(raw)
  // Deliberately not trimmed. An empty heading is written "# " and an empty bullet "- "; trimming
  // turns those into "#" and "-", which match nothing and come back as prose.

  const ink = INK.exec(rest)
  if (ink) return { kind: 'ink', id: ink[1]!, indent, raw }
  const image = IMAGE.exec(rest)
  if (image) return { kind: 'image', uri: image[1]!, indent, raw }

  // The box, with or without anything after it. Requiring the trailing space would make a block's
  // *kind* depend on a space at the end of a line, which any editor or git hook will strip — so a
  // task you made and did not type into would change into a bullet behind your back.
  const marker = TASK_MARKER.exec(rest)
  if (marker) {
    const status: TaskStatus =
      marker[1] === 'x' || marker[1] === 'X' ? 'done' : marker[1] === '~' ? 'in_progress' : 'open'
    return parseTask(rest.slice(marker[0].length), status, indent, raw)
  }

  if (rest.startsWith('# ')) return { kind: 'heading', text: rest.slice(2), indent, raw }
  if (rest.startsWith('- ')) return { kind: 'bullet', text: rest.slice(2), indent, raw }
  const numbered = NUMBERED.exec(rest)
  if (numbered) return { kind: 'numbered', text: numbered[1]!, indent, raw }
  // A line with nothing but whitespace is an empty block, and empty is what it has to come back as:
  // the emitter writes a lone space for one, and reading that space back as the block's *text* is
  // how notes used to be born holding a space nobody typed.
  if (rest.trim() === '') return { kind: 'prose', text: '', indent, raw }
  return { kind: 'prose', text: rest, indent, raw }
}

/**
 * Splits a task line into title and trailing tokens, scanning **right to left** and stopping at the
 * first word that is not a token.
 *
 * That direction is the whole trick. "Buy milk #groceries" tags the task; "Buy #2 pencils" does
 * not, because the scan hits `pencils` and stops, leaving `#2` in the title where it belongs.
 */
function parseTask(body: string, status: TaskStatus, indent: number, raw: string): Block {
  const words = body.trim().split(' ')
  let id = ''
  let due: DueSpec | undefined
  let deadline: string | undefined
  let doneAt: string | undefined
  let priority: string | undefined
  let assignee: string | undefined
  const labels: string[] = []

  while (words.length > 0) {
    const w = words[words.length - 1]!
    let consumed = false
    // A word carrying a link's closing brackets is part of the title, whatever it starts with.
    // Without this, `[[Buy milk #2|^abc]]` read from the right files the task under a label called
    // `2|^abc]]` and takes half the link out of the title on the way.
    if (w.includes(LINK_CLOSE)) {
      consumed = false
    } else if (w.startsWith('^') && id === '') {
      id = w.slice(1); consumed = true
    } else if (w.startsWith('due:')) {
      const d = parseDue(w.slice(4)); if (d) { due = d; consumed = true }
    } else if (w.startsWith('deadline:')) {
      const d = parseDate(w.slice(9)); if (d) { deadline = d; consumed = true }
    } else if (w.startsWith('done:')) {
      const d = parseDate(w.slice(5)); if (d) { doneAt = d; consumed = true }
    } else if (w.startsWith('!') && w.length > 1) {
      priority = w.slice(1); consumed = true
    } else if (w.startsWith('@') && w.length > 1) {
      assignee = w.slice(1); consumed = true
    } else if (w.startsWith('#') && w.length > 1) {
      labels.push(w.slice(1)); consumed = true
    }
    if (!consumed) break
    words.pop()
  }

  return {
    kind: 'task',
    id,
    title: words.join(' '),
    status,
    indent,
    due,
    deadline,
    doneAt,
    priority,
    labels: labels.reverse(), // scanned right to left
    assignee,
    raw,
  }
}

/** `2026-08-26`, `2026-08-26T09:00:00Z`, either optionally suffixed `+r<minutes>`. */
function parseDue(token: string): DueSpec | undefined {
  const at = token.indexOf('+r')
  const body = at >= 0 ? token.slice(0, at) : token
  let reminderMin: number | undefined
  if (at >= 0) {
    const n = Number(token.slice(at + 2))
    if (!Number.isInteger(n)) return undefined
    reminderMin = n
  }
  let value: DueValue | undefined
  if (body.includes('T')) {
    if (!Number.isNaN(Date.parse(body))) value = { kind: 'at', instant: body }
  } else {
    const d = parseDate(body)
    if (d) value = { kind: 'allDay', date: d }
  }
  if (!value) return undefined
  return reminderMin === undefined ? { value } : { value, reminderMin }
}

/** Strict: a date is `YYYY-MM-DD` and a real day, so `2026-13-40` is not one. */
function parseDate(s: string): string | undefined {
  if (!ISO_DATE.test(s)) return undefined
  const [y, m, d] = s.split('-').map(Number) as [number, number, number]
  const probe = new Date(Date.UTC(y, m - 1, d))
  if (probe.getUTCFullYear() !== y || probe.getUTCMonth() !== m - 1 || probe.getUTCDate() !== d) {
    return undefined
  }
  return s
}

// ---- encode ----

export function encode(page: PageDoc): string {
  let out = ''
  out += FENCE + '\n'
  out += 'id: ' + page.id + '\n'
  out += 'type: ' + page.type + '\n'
  if (page.parent) out += 'parent: ' + page.parent + '\n'
  if (page.title) out += 'title: ' + page.title + '\n'
  if (page.systemKey) out += 'system_key: ' + page.systemKey + '\n'
  out += 'modified_at: ' + page.modifiedAt + '\n'
  if (page.device) out += 'device: ' + page.device + '\n'
  for (const [k, v] of Object.entries(page.unknownKeys)) out += k + ': ' + v + '\n'
  out += FENCE + '\n'

  page.blocks.forEach((block, i) => {
    if (i > 0 && needsBlankBefore(page.blocks[i - 1]!, block)) out += '\n'
    const ordinal = ordinalOf(page.blocks, i)
    const text = rawStillDescribes(block, ordinal) ? block.raw! : render(block, ordinal)
    // An empty block still has to occupy a line, or reading the file back would lose it.
    out += (text === '' ? ' ' : text) + '\n'
  })
  return out
}

/**
 * One block as the line it would occupy in a page — for surfaces that hold a list of lines rather
 * than a document. Round-trips through `parseBlock`.
 */
export function encodeBlock(block: Block): string {
  const text = rawStillDescribes(block, 0) ? block.raw! : render(block, 0)
  return text === '' ? ' ' : text
}

/**
 * Whether a block's original line still says exactly what the block now says.
 *
 * Re-parsing the source and comparing is what makes preservation safe. Asking callers to clear
 * `raw` whenever they change something works right up until somebody forgets, and then the stale
 * line is written back and the edit vanishes with no error anywhere. Verifying costs a parse per
 * block and cannot be forgotten.
 */
function rawStillDescribes(block: Block, ordinal: number): boolean {
  if (block.raw === undefined) return false
  if (!sameBlock(parseBlock(block.raw), block)) return false
  if (block.kind !== 'numbered') return true
  // A numbered item's ordinal is positional and therefore not part of the model, so a moved item
  // compares equal to its own stale text.
  const m = NUMBERED_PREFIX.exec(splitIndent(block.raw)[1])
  return m !== null && Number(m[1]) === ordinal
}

/** Structural equality ignoring `raw`, which is the thing being validated. */
function sameBlock(a: Block, b: Block): boolean {
  const strip = ({ raw: _raw, ...rest }: Block) => rest
  return JSON.stringify(sortKeys(strip(a))) === JSON.stringify(sortKeys(strip(b)))
}

function sortKeys(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(sortKeys)
  if (v && typeof v === 'object') {
    const o = v as Record<string, unknown>
    const out: Record<string, unknown> = {}
    // Undefined and absent must compare equal: a task parsed with no assignee has the key missing,
    // one built in code may carry `assignee: undefined`, and they describe the same line.
    for (const k of Object.keys(o).sort()) if (o[k] !== undefined) out[k] = sortKeys(o[k])
    return out
  }
  return v
}

/** Prose and headings breathe; consecutive list items do not. */
function needsBlankBefore(prev: Block, next: Block): boolean {
  const listish = (b: Block) => b.kind === 'task' || b.kind === 'bullet' || b.kind === 'numbered'
  return !(listish(prev) && listish(next))
}

/** Numbered items count from the start of their own unbroken, same-indent run. */
function ordinalOf(blocks: Block[], index: number): number {
  const here = blocks[index]!
  if (here.kind !== 'numbered') return 0
  let n = 1
  for (let i = index - 1; i >= 0; i--) {
    const b = blocks[i]!
    if (b.kind !== 'numbered' || b.indent !== here.indent) break
    n++
  }
  return n
}

function render(block: Block, ordinal: number): string {
  // Markers are space-separated, matching what the parser accepts. Writing "»»" for depth two would
  // still parse, but the file would stop looking like the one the user typed.
  const pad = block.indent === 0 ? '' : Array(block.indent).fill(INDENT).join(' ') + ' '
  switch (block.kind) {
    case 'heading': return pad + '# ' + block.text
    case 'bullet': return pad + '- ' + block.text
    case 'numbered': return pad + ordinal + '. ' + block.text
    case 'prose': return pad + block.text
    case 'ink': return pad + '![[ink:' + block.id + ']]'
    case 'image': return pad + '![[image:' + block.uri + ']]'
    case 'task': return pad + renderTask(block)
  }
}

function renderTask(t: Extract<Block, { kind: 'task' }>): string {
  // No trailing space after the box, and none between an empty title and the first token. A line
  // that ends in whitespace is a line something else will eventually trim, and the parser must not
  // be the only thing standing between that and a block changing kind.
  let s = t.status === 'open' ? '- [ ]' : t.status === 'done' ? '- [x]' : '- [~]'
  if (t.title !== '') s += ' ' + t.title
  // Order is fixed so the same task always renders the same bytes — otherwise two clients holding
  // identical data would produce a diff, and every sync would look like a change.
  if (t.id !== '') s += ' ^' + t.id
  if (t.due) s += ' due:' + renderDue(t.due)
  if (t.deadline) s += ' deadline:' + t.deadline
  // Only ever written on a finished task, so an open one carries no dead token — and un-finishing
  // clears it, so a task cannot claim to have been completed on a day it was not.
  if (t.doneAt && t.status === 'done') s += ' done:' + t.doneAt
  if (t.priority) s += ' !' + t.priority
  for (const l of t.labels) s += ' #' + l
  if (t.assignee) s += ' @' + t.assignee
  return s
}

function renderDue(d: DueSpec): string {
  const body = d.value.kind === 'allDay' ? d.value.date : d.value.instant
  return d.reminderMin === undefined ? body : body + '+r' + d.reminderMin
}
