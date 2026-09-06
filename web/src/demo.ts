import type { PageSource } from './workspace/workspace'

/**
 * A workspace made of nothing, for looking at the client without a token.
 *
 * Deliberately synthetic rather than a copy of anyone's real pages: it is checked into a public
 * repository. The shapes are the ones that matter — an indented subtask, every trailing token, a
 * task whose child page has a body, prose and a heading between list items, a smart list that is
 * computed and therefore empty here.
 */

const page = (front: string, body = '') => `---\n${front}\n---\n${body}`

const files: Record<string, string> = {
  '.yantra/manifest.json': '{"name":"Demo","createdAt":1787674492642}',

  'pages/inbox.md': page(
    'id: inbox\ntype: list\ntitle: Inbox\nsystem_key: inbox\nmodified_at: 2026-09-06T09:00:00Z',
    [
      '- [ ] Read the format notes ^p-format due:2026-09-08 #reading',
      '- [~] Draft the release checklist ^p-release !high @batunii',
      '- [x] Register the GitHub App ^p-ghapp done:2026-09-05',
      '',
    ].join('\n'),
  ),

  'pages/work.md': page(
    'id: work\ntype: list\ntitle: September Tasks\nmodified_at: 2026-09-06T09:00:00Z',
    [
      '# This week',
      '',
      'Everything below is one file. The order of these lines *is* the list.',
      '',
      '- [ ] Ship the beta ^p-beta due:2026-09-12 !high #release',
      '» - [ ] Write the privacy policy ^p-privacy',
      '» - [x] Regenerate the signing key ^p-key done:2026-09-06',
      '- [ ] Buy #2 pencils ^p-pencils',
      '',
      'That last one keeps its hash: trailing tokens are scanned right to left, so the scan stops',
      'at "pencils" and never reaches it.',
      '',
    ].join('\n'),
  ),

  'pages/today.md': page(
    'id: today\ntype: smart_list\ntitle: Today\nsystem_key: today\nmodified_at: 2026-09-06T09:00:00Z',
  ),

  'pages/p-format.md': page(
    'id: p-format\ntype: task\nparent: inbox\nmodified_at: 2026-09-06T09:00:00Z',
    [
      '# Why the codec came first',
      '',
      'Both clients write the same files. A browser that emits slightly different Markdown does not',
      'produce a bug you notice — it produces a diff on every file and a conflict on anything two',
      'devices touched.',
      '',
      '- [ ] Port the page codec ^p-port',
      '- [x] Round-trip real pages ^p-real done:2026-09-06',
      '',
    ].join('\n'),
  ),

  'pages/p-release.md': page(
    'id: p-release\ntype: task\nparent: inbox\nmodified_at: 2026-09-06T09:00:00Z',
    '- [ ] Closed testing, 12 people, 14 days ^p-testing\n',
  ),
  'pages/p-beta.md': page(
    'id: p-beta\ntype: task\nparent: work\nmodified_at: 2026-09-06T09:00:00Z',
    'A task opened as a page holds anything — notes, headings, other tasks.\n',
  ),
}

for (const id of ['p-ghapp', 'p-privacy', 'p-key', 'p-pencils', 'p-port', 'p-real', 'p-testing']) {
  files[`pages/${id}.md`] = page(`id: ${id}\ntype: task\nmodified_at: 2026-09-06T09:00:00Z`)
}

export const demoSource: PageSource = {
  list: async () => Object.keys(files).map((path) => ({ path, sha: path })),
  read: async (path) => files[path]!,
}

/** Writes are accepted and discarded — the point is to show the interaction, not to keep it. */
export const demoWrite = async () => ({ sha: 'demo' })
