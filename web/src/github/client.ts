/**
 * The GitHub API, as much of it as a workspace needs.
 *
 * A browser can do this at all because `api.github.com` answers with
 * `access-control-allow-origin: *` and exposes the rate-limit headers — so reading and writing a
 * repository needs no server of ours in the path. The one thing it cannot do is *obtain* a token:
 * `github.com/login/oauth/access_token` refuses CORS outright, which is why sign-in needs a
 * server-side exchange and nothing else does.
 */

const API = 'https://api.github.com'

export class GitHubError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** Seconds until the rate limit resets, when that is why this failed. */
    readonly retryAfterSec?: number,
  ) {
    super(message)
    this.name = 'GitHubError'
  }
}

/** A file in a repository, as the tree API describes it. */
export interface TreeEntry {
  path: string
  /** The blob's own hash — the id to read it by, and stable across commits that do not touch it. */
  sha: string
  size: number
}

export class GitHubClient {
  constructor(private readonly token: string) {}

  private async call(path: string, init?: RequestInit): Promise<Response> {
    const res = await fetch(API + path, {
      ...init,
      headers: {
        Authorization: `Bearer ${this.token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    })
    if (res.ok) return res

    // The three failures worth telling apart, because the user can act on each differently: a token
    // that is wrong, a token that is right but not scoped to this repository, and a limit that will
    // lift on its own.
    const remaining = res.headers.get('x-ratelimit-remaining')
    if (res.status === 403 && remaining === '0') {
      const reset = Number(res.headers.get('x-ratelimit-reset') ?? 0)
      const wait = Math.max(0, reset - Math.floor(Date.now() / 1000))
      throw new GitHubError(res.status, `Rate limit reached. It lifts in ${Math.ceil(wait / 60)} min.`, wait)
    }
    const body = await res.text()
    const detail = safeMessage(body) ?? res.statusText
    if (res.status === 401) throw new GitHubError(401, `GitHub rejected the token: ${detail}`)
    if (res.status === 404) {
      throw new GitHubError(404, 'Not found — or the token has no access to it. GitHub returns 404 rather than 403 for a private repository it cannot see, so these are the same answer.')
    }
    throw new GitHubError(res.status, detail)
  }

  async login(): Promise<{ login: string; name: string | null; avatarUrl: string }> {
    const j = await (await this.call('/user')).json()
    return { login: j.login, name: j.name ?? null, avatarUrl: j.avatar_url }
  }

  /**
   * Every file on a branch, in one request.
   *
   * `recursive=1` is the difference between one round trip and one per directory. GitHub truncates
   * beyond roughly 100k entries and says so, which no Yantra workspace will approach — but a silent
   * half-answer would look exactly like a workspace missing pages, so it is checked.
   */
  async tree(owner: string, repo: string, ref: string): Promise<TreeEntry[]> {
    const j = await (await this.call(
      `/repos/${owner}/${repo}/git/trees/${encodeURIComponent(ref)}?recursive=1`,
    )).json()
    if (j.truncated) {
      throw new GitHubError(200, 'This repository is too large for a single tree read, and a partial answer would look like missing pages.')
    }
    return (j.tree as Array<{ path: string; type: string; sha: string; size?: number }>)
      .filter((e) => e.type === 'blob')
      .map((e) => ({ path: e.path, sha: e.sha, size: e.size ?? 0 }))
  }

  /** A blob's bytes, decoded as UTF-8. Addressed by blob sha, so it is cacheable forever. */
  async blobText(owner: string, repo: string, sha: string): Promise<string> {
    const j = await (await this.call(`/repos/${owner}/${repo}/git/blobs/${sha}`)).json()
    if (j.encoding !== 'base64') throw new GitHubError(200, `Unexpected blob encoding ${j.encoding}`)
    return decodeBase64(j.content)
  }

  /**
   * Write one file, refusing to clobber.
   *
   * [sha] is the blob the edit was made against, and GitHub rejects the write with 409 if the file
   * has moved on since. That check is the whole safety story for a client with no merge machinery:
   * the alternative is a last-writer-wins overwrite of whatever the phone committed thirty seconds
   * ago, silently. Pass `undefined` only to create a file that does not exist yet.
   */
  async putFile(args: {
    owner: string
    repo: string
    path: string
    branch: string
    text: string
    sha?: string
    message: string
  }): Promise<{ sha: string }> {
    const res = await this.call(
      `/repos/${args.owner}/${args.repo}/contents/${args.path.split('/').map(encodeURIComponent).join('/')}`,
      {
        method: 'PUT',
        body: JSON.stringify({
          message: args.message,
          content: encodeBase64(args.text),
          branch: args.branch,
          ...(args.sha ? { sha: args.sha } : {}),
          // The same identity the Android app commits under, so a history written from two clients
          // reads as one person rather than two.
          committer: { name: 'Yantra', email: 'yantra@shoonya.ie' },
          author: { name: 'Yantra', email: 'yantra@shoonya.ie' },
        }),
      },
    ).catch((e: unknown) => {
      if (e instanceof GitHubError && e.status === 409) {
        throw new GitHubError(409, 'This page changed on GitHub since it was loaded. Reload before saving, or the other edit is lost.')
      }
      throw e
    })
    const j = await res.json()
    return { sha: j.content.sha }
  }
}

function safeMessage(body: string): string | null {
  try {
    const j = JSON.parse(body)
    return typeof j.message === 'string' ? j.message : null
  } catch {
    return null
  }
}

/** base64 → UTF-8. `atob` yields bytes-as-code-units, which is not a string until decoded. */
export function decodeBase64(b64: string): string {
  const binary = atob(b64.replace(/\n/g, ''))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return new TextDecoder('utf-8').decode(bytes)
}

/**
 * UTF-8 → base64, in chunks.
 *
 * `String.fromCharCode(...bytes)` is the obvious spelling and blows the argument limit on a page of
 * any size — which would mean saving worked in testing and threw on the first long note.
 */
export function encodeBase64(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}
