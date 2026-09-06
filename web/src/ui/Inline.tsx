import { Fragment } from 'react'
import { spans } from '../format/markdown'
import type { Span } from '../format/markdown'

/**
 * A line of text with its emphasis drawn and its markers taken out.
 *
 * Deliberately as shallow as the Android renderer: only the outermost run of a nesting is unwrapped,
 * so `**a *b* c**` is bold with the inner asterisks still showing. See `markdown.ts` — going a level
 * deeper here would put a different string on screen from the one a widget or notification shows.
 */
export function Inline({ text }: { text: string }) {
  return <>{spans(text).map((s, i) => <Piece key={i} span={s} />)}</>
}

function Piece({ span }: { span: Span }) {
  if ('text' in span) return <Fragment>{span.text}</Fragment>
  const inner = span.children.map((c, i) => <Piece key={i} span={c} />)
  switch (span.kind) {
    case 'bold': return <strong>{inner}</strong>
    case 'italic': return <em>{inner}</em>
    case 'boldItalic': return <strong><em>{inner}</em></strong>
    case 'code': return <code className="code">{inner}</code>
  }
}
