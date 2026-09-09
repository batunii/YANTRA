import type { PageSource } from './workspace/workspace'
import { GitHubClient } from './github/client'
import { demoSource } from './demo'

/**
 * Where a workspace's pages come from and go back to.
 *
 * The view knows only this. GitHub is one implementation and the demo fixture is another, which is
 * what lets the whole interaction be exercised — including a failing write — without a token, and
 * keeps the network out of the component that draws a list.
 */
export interface Backend {
  label: string
  source: PageSource
  /** Returns the new blob sha, which becomes the precondition for the next write to that file. */
  write(args: { path: string; sha?: string; text: string; message: string }): Promise<{ sha: string }>
  readOnly: boolean
}

export function githubBackend(client: GitHubClient, slug: string, branch: string): Backend {
  const [owner, repo] = slug.split('/') as [string, string]
  let entries: Array<{ path: string; sha: string }> | null = null
  return {
    label: slug,
    readOnly: false,
    source: {
      list: async () => (entries ??= await client.tree(owner, repo, branch)),
      read: (_path, sha) => client.blobText(owner, repo, sha),
    },
    write: ({ path, sha, text, message }) =>
      client.putFile({ owner, repo, path, branch, text, sha, message }),
  }
}

export const demoBackend: Backend = {
  label: 'demo',
  readOnly: false,
  source: demoSource,
  // Accepted and discarded. Returning a fresh sha each time keeps the optimistic update honest:
  // the row settles exactly as it would against a real repository.
  write: async () => ({ sha: 'demo-' + Math.random().toString(36).slice(2, 8) }),
}
