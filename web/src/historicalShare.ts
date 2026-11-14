import type { HistoricalGameState, HistoricalResult } from './api'
import { isOldFormat, ordinal, outcomeHeadline, sportOf } from './historicalCopy'

/**
 * Spec 070: the pieces a finished Time Machine game is summed up with (the draft score, one mark per pick, the
 * text a fan copies to a friend). Everything here is arithmetic on numbers the results page already prints, so
 * each one can be checked against the tables further down the page.
 */

export type PickMark = 'ahead' | 'same' | 'behind'

/** Lower rank is better, so a pick that finished at a smaller rank than it was drafted at is "ahead". */
export function pickMark(draftedPositionRank: number, finishPositionRank: number): PickMark {
  if (finishPositionRank < draftedPositionRank) return 'ahead'
  if (finishPositionRank > draftedPositionRank) return 'behind'
  return 'same'
}

/**
 * Your season points as a whole-number share of the hindsight draft's, rounded down (the same rounding the
 * near-perfect badge's sentence uses). Above 100 when you beat it. Null when there is no hindsight total to
 * divide by.
 */
export function draftScore(h: Pick<HistoricalResult['hindsight'], 'userPointsFor' | 'pointsFor'>): number | null {
  if (!(h.pointsFor > 0) || !Number.isFinite(h.userPointsFor)) return null
  return Math.floor((100 * h.userPointsFor) / h.pointsFor)
}

const SQUARE: Record<PickMark, string> = { ahead: '🟩', same: '⬜', behind: '🟥' }

function finishLine(game: HistoricalGameState, result: HistoricalResult): string {
  const row = result.standings.find((s) => s.slot === game.userSlot)
  const record = row ? ` · ${row.wins}-${row.losses}${row.ties > 0 ? `-${row.ties}` : ''}` : ''
  const headline = isOldFormat(result)
    ? `Finished ${ordinal(result.userFinish)} of ${game.teams}`
    : outcomeHeadline(result.userOutcome, result.userFinish, game.teams)
  return `${result.userOutcome === 'CHAMPION' && !isOldFormat(result) ? '🏆 ' : ''}${headline}${record}`
}

/**
 * The text behind the Share button: the season, the finish, the draft score and one square per pick in draft
 * order. Plain text so it pastes anywhere. Lines with nothing to say (no score, no pick finishes) are left out.
 */
export function shareText(game: HistoricalGameState, result: HistoricalResult, url: string): string {
  const sport = sportOf(game) === 'NFL' ? 'Football' : 'Basketball'
  const lines = [`Time Machine · ${game.season.label} ${sport}`, finishLine(game, result)]
  const score = draftScore(result.hindsight)
  if (score != null) lines.push(`Draft score ${score}`)
  const rows = result.pickFinishes ?? []
  if (rows.length > 0) {
    lines.push([...rows].sort((a, b) => a.pickNo - b.pickNo).map((r) => SQUARE[pickMark(r.draftedPositionRank, r.finishPositionRank)]).join(''))
  }
  lines.push(url)
  return lines.join('\n')
}
