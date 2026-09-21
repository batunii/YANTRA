import { describe, expect, it } from 'vitest'
import { decode } from '../format/pageCodec'
import { loadWorkspace, normalizeIndents, pageText, tasksOf, topLevel, withBlockMoved, withIndent, withNewTask, withTaskStatus, withTaskTitle } from './workspace'
import type { PageSource } from './workspace'

const page = (front: string, body = '') => `---\n${front}\n---\n${body}`

const fixture: Record<string, string> = {
  '.yantra/manifest.json': '{"name":"Personal","createdAt":1787674492642}',
  'pages/inbox.md': page(
    'id: inbox-id\ntype: list\ntitle: Inbox\nsystem_key: inbox\nmodified_at: 2026-09-01T00:00:00Z',
    '- [ ] first ^t1\n- [x] second ^t2 done:2026-09-01\n',
  ),
  'pages/sept.md': page(
    'id: sept-id\ntype: list\ntitle: September Tasks\nmodified_at: 2026-09-01T00:00:00Z',
    '- [ ] a task ^t3\n',
  ),
  'pages/t1.md': page('id: t1\ntype: task\nparent: inbox-id\nmodified_at: 2026-09-01T00:00:00Z'),
  'pages/today.md': page(
    'id: today-id\ntype: smart_list\ntitle: Today\nsystem_key: today\nmodified_at: 2026-09-01T00:00:00Z',
  ),
  'focus/2026-09.log': 'not a page\n',
}

const source: PageSource = {
  list: async () => Object.keys(fixture).map((path) => ({ path, sha: 'sha-' + path })),
  read: async (path) => fixture[path]!,
}

describe('loading a workspace', () => {
  it('takes its name from the manifest', async () => {
    expect((await loadWorkspace(source)).name).toBe('Personal')
  })

  it('indexes pages by the id in their frontmatter, not by filename', async () => {
    const ws = await loadWorkspace(source)
    expect(ws.pages.get('inbox-id')?.path).toBe('pages/inbox.md')
    expect(ws.pages.get('t1')?.doc.parent).toBe('inbox-id')
  })

  it('reads only pages/, not the focus ledger', async () => {
    const ws = await loadWorkspace(source)
    expect([...ws.byPath.keys()]).not.toContain('focus/2026-09.log')
  })

  it('survives a manifest it cannot parse', async () => {
    const broken: PageSource = {
      ...source,
      read: async (p) => (p === '.yantra/manifest.json' ? '{ not json' : fixture[p]!),
    }
    const ws = await loadWorkspace(broken)
    // Costs the workspace its name and nothing else.
    expect(ws.name).toBe('Workspace')
    expect(ws.pages.size).toBe(4)
  })

  it('opens at lists and smart lists that are nobody’s child, inbox first', async () => {
    const ws = await loadWorkspace(source)
    expect(topLevel(ws).map((p) => p.doc.title)).toEqual(['Inbox', 'September Tasks', 'Today'])
  })
})

describe('ticking a task', () => {
  const now = new Date('2026-09-06T10:00:00.000Z')

  it('flips the status and stamps the day it finished', () => {
    const doc = decode(fixture['pages/inbox.md']!)
    const next = withTaskStatus(doc, 0, 'done', now, 'web-abc123')
    const t = next.blocks[0]!
    expect(t.kind === 'task' && t.status).toBe('done')
    expect(t.kind === 'task' && t.doneAt).toBeTruthy()
  })

  it('clears done: when a task is un-finished', () => {
    const doc = decode(fixture['pages/inbox.md']!)
    const next = withTaskStatus(doc, 1, 'open', now, 'web-abc123')
    const t = next.blocks[1]!
    expect(t.kind === 'task' && t.doneAt).toBeUndefined()
    expect(pageText(next)).not.toContain('done:2026-09-01')
  })

  it('re-renders the edited line and leaves its neighbour byte-identical', () => {
    const doc = decode(fixture['pages/inbox.md']!)
    const out = pageText(withTaskStatus(doc, 0, 'done', now, 'web-abc123'))
    expect(out).toContain('- [x] first ^t1')
    expect(out).toContain('- [x] second ^t2 done:2026-09-01')
  })

  it('records who wrote it and when, so the tiebreak has something to compare', () => {
    const doc = decode(fixture['pages/inbox.md']!)
    const next = withTaskStatus(doc, 0, 'done', now, 'web-abc123')
    expect(next.device).toBe('web-abc123')
    expect(next.modifiedAt).toBe('2026-09-06T10:00:00.000Z')
    expect(pageText(next)).toContain('device: web-abc123')
  })

  it('refuses to edit a block that is not a task', () => {
    const doc = decode(page('id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z', '# heading\n'))
    expect(() => withTaskStatus(doc, 0, 'done', now, 'web-abc123')).toThrow(/not a task/)
  })

  it('addresses tasks by block index, so prose between them does not shift the target', () => {
    const doc = decode(page(
      'id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
      '- [ ] one ^a\n\nsome prose\n\n- [ ] two ^b\n',
    ))
    const tasks = tasksOf({ id: 'x', path: 'p', sha: 's', doc })
    expect(tasks.map((t) => t.task.title)).toEqual(['one', 'two'])
    expect(tasks[1]!.index).toBe(2)
    const out = pageText(withTaskStatus(doc, tasks[1]!.index, 'done', now, 'd'))
    expect(out).toContain('- [x] two ^b')
    expect(out).toContain('- [ ] one ^a')
  })
})

describe('capturing a task', () => {
  const now = new Date('2026-09-06T10:00:00.000Z')
  const list = () => decode(fixture['pages/sept.md']!)

  it('appends it to the page and gives it an id', () => {
    const { doc, id } = withNewTask(list(), 'Buy milk', now, 'web-1', 'fixed-id')
    expect(id).toBe('fixed-id')
    const b = doc.blocks[doc.blocks.length - 1]!
    expect(b.kind === 'task' && b.title).toBe('Buy milk')
    expect(pageText(doc)).toContain('- [ ] Buy milk ^fixed-id')
  })

  it('reads trailing tokens out of what was typed, using the file’s own rules', () => {
    const { doc } = withNewTask(list(), 'Buy milk #shop !high @batunii', now, 'web-1', 'x')
    const b = doc.blocks[doc.blocks.length - 1]!
    if (b.kind !== 'task') throw new Error('expected a task')
    expect(b.title).toBe('Buy milk')
    expect(b.labels).toEqual(['shop'])
    expect(b.priority).toBe('high')
    expect(b.assignee).toBe('batunii')
  })

  it('keeps a hash that is part of the words, exactly as a file would', () => {
    const { doc } = withNewTask(list(), 'Buy #2 pencils', now, 'web-1', 'x')
    const b = doc.blocks[doc.blocks.length - 1]!
    expect(b.kind === 'task' && b.title).toBe('Buy #2 pencils')
    expect(b.kind === 'task' && b.labels).toEqual([])
  })

  it('writes the canonical shape, not the line it was assembled from', () => {
    // Typed with the label before the id; the file must come out in the app's fixed token order.
    const { doc } = withNewTask(list(), 'Buy milk #shop', now, 'web-1', 'x')
    expect(pageText(doc)).toContain('- [ ] Buy milk ^x #shop')
  })

  it('leaves the rest of the page byte-identical', () => {
    const { doc } = withNewTask(list(), 'new one', now, 'web-1', 'x')
    expect(pageText(doc)).toContain('- [ ] a task ^t3')
  })
})

describe('retitling a task', () => {
  const now = new Date('2026-09-06T10:00:00.000Z')

  it('keeps the id, the status and the indent', () => {
    const doc = decode(fixture['pages/inbox.md']!)
    const next = withTaskTitle(doc, 1, 'renamed', now, 'web-1')
    const b = next.blocks[1]!
    if (b.kind !== 'task') throw new Error('expected a task')
    expect(b.id).toBe('t2')
    expect(b.status).toBe('done')
    expect(b.title).toBe('renamed')
    expect(pageText(next)).toContain('- [x] renamed ^t2 done:2026-09-01')
  })

  it('reads tokens out of an edited title too', () => {
    const doc = decode(fixture['pages/inbox.md']!)
    const next = withTaskTitle(doc, 0, 'first #later', now, 'web-1')
    const b = next.blocks[0]!
    expect(b.kind === 'task' && b.labels).toEqual(['later'])
  })
})

describe('reordering', () => {
  const now = new Date('2026-09-06T10:00:00.000Z')
  const doc = () => decode(page(
    'id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
    '- [ ] one ^a\n- [ ] two ^b\n- [ ] three ^c\n',
  ))

  it('moves a line up', () => {
    const next = withBlockMoved(doc(), 2, 0, now, 'd')
    expect(next.blocks.map((b) => (b.kind === 'task' ? b.title : ''))).toEqual(['three', 'one', 'two'])
  })

  it('moves a line down', () => {
    const next = withBlockMoved(doc(), 0, 2, now, 'd')
    expect(next.blocks.map((b) => (b.kind === 'task' ? b.title : ''))).toEqual(['two', 'three', 'one'])
  })

  it('leaves the untouched lines byte-identical', () => {
    const out = pageText(withBlockMoved(doc(), 0, 2, now, 'd'))
    expect(out).toContain('- [ ] two ^b')
    expect(out).toContain('- [ ] three ^c')
  })

  it('is a no-op when the position does not change', () => {
    const before = doc()
    expect(withBlockMoved(before, 1, 1, now, 'd')).toBe(before)
  })

  it('moves one line, not the indented run under it', () => {
    // Indentation here is layout, not parentage: a task's children live on its own page.
    const d = decode(page('id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
      '- [ ] parent ^a\n» - [ ] child ^b\n- [ ] other ^c\n'))
    const next = withBlockMoved(d, 0, 2, now, 'd')
    expect(next.blocks.map((b) => (b.kind === 'task' ? b.title : ''))).toEqual(['child', 'other', 'parent'])
  })
})

describe('the indent clamp', () => {
  const now = new Date('2026-09-06T10:00:00.000Z')

  it('pulls the first line flush left', () => {
    const d = decode(page('id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
      '» - [ ] orphaned indent ^a\n'))
    expect(normalizeIndents(d.blocks)[0]!.indent).toBe(0)
  })

  it('never lets a line be more than one step deeper than the one above', () => {
    const d = decode(page('id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
      '- [ ] a ^1\n» » » - [ ] too deep ^2\n'))
    expect(normalizeIndents(d.blocks).map((b) => b.indent)).toEqual([0, 1])
  })

  it('runs after a move, so dragging to the top cannot strand an indent', () => {
    const d = decode(page('id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
      '- [ ] a ^1\n» - [ ] b ^2\n'))
    const next = withBlockMoved(d, 1, 0, now, 'dev')
    expect(next.blocks.map((b) => b.indent)).toEqual([0, 0])
    expect(pageText(next)).toContain('- [ ] b ^2')
  })

  it('indents and outdents within the legal depth', () => {
    const d = decode(page('id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
      '- [ ] a ^1\n- [ ] b ^2\n'))
    expect(withIndent(d, 1, +1, now, 'dev').blocks[1]!.indent).toBe(1)
    // The first line has no line to be under, so it stays flush left however hard it is pushed.
    expect(withIndent(d, 0, +1, now, 'dev').blocks[0]!.indent).toBe(0)
  })

  it('writes the indent marker back at the depth it ended up', () => {
    const d = decode(page('id: x\ntype: list\nmodified_at: 2026-09-01T00:00:00Z',
      '- [ ] a ^1\n- [ ] b ^2\n'))
    expect(pageText(withIndent(d, 1, +1, now, 'dev'))).toContain('» - [ ] b ^2')
  })
})
