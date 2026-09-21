/**
 * A page file's model — the same shape `PageDoc.kt` describes on Android.
 *
 * These types are a contract with a *file format*, not with the Kotlin code: both clients write
 * into the same git repository, and a page written by one is read by the other on the next sync.
 * Where this disagrees with `data/format/PageDoc.kt`, this is wrong.
 */

export type TaskStatus = 'open' | 'in_progress' | 'done'

/**
 * A due date is either a day or a moment, and the difference is not cosmetic: an all-day task is
 * due wherever you are, and a timed one is due at an instant that moves with the timezone.
 */
export type DueValue =
  | { kind: 'allDay'; date: string }   // ISO date, `2026-09-06`
  | { kind: 'at'; instant: string }    // ISO instant, `2026-09-06T09:00:00Z`

export interface DueSpec {
  value: DueValue
  /** Minutes before the due moment to remind. Negative means after. */
  reminderMin?: number
}

interface BlockBase {
  indent: number
  /**
   * The line this block was parsed from, kept so an untouched block can be written back byte for
   * byte. The emitter re-parses it and compares before trusting it — see `rawStillDescribes` — so
   * a caller that edits a block without clearing this does not silently write the stale line.
   */
  raw?: string
}

export type Block =
  | ({ kind: 'prose'; text: string } & BlockBase)
  | ({ kind: 'heading'; text: string } & BlockBase)
  | ({ kind: 'bullet'; text: string } & BlockBase)
  | ({ kind: 'numbered'; text: string } & BlockBase)
  | ({ kind: 'ink'; id: string } & BlockBase)
  | ({ kind: 'image'; uri: string } & BlockBase)
  | ({
      kind: 'task'
      /** The child page this line points at. Empty for a task that has never been given one. */
      id: string
      title: string
      status: TaskStatus
      due?: DueSpec
      deadline?: string
      doneAt?: string
      priority?: string
      labels: string[]
      assignee?: string
    } & BlockBase)

export interface PageDoc {
  id: string
  type: string
  parent?: string
  title?: string
  systemKey?: string
  /** ISO instant. `1970-01-01T00:00:00Z` when the file did not carry a readable one. */
  modifiedAt: string
  device?: string
  blocks: Block[]
  /**
   * Frontmatter keys this version does not know about, kept so that a page written by a newer
   * client survives a round trip through an older one. Nothing is dropped for being unrecognised.
   */
  unknownKeys: Record<string, string>
}

export const EPOCH = '1970-01-01T00:00:00Z'
