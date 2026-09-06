import { useState } from 'react'

/**
 * The capture field, which is never allowed to be in the way.
 *
 * The app's rule is that writing something down must not cost a mode, so this is always open at the
 * bottom of a list rather than behind a button. It clears optimistically: the line is gone from the
 * field before the write returns, because the next thing you want to type should not wait on the
 * network — and a failed write puts the text back rather than losing it.
 */
export function Capture({ onAdd, busy }: {
  onAdd: (text: string) => Promise<void>
  busy: boolean
}) {
  const [text, setText] = useState('')

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const typed = text.trim()
    if (!typed || busy) return
    setText('')
    try {
      await onAdd(typed)
    } catch {
      setText(typed) // Handed back, not swallowed.
    }
  }

  return (
    <form className="capture" onSubmit={submit}>
      <input
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Add a task…   #label  !high  @who  due:2026-09-30"
        aria-label="Add a task"
        spellCheck={false}
      />
      <button type="submit" disabled={!text.trim() || busy} aria-label="Add">↵</button>
    </form>
  )
}
