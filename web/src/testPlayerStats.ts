import type { PlayerStatsPage, PlayerWindowKind, WindowQualification } from './api'
import { owner, win } from './testStatBoard'

export { owner, rate, win } from './testStatBoard'

/** A qualified, fresh set of windows: the baseline every comparison test bends one thing away from. */
export const OK_QUAL: Partial<Record<PlayerWindowKind, WindowQualification>> = {
  SEASON: { qualified: true, reason: null, lastN: null },
  LAST_10: { qualified: true, reason: null, lastN: 10 },
  LAST_5: { qualified: true, reason: null, lastN: 5 },
}

/**
 * A full `PlayerStatsPage` for the comparison tests (specs/049): season 2025, three full windows, every
 * figure set, and `windowQualification` present. Mirrors the shape of a real response; override per test.
 */
export function statsPage(over: Partial<PlayerStatsPage> & { id?: string; name?: string } = {}): PlayerStatsPage {
  const { id = '1', name = 'Player One', ...rest } = over
  return {
    sport: 'nba',
    season: 2025,
    requestedSeason: null,
    available: true,
    reason: null,
    dataAsOf: '2026-04-13T03:00:00Z',
    seasons: [],
    currentOwnership: null,
    player: { sleeperPlayerId: id, name, positions: ['PG'], team: 'LAL', known: true },
    teamsThisSeason: ['LAL'],
    teamGamesMissed: 0,
    ownership: owner(),
    windows: { SEASON: win(), LAST_10: win({ games: 10 }), LAST_5: win({ games: 5 }) },
    percentiles: {},
    fantasy: {
      fpPerGame: { SEASON: 30, LAST_10: 31, LAST_5: 32 },
      ranks: { leagueRank: 10, positionRank: 5, pointsRank: 12, rankMove: 2, groupSize: 300, positionGroupSize: 60, position: 'PG', reason: null },
      breakdown: [],
      seasonTotal: 1800,
    },
    gameLog: [],
    qualification: { minGamesShare: 0.5, minGames: 42, maxTeamGames: 82, minMinutesPerGame: 15, recencyDays: 14 },
    status: null,
    nextGame: null,
    nextGameReason: 'NO_GAMES_LEFT',
    upcoming: [],
    scheduleAsOf: '2026-10-20T09:00:00Z',
    thisWeek: null,
    windowQualification: OK_QUAL,
    ...rest,
  }
}
