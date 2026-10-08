import type { LeaderboardRow, PlayerCounting, PlayerOwnership, PlayerWindow, Rate, StatLeaderboard } from './api'

/** Builders for the stats leaderboard tests (specs/022 US4). */
export const zero: PlayerCounting = {
  pts: 0, reb: 0, oreb: 0, dreb: 0, ast: 0, stl: 0, blk: 0, tov: 0, pf: 0, fgm: 0, fga: 0, tpm: 0, tpa: 0, ftm: 0, fta: 0,
}
export const rate = (value: number | null, reason: Rate['reason'] = null): Rate => ({ value, reason })

export function win(
  over: Partial<PlayerWindow> & { pg?: Partial<PlayerCounting>; adv?: Partial<PlayerWindow['advanced']> } = {},
): PlayerWindow {
  const { pg, adv, ...rest } = over
  const perGame = { ...zero, pts: 10, reb: 5, ast: 3, stl: 1, blk: 0.5, tpm: 1, tov: 2, fgm: 4, fga: 9, ftm: 2, fta: 3, tpa: 3, ...pg }
  return {
    games: 60, firstGameDate: '2025-10-22', lastGameDate: '2026-04-12', minutes: 1800, minutesPerGame: 30,
    perGame,
    totals: { ...perGame, pts: perGame.pts * 60, reb: perGame.reb * 60, ast: perGame.ast * 60, fgm: 240, fga: 540 },
    per36: { ...perGame, pts: perGame.pts * 1.2 },
    shooting: { fgPct: rate(44.4), tpPct: rate(33.3), ftPct: rate(66.7) },
    gameScorePerGame: 12, plusMinusPerGame: 1, smallSample: false,
    advanced: {
      ts: rate(55), efg: rate(50), ftr: rate(30), tpar: rate(33), usg: rate(20), minutesShare: rate(60), astPct: rate(15),
      orbPct: rate(5), drbPct: rate(15), trbPct: rate(10), stlPct: rate(1.5), blkPct: rate(1), tovPct: rate(12), ...adv,
    },
    ...rest,
  }
}

export function owner(over: Partial<PlayerOwnership> = {}): PlayerOwnership {
  return {
    state: 'ROSTERED', rosterId: 1, ownerName: 'Dunk Tank', avatarId: null, isMe: false,
    asOf: { kind: 'WEEK', fetchedAt: null, week: 18 }, ...over,
  }
}

export function row(id: string, over: Partial<LeaderboardRow> = {}): LeaderboardRow {
  return {
    sleeperPlayerId: id, name: `Player ${id}`, positions: ['PG'], team: 'LAL', ownership: owner(), currentOwnership: null,
    qualified: true, reason: null, stats: win(), fpPerGame: 30, leagueRank: 10, positionRank: 5, pointsRank: 12, rankMove: 2,
    valueOverReplacement: 12.5, vorPosition: 'PG', draft: { pickNo: 14, round: 2, managerName: 'Dunk Tank' }, draftValue: 3.2, adp: 20.5,
    ...over,
  }
}

export function board(rows: LeaderboardRow[], over: Partial<StatLeaderboard> = {}): StatLeaderboard {
  return {
    sport: 'nba', season: 2025, requestedSeason: null, available: true, reason: null, dataAsOf: '2026-04-13T03:00:00Z',
    seasons: [
      { season: 2025, sleeperLeagueId: 'L25', hasGames: true },
      { season: 2024, sleeperLeagueId: 'L24', hasGames: true },
    ],
    window: 'SEASON',
    qualification: { minGamesShare: 0.5, minGames: 42, maxTeamGames: 83, minMinutesPerGame: 15, recencyDays: 14 },
    ownershipAsOf: { kind: 'WEEK', fetchedAt: null, week: 18 },
    draft: { state: 'COMPLETE', draftId: 'D1', draftSeason: 2025 },
    adp: { source: 'blend', capturedOn: null, reason: 'NO_ADP_STORED' },
    draftGrades: { available: true, reason: null, gradesEarly: false, weeksCounted: 18 },
    replacement: { byPosition: { PG: 16.5, SG: 16.7, SF: 16.9, PF: 16.6, C: 16.8 }, rule: 'GREEDY_SLOT_FILL', teams: 12, slots: ['PG', 'SG'] },
    rows,
    ...over,
  }
}
