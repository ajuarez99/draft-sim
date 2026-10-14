import type { RivalryKindTally, RivalryPair, RivalryTally } from './api'

/**
 * specs/034-rivalry-schedule-grid. Pairs ride the wire unordered (aManagerId < bManagerId), so the
 * matrix reads one pair from either side through this, never by indexing a [row][col] grid directly.
 */

export type PairView = {
  pair: RivalryPair
  /** True when the row manager is the pair's A side (the lower manager id). */
  rowIsA: boolean
  /** The row manager's wins, losses and ties against the column manager. */
  wins: number
  losses: number
  ties: number
}

export const pairKey = (x: number, y: number) => `${Math.min(x, y)}:${Math.max(x, y)}`

export function indexPairs(pairs: RivalryPair[]): Map<string, RivalryPair> {
  const m = new Map<string, RivalryPair>()
  for (const p of pairs) m.set(pairKey(p.aManagerId, p.bManagerId), p)
  return m
}

/** The pair seen from `rowId`'s side: A's wins are the row's when the row is the lower id, else mirrored. */
export function viewFrom(index: Map<string, RivalryPair>, rowId: number, colId: number): PairView | null {
  const pair = index.get(pairKey(rowId, colId))
  if (!pair) return null
  const rowIsA = rowId === pair.aManagerId
  return {
    pair,
    rowIsA,
    wins: rowIsA ? pair.aWins : pair.bWins,
    losses: rowIsA ? pair.bWins : pair.aWins,
    ties: pair.ties,
  }
}

/** "5-3", or "5-3-1" only when there are ties. */
export function recordText(wins: number, losses: number, ties: number): string {
  return ties > 0 ? `${wins}-${losses}-${ties}` : `${wins}-${losses}`
}

export const tallyText = (t: RivalryTally) => recordText(t.wins, t.losses, t.ties)

const KIND_LABEL: Record<string, string> = {
  REGULAR: 'Regular season',
  PLAYOFF: 'Playoff',
  PLACEMENT: 'Placement',
  CONSOLATION: 'Consolation',
  POSTSEASON_UNSPLIT: 'Unsplit',
}

/** The nonzero game types of a pair, from the row manager's side, as "Regular season 3-1, Playoff 1-0". */
export function kindSplit(v: PairView): string {
  const parts: string[] = []
  for (const [k, t] of Object.entries(v.pair.byKind) as [string, RivalryKindTally][]) {
    if (t.aWins + t.bWins + t.ties === 0) continue
    const w = v.rowIsA ? t.aWins : t.bWins
    const l = v.rowIsA ? t.bWins : t.aWins
    parts.push(`${KIND_LABEL[k] ?? k} ${recordText(w, l, t.ties)}`)
  }
  return parts.join(', ')
}

/** 0..4: the row manager's win share of meetings (a tie counts half), bucketed for the tint; null when none. */
export function winShareBucket(v: PairView): number | null {
  const n = v.wins + v.losses + v.ties
  if (n === 0) return null
  return Math.min(4, Math.floor(((v.wins + v.ties / 2) / n) * 5))
}
