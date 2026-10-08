import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- specs/006-deeper-history-both-sports US4: manager-vs-manager comparison ---

/** One side of a `versus` response -- just enough to render an identity, not a full ManagerHistory. */
export type VersusManagerRef = {
  managerId: number
  manager: string | null
  avatarId: string | null
}

/**
 * One scored, paired game between the two managers this endpoint compares.
 * `winner` is decided by `starters_points`, never by `roster_season.wins` --
 * that column is a whole season, and this is one game (mirrors
 * LeagueRecordService.margin's own comment). Ties are their own outcome
 * (US4.5), never folded into a side's losses.
 */
export type VersusMeeting = {
  season: number
  week: number
  leagueName: string
  sleeperLeagueId: string
  aPoints: number
  bPoints: number
  winner: 'A' | 'B' | 'TIE'
}

/**
 * A season both managers shared that produced zero meetings between THEM
 * specifically, named with its own reason rather than left silently absent
 * (US4.4). Backend's `HeadToHeadService.exclusionReason` distinguishes three
 * different facts here: fixtures scheduled but not yet scored, a schedule
 * that never paired them this season, or a league with nothing loaded at all
 * -- only the first of those resolves itself by waiting.
 */
export type VersusSeasonExcluded = {
  season: number
  leagueName: string
  reason: string
}

/**
 * One comparison figure, one value per side. Null on a side with no counted
 * season to compute it from -- the same null rules `CareerProfile`'s own
 * fields already state, since every value here is read straight off it.
 */
export type VersusFigure<T> = { a: T | null; b: T | null }

/**
 * The side-by-side career comparison -- each side is one manager's own
 * `CareerProfile` for this sport, read by `ManagerComparisonController`
 * rather than re-derived, so this page and `/managers/:id/history` can never
 * print two different numbers for the same manager.
 */
export type VersusComparison = {
  titles: VersusFigure<number>
  record: VersusFigure<string>
  pointsFor: VersusFigure<number>
  /** `pointsFor / (wins + losses + ties)`, not `/ weeks` -- matches the record beside it. */
  pointsPerGame: VersusFigure<number>
  winsAboveExpected: VersusFigure<number>
  averageEfficiency: VersusFigure<number>
  seasonsCounted: VersusFigure<number>
}

/** One sport's worth of the two managers' shared history. Never combined across sports (US4.2). */
export type VersusSport = {
  sport: Sport
  aWins: number
  bWins: number
  ties: number
  meetings: VersusMeeting[]
  seasonsExcluded: VersusSeasonExcluded[]
  comparison: VersusComparison
}

/**
 * Mirrors `ManagerComparisonController.versus`'s response shape. `sports` is
 * empty with `sharedNothing: true` when the two managers have never owned a
 * roster in the same `league_id` at all -- never a bare `0-0`, which would be
 * indistinguishable from "they played and split everything evenly" (US4.3).
 */
export type ManagerComparison = {
  a: VersusManagerRef
  b: VersusManagerRef
  sports: VersusSport[]
  sharedNothing: boolean
}

export const getManagerComparison = (aId: number, bId: number) =>
  apiFetch(`/api/managers/${aId}/versus/${bId}`).then(json<ManagerComparison>)
