/**
 * Where the token lives, for now.
 *
 * A pasted fine-grained token, kept in localStorage. This is the placeholder for the real sign-in:
 * a browser cannot complete GitHub's OAuth exchange because `github.com/login/oauth/access_token`
 * refuses CORS, so the button that says "Sign in with GitHub" needs a server-side exchange that
 * does not exist yet. Everything downstream of the token is identical either way, which is why the
 * UI is worth building against this first — swapping the source changes this file and nothing else.
 */

const KEY = 'yantra.token'

export function readToken(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function writeToken(token: string | null) {
  try {
    if (token) localStorage.setItem(KEY, token)
    else localStorage.removeItem(KEY)
  } catch {
    // Private mode. The session still works; it just will not be remembered.
  }
}

const REPO_KEY = 'yantra.repos'

/** `owner/name` of each repository holding a workspace. */
export function readRepos(): string[] {
  try {
    const raw = localStorage.getItem(REPO_KEY)
    return raw ? (JSON.parse(raw) as string[]) : []
  } catch {
    return []
  }
}

export function writeRepos(repos: string[]) {
  try {
    localStorage.setItem(REPO_KEY, JSON.stringify(repos))
  } catch {
    /* as above */
  }
}
