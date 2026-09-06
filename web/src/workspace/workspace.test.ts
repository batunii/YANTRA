import { describe, expect, it } from 'vitest'
import { decode } from '../format/pageCodec'
import { loadWorkspace, pageText, tasksOf, topLevel, withTaskStatus } from './workspace'
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
