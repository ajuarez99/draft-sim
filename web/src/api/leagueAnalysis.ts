import { apiFetch, json } from './http'

// --- claude/league-analysis.md: the League analysis page ---

/**
 * Mirrors LeagueAnalysisService's records. Both blocks carry `available` and a
 * `reason` rather than an empty list the page has to interpret: "too early to
 * say" and "something broke" look identical from a zero-length array, and only
 * one of them is worth showing the reader.
 */
export type AnalysisWindow = {
  fromWeek: number
  toWeek: number
  weeks: number
  scoredWeeks: number
}

export type AnalysisScoreEntry = {
  rank: number
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  /** 1-100, league mean at 50. Our scaling of `raw` -- see the service. */
  score: number
  /** What ffwrapped's published formula actually returns, unscaled. */
  raw: number
  avgWeekly: number
  high: number
  low: number
  winPct: number
  wins: number
  losses: number
  ties: number
  /** LeagueAnalysisService.ScoreEntry.grade: letter for the composite-score rank; null when no grade cutoffs are configured. */
  grade?: string | null
}

export type AnalysisRankingScores = {
  available: boolean
  reason: string | null
  formula: string
  weeksScored: number
  weeksRequired: number
  entries: AnalysisScoreEntry[]
  /** LeagueAnalysisService.RankingScores.gradesEarly: weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS. */
  gradesEarly?: boolean
  /** LeagueAnalysisService.RankingScores.earlyThresholdWeeks (= SeasonWindow.EARLY_THRESHOLD_WEEKS). */
  earlyThresholdWeeks?: number
}

export type AnalysisLineupPlayer = {
  sleeperPlayerId: string
  name: string
  position: string
  team: string | null
  /** The slot filled -- "FLEX" where `position` is RB/WR/TE, "BN" on the bench. */
  slot: string
  points: number
  /** Sleeper's tag as of the last player ingest: "IR", "Out", "Questionable"... */
  injuryStatus: string | null
}

export type AnalysisRosterProjection = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  rank: number
  /** This is the signed-in reader's own roster, decided by Sleeper owner_id on the backend. */
  isMe: boolean
  total: number
  byPosition: Record<string, number>
  rankByPosition: Record<string, number>
  /** In the league's own slot order -- QB, RB, RB, WR, WR, TE, FLEX, FLEX, K, DEF. */
  starters: AnalysisLineupPlayer[]
  /** Everyone who did not start, best projection first. The zeroes at the bottom are the IRs. */
  bench: AnalysisLineupPlayer[]
  /**
   * The rest-of-season total taken apart again, one entry per remaining week.
   * `total` is the sum of these; the sum is where a bye week disappears, which
   * is why the list is carried separately rather than derived from it.
   */
  byWeek: AnalysisWeekTotal[]
  /** Rostered players with no projection in the window: IR, Out, PUP. */
  missing: number
}

export type AnalysisWeekTotal = {
  week: number
  points: number
  /** This roster's projected rank in THAT week, 1 = highest, ties shared. */
  rank: number
}

export type AnalysisProjections = {
  available: boolean
  reason: string | null
  positionGroups: string[]
  rosters: AnalysisRosterProjection[]
}

/**
 * One side of one game. Sleeper has no home team -- only a `matchupId`
 * grouping two rosters -- so the wire has sides, best projection first, and a
 * `sides` of length 1 is a bye rather than a missing opponent.
 */
export type AnalysisSide = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  isMe: boolean
  projected: number
  byPosition: Record<string, number>
  starters: AnalysisLineupPlayer[]
}

export type AnalysisMatchup = {
  matchupId: number
  sides: AnalysisSide[]
}

export type AnalysisMatchups = {
  available: boolean
  reason: string | null
  /** The next unplayed week, projected on its own rather than sliced out of the rest of the season. */
  week: number
  matchups: AnalysisMatchup[]
}

/** @param rank this roster's scoring rank in THAT week, 1 = highest. */
export type AnalysisWeekScore = {
  week: number
  points: number
  rank: number
}

export type AnalysisScoreRow = {
  rosterId: number
  managerId: number | null
  manager: string | null
  avatarId: string | null
  isMe: boolean
  weeks: AnalysisWeekScore[]
  total: number
  avg: number
  high: number
  low: number
}

/** What every roster actually scored, week by week -- settled data, unlike the projections. */
export type AnalysisScores = {
  available: boolean
  reason: string | null
  weeks: number[]
  rosters: AnalysisScoreRow[]
}

export type LeagueAnalysis = {
  season: number
  /** Which of Sleeper's three scoring totals this league is read under. */
  scoringKey: 'PPR' | 'HALF_PPR' | 'STANDARD'
  window: AnalysisWindow
  rankingScores: AnalysisRankingScores
  projections: AnalysisProjections
  matchups: AnalysisMatchups
  scores: AnalysisScores
  /** Every roster's two names, present even when a block is unavailable. */
  teams: AnalysisTeamLabel[]
}

export type AnalysisTeamLabel = {
  rosterId: number
  teamName: string
  username: string | null
}

/**
 * @param week which week the MATCHUP block is valued for; the next unplayed
 *   one when omitted. Nothing else in the response moves with it -- the
 *   rest-of-season projections and the scores grid are not statements about a
 *   chosen week.
 */
export const getLeagueAnalysis = (sleeperLeagueId: string, week?: number) =>
  apiFetch(
    `/api/leagues/${sleeperLeagueId}/analysis${week == null ? '' : `?week=${week}`}`,
  ).then(json<LeagueAnalysis>)
