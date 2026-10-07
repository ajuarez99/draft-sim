import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- specs/004-ffwrapped-feature-parity US3: Expected wins ---

/**
 * `luckSource` is a discriminator, not a pair of optional blocks: `swingWeeks`
 * is non-empty only for SWING_WEEKS, so the page shows one explanation or the
 * other and never both.
 */
export type ExpectedWinsSwing = {
  week: number
  result: 'WON' | 'LOST'
  points: number
  weeklyRank: number
  opponent: string
}

export type ExpectedWinsTeam = {
  rosterId: number
  managerId: number | null
  teamName: string
  /** Sleeper username, shown under the team name; null for an unowned roster. */
  username: string | null
  avatarId: string | null
  expectedWins: number
  actualWins: number
  winsAboveExpected: number
  /** Opponents' points per game minus the league's. Positive = harder schedule. */
  strengthOfSchedule: number
  luckSource: 'SWING_WEEKS' | 'CONSISTENT_OPPONENT_SCORING'
  swingWeeks: ExpectedWinsSwing[]
  /** ExpectedWinsService.TeamRow.allPlay: regular-season record against every other roster scored each week. */
  allPlay?: { wins: number; losses: number; ties: number }
  /** ExpectedWinsService.TeamRow.median: regular-season record against each week's median score (odd rosters: the median roster ties). */
  median?: { wins: number; losses: number; ties: number }
}

export type ExpectedWins = {
  available: boolean
  reason?: string | null
  season: number
  requestedSeason?: number | null
  sport: Sport
  weeksScored: number
  /** Spec 013: weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS, decided server-side
   *  (ExpectedWinsController). Absent on an older backend: treat as early. */
  early?: boolean
  leagueAveragePpg: number
  teams: ExpectedWinsTeam[]
}

export const getExpectedWins = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/expected-wins`).then(json<ExpectedWins>)
