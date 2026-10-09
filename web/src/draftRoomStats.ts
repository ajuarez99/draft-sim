import type { LeaderboardRow, PlayerRef, StatLeaderboard } from './api'
import { RISK_MAX } from './survivalBands'
import { seasonLabel } from './statCopy'
import { COLUMNS, compareRows, type SortState, type StatMode } from './statLeaderboard'

/**
 * specs/023-draft-room-player-stats US1: the rules behind the live room's Stats view, kept out of
 * the components so each is unit-tested. The values are 022's leaderboard rows, unchanged; nothing
 * here computes a stat.
 */

export type DraftStatRow = {
  player: PlayerRef
  /** The leaderboard row for this player, or null when he has no game in the shown season. */
  stats: LeaderboardRow | null
  /** Set exactly when `stats` is null. */
  reason: 'NO_SEASON_GAMES' | null
  /** Survival to the member's next pick; null when the seat is unknown or in a mock. */
  survivalNext: number | null
  /** The sheet's open-slot tag. */
  fillsSlot: string | null
}

/**
 * One row per pool player, in pool order. Pool-driven on purpose: the table's row count must equal
 * the tier list's, so a player missing from the stats data shows as "no games" instead of
 * vanishing, and a drafted player (not in the pool) can't linger. The join key is the Sleeper id,
 * the one id both sides share; `PlayerRef.id` is internal to the board and means nothing to the
 * leaderboard.
 */
export function joinPoolStats(
  pool: PlayerRef[],
  rows: LeaderboardRow[],
  survivalNext: (p: PlayerRef) => number | null,
  fillsSlot: (p: PlayerRef) => string | null,
): DraftStatRow[] {
  const bySleeper = new Map(rows.map((r) => [r.sleeperPlayerId, r]))
  return pool.map((player) => {
    const stats = bySleeper.get(player.sleeperId) ?? null
    return {
      player,
      stats,
      reason: stats ? null : 'NO_SEASON_GAMES',
      survivalNext: survivalNext(player),
      fillsSlot: fillsSlot(player),
    }
  })
}

/**
 * The leaderboard's comparator for two rows that both have stats, so the order can't drift from
 * the leaderboard's. A row with no stats goes after every row that has some, in either direction;
 * among themselves they keep pool order (the sort is stable).
 */
export function sortDraftRows(rows: DraftStatRow[], sort: SortState, mode: StatMode): DraftStatRow[] {
  const cmp = compareRows(COLUMNS[sort.col] ?? COLUMNS.name, sort.dir, mode)
  return [...rows].sort((a, b) => {
    if (a.stats && b.stats) return cmp(a.stats, b.stats)
    if (a.stats) return -1
    if (b.stats) return 1
    return 0
  })
}

/**
 * "Likely there at my next pick": survival at least RISK_MAX, the same boundary the sheet's
 * verdict calls "Act now" below. With no survival numbers at all (seat unknown, or a mock) the
 * filter has nothing to filter on, so it keeps every row; the caller says why it is unavailable.
 */
export function filterLikely(rows: DraftStatRow[], on: boolean): DraftStatRow[] {
  if (!on) return rows
  if (rows.every((r) => r.survivalNext == null)) return rows
  return rows.filter((r) => r.survivalNext != null && r.survivalNext >= RISK_MAX)
}

/** The filter's rule, printed beside the toggle so the threshold is never a secret. */
export const likelyRule = (pickLabel: string) => `${Math.round(RISK_MAX * 100)}%+ chance he’s there at ${pickLabel} (players the projection tracks)`

export type ShownSeason = {
  /** "2025–26" (Sleeper start year). */
  seasonLabel: string
  /** The stats are an earlier season's because the requested one has no games yet. */
  fellBack: boolean
  /** Which season's league scoring scored the fantasy figures; null from an older backend. */
  scoringLabel: string | null
  /** True only when the server said the requested season's scoring differs. */
  scoringChanged: boolean
}

export function shownSeason(b: StatLeaderboard): ShownSeason {
  return {
    seasonLabel: seasonLabel(b.season),
    fellBack: b.requestedSeason != null,
    scoringLabel: b.scoringSeason != null ? `Fantasy points under ${seasonLabel(b.scoringSeason)} league scoring.` : null,
    scoringChanged: b.scoringMatchesRequested === false,
  }
}
