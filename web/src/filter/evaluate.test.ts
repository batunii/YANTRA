import { describe, expect, it } from 'vitest'
import { parseBlock } from '../format/pageCodec'
import type { Block } from '../format/pageDoc'
import { clockFor, evaluate, sortMatches } from './evaluate'
import type { Candidate } from './evaluate'
import { parseSmartList } from './filter'
import type { Filter } from './filter'

/**
 * The two rules below are the exact contents of `.yantra/meta/smartlists/*.json` on a real install,
 * copied verbatim. Inventing a rule and then satisfying it proves nothing; these are the ones the
 * Android app actually wrote and actually evaluates.
 */
const TODAY_JSON = '{"nodeId":"today","filterJson":"{\\"kind\\":\\"all\\",\\"filters\\":[{\\"kind\\":\\"type\\",\\"value\\":\\"task\\"},{\\"kind\\":\\"done\\",\\"value\\":false},{\\"kind\\":\\"any\\",\\"filters\\":[{\\"kind\\":\\"prop\\",\\"defId\\":\\"builtin-due\\",\\"op\\":\\"lte\\",\\"dateRel\\":\\"today_end\\"},{\\"kind\\":\\"prop\\",\\"defId\\":\\"builtin-deadline\\",\\"op\\":\\"lte\\",\\"dateRel\\":\\"today_end\\"}]}]}","sortJson":"[{\\"by\\":\\"created\\",\\"desc\\":true}]","homeParentId":"inbox"}'

const HIGH_JSON = '{"nodeId":"high","filterJson":"{\\"kind\\":\\"all\\",\\"filters\\":[{\\"kind\\":\\"type\\",\\"value\\":\\"task\\"},{\\"kind\\":\\"done\\",\\"value\\":false},{\\"kind\\":\\"prop\\",\\"defId\\":\\"builtin-priority\\",\\"op\\":\\"eq\\",\\"text\\":\\"High\\"}]}","sortJson":"[{\\"by\\":\\"created\\",\\"desc\\":true}]","homeParentId":"inbox"}'

const NOW = new Date(2026, 8, 6, 14, 0, 0) // 6 Sept 2026, local
const clock = clockFor(NOW)

let n = 0
const cand = (line: string): Candidate => {
  const b = parseBlock(line)
  if (b.kind !== 'task') throw new Error('not a task')
  return { task: b as Extract<Block, { kind: 'task' }>, pageId: 'p', ordinal: n++ }
}

const matches = (f: Filter, lines: string[]) =>
  lines.map(cand).filter((c) => evaluate(f, c, clock).matched).map((c) => c.task.title)

describe('the real Today rule', () => {
  const def = parseSmartList(TODAY_JSON)

  it('parses out of the nested JSON the file stores', () => {
    expect(def.nodeId).toBe('today')
    expect(def.filter.kind).toBe('all')
    expect(def.sort).toEqual([{ by: 'created', desc: true }])
  })

  it('takes tasks due today or overdue, and leaves the rest', () => {
    expect(matches(def.filter, [
      '- [ ] due today ^a due:2026-09-06',
      '- [ ] overdue ^b due:2026-09-01',
      '- [ ] due tomorrow ^c due:2026-09-07',
      '- [ ] no date ^d',
      '- [x] done today ^e due:2026-09-06',
    ])).toEqual(['due today', 'overdue'])
  })

  it('counts a deadline as well as a due date', () => {
    expect(matches(def.filter, [
      '- [ ] deadline today ^a deadline:2026-09-06',
      '- [ ] deadline later ^b deadline:2026-12-01',
    ])).toEqual(['deadline today'])
  })

  it('includes a timed task later the same day', () => {
    // today_end is the last millisecond of the local day, so 23:00 is still today.
    const late = new Date(2026, 8, 6, 23, 0).toISOString()
    expect(matches(def.filter, [`- [ ] tonight ^a due:${late}`])).toEqual(['tonight'])
  })

  it('answers completely — no clause it cannot evaluate', () => {
    expect(evaluate(def.filter, cand('- [ ] x ^a due:2026-09-06'), clock).unsupported).toEqual([])
  })
})

describe('the real High Priority rule', () => {
  const def = parseSmartList(HIGH_JSON)

  it('takes open high-priority tasks only', () => {
    expect(matches(def.filter, [
      '- [ ] big ^a !High',
      '- [ ] small ^b !Low',
      '- [x] finished ^c !High',
      '- [ ] none ^d',
    ])).toEqual(['big'])
  })

  it('does not care how the priority was capitalised', () => {
    // `!high` typed by hand and `!High` from the picker are the same priority.
    expect(matches(def.filter, ['- [ ] shouty ^a !high'])).toEqual(['shouty'])
  })
})

describe('clauses', () => {
  it('handles not, any and all', () => {
    const f: Filter = { kind: 'not', filter: { kind: 'done', value: true } }
    expect(matches(f, ['- [ ] open ^a', '- [x] shut ^b'])).toEqual(['open'])
  })

  it('matches labels by the id derived from their name', () => {
    const f: Filter = { kind: 'has_label', labelId: ':label:home' }
    expect(matches(f, ['- [ ] a ^1 #home', '- [ ] b ^2 #work'])).toEqual(['a'])
  })

  it('handles in_progress', () => {
    const f: Filter = { kind: 'in_progress', value: true }
    expect(matches(f, ['- [~] running ^a', '- [ ] not ^b'])).toEqual(['running'])
  })

  it('handles is_set and not_set without needing a value', () => {
    expect(matches({ kind: 'prop', defId: 'builtin-due', op: 'is_set' },
      ['- [ ] a ^1 due:2026-01-01', '- [ ] b ^2'])).toEqual(['a'])
    expect(matches({ kind: 'prop', defId: 'builtin-due', op: 'not_set' },
      ['- [ ] a ^1 due:2026-01-01', '- [ ] b ^2'])).toEqual(['b'])
  })
})

describe('honesty about what it cannot answer', () => {
  it('reports a user-defined property rather than quietly not matching', () => {
    const f: Filter = { kind: 'prop', defId: 'custom-effort', op: 'eq', text: 'L' }
    const r = evaluate(f, cand('- [ ] x ^a'), clock)
    expect(r.matched).toBe(false)
    expect(r.unsupported).toEqual(['prop:custom-effort'])
  })

  it('reports a cross-workspace clause', () => {
    const f: Filter = { kind: 'in_workspace', workspaceId: 'other' }
    expect(evaluate(f, cand('- [ ] x ^a'), clock).unsupported).toEqual(['in_workspace'])
  })

  it('says nothing when every clause was answerable', () => {
    const f: Filter = { kind: 'all', filters: [{ kind: 'done', value: false }] }
    expect(evaluate(f, cand('- [ ] x ^a'), clock).unsupported).toEqual([])
  })
})

describe('ordering', () => {
  it('falls back to document order for created, which the file format has no field for', () => {
    const list = [cand('- [ ] first ^a'), cand('- [ ] second ^b')]
    expect(sortMatches(list, [{ by: 'created', desc: true }], clock).map((c) => c.task.title))
      .toEqual(['second', 'first'])
  })

  it('sorts by due date, putting the undated last', () => {
    const list = [cand('- [ ] none ^a'), cand('- [ ] late ^b due:2026-12-01'), cand('- [ ] soon ^c due:2026-09-07')]
    expect(sortMatches(list, [{ by: 'prop_date', defId: 'builtin-due' }], clock).map((c) => c.task.title))
      .toEqual(['soon', 'late', 'none'])
  })
})
