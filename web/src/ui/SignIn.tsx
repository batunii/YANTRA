import { useState } from 'react'
import { Bhupura } from './Bhupura'

/**
 * The token screen, and an honest note about why it is a token screen.
 *
 * The real button belongs here and cannot exist yet: GitHub's token exchange refuses CORS, so
 * "Sign in with GitHub" requires a server-side step this client does not have. Pretending otherwise
 * — a button that opens a popup and then asks you to paste something anyway — would be worse than
 * saying what is actually going on.
 */
export function SignIn({ onToken, error, pending }: {
  onToken: (t: string) => void
  error: string | null
  pending: boolean
}) {
  const [value, setValue] = useState('')

  return (
    <main className="signin">
      <div className="signin-mark"><Bhupura state="in_progress" size={56} /></div>
      <h1>Yantra</h1>
      <p className="lede">
        Your tasks are Markdown in a git repository you own. This reads and writes the same files
        the Android app does.
      </p>

      {error && <p className="error">{error}</p>}

      <form
        onSubmit={(e) => { e.preventDefault(); if (value.trim()) onToken(value.trim()) }}
      >
        <label className="mono" htmlFor="token">GitHub token</label>
        <input
          id="token"
          type="password"
          autoComplete="off"
          spellCheck={false}
          placeholder="github_pat_…"
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
        <button className="primary" type="submit" disabled={!value.trim() || pending}>
          {pending ? 'Checking…' : 'Continue'}
        </button>
      </form>

      <details className="note">
        <summary>Why paste a token instead of signing in?</summary>
        <p>
          A browser can read and write your repositories directly — <code>api.github.com</code>
          allows it. What it cannot do is complete GitHub's sign-in exchange, because
          <code>github.com/login/oauth/access_token</code> refuses cross-origin requests outright.
          A “Sign in with GitHub” button therefore needs a small server to swap the code for a
          token; until that exists, this is the honest version.
        </p>
        <p>
          Use a <strong>fine-grained</strong> token scoped to just your task repositories, with
          <em> Contents: read and write</em>. It is kept in this browser's local storage and sent
          only to GitHub.
        </p>
      </details>
    </main>
  )
}
