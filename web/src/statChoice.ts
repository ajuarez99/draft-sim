import { DEFAULT_COLUMNS, PICKABLE_IDS } from './statLeaderboard'

const STORAGE_KEY = 'bk.draftStats.v1'

/**
 * The stat columns the member chose for the live room's Stats view (spec 023 US2, contract C3),
 * kept per device. Same try/catch discipline as pickCardsPref: a blocked store reads as "never
 * chose" and a failed write leaves the in-memory choice working for this page load.
 *
 * Anything but `{ v: 1, columns: string[] }` reads as never chosen, so the defaults apply. An
 * empty list is a real choice (the member removed everything), not an absent one. Ids the picker
 * doesn't offer are skipped and duplicates dropped, so a stale or hand-edited value can't put a
 * hole or a repeat in the table.
 */
export function readStatChoice(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw == null) return [...DEFAULT_COLUMNS]
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return [...DEFAULT_COLUMNS]
    const { v, columns } = parsed as { v?: unknown; columns?: unknown }
    if (v !== 1 || !Array.isArray(columns)) return [...DEFAULT_COLUMNS]
    const seen = new Set<string>()
    for (const id of columns) {
      if (typeof id === 'string' && PICKABLE_IDS.has(id)) seen.add(id)
    }
    return [...seen]
  } catch {
    return [...DEFAULT_COLUMNS]
  }
}

export function writeStatChoice(columns: string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, columns }))
  } catch {
    // The in-memory choice still works for this page load.
  }
}

/** Back to the defaults, written explicitly so the stored choice is the defaults themselves. */
export function resetStatChoice(): string[] {
  const columns = [...DEFAULT_COLUMNS]
  writeStatChoice(columns)
  return columns
}
