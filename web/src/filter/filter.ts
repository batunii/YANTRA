/**
 * Smart-list rules — the model from `data/filter/Filter.kt`, as it is stored on disk.
 *
 * A smart list's contents are not written anywhere: the rule is, and the contents are whatever
 * currently matches it. Android compiles the rule to SQL over Room; there is no SQL here, so this
 * evaluates it against the pages already in memory. Same semantics, different machine — which is
 * exactly the kind of second implementation that drifts, so the interesting part is `evaluate.ts`
 * and its tests.
 */

export type Op = 'eq' | 'neq' | 'lt' | 'lte' | 'gt' | 'gte' | 'is_set' | 'not_set'
export type DateRel = 'today_start' | 'today_end'

export type Filter =
  | { kind: 'all'; filters: Filter[] }
  | { kind: 'any'; filters: Filter[] }
  | { kind: 'not'; filter: Filter }
  | { kind: 'done'; value: boolean }
  | { kind: 'in_progress'; value: boolean }
  | { kind: 'type'; value: string }
  | {
      kind: 'prop'
      defId: string
      op: Op
      text?: string
      number?: number
      date?: number
      bool?: boolean
      dateRel?: DateRel
    }
  | { kind: 'has_label'; labelId: string }
  | { kind: 'in_workspace'; workspaceId: string }

export type SortBy = 'prop_date' | 'prop_number' | 'prop_text' | 'title' | 'created'

export interface SortSpec {
  by: SortBy
  defId?: string
  desc?: boolean
  nullsLast?: boolean
}

/** The built-in property ids, from `BuiltIns.kt`. */
export const DUE = 'builtin-due'
export const DEADLINE = 'builtin-deadline'
export const PRIORITY = 'builtin-priority'
export const ASSIGNEE = 'builtin-assignee'

/** One `.yantra/meta/smartlists/<id>.json`. */
export interface SmartListDef {
  nodeId: string
  filter: Filter
  sort: SortSpec[]
  homeParentId?: string
}

/**
 * The stored form nests JSON inside JSON — `filterJson` is a *string* holding the rule, not the
 * rule. Parsing it wrong yields a definition that matches nothing rather than an error, so this
 * throws instead of shrugging.
 */
export function parseSmartList(text: string): SmartListDef {
  const raw = JSON.parse(text) as {
    nodeId: string
    filterJson: string
    sortJson?: string
    homeParentId?: string
  }
  if (!raw.nodeId || !raw.filterJson) throw new Error('smart list definition has no rule')
  return {
    nodeId: raw.nodeId,
    filter: JSON.parse(raw.filterJson) as Filter,
    sort: raw.sortJson ? (JSON.parse(raw.sortJson) as SortSpec[]) : [],
    homeParentId: raw.homeParentId,
  }
}

/** A label's id is derived from its name — see `labels.json`, where they are `:label:<name>`. */
export function labelIdFor(name: string): string {
  return ':label:' + name
}
