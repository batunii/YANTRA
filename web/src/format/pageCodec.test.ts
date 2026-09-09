import { describe, expect, it } from 'vitest'
import { decode, encode, encodeBlock, parseBlock, INDENT } from './pageCodec'
import type { Block, PageDoc } from './pageDoc'

/**
 * Ported from `PageCodecTest.kt`, deliberately case for case.
 *
 * These are not tests of this file. They are tests of an agreement between two clients writing into
 * one git repository: the phone will read what the browser wrote and the browser will read what the
 * phone wrote, and every one of these cases is a way that agreement was found to break. Where a
 * case here disagrees with the Kotlin suite, this port is wrong and should be corrected against it,
 * not the other way round.
 */

const canonical = [
  '---',
  'id: 7c3f',
  'type: task',
  'parent: 1a2b',
  'title: Wire up the sync worker',
  'modified_at: 2026-08-25T14:22:31.402Z',
  'device: sm-s921b',
  '---',
  '# Welcome',
  '',
  'A list holds tasks.',
  '',
  '- [ ] Tasks can nest ^9f1e due:2026-08-26 !high #sync @batunii',
  `${INDENT} - [x] An indented, finished subtask ^4d2c`,
  '',
  '![[ink:5b8a]]',
  '',
].join('\n')

const page = (body: string): string =>
  `---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n${body}\n`

/** The single task on a one-line page. */
const one = (line: string) => {
  const b = decode(page(line)).blocks[0]!
  if (b.kind !== 'task') throw new Error(`expected a task, got ${b.kind}`)
  return b
}

describe('the round trip', () => {
  it('round-trips canonical text byte for byte', () => {
    expect(encode(decode(canonical))).toBe(canonical)
  })

  it('decodes stably across a round trip', () => {
    const once = decode(canonical)
    expect(decode(encode(once))).toEqual(once)
  })

  it('keeps frontmatter', () => {
    const p = decode(canonical)
    expect(p.id).toBe('7c3f')
    expect(p.type).toBe('task')
    expect(p.parent).toBe('1a2b')
    expect(p.title).toBe('Wire up the sync worker')
    expect(p.device).toBe('sm-s921b')
    expect(p.modifiedAt).toBe('2026-08-25T14:22:31.402Z')
  })

  it('recognises every block type', () => {
    const kinds = decode(canonical).blocks.map((b) => b.kind)
    expect(kinds).toEqual(['heading', 'prose', 'task', 'task', 'ink'])
  })
})

describe('raw preservation', () => {
  it('re-renders a modified block even though its raw line is stale', () => {
    const p = decode(canonical)
    const task = p.blocks[2]!
    if (task.kind !== 'task') throw new Error('expected a task')
    // Edited in place, raw left behind on purpose — the emitter must not trust it.
    const edited: Block = { ...task, status: 'done' }
    const out = encode({ ...p, blocks: [edited] })
    expect(out).toContain('- [x] Tasks can nest ^9f1e')
    expect(out).not.toContain('- [ ] Tasks can nest')
  })

  it('leaves neighbours byte-identical when one block is edited', () => {
    const p = decode(canonical)
    const blocks = [...p.blocks]
    const t = blocks[2]!
    if (t.kind !== 'task') throw new Error('expected a task')
    blocks[2] = { ...t, status: 'done' }
    const out = encode({ ...p, blocks })
    // The hand-spaced indented subtask below it must not have been touched.
    expect(out).toContain(`${INDENT} - [x] An indented, finished subtask ^4d2c`)
  })

  it('keeps an untouched hand-edited line’s own spacing', () => {
    const odd = '- [ ]    lots   of   spacing   ^a1'
    const b = parseBlock(odd)
    expect(encodeBlock(b)).toBe(odd)
  })

  it('renumbers a moved numbered item rather than keeping its old number', () => {
    const p = decode(page('1. first\n2. second'))
    // Swapped, both carrying stale raw lines that still say 1. and 2.
    const swapped = [p.blocks[1]!, p.blocks[0]!]
    const out = encode({ ...p, blocks: swapped })
    expect(out).toContain('1. second')
    expect(out).toContain('2. first')
  })
})

describe('the right-to-left token scan', () => {
  it('parses trailing tokens and leaves a hash inside a title alone', () => {
    const t = one('- [ ] Buy milk ^a1 due:2026-08-26 !high #shop @batunii')
    expect(t.title).toBe('Buy milk')
    expect(t.id).toBe('a1')
    expect(t.priority).toBe('high')
    expect(t.labels).toEqual(['shop'])
    expect(t.assignee).toBe('batunii')
    expect(t.due?.value).toEqual({ kind: 'allDay', date: '2026-08-26' })
  })

  it('keeps a token-looking word mid-title in the title', () => {
    // Right-to-left stops at `pencils`, so `#2` never gets read as a label.
    const t = one('- [ ] Buy #2 pencils ^a1')
    expect(t.title).toBe('Buy #2 pencils')
    expect(t.id).toBe('a1')
    expect(t.labels).toEqual([])
  })

  it('keeps labels in their written order', () => {
    expect(one('- [ ] x ^i #one #two #three').labels).toEqual(['one', 'two', 'three'])
  })

  it('treats a task with no tokens as just a title', () => {
    const t = one('- [ ] Just a task')
    expect(t.title).toBe('Just a task')
    expect(t.id).toBe('')
    expect(t.due).toBeUndefined()
  })

  it('does not read a link in a title as trailing tokens', () => {
    const t = one('- [ ] see [[Buy milk #2|^abc]] ^a1')
    expect(t.title).toBe('see [[Buy milk #2|^abc]]')
    expect(t.id).toBe('a1')
    expect(t.labels).toEqual([])
  })

  it('does not read an at-sign inside a link as an assignee', () => {
    const t = one('- [ ] ping [[the @team page]] ^a1')
    expect(t.assignee).toBeUndefined()
    expect(t.title).toBe('ping [[the @team page]]')
  })
})

describe('due encoding', () => {
  it('distinguishes all-day from timed by the value itself', () => {
    expect(one('- [ ] x ^i due:2026-08-26').due?.value)
      .toEqual({ kind: 'allDay', date: '2026-08-26' })
    expect(one('- [ ] x ^i due:2026-08-26T09:00:00Z').due?.value)
      .toEqual({ kind: 'at', instant: '2026-08-26T09:00:00Z' })
  })

  it('survives a negative reminder offset', () => {
    // -540 is 09:00 on the day of an all-day task.
    const due = one('- [ ] x ^i due:2026-08-26+r-540').due
    expect(due?.value).toEqual({ kind: 'allDay', date: '2026-08-26' })
    expect(due?.reminderMin).toBe(-540)
    const rendered = encodeBlock({
      kind: 'task', id: 'i', title: 'x', status: 'open', indent: 0, labels: [], due,
    })
    expect(rendered.endsWith('due:2026-08-26+r-540')).toBe(true)
  })

  it('leaves a malformed due in the title rather than swallowing it', () => {
    const t = one('- [ ] pay rent due:not-a-date ^a1')
    expect(t.title).toBe('pay rent due:not-a-date')
    expect(t.due).toBeUndefined()
  })
})

describe('forgiveness', () => {
  it('turns an unclassifiable line into prose holding its original text', () => {
    const b = decode(page('| a | markdown | table |')).blocks[0]!
    expect(b.kind).toBe('prose')
    expect(b.kind === 'prose' && b.text).toBe('| a | markdown | table |')
  })

  it('survives frontmatter from a newer client', () => {
    // Dropping a key we do not know is how "it lost a field on my laptop" happens.
    const text = '---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\nfuture_key: hello\n---\n'
    const p = decode(text)
    expect(p.unknownKeys).toEqual({ future_key: 'hello' })
    expect(encode(p)).toContain('future_key: hello')
  })

  it('parses a file with no frontmatter at all', () => {
    const p = decode('- [ ] orphan task ^a1\n')
    expect(p.id).toBe('')
    expect(p.blocks).toHaveLength(1)
    expect(p.blocks[0]!.kind).toBe('task')
  })

  it('accepts windows line endings', () => {
    const p = decode(canonical.replace(/\n/g, '\r\n'))
    expect(encode(p)).toBe(canonical)
  })
})

describe('block kinds', () => {
  it('does not mistake bullets for tasks', () => {
    expect(parseBlock('- just a bullet').kind).toBe('bullet')
    expect(parseBlock('- [ ] a task').kind).toBe('task')
  })

  it('treats an empty task as a task, with or without the trailing space', () => {
    expect(parseBlock('- [ ]').kind).toBe('task')
    expect(parseBlock('- [ ] ').kind).toBe('task')
  })

  it('round-trips an empty task, id and all', () => {
    const line = '- [ ] ^a1'
    const b = parseBlock(line)
    expect(b.kind === 'task' && b.title).toBe('')
    expect(encodeBlock(b)).toBe(line)
  })

  it('keeps markdown blockquotes as prose at depth zero', () => {
    const b = parseBlock('> a quote')
    expect(b.kind).toBe('prose')
    expect(b.indent).toBe(0)
  })

  it('writes indent back at the depth it was read', () => {
    const line = `${INDENT} ${INDENT} - [ ] deep ^a1`
    const b = parseBlock(line)
    expect(b.indent).toBe(2)
    expect(encodeBlock(b)).toBe(line)
  })

  it('keeps ink and image ids', () => {
    const ink = parseBlock('![[ink:5b8a]]')
    const img = parseBlock('![[image:pages/x.jpg]]')
    expect(ink.kind === 'ink' && ink.id).toBe('5b8a')
    expect(img.kind === 'image' && img.uri).toBe('pages/x.jpg')
  })

  it('does not bring an empty block back holding whitespace', () => {
    // The emitter writes a lone space for an empty block; reading that back as text is how notes
    // used to be born holding a character nobody typed.
    const p: PageDoc = {
      id: 'a', type: 'task', modifiedAt: '2026-01-01T00:00:00Z', unknownKeys: {},
      blocks: [{ kind: 'prose', text: '', indent: 0 }],
    }
    const out = encode(p)
    expect(out.endsWith('---\n \n')).toBe(true)
    const back = decode(out).blocks[0]!
    expect(back.kind === 'prose' && back.text).toBe('')
  })
})

describe('rendering is deterministic', () => {
  it('renders the same task to the same bytes every time', () => {
    const t: Block = {
      kind: 'task', id: 'a1', title: 'x', status: 'done', indent: 0,
      due: { value: { kind: 'allDay', date: '2026-08-26' } },
      deadline: '2026-08-30', doneAt: '2026-08-27', priority: 'high',
      labels: ['one', 'two'], assignee: 'batunii',
    }
    const expected =
      '- [x] x ^a1 due:2026-08-26 deadline:2026-08-30 done:2026-08-27 !high #one #two @batunii'
    expect(encodeBlock(t)).toBe(expected)
    expect(encodeBlock(parseBlock(expected))).toBe(expected)
  })

  it('writes done: only on a finished task', () => {
    const open: Block = {
      kind: 'task', id: 'a1', title: 'x', status: 'open', indent: 0,
      labels: [], doneAt: '2026-08-27',
    }
    expect(encodeBlock(open)).not.toContain('done:')
  })

  it('numbers items from their own run', () => {
    const p = decode(page('1. a\n2. b\n\nprose\n\n1. c'))
    const out = encode(p)
    expect(out).toContain('1. a')
    expect(out).toContain('2. b')
    expect(out).toContain('1. c')
  })
})
