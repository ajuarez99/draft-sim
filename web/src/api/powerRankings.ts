import { apiFetch, json } from './http'

/**
 * The one place the mode list is written down.
 *
 * It used to be written down four times -- the Record types below plus three
 * `as PowerRankingKind[]` array literals in PowerRankings.tsx (the mode
 * selector, the per-team transpose's mode list, and the per-team legend). The
 * Records fail to compile when a mode is added, which is what you want; the
 * casts silently do not, which is how adding MEMBER would have shipped a mode
 * selector with four segments and a comparison view still drawing three lines
 * under copy reading "across all three modes"
 * (claude/plan-review-power-rankings-ballots.md finding 9d).
 *
 * Order is display order, and MEMBER is last on purpose -- it is never the
 * default mode (finding 20).
 *
 * COMPUTED_MARKET_VALUE stopped being its own mode once it was demoted to
 * week 0 of COMPUTED_REALIZED (the one-time preseason baseline that weeks 1+,
 * built purely from games played, walk forward from) -- see the backend's
 * PowerRankingService#computeWeek0IfMissing.
 */
export const ALL_POWER_RANKING_KINDS = ['COMPUTED_REALIZED', 'COMMISSIONER', 'MEMBER'] as const

export type PowerRankingKind = (typeof ALL_POWER_RANKING_KINDS)[number]

// Mirrors LeagueHistoryController.snapshotRow()'s shape. score is null for
// COMMISSIONER (an ordering, not a measurement -- claude/league-suite.md's
// "only rank is shared across every mode" argument).
export type PowerRankingEntry = {
  season: number
  week: number
  kind: PowerRankingKind
  rosterId: number
  managerId: number | null
  manager: string | null
  /** Sleeper team name for this league, falling back to the username; null for an unowned roster. */
  teamName: string | null
  avatarId: string | null
  rank: number
  score: number | null
  note: string | null

  // MEMBER only, null on every stored mode. The spread is a first-class output
  // rather than something the client derives, and it cannot be parsed back out
  // of `note` -- the note is prose for humans and the spread bar is a drawing
  // (claude/power-rankings-ballots.md, finding 7).
  //
  // ballotCount is per-ROSTER, not per-week: a roster ranked 1st by the only
  // manager who bothered would otherwise average 1.00 and win the week outright
  // over a roster averaging 1.4 across seven ballots (finding 8).
  bestRank?: number | null
  worstRank?: number | null
  stdev?: number | null
  ballotCount?: number | null

  // MEMBER only: this roster's own owner's rank of themselves, minus the room's
  // average of them. Negative = ranks himself higher than the room does. Kept
  // per-entry rather than as its own endpoint because it is exactly a property
  // of this entry -- and it is the only place one member's individual vote
  // surfaces at all, which is why it is a single derived integer rather than
  // the ballot it came from.
  selfRankBias?: number | null

  // Rest-of-season Monte Carlo, real as of claude/playoff-odds.md. Null for
  // any week with no stored odds snapshot -- weeks that predate the feature,
  // and leagues whose seeding this app refuses to model (divisions, a
  // non-default playoff_seed_type). Null means "no answer", never "0%".
  makesPlayoffsPct?: number | null
}

/** `GET /state/{sport}` for THIS league's sport, not always football.
 *
 *  `week` is legitimately 0: measured 2026-09-15, `/state/nba` answers
 *  `week: 0` for the whole offseason while `/state/nfl` answers 2. Compare it
 *  with `===` or a null check and never for truthiness -- football is never at
 *  week 0 during a season, so a `!week` written here fails for basketball
 *  alone, and silently. */
export type SportState = {
  week: number
  season: string
  seasonStartDate: string
  started: boolean
}

/** How the odds on these entries were produced, so the page can say so out loud. */
export type PlayoffOddsSummary = {
  week: number
  iterations: number
  model: string
  weeksOfScoring: number
}

export type PowerRankings = {
  sleeperLeagueId: string
  sportState: SportState
  entries: PowerRankingEntry[]
  // Null when this league has no odds at all; the page then says nothing about
  // a simulation rather than describing one that never ran.
  playoffOdds?: PlayoffOddsSummary | null
}

export const getPowerRankings = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/power`).then(json<PowerRankings>)

export const computePowerRankings = (sleeperLeagueId: string, season: number, week: number) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/power/compute?season=${season}&week=${week}`, {
    method: 'POST',
  }).then(
    json<{
      week0: number
      realized: number
      /** How many playoff-odds rows were written. */
      playoffOdds: number
      /** Absent when the week's ranking was written. Null (present) when a final week
       *  wrote nothing and the server has no reason to give; see ComputeResponse. */
      realizedSkipped?: string | null
      /** The week the odds were computed through: the requested week only when it is final. */
      playoffOddsThroughWeek?: number
      /** Present instead of `playoffOddsThroughWeek` when no week is final yet. */
      playoffOddsSkipped?: string
      /** Present only for a league that hasn't drafted. */
      week0Skipped?: string
    }>,
  )

export const saveCommissionerRanking = (
  sleeperLeagueId: string,
  season: number,
  week: number,
  rosterIds: number[],
) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/power/commissioner`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ season, week, rosterIds }),
  }).then(json<{ saved: number }>)
