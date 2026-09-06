import { useCallback, useEffect, useState } from 'react'
import type { Backend } from '../backend'
import { GitHubError } from '../github/client'
import { pageText, withTaskStatus, loadWorkspace, tasksOf, topLevel, deviceId } from '../workspace/workspace'
import type { LoadedPage, Workspace } from '../workspace/workspace'
import type { TaskStatus } from '../format/pageDoc'
import { Bhupura } from './Bhupura'

export function WorkspaceView({ backend }: { backend: Backend }) {
  const [ws, setWs] = useState<Workspace | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [openId, setOpenId] = useState<string | null>(() => location.hash.slice(1) || null)
  const [saving, setSaving] = useState<string | null>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const w = await loadWorkspace(backend.source)
      setWs(w)
      setOpenId((id) => id ?? topLevel(w)[0]?.id ?? null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [backend])

  useEffect(() => { void load() }, [load])

  const toggle = useCallback(async (page: LoadedPage, index: number, to: TaskStatus) => {
    if (saving) return
    setSaving(`${page.id}:${index}`)
    const next = withTaskStatus(page.doc, index, to, new Date(), deviceId())
    // Optimistic: the tick lands instantly and is reconciled by the sha the write returns. A
    // checkbox that waits for a network round trip feels broken even when it is working.
    setWs((w) => w && patch(w, { ...page, doc: next }))
    try {
      const title = titleOf(page.doc.blocks[index])
      const { sha } = await backend.write({
        path: page.path, sha: page.sha, text: pageText(next),
        message: `${to === 'done' ? 'Complete' : 'Reopen'} ${title}`,
      })
      setWs((w) => w && patch(w, { ...page, doc: next, sha }))
      setError(null)
    } catch (e) {
      // Put the old page back. A failed write that leaves the tick showing is the one outcome
      // worse than not writing at all: the screen would claim something the repository does not.
      setWs((w) => w && patch(w, page))
      setError(e instanceof GitHubError && e.status === 409
        ? 'That page changed on GitHub since it loaded — reloading rather than overwriting.'
        : e instanceof Error ? e.message : String(e))
      if (e instanceof GitHubError && e.status === 409) void load()
    } finally {
      setSaving(null)
    }
  }, [backend, saving, load])

  const open_ = (id: string) => { setOpenId(id); history.replaceState(null, '', '#' + id) }

  if (error && !ws) return <main className="empty"><h1>Could not open {backend.label}</h1><p className="error">{error}</p></main>
  if (!ws) return <main className="empty"><p className="mono">Reading {backend.label}…</p></main>

  const lists = topLevel(ws)
  const open = openId ? ws.pages.get(openId) ?? null : null

  return (
    <div className="workspace">
      <nav className="lists">
        <div className="mono lists-head">{ws.name}</div>
        {lists.map((p) => (
          <button
            key={p.id}
            className={p.id === openId ? 'list on' : 'list'}
            onClick={() => open_(p.id)}
          >
            <span className="list-title">{p.doc.title ?? 'Untitled'}</span>
            <span className="list-count">{tasksOf(p).filter((t) => t.task.status !== 'done').length || ''}</span>
          </button>
        ))}
      </nav>

      <main className="page">
        {error && <p className="error banner">{error}</p>}
        {open ? <PageBody ws={ws} page={open} onToggle={toggle} saving={saving} onOpen={open_} /> : null}
      </main>
    </div>
  )
}

function PageBody({ ws, page, onToggle, saving, onOpen }: {
  ws: Workspace
  page: LoadedPage
  onToggle: (p: LoadedPage, i: number, to: TaskStatus) => void
  saving: string | null
  onOpen: (id: string) => void
}) {
  const tasks = tasksOf(page)
  const done = tasks.filter((t) => t.task.status === 'done').length

  return (
    <>
      <header className="page-head">
        <h1>{page.doc.title ?? 'Untitled'}</h1>
        {tasks.length > 0 && (
          <p className="mono">{done} of {tasks.length} done</p>
        )}
      </header>

      <div className="blocks">
        {page.doc.blocks.map((b, i) => {
          if (b.kind === 'task') {
            const child = b.id ? ws.pages.get(b.id) : undefined
            const hasPage = !!child && child.doc.blocks.length > 0
            return (
              <div className="row" key={i} style={{ paddingLeft: b.indent * 22 }}>
                <button
                  className="tick"
                  disabled={saving === `${page.id}:${i}`}
                  aria-label={b.status === 'done' ? `Reopen ${b.title}` : `Complete ${b.title}`}
                  onClick={() => onToggle(page, i, b.status === 'done' ? 'open' : 'done')}
                >
                  <Bhupura state={b.status} />
                </button>
                <span className={b.status === 'done' ? 'title struck' : 'title'}>
                  {b.title || 'Untitled'}
                </span>
                <span className="tokens">
                  {b.priority && <em className="tok pri">!{b.priority}</em>}
                  {b.due && <em className="tok due">{b.due.value.kind === 'allDay' ? b.due.value.date : b.due.value.instant.slice(0, 10)}</em>}
                  {b.labels.map((l) => <em className="tok" key={l}>#{l}</em>)}
                  {b.assignee && <em className="tok">@{b.assignee}</em>}
                </span>
                {hasPage && (
                  <button className="into" onClick={() => onOpen(b.id)} title="Open as a page">›</button>
                )}
              </div>
            )
          }
          if (b.kind === 'heading') return <h2 key={i} style={{ marginLeft: b.indent * 22 }}>{b.text}</h2>
          if (b.kind === 'bullet') return <p key={i} className="bullet" style={{ marginLeft: b.indent * 22 }}>{b.text}</p>
          if (b.kind === 'numbered') return <p key={i} className="bullet" style={{ marginLeft: b.indent * 22 }}>{b.text}</p>
          if (b.kind === 'ink') return <p key={i} className="placeholder mono">ink — not drawn here yet</p>
          if (b.kind === 'image') return <p key={i} className="placeholder mono">image — not shown here yet</p>
          return b.text ? <p key={i} className="prose" style={{ marginLeft: b.indent * 22 }}>{b.text}</p> : null
        })}
      </div>
    </>
  )
}

function patch(ws: Workspace, page: LoadedPage): Workspace {
  const pages = new Map(ws.pages)
  const byPath = new Map(ws.byPath)
  if (page.id) pages.set(page.id, page)
  byPath.set(page.path, page)
  return { ...ws, pages, byPath }
}

function titleOf(b: unknown): string {
  return b && typeof b === 'object' && 'title' in b && typeof b.title === 'string' && b.title
    ? b.title
    : 'a task'
}
