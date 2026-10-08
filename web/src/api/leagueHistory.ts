import type { Sport } from './draft'
import { apiFetch, json } from './http'

// --- claude/league-suite.md Phase A: league history + power rankings ---

// Mirrors LeagueHistoryController.standingRow()'s shape. season/sleeperLeagueId
// are only populated by getManagerHistory (a per-league standings list already
// knows both without repeating them on every row).
export type StandingRow = {
  /**
   * specs/006-deeper-history-both-sports T040. The internal id, present on
   * every row from standingRow() (not conditional the way season/sleeperLeagueId
   * are) -- what a rank/chain lookup joins on, distinct from sleeperLeagueId,
   * which is what a re-ingest or a deep link uses.
   */
  leagueId: number
  rosterId: number
  managerId: number | null
  manager: string | null
  /** This season's team name (league history rows only; manager-history rows omit it). */
  teamName?: string | null
  /** LeagueHistoryController.history(): this row's manager is the caller's manager (false when signed out). League history only. */
  isMe?: boolean
  avatarId: string | null
  wins: number | null
  losses: number | null
  ties: number | null
  pointsFor: number | null
  pointsAgainst: number | null
  champion: boolean
  season: number | null
  sleeperLeagueId: string | null

  /**
   * specs/006-deeper-history-both-sports US1. Null from getLeagueHistory's
   * per-league call (the page already knows all three -- it asked for this
   * one league); populated from getManagerHistory, whose rows span several
   * leagues and sports with nothing else to tell them apart. Before this,
   * `ManagerHistory.tsx` summed NBA and NFL rows into one header record --
   * see baseline.md's T002 for the six-row, two-sport, no-`sport`-field
   * defect this fixes.
   */
  sport: Sport | null
  leagueName: string | null
  /** league.status == 'complete'. A null/false value is NOT complete -- see LeagueRepository.LeagueRow#complete(). */
  complete: boolean | null

  /**
   * specs/002-league-history-record-book US2. Optional, not required: this same
   * type backs getManagerHistory, whose rows are a manager's seasons across
   * leagues and carry no rank -- making these required would break that call's
   * typecheck rather than describe it.
   *
   * finalRank is non-null iff rankStatus is 'RANKED'. Every other status is a
   * different REASON there is no rank, and the page renders the reason; see
   * RankStatus.
   */
  finalRank?: number | null
  finalRankWeek?: number | null
  rankStatus?: RankStatus
}

/**
 * Why a season's row does or doesn't show a final power rank.
 *
 * The three absent cases are deliberately distinct. A season still being played
 * has no final rank and that is not an error; a finished season that was never
 * computed is one button away from having one; a finished season with no stored
 * weekly scores has nothing to compute from. Collapsing them into a bare "--"
 * is what this enum exists to prevent.
 */
export type RankStatus = 'RANKED' | 'IN_PROGRESS' | 'NOT_COMPUTED' | 'UNAVAILABLE'

export type SeasonHistory = {
  season: number
  leagueId: number
  sleeperLeagueId: string
  name: string | null
  standings: StandingRow[]
}

/**
 * specs/002-league-history-record-book. Mirrors
 * LeagueHistoryController.weeklyScoreRow(). `points` is a number, not a
 * pre-formatted string -- the wire carries the stored numeric(8,2) and the page
 * decides how to show it.
 */
export type WeeklyScoreRecord = {
  season: number
  week: number
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  points: number
}

export type MarginSide = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  points: number
}

export type MarginRecord = {
  season: number
  week: number
  margin: number
  winner: MarginSide
  loser: MarginSide
}

/**
 * specs/006-deeper-history-both-sports T060/T064. All-time points scored,
 * summed across the whole chain per manager (or per unowned roster-season --
 * see RecordWho, which already renders that case for the other four lists).
 * `spanSeasons` is what lets the page state "over N seasons" beside a total,
 * per US5.4: with one or two played seasons in this database, an unlabeled
 * all-time figure reads as a season record wearing a career number's name.
 */
export type PointsLeaderRecord = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  points: number
  spanSeasons: number[]
}

/**
 * A run of consecutive weeks one roster won (or lost) every game, within one
 * season (research R7). `withinSeasonOnly` rides on every entry so the page
 * states the rule rather than leaving it for the reader to assume (US5.2).
 */
export type StreakRecord = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  length: number
  spanSeasons: number[]
  startWeek: number
  endWeek: number
  withinSeasonOnly: boolean
}

/**
 * Always present on a 200, even when every list is empty. An absent key would
 * make "this league has no records" indistinguishable from "this server predates
 * the record book", which is exactly the ambiguity the contract forbids.
 *
 * `marginsUnavailableReason` is non-null exactly when the margin lists are
 * empty. A panel that renders nothing and says nothing reads as broken.
 *
 * `pointsLeaders`/`winStreaks`/`lossStreaks` carry no reason field of their
 * own: `pointsLeaders` is empty under the same condition as `highestWeeks`/
 * `lowestWeeks` (no stored weekly scores), and the two streak lists are empty
 * under the same condition as the margin lists (no paired games) -- the page
 * already has a sentence for each cause and reuses it.
 */
export type LeagueRecords = {
  limit: number
  highestWeeks: WeeklyScoreRecord[]
  lowestWeeks: WeeklyScoreRecord[]
  closestMatchups: MarginRecord[]
  biggestBlowouts: MarginRecord[]
  marginsUnavailableReason: string | null
  pointsLeaders: PointsLeaderRecord[]
  winStreaks: StreakRecord[]
  lossStreaks: StreakRecord[]
}

export type LeagueHistory = {
  sleeperLeagueId: string
  /** LeagueHistoryController.history(): LeagueMembership.canCommission for the requested league. Absent (old backend) = treat as false. */
  canCommission?: boolean
  seasons: SeasonHistory[]
  records: LeagueRecords
}

export const getLeagueHistory = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/history`).then(json<LeagueHistory>)

export type BackfillResult = {
  backfilled: { season: number; week: number | null; entries: number }[]
  skipped: { season: number; reason: string }[]
}

/**
 * Computes the missing end-of-season power rank for completed seasons.
 *
 * Backs the button in a NOT_COMPUTED rank cell. It exists so the page never
 * has to print `POST /api/leagues/{id}/power/backfill` for the reader to run
 * in a terminal -- the same convention ingestLeagueHistory above already
 * carries.
 */
export const backfillFinalRanks = (sleeperLeagueId: string, season?: number) =>
  apiFetch(
    `/api/leagues/${sleeperLeagueId}/power/backfill${season == null ? '' : `?season=${season}`}`,
    { method: 'POST' },
  ).then(json<BackfillResult>)
