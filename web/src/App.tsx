import { useCallback, useEffect, useState } from 'react'
import { GitHubClient, GitHubError } from './github/client'
import { demoBackend, githubBackend } from './backend'
import { useMemo } from 'react'
import { readRepos, readToken, writeRepos, writeToken } from './auth'
import { SignIn } from './ui/SignIn'
import { WorkspaceView } from './ui/WorkspaceView'
import './ui/app.css'

/** The branch a Yantra workspace keeps its files on, off whatever else the repo contains. */
export const BRANCH = 'yantra-tasks'

/** `?demo` opens a synthetic workspace with no token and no network. */
const DEMO = new URLSearchParams(location.search).has('demo')

export function App() {
  const [token, setToken] = useState<string | null>(() => readToken())
  const [repos, setRepos] = useState<string[]>(() => readRepos())
  const [who, setWho] = useState<{ login: string; avatarUrl: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [active, setActive] = useState<string | null>(() => readRepos()[0] ?? null)

  const client = useMemo(() => (token ? new GitHubClient(token) : null), [token])
  const backend = useMemo(
    () => (client && active ? githubBackend(client, active, BRANCH) : null),
    [client, active],
  )

  useEffect(() => {
    if (!client) { setWho(null); return }
    let live = true
    client.login()
      .then((u) => { if (live) { setWho(u); setError(null) } })
      .catch((e: unknown) => {
        if (!live) return
        // A token that has expired or been revoked is indistinguishable from a typo, and the fix is
        // the same: show the paste screen again rather than a dead app with an error banner.
        setError(e instanceof Error ? e.message : String(e))
        setWho(null)
      })
    return () => { live = false }
  }, [token])

  const signOut = useCallback(() => {
    writeToken(null)
    setToken(null)
    setWho(null)
  }, [])

  const addRepo = useCallback((slug: string) => {
    setRepos((prev) => {
      const next = prev.includes(slug) ? prev : [...prev, slug]
      writeRepos(next)
      return next
    })
    setActive(slug)
  }, [])

  if (DEMO) {
    return (
      <div className="app">
        <header className="chrome">
          <div className="brand">
            <span className="mono">Yantra</span>
            <span className="chrome-sep">/</span>
            <span className="repo on">demo</span>
          </div>
          <a className="ghost" href={location.pathname}>Leave the demo</a>
        </header>
        <WorkspaceView backend={demoBackend} />
      </div>
    )
  }

  if (!token || !who) {
    return <SignIn onToken={(t) => { writeToken(t); setToken(t) }} error={error} pending={!!token && !error} />
  }

  return (
    <div className="app">
      <header className="chrome">
        <div className="brand">
          <span className="mono">Yantra</span>
          <span className="chrome-sep">/</span>
          <RepoPicker repos={repos} active={active} onPick={setActive} onAdd={addRepo} />
        </div>
        <button className="ghost" onClick={signOut} title={`Signed in as ${who.login}`}>
          <img src={who.avatarUrl} alt="" width={22} height={22} className="avatar" />
          Sign out
        </button>
      </header>
      {active && backend
        ? <WorkspaceView key={active} backend={backend} />
        : <Empty onAdd={addRepo} />}
    </div>
  )
}

function RepoPicker({ repos, active, onPick, onAdd }: {
  repos: string[]
  active: string | null
  onPick: (s: string) => void
  onAdd: (s: string) => void
}) {
  return (
    <span className="repo-picker">
      {repos.map((r) => (
        <button key={r} className={r === active ? 'repo on' : 'repo'} onClick={() => onPick(r)}>
          {r.split('/')[1]}
        </button>
      ))}
      <button className="repo add" onClick={() => {
        const slug = prompt('Repository holding a workspace, as owner/name')
        if (slug?.includes('/')) onAdd(slug.trim())
      }}>+</button>
    </span>
  )
}

function Empty({ onAdd }: { onAdd: (s: string) => void }) {
  return (
    <main className="empty">
      <h1>No workspace yet</h1>
      <p>
        A workspace is a git repository with a <code>{BRANCH}</code> branch — the same one the
        Android app pushes to. Add the one you already have.
      </p>
      <button className="primary" onClick={() => {
        const slug = prompt('Repository, as owner/name')
        if (slug?.includes('/')) onAdd(slug.trim())
      }}>Add a repository</button>
    </main>
  )
}

export { GitHubError }
