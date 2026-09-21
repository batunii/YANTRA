/**
 * Inline emphasis — a port of `data/format/Markdown.kt`.
 *
 * Not a markdown parser. It finds the runs of emphasis in one line and says where their markers and
 * their words are, which is what both a renderer and a marker-stripper need. Block structure is the
 * page codec's job; this only ever looks at the inside of a line.
 */

export type Kind = 'code' | 'bold' | 'italic' | 'boldItalic'

/** One run: [outerStart, outerEnd) includes the markers, [innerStart, innerEnd) is the text. */
export interface Run {
  kind: Kind
  outerStart: number
  outerEnd: number
  innerStart: number
  innerEnd: number
}

/**
 * `***both***` needs its own pattern and has to be tried first: the bold pattern would otherwise
 * match `***both**` — two markers at the front, two at the back — leaving a stray asterisk and an
 * unmatched closing pair.
 */
const BOLD_ITALIC = /\*\*\*(?=\S)([\s\S]+?)(?<=\S)\*\*\*/g
const BOLD = /\*\*(?=\S)([\s\S]+?)(?<=\S)\*\*/g
const ITALIC = /(?<!\*)\*(?=\S)([^*]+?)(?<=\S)\*(?!\*)/g
const CODE = /`(?=\S)([^`]+?)(?<=\S)`/g

/** Every emphasis run in [text], nested ones included, in the order they start. */
export function runs(text: string): Run[] {
  return runsIn(text, 0, text.length).sort((a, b) => a.outerStart - b.outerStart)
}

function runsIn(text: string, from: number, to: number): Run[] {
  if (to - from < 3) return []
  const slice = text.slice(from, to)
  const found: Run[] = []
  // Claimed characters, so `*italic*` cannot match the inner asterisks of `**bold**`, and code
  // spans win over anything that looks like emphasis inside them.
  const claimed = new Array<boolean>(slice.length).fill(false)

  const scan = (pattern: RegExp, markerLength: number, kind: Kind) => {
    pattern.lastIndex = 0
    for (const match of slice.matchAll(pattern)) {
      const start = match.index
      const end = start + match[0].length // exclusive
      let taken = false
      for (let i = start; i < end; i++) if (claimed[i]) { taken = true; break }
      if (taken) continue
      const innerStart = start + markerLength
      const innerEnd = end - markerLength
      if (innerEnd <= innerStart) continue
      for (let i = start; i < end; i++) claimed[i] = true
      found.push({
        kind,
        outerStart: from + start,
        outerEnd: from + end,
        innerStart: from + innerStart,
        innerEnd: from + innerEnd,
      })
      // Inside a code span the markers are content; anywhere else they are markers.
      if (kind !== 'code') found.push(...runsIn(text, from + innerStart, from + innerEnd))
    }
  }

  // Longest marker first: *** before **, ** before *, or each pattern eats part of a longer pair
  // and leaves the remainder stranded.
  scan(CODE, 1, 'code')
  scan(BOLD_ITALIC, 3, 'boldItalic')
  scan(BOLD, 2, 'bold')
  scan(ITALIC, 1, 'italic')
  return found
}

/**
 * [text] with its emphasis markers taken out and nothing put in their place.
 *
 * Only the outermost run of a nesting is unwrapped, matching what a renderer does: a surface that
 * strips more than the renderer would is showing a different title from the one beside it.
 */
export function plain(text: string): string {
  const rs = runs(text)
  if (rs.length === 0) return text
  let out = ''
  let at = 0
  let lastEnd = -1
  for (const r of rs) {
    if (r.outerStart < lastEnd) continue // nested inside one already unwrapped
    out += text.slice(at, r.outerStart)
    out += text.slice(r.innerStart, r.innerEnd)
    at = r.outerEnd
    lastEnd = at
  }
  return out + text.slice(at)
}

/** A rendering tree: nested spans with their emphasis, markers removed. */
export type Span = { text: string } | { kind: Kind; children: Span[] }

/**
 * [text] as spans ready to draw.
 *
 * **Only the outermost run of a nesting is unwrapped, and the inner one keeps its markers.** That
 * looks like a shortcut and is the opposite: `InlineText.stripEmphasis` on Android does exactly
 * this, so `**a *b* c**` is drawn bold with the asterisks around `b` still visible, and
 * [plain] leaves those same asterisks behind. A renderer that went one level deeper would show a
 * different string from the one the stripper produces for a widget or a notification — and the two
 * sitting side by side disagreeing about a task's name is the failure both are written to avoid.
 *
 * The last test in the suite pins that: what this draws is character-for-character what [plain]
 * returns.
 */
export function spans(text: string): Span[] {
  const out: Span[] = []
  let at = 0
  let lastEnd = -1
  for (const r of runs(text)) {
    if (r.outerStart < lastEnd) continue // nested inside one already unwrapped
    if (r.outerStart > at) out.push({ text: text.slice(at, r.outerStart) })
    out.push({ kind: r.kind, children: [{ text: text.slice(r.innerStart, r.innerEnd) }] })
    at = r.outerEnd
    lastEnd = at
  }
  if (at < text.length) out.push({ text: text.slice(at) })
  return out
}
