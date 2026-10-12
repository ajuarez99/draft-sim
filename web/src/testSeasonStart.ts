import type { AnalysisRosterProjection, SeasonFavorite, SeasonStrength } from './api'

/** Fixtures for the season-start payload (spec 030), shared by the home page and strength block tests. */

export function projRoster(rosterId: number, rank: number, total: number, over: Partial<AnalysisRosterProjection> = {}): AnalysisRosterProjection {
  return {
    rosterId,
    managerId: rosterId,
    manager: `user${rosterId}`,
    avatarId: null,
    rank,
    isMe: false,
    total,
    byPosition: {},
    rankByPosition: {},
    starters: [],
    bench: [],
    byWeek: [],
    missing: 0,
    unavailable: 0,
    ...over,
  }
}

export function favorite(over: Partial<SeasonFavorite> = {}): SeasonFavorite {
  return {
    leaders: [{ rosterId: 1, teamName: 'Dunk Tank', username: 'user1', avatarId: null, total: 312.4 }],
    tie: false,
    leaderTotal: 312.4,
    nextTotal: 301.1,
    marginPoints: 11.3,
    marginPct: 3.8,
    minGapPct: 3,
    ...over,
  }
}

/** Three teams on top, you (roster 9) outside them at rank 7 of 12. */
export function strength(over: Partial<SeasonStrength> = {}): SeasonStrength {
  return {
    available: true,
    reason: null,
    reasonCode: null,
    unit: 'PER_WEEK',
    top: [projRoster(1, 1, 312.4), projRoster(2, 2, 301.1), projRoster(3, 3, 290.0)],
    me: projRoster(9, 7, 250.5, { isMe: true, manager: 'popsharky' }),
    teams: 12,
    favorite: favorite(),
    provenance: { source: 'sleeper', sourceModifiedAt: new Date().toISOString(), fetchedAt: new Date().toISOString() },
    unprojectedCategories: ['bonus_pt_50p'],
    basisNote: 'ONE_AVERAGE_GAME_PER_STARTER',
    staleAfterDays: 3,
    teamLabels: [
      { rosterId: 1, teamName: 'Dunk Tank', username: 'user1' },
      { rosterId: 2, teamName: 'Brick City', username: 'user2' },
      { rosterId: 9, teamName: 'Sharky Bytes', username: 'popsharky' },
    ],
    ...over,
  }
}
