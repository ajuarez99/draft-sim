import type { HistoricalGameState, HistoricalResult } from './api'
import { isOldFormat, ordinal, outcomeHeadline, sportOf } from './historicalCopy'
import { dailyName } from './historicalDaily'
import { brand, shareChosenLine } from './redraftCopy'

/**
 * Spec 070: the pieces a finished Redraft game is summed up with (the draft score, one mark per pick, the
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
 * The mark for one row. A reduced public game (today's game of the day) ships only `mark`, never the ranks it is made
 * from (CR-03); every other row carries both ranks and the mark is worked out here.
 */
export function markOf(r: { mark?: PickMark | null; draftedPositionRank?: number; finishPositionRank?: number }): PickMark {
  if (r.mark === 'ahead' || r.mark === 'same' || r.mark === 'behind') return r.mark
  return pickMark(r.draftedPositionRank ?? 0, r.finishPositionRank ?? 0)
}

/**
 * Your season points as a whole-number share of the hindsight draft's, rounded down (the same rounding the
 * near-perfect badge's sentence uses). Above 100 when you beat it. Null when there is no hindsight total to
 * divide by.
 */
export function draftScore(h: Pick<HistoricalResult['hindsight'], 'userPointsFor' | 'pointsFor' | 'draftScore'>): number | null {
  // A reduced public game withholds the hindsight total and ships the server's own score instead (spec 074).
  if (h.pointsFor == null) return typeof h.draftScore === 'number' ? h.draftScore : null
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
 * order, and `url` is the game's own address (tmGame). Plain text so it pastes anywhere. Lines with nothing to say (no score, no pick finishes) are left out.
 */
export function shareText(game: HistoricalGameState, result: HistoricalResult, url: string): string {
  const sport = sportOf(game)
  // A game of the day names its day, so two people comparing know they drafted the same season; its number
  // (spec 078) makes it a series: "did you do #8?".
  const title = game.dailyDate ? dailyName(`${brand} daily`, game.dailyDate, game.dailyNumber) : brand
  const lines = [`${title} · ${game.season.label} ${sport}`]
  // Spec 074: a season the player picked is said so, because it does not count toward achievements.
  if (game.chosenSeason) lines.push(shareChosenLine)
  lines.push(finishLine(game, result))
  const score = draftScore(result.hindsight)
  if (score != null) lines.push(`Draft score ${score}%`)
  const rows = result.pickFinishes ?? []
  if (rows.length > 0) {
    lines.push([...rows].sort((a, b) => a.pickNo - b.pickNo).map((r) => SQUARE[markOf(r)]).join(''))
  }
  lines.push(url)
  return lines.join('\n')
}
