import type { Block } from '../format/pageDoc'
import { DEADLINE, DUE, PRIORITY, ASSIGNEE, labelIdFor } from './filter'
import type { DateRel, Filter, Op, SortSpec } from './filter'

/**
 * Does this task match this rule?
 *
 * ## Unsupported clauses do not evaluate to false
 *
 * Android's own note on smart lists says it plainly: a Today quietly missing half your tasks is
 * worse than no Today. A clause this client cannot answer therefore does not silently fail to
 * match — it is collected in [Match.unsupported], and the caller is expected to say so rather than
 * present a short list as if it were the whole answer.
 *
 * ## Where a property lives
 *
 * On Android a property is a row in `property_value` keyed by `defId`. In the file format it is a
 * token on the task's own line, so `builtin-due` is `due:`, `builtin-priority` is `!high`, and so
 * on. Anything outside that handful is a user-defined property, which the file format carries no
 * way to express yet — hence unsupported rather than absent.
 */

export interface Candidate {
  task: Extract<Block, { kind: 'task' }>
  /** The page the line lives on, so a match can be opened. */
  pageId: string
  /** Position in the whole workspace, for the stable order `created` falls back to. */
  ordinal: number
}

export interface Match {
  matched: boolean
  /** Clause kinds this client could not answer, if any. Empty means the verdict is complete. */
  unsupported: string[]
}

export interface Clock {
  todayStart: number
  todayEnd: number
}

/** Local midnight and the last millisecond of the local day — the two `DateRel` values. */
export function clockFor(now: Date): Clock {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  return { todayStart: start, todayEnd: start + 24 * 60 * 60 * 1000 - 1 }
}

export function evaluate(filter: Filter, c: Candidate, clock: Clock): Match {
  const unsupported: string[] = []
  const matched = run(filter, c, clock, unsupported)
  return { matched, unsupported: [...new Set(unsupported)] }
}

function run(f: Filter, c: Candidate, clock: Clock, un: string[]): boolean {
  switch (f.kind) {
    case 'all': return f.filters.every((x) => run(x, c, clock, un))
    case 'any': return f.filters.some((x) => run(x, c, clock, un))
    case 'not': return !run(f.filter, c, clock, un)
    case 'done': return (c.task.status === 'done') === f.value
    case 'in_progress': return (c.task.status === 'in_progress') === f.value
    // Every candidate is a task line; there is no other kind of thing to match here yet.
    case 'type': return f.value === 'task'
    case 'has_label': return c.task.labels.some((l) => labelIdFor(l) === f.labelId)
    case 'in_workspace':
      // Answerable, but only by a caller that knows which repository this page came from. The view
      // loads one workspace at a time, so a rule that names one is a rule about somewhere else.
      un.push('in_workspace')
      return false
    case 'prop': return prop(f, c, clock, un)
  }
}

function prop(
  f: Extract<Filter, { kind: 'prop' }>,
  c: Candidate,
  clock: Clock,
  un: string[],
): boolean {
  const t = c.task
  switch (f.defId) {
    case DUE: return compareDate(dueMillis(t.due), f, clock, un)
    case DEADLINE: return compareDate(t.deadline ? startOfLocalDay(t.deadline) : null, f, clock, un)
    case PRIORITY: return compareText(t.priority ?? null, f, un)
    case ASSIGNEE: return compareText(t.assignee ?? null, f, un)
    default:
      // A user-defined property. The file format has no place to put one on a task line, so this
      // is not "absent", it is "cannot be asked here".
      un.push(`prop:${f.defId}`)
      return false
  }
}

/** All-day is local midnight of that date; timed is the instant itself — matching `PageMapper`. */
export function dueMillis(due: Extract<Block, { kind: 'task' }>['due']): number | null {
  if (!due) return null
  return due.value.kind === 'allDay'
    ? startOfLocalDay(due.value.date)
    : Date.parse(due.value.instant)
}

function startOfLocalDay(isoDate: string): number {
  const [y, m, d] = isoDate.split('-').map(Number) as [number, number, number]
  return new Date(y, m - 1, d).getTime()
}

function resolve(f: { date?: number; dateRel?: DateRel }, clock: Clock): number | null {
  if (f.dateRel === 'today_start') return clock.todayStart
  if (f.dateRel === 'today_end') return clock.todayEnd
  return f.date ?? null
}

function compareDate(
  value: number | null,
  f: Extract<Filter, { kind: 'prop' }>,
  clock: Clock,
  un: string[],
): boolean {
  if (f.op === 'is_set') return value !== null
  if (f.op === 'not_set') return value === null
  if (value === null) return false
  const against = resolve(f, clock)
  if (against === null) { un.push(`prop:${f.defId}:${f.op}`); return false }
  return numeric(value, against, f.op, un)
}

function compareText(
  value: string | null,
  f: Extract<Filter, { kind: 'prop' }>,
  un: string[],
): boolean {
  if (f.op === 'is_set') return value !== null && value !== ''
  if (f.op === 'not_set') return value === null || value === ''
  if (f.text === undefined) { un.push(`prop:${f.defId}:${f.op}`); return false }
  // Case-insensitive, because a priority written `!high` by hand and `!High` by the picker are the
  // same priority and a rule should not be able to tell them apart.
  const a = (value ?? '').toLowerCase()
  const b = f.text.toLowerCase()
  switch (f.op) {
    case 'eq': return value !== null && a === b
    case 'neq': return value === null || a !== b
    default: un.push(`prop:${f.defId}:${f.op}`); return false
  }
}

function numeric(a: number, b: number, op: Op, un: string[]): boolean {
  switch (op) {
    case 'eq': return a === b
    case 'neq': return a !== b
    case 'lt': return a < b
    case 'lte': return a <= b
    case 'gt': return a > b
    case 'gte': return a >= b
    default: un.push(`op:${op}`); return false
  }
}

/**
 * Order the matches.
 *
 * `created` has no counterpart in the file format — a task line carries no creation time — so it
 * falls back to the order the tasks appear in, which is the order they were appended in and
 * therefore the same answer most of the time. Said out loud here rather than silently approximated.
 */
export function sortMatches(list: Candidate[], sort: SortSpec[], clock: Clock): Candidate[] {
  if (sort.length === 0) return list
  const out = [...list]
  out.sort((x, y) => {
    for (const s of sort) {
      const cmp = compareBy(x, y, s, clock)
      if (cmp !== 0) return s.desc ? -cmp : cmp
    }
    return x.ordinal - y.ordinal
  })
  return out
}

function compareBy(x: Candidate, y: Candidate, s: SortSpec, clock: Clock): number {
  const nullsLast = s.nullsLast !== false
  if (s.by === 'title') return x.task.title.localeCompare(y.task.title)
  if (s.by === 'created') return x.ordinal - y.ordinal
  if (s.by === 'prop_date') {
    const a = s.defId === DEADLINE
      ? (x.task.deadline ? Date.parse(x.task.deadline) : null)
      : dueMillis(x.task.due)
    const b = s.defId === DEADLINE
      ? (y.task.deadline ? Date.parse(y.task.deadline) : null)
      : dueMillis(y.task.due)
    return nullable(a, b, nullsLast)
  }
  if (s.by === 'prop_text') {
    const a = s.defId === PRIORITY ? x.task.priority ?? null : x.task.assignee ?? null
    const b = s.defId === PRIORITY ? y.task.priority ?? null : y.task.assignee ?? null
    if (a === null || b === null) return nullable(a === null ? null : 0, b === null ? null : 0, nullsLast)
    return a.localeCompare(b)
  }
  void clock
  return 0
}

function nullable(a: number | null, b: number | null, nullsLast: boolean): number {
  if (a === null && b === null) return 0
  if (a === null) return nullsLast ? 1 : -1
  if (b === null) return nullsLast ? -1 : 1
  return a - b
}
