import { apiFetch, json } from './http'

// --- specs/004-ffwrapped-feature-parity US4: Season forecast ---

/**
 * Read from one stored simulation snapshot, never computed on load — which is
 * what keeps this view and the playoff-odds figure in the Record cell showing
 * the same number.
 *
 * `winRange` and `averageSeed` are nullable: a snapshot taken before the
 * distributions were stored has no range, and that is not a range of zero.
 */
export type ForecastTeam = {
  rosterId: number
  managerId: number | null
  teamName: string
  /** Sleeper username, shown under the team name; null for an unowned roster. */
  username: string | null
  avatarId: string | null
  playoffOdds: number
  averageWins: number
  projectedPoints: number
  winRange: { p10: number | null; p90: number | null }
  averageSeed: number | null
  seedOnePct: number
  seedOdds: Record<string, number>
}

/** `reason` names which refusal applies; they need different words on screen. */
export type SeasonForecast = {
  available: boolean
  reason?: 'UNMODELLED_SEEDING' | 'NO_SCORED_WEEKS' | 'NOT_COMPUTED' | null
  season: number
  requestedSeason?: number | null
  week?: number
  iterations?: number
  model?: string | null
  teams: ForecastTeam[]
  /**
   * The newest week with stored scores (0 when none), in progress or not. `week` is the
   * week the stored snapshot was taken at. Present on every shape, refusals included.
   */
  latestScoredWeek: number
  /**
   * The newest FINAL week (0 when none): the one the "behind" notice counts against and the
   * recompute targets, so an in-progress week never reads as a week scored since the forecast.
   */
  latestFinalWeek: number
  /** Display only: whether to offer the recompute button. The route re-checks. */
  canCommission: boolean
  /** The resolved season's league is finished (league.status): nothing left to forecast. Present on every shape. */
  seasonComplete: boolean
}

export const getSeasonForecast = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/forecast`).then(json<SeasonForecast>)
