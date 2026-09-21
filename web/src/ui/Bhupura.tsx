/**
 * The mark, from the same 28-unit design space as `YantraGlyph.bhupuraPath`.
 *
 * Redrawn here rather than exported as an asset because it is one path at every size and the app
 * treats it as a shape, not a picture: the checkbox, the launcher icon, the widget and the
 * notification all draw this outline and vary only what sits inside it.
 */

const PATH =
  'M8 4 L11 4 L11 2 L17 2 L17 4 L20 4 Q24 4 24 8 L24 11 L26 11 L26 17 L24 17 L24 20 ' +
  'Q24 24 20 24 L17 24 L17 26 L11 26 L11 24 L8 24 Q4 24 4 20 L4 17 L2 17 L2 11 L4 11 L4 8 ' +
  'Q4 4 8 4 Z'

export type MarkState = 'open' | 'in_progress' | 'done'

export function Bhupura({ state, size = 22 }: { state: MarkState; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 28 28" aria-hidden focusable="false">
      <path
        d={PATH}
        fill={state === 'done' ? 'var(--accent-fill)' : 'none'}
        stroke={state === 'open' ? 'var(--line)' : 'var(--accent)'}
        strokeWidth={1.5}
        strokeLinejoin="round"
      />
      {/* The bindu: filled once the task is running, absent while it is merely open. */}
      {state === 'in_progress' && <circle cx="14" cy="14" r="4" fill="var(--accent)" />}
      {state === 'done' && (
        <path
          d="M9.5 14.5 L12.5 17.5 L18.5 10.5"
          fill="none"
          stroke="var(--accent)"
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      )}
    </svg>
  )
}
