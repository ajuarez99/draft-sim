import type { PairView } from './rivalries'
import type { RivalryRow } from './api'

/**
 * The one sentence for "Sleeper's bracket gave the final to a side whose stored scores say it lost" (spec 034
 * code-review B2). The Rivalries matrix and, since spec 047, the History trophy case both print it, so the wording
 * lives here once. `who` is the side the bracket gave the final to; null when it can't be named.
 */
export function finalDisagreeSentence(season: number, who: string | null): string {
  return `${season}: Sleeper's bracket gives the final to ${who ?? 'one side'}; the stored scores say otherwise.`
}

/** The matrix's version: names the winner from the cell's own row/column. */
export function finalDisagreeText(season: number, winner: 'A' | 'B' | null, v: PairView, r: RivalryRow, c: RivalryRow): string {
  const rowWon = winner != null && (winner === 'A') === v.rowIsA
  const who = winner == null ? null : rowWon ? r.teamName : c.teamName
  return finalDisagreeSentence(season, who)
}
