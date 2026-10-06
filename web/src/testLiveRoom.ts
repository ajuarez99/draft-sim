/**
 * Fixtures shared by the LiveDraftView component tests (landed / card / resim).
 * vi.mock calls must live in each test file (they hoist), so what is shared
 * here is only data: a league, its seats, and live state frames.
 */
import type { LiveState, PlayerRef, RealPick, Seat, SeatsResponse } from './api'

export const NFL_TEMPLATE = ['QB', 'RB', 'RB', 'WR', 'WR', 'TE', 'FLEX', 'FLEX', 'K', 'DEF', 'BN', 'BN']

let nextPlayerId = 1000
export function mkPlayer(position: string, name?: string, adp = 50): PlayerRef {
  const id = nextPlayerId++
  return {
    id,
    sleeperId: `s${id}`,
    name: name ?? `${position} Player ${id}`,
    position: position as PlayerRef['position'],
    team: 'SEA',
    adp,
    positionalRank: 1,
  }
}

export function mkSeat(slot: number, over: Partial<Seat> = {}): Seat {
  return {
    slot,
    managerId: slot,
    manager: `Mgr${slot}`,
    avatarId: null,
    provenance: 'FITTED',
    reachBias: 0,
    relativeReachBias: null,
    relativeReachStdErr: null,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 2,
    picksScored: 20,
    ...over,
  }
}

export function mkSeats(teams: number, over: Partial<SeatsResponse> = {}): SeatsResponse {
  return {
    draftId: 'd1',
    teams,
    rounds: 15,
    status: 'drafting',
    seats: Array.from({ length: teams }, (_, i) => mkSeat(i + 1)),
    mySlot: 1,
    sport: 'nfl',
    rosterPositions: NFL_TEMPLATE,
    reversalRound: 0,
    reversalRoundFromSleeper: 0,
    reversalRoundOverridden: false,
    ...over,
  } as SeatsResponse
}

/** Who owns a pick in plain snake. */
export function slotOfPick(pickNo: number, teams: number): number {
  const round = Math.ceil(pickNo / teams)
  const i = pickNo - (round - 1) * teams
  return round % 2 === 1 ? i : teams - i + 1
}

export function mkRealPick(pickNo: number, teams: number, player: PlayerRef, slot?: number): RealPick {
  const s = slot ?? slotOfPick(pickNo, teams)
  return {
    pickNo,
    round: Math.ceil(pickNo / teams),
    slot: s,
    manager: `Mgr${s}`,
    avatarId: null,
    player,
  }
}

export function mkLive(picks: RealPick[], teams: number, over: Partial<LiveState> = {}): LiveState {
  const last = picks[picks.length - 1]
  return {
    draftId: 'd1',
    status: 'drafting',
    tracking: true,
    picksMade: last ? last.pickNo : 0,
    lastPickNo: last ? last.pickNo : 0,
    totalPicks: teams * 15,
    teams,
    rounds: 15,
    seatsMapped: teams,
    onTheClockSlot: null,
    recentPicks: picks.slice(-12),
    serverTime: '2026-09-30T00:00:00Z',
    ...over,
  }
}

/** A PickGrade with every optional number filled; override per test (spec 018). */
export function mkPickGrade(pickNo: number, over: Partial<import('./api').PickGrade> = {}): import('./api').PickGrade {
  return {
    pickNo,
    round: 1,
    slot: pickNo,
    sleeperPlayerId: `s${pickNo}`,
    playerName: `Player ${pickNo}`,
    position: 'WR',
    production: 100,
    weeksPlayed: 3,
    slotBaseline: 80,
    valueOverSlot: 20,
    positionDrafted: 4,
    positionFinish: 2,
    countedForYou: null,
    creditedForYou: null,
    weeksStartedForYou: null,
    weeksUnknownForYou: null,
    ...over,
  }
}

/** A DraftGrades payload; override per test (spec 018). */
export function mkDraftGrades(over: Partial<import('./api').DraftGrades> = {}): import('./api').DraftGrades {
  return {
    draftId: 'd1',
    sport: 'nba',
    season: 2025,
    available: true,
    reason: null,
    productionBasis: 'WEEKLY_AVERAGE_GAME',
    countedWeeks: [1, 2, 3],
    weeksCounted: 3,
    weeksMissingGameData: [],
    gradesEarly: false,
    earlyThresholdWeeks: 4,
    minPicksPerPosition: 8,
    excludedPicks: 0,
    unmappedPicks: 0,
    unpositionedPicks: 0,
    averageTeamRawValue: 0,
    picks: [],
    teams: [],
    steals: [],
    busts: [],
    ...over,
  }
}
