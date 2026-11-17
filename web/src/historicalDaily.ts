import type { HistoricalGameSummary } from './api'

/**
 * Spec 071: small pure pieces behind the game of the day and the landing-page stats. Everything here is arithmetic
 * on values the server sent, so each can be checked by hand against the games list.
 */

/** "Nov 14" from an ISO calendar date. Parsed as plain numbers: `new Date('2026-11-14')` is UTC and can print the 13th. */
export function dayLabel(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number)
  if (!y || !m || !d) return isoDate
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
}

/** Time left until `nextAtIso`, as "5h 12m", "12m" or "under a minute". Null when that moment has passed. */
export function countdown(nextAtIso: string, nowMs: number): string | null {
  const left = new Date(nextAtIso).getTime() - nowMs
  if (!Number.isFinite(left) || left <= 0) return null
  const minutes = Math.floor(left / 60_000)
  if (minutes < 1) return 'under a minute'
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

export type LandingStats = {
  /** COMPLETE games, scored or not. */
  finished: number
  /** How many of those carry a draft score: the best and the average cover only these. */
  scored: number
  best: number | null
  /** Mean draft score, rounded to the nearest whole number. */
  average: number | null
}

/** What the landing page says about your finished games. Abandoned, unfinished and chosen-season games count for nothing. */
export function landingStats(games: Pick<HistoricalGameSummary, 'status' | 'draftScore' | 'chosenSeason'>[]): LandingStats {
  // Spec 074 FR-011: a season the player picked is practice. It is out of "Finished", the best score and the average
  // (the server leaves it out of achievements and the streak the same way).
  const done = games.filter((g) => g.status === 'COMPLETE' && !g.chosenSeason)
  const scores = done.map((g) => g.draftScore).filter((s): s is number => typeof s === 'number')
  if (scores.length === 0) return { finished: done.length, scored: 0, best: null, average: null }
  const sum = scores.reduce((a, b) => a + b, 0)
  return { finished: done.length, scored: scores.length, best: Math.max(...scores), average: Math.round(sum / scores.length) }
}
