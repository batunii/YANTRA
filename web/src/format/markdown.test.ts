import { describe, expect, it } from 'vitest'
import { plain, runs, spans } from './markdown'

/** Ported from `MarkdownTest.kt`. Same reason as the codec suite: the two clients must agree. */

const emphasis = (text: string) =>
  runs(text).map((r) => `${r.kind}:${text.slice(r.innerStart, r.innerEnd)}`)

/** Every index occupied by a run's markers. */
const markerIndices = (text: string) => {
  const out = new Set<number>()
  for (const r of runs(text)) {
    for (let i = r.outerStart; i < r.innerStart; i++) out.add(i)
    for (let i = r.innerEnd; i < r.outerEnd; i++) out.add(i)
  }
  return out
}

describe('finding emphasis', () => {
  it('finds the three kinds and the words inside them', () => {
    expect(emphasis('**ship it**')).toEqual(['bold:ship it'])
    expect(emphasis('*ship it*')).toEqual(['italic:ship it'])
    expect(emphasis('`ship it`')).toEqual(['code:ship it'])
    expect(emphasis('really **ship it** now')).toEqual(['bold:ship it'])
  })

  it('finds several runs in one line', () => {
    expect(emphasis('**bold** and *italic* and `code`'))
      .toEqual(['bold:bold', 'italic:italic', 'code:code'])
  })

  it('lets bold win over the italic that lives inside it', () => {
    // `*italic*` must not match the inner asterisks of `**bold**`, or the pair splits across two
    // runs and one asterisk of each end is left over.
    expect(emphasis('**bold**')).toEqual(['bold:bold'])
  })

  it('lets code win over emphasis inside it', () => {
    expect(emphasis('`a *b* c`')).toEqual(['code:a *b* c'])
    expect(emphasis('`**not bold**`')).toEqual(['code:**not bold**'])
  })

  it('finds nested emphasis all the way down', () => {
    expect(emphasis('***both***')).toEqual(['boldItalic:both'])
    expect(emphasis('**a *b* c**')).toEqual(['bold:a *b* c', 'italic:b'])
  })

  it('finds nothing in text with no emphasis', () => {
    expect(runs('buy milk tomorrow')).toEqual([])
  })

  it('treats lone or unmatched markers as content', () => {
    // "2 * 3" is arithmetic and "**bold" is a line someone is still typing.
    for (const line of ['2 * 3', '**bold', 'an * asterisk', 'a ` backtick']) {
      expect(runs(line), `claimed something in: ${line}`).toEqual([])
    }
  })

  it('leaves no marker of a claimed pair unclaimed', () => {
    // If the parse decides a line has emphasis, every asterisk and backtick belonging to it must
    // sit inside some run's markers. One left over is drawn at full strength beside dimmed twins.
    for (const line of ['**a** *b* `c`', '***both***', '**a *b* c**', '***a* b**']) {
      const claimed = markerIndices(line)
      const inCode = new Set<number>()
      for (const r of runs(line)) {
        if (r.kind === 'code') for (let i = r.innerStart; i < r.innerEnd; i++) inCode.add(i)
      }
      ;[...line].forEach((ch, i) => {
        if (ch === '*' || ch === '`') {
          expect(claimed.has(i) || inCode.has(i), `unclaimed ${ch} at ${i} in '${line}'`).toBe(true)
        }
      })
    }
  })

  it('reports both the markers and the words', () => {
    const text = 'really **ship it** now'
    const r = runs(text)[0]!
    expect(r.kind).toBe('bold')
    expect(text.slice(r.outerStart, r.outerEnd)).toBe('**ship it**')
    expect(text.slice(r.innerStart, r.innerEnd)).toBe('ship it')
  })
})

describe('stripping to plain', () => {
  it('agrees with the Kotlin port, case for case', () => {
    expect(plain('Buy milk')).toBe('Buy milk')
    expect(plain('*urgent*')).toBe('urgent')
    expect(plain('**urgent**')).toBe('urgent')
    expect(plain('***urgent***')).toBe('urgent')
    expect(plain('`urgent`')).toBe('urgent')
    expect(plain('Ship the **beta** today')).toBe('Ship the beta today')
    expect(plain('*red* and **green**')).toBe('red and green')
    expect(plain('2 * 3 = 6')).toBe('2 * 3 = 6')
    expect(plain('does *it')).toBe('does *it')
    expect(plain('`2 * 3`')).toBe('2 * 3')
  })

  it('unwraps only the outermost run, exactly as the renderer does', () => {
    expect(plain('**a *b* c**')).toBe('a *b* c')
  })
})

describe('spans for drawing', () => {
  it('splits a line into text and emphasis', () => {
    expect(spans('really **ship it** now')).toEqual([
      { text: 'really ' },
      { kind: 'bold', children: [{ text: 'ship it' }] },
      { text: ' now' },
    ])
  })

  it('draws only the outermost run, leaving the inner markers visible', () => {
    // Not an omission. `stripEmphasis` on Android skips nested runs too, so the phone draws this
    // bold with the asterisks around `b` still showing — and `plain` leaves them in. A renderer
    // that unwrapped one level deeper would disagree with both.
    expect(spans('**a *b* c**')).toEqual([{ kind: 'bold', children: [{ text: 'a *b* c' }] }])
  })

  it('keeps a code span’s contents literal', () => {
    expect(spans('`a *b* c`')).toEqual([{ kind: 'code', children: [{ text: 'a *b* c' }] }])
  })

  it('leaves plain text as one span', () => {
    expect(spans('buy milk')).toEqual([{ text: 'buy milk' }])
  })

  /** The invariant that keeps the two halves honest. */
  it('draws exactly the characters plain() would leave behind', () => {
    const flat = (ss: ReturnType<typeof spans>): string =>
      ss.map((s) => ('text' in s ? s.text : flat(s.children))).join('')
    for (const line of ['**a *b* c**', '`a *b* c`', 'x **y** z', '***both***', '2 * 3']) {
      expect(flat(spans(line))).toBe(plain(line))
    }
  })
})
