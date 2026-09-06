import { useCallback, useEffect, useState } from 'react'
import type { Backend } from '../backend'
import { GitHubError } from '../github/client'
import { pageText, withTaskStatus, withNewTask, withTaskTitle, loadWorkspace, resolveSmartList, tasksOf, topLevel, deviceId } from '../workspace/workspace'
import type { LoadedPage, Workspace } from '../workspace/workspace'
import type { PageDoc, TaskStatus } from '../format/pageDoc'
import { Bhupura } from './Bhupura'
import { Inline } from './Inline'
import { Capture } from './Capture'

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

  /**
   * Every write goes through here: the edit lands on screen first, then the file.
   *
   * Optimistic because a checkbox that waits on a network round trip feels broken even while it is
   * working — and reverting on failure because the one outcome worse than not writing is a screen
   * claiming something the repository does not say.
   */
  const commit = useCallback(async (
    page: LoadedPage,
    next: PageDoc,
    message: string,
    key: string,
  ) => {
    if (saving) return
    setSaving(key)
    setWs((w) => w && patch(w, { ...page, doc: next }))
    try {
      const { sha } = await backend.write({
        path: page.path, sha: page.sha, text: pageText(next), message,
      })
      setWs((w) => w && patch(w, { ...page, doc: next, sha }))
      setError(null)
    } catch (e) {
      setWs((w) => w && patch(w, page))
      const conflict = e instanceof GitHubError && e.status === 409
      setError(conflict
        ? 'That page changed on GitHub since it loaded — reloading rather than overwriting.'
        : e instanceof Error ? e.message : String(e))
      if (conflict) void load()
      throw e
    } finally {
      setSaving(null)
    }
  }, [backend, saving, load])

  const toggle = useCallback((page: LoadedPage, index: number, to: TaskStatus) => {
    const title = titleOf(page.doc.blocks[index])
    const next = withTaskStatus(page.doc, index, to, new Date(), deviceId())
    return commit(page, next, `${to === 'done' ? 'Complete' : 'Reopen'} ${title}`, `${page.id}:${index}`)
      .catch(() => {})
  }, [commit])

  const add = useCallback((page: LoadedPage, typed: string) => {
    const { doc } = withNewTask(page.doc, typed, new Date(), deviceId())
    return commit(page, doc, `Add ${typed}`, `${page.id}:new`)
  }, [commit])

  const retitle = useCallback((page: LoadedPage, index: number, typed: string) => {
    const next = withTaskTitle(page.doc, index, typed, new Date(), deviceId())
    return commit(page, next, `Edit ${typed}`, `${page.id}:${index}`).catch(() => {})
  }, [commit])

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
            <span className="list-count">{countOf(ws, p) || ''}</span>
          </button>
        ))}
      </nav>

      <main className="page">
        {error && <p className="error banner">{error}</p>}
        {open && open.doc.type === 'smart_list' ? (
          <SmartListBody ws={ws} page={open} onToggle={toggle} saving={saving} onOpen={open_} />
        ) : open ? (
          <PageBody
            ws={ws} page={open} saving={saving} onOpen={open_}
            onToggle={toggle} onRetitle={retitle}
            onAdd={(typed) => add(open, typed)}
          />
        ) : null}
      </main>
    </div>
  )
}

function PageBody({ ws, page, onToggle, onRetitle, onAdd, saving, onOpen }: {
  ws: Workspace
  page: LoadedPage
  onToggle: (p: LoadedPage, i: number, to: TaskStatus) => void
  onRetitle: (p: LoadedPage, i: number, text: string) => void
  onAdd: (text: string) => Promise<void>
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
                <EditableTitle
                  text={b.title}
                  done={b.status === 'done'}
                  onCommit={(t) => onRetitle(page, i, t)}
                />
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
          if (b.kind === 'heading') return <h2 key={i} style={{ marginLeft: b.indent * 22 }}><Inline text={b.text} /></h2>
          if (b.kind === 'bullet') return <p key={i} className="bullet" style={{ marginLeft: b.indent * 22 }}><Inline text={b.text} /></p>
          if (b.kind === 'numbered') return <p key={i} className="bullet" style={{ marginLeft: b.indent * 22 }}><Inline text={b.text} /></p>
          if (b.kind === 'ink') return <p key={i} className="placeholder mono">ink — not drawn here yet</p>
          if (b.kind === 'image') return <p key={i} className="placeholder mono">image — not shown here yet</p>
          return b.text ? <p key={i} className="prose" style={{ marginLeft: b.indent * 22 }}><Inline text={b.text} /></p> : null
        })}
      </div>

      <Capture onAdd={onAdd} busy={saving === `${page.id}:new`} />
    </>
  )
}

/**
 * A title you can click into.
 *
 * The edited text goes back through the task parser, so typing `#reading` on the end makes a label
 * rather than words — which is what the file would decide on the next load regardless. Escape
 * abandons, Enter and blur commit, and an unchanged title writes nothing at all: a stray click
 * should not put a commit in someone's history.
 */
function EditableTitle({ text, done, onCommit }: {
  text: string
  done: boolean
  onCommit: (text: string) => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(text)

  if (!editing) {
    return (
      <span
        className={done ? 'title struck' : 'title'}
        onClick={() => { setDraft(text); setEditing(true) }}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === 'Enter') { setDraft(text); setEditing(true) } }}
      >
        {text ? <Inline text={text} /> : <span className="untitled">Untitled</span>}
      </span>
    )
  }

  const done_ = () => {
    setEditing(false)
    const next = draft.trim()
    if (next && next !== text) onCommit(next)
  }

  return (
    <input
      className="title editing"
      autoFocus
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={done_}
      onKeyDown={(e) => {
        if (e.key === 'Enter') done_()
        if (e.key === 'Escape') setEditing(false)
      }}
    />
  )
}

/**
 * A smart list, which has no contents of its own — it has a rule, and these are what match it now.
 *
 * Ticking one writes to the page the task actually lives on, not to this one. There is nothing here
 * to write to: the whole point of a computed list is that its membership is derived, so a task
 * leaving it is a side effect of the edit rather than something this screen does.
 */
function SmartListBody({ ws, page, onToggle, saving, onOpen }: {
  ws: Workspace
  page: LoadedPage
  onToggle: (p: LoadedPage, i: number, to: TaskStatus) => void
  saving: string | null
  onOpen: (id: string) => void
}) {
  const { candidates, unsupported, ruleFound } = resolveSmartList(ws, page.id, new Date())

  return (
    <>
      <header className="page-head">
        <p className="mono">Smart view</p>
        <h1>{page.doc.title ?? 'Untitled'}</h1>
        <p className="mono">{candidates.length} matching</p>
      </header>

      {!ruleFound && (
        <p className="error banner">
          This list's rule is missing from the workspace, so there is nothing to evaluate. It is not
          empty — it is unreadable, which is a different thing.
        </p>
      )}
      {unsupported.length > 0 && (
        <p className="warn banner">
          Part of this rule cannot be evaluated here yet ({unsupported.join(', ')}), so this list may
          be missing tasks the phone would show. Shown short rather than shown wrong.
        </p>
      )}

      <div className="blocks">
        {candidates.map(({ task, pageId }, i) => {
          const home = ws.pages.get(pageId)
          const index = home?.doc.blocks.indexOf(task) ?? -1
          return (
            <div className="row" key={task.id || i}>
              <button
                className="tick"
                disabled={!home || index < 0 || saving === `${pageId}:${index}`}
                aria-label={`Complete ${task.title}`}
                onClick={() => home && index >= 0 && onToggle(home, index, task.status === 'done' ? 'open' : 'done')}
              >
                <Bhupura state={task.status} />
              </button>
              <span className={task.status === 'done' ? 'title struck' : 'title'}>
                <Inline text={task.title || 'Untitled'} />
              </span>
              <span className="tokens">
                {home?.doc.title && (
                  <em className="tok where" onClick={() => onOpen(pageId)}>{home.doc.title}</em>
                )}
                {task.priority && <em className="tok pri">!{task.priority}</em>}
                {task.due && <em className="tok due">{task.due.value.kind === 'allDay' ? task.due.value.date : task.due.value.instant.slice(0, 10)}</em>}
                {task.labels.map((l) => <em className="tok" key={l}>#{l}</em>)}
              </span>
            </div>
          )
        })}
        {ruleFound && candidates.length === 0 && (
          <p className="prose">Nothing matches this rule right now.</p>
        )}
      </div>
    </>
  )
}

/** Open tasks in a list; matches in a smart list. */
function countOf(ws: Workspace, p: LoadedPage): number {
  if (p.doc.type === 'smart_list') {
    return resolveSmartList(ws, p.id, new Date()).candidates.filter((c) => c.task.status !== 'done').length
  }
  return tasksOf(p).filter((t) => t.task.status !== 'done').length
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
