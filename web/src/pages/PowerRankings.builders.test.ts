import { describe, expect, it } from 'vitest'
import type { BallotState, PowerRankingEntry, StandingRow } from '../api'
import { ballotBlockState, buildDeck, recordLabel, roomTakeSentence, type WeeklyStory } from './PowerRankings'

/**
 * specs/021-codebase-cleanup T006 (FR-003). These four builders were exercised only
 * by the dev-only PowerRankings.verify.tsx harness, and had no test of their own.
 * The harness is being deleted, so this file pins their current outputs first.
 * It characterizes the code as it is; it doesn't redefine what the outputs should be.
 *
 * Ranks stay at or below 14 on purpose. The ordinal in PowerRankings.tsx prints "21th",
 * which the spec 021 FR-001 exception fixes in its own commit (T026). Keeping this
 * file below 21 means that fix doesn't have to edit a characterization test.
 */

const entry = (over: Partial<PowerRankingEntry>): PowerRankingEntry =>
  ({
    season: 2026,
    week: 3,
    kind: 'MEMBER',
    rosterId: 1,
    managerId: 1,
    manager: 'popsharky',
    teamName: 'Sharks',
    rank: 1,
    score: 2.5,
    ...over,
  }) as unknown as PowerRankingEntry

const story = (over: Partial<WeeklyStory>): WeeklyStory => ({
  top: null,
  newTop: false,
  riser: null,
  faller: null,
  divisive: null,
  ...over,
})

describe('recordLabel', () => {
  it('prints W-L, adds ties only when there are some, and -- when unknown', () => {
    expect(recordLabel({ wins: 7, losses: 3, ties: 0 } as StandingRow)).toBe('7-3')
    expect(recordLabel({ wins: 7, losses: 3, ties: 1 } as StandingRow)).toBe('7-3-1')
    expect(recordLabel({ wins: 0, losses: 0, ties: 0 } as StandingRow)).toBe('0-0')
    expect(recordLabel({ wins: null, losses: 3 } as unknown as StandingRow)).toBe('--')
    expect(recordLabel(null)).toBe('--')
    expect(recordLabel(undefined)).toBe('--')
  })
})

describe('roomTakeSentence', () => {
  it('says not-yet-ranked or a tally when there is no spread', () => {
    expect(roomTakeSentence(entry({ bestRank: null, worstRank: null, ballotCount: 0 }), 12)).toBe('Not yet ranked')
    expect(roomTakeSentence(entry({ bestRank: null, worstRank: null, ballotCount: 1 }), 12)).toBe('1 ballot counted')
    expect(roomTakeSentence(entry({ bestRank: null, worstRank: null, ballotCount: 4 }), 12)).toBe('4 ballots counted')
  })

  it('reads the spread: unanimous, adjacent, split, and no consensus', () => {
    expect(roomTakeSentence(entry({ bestRank: 3, worstRank: 3, ballotCount: 4 }), 12)).toBe('Ranked 3rd on every ballot')
    expect(roomTakeSentence(entry({ bestRank: 1, worstRank: 2, ballotCount: 4 }), 12)).toBe('1st or 2nd on every ballot')
    expect(roomTakeSentence(entry({ bestRank: 2, worstRank: 5, ballotCount: 4 }), 12)).toBe('Ranked 2nd to 5th, 4 ballots')
    expect(roomTakeSentence(entry({ bestRank: 2, worstRank: 8, ballotCount: 4 }), 12)).toBe('2nd to 8th — no consensus')
    expect(roomTakeSentence(entry({ bestRank: 11, worstRank: 13, ballotCount: undefined }), 14)).toBe(
      'Ranked 11th to 13th, 0 ballots',
    )
  })
})

describe('buildDeck', () => {
  it('is just the tally when nothing stands out, singular for one member, Preseason for week 0', () => {
    expect(buildDeck(story({}), 4, 12, 3)).toBe('4 of 12 ballots in for week 3.')
    expect(buildDeck(story({}), 1, 1, 0)).toBe('1 of 1 ballot in for Preseason.')
  })

  it('leads with a riser the headline did not already name', () => {
    const riser = { entry: entry({ rosterId: 5, teamName: 'Comets', rank: 4 }), delta: 2 }
    expect(buildDeck(story({ riser }), 4, 12, 3)).toBe('Comets climbed 2 spots; 4 of 12 ballots in for week 3.')
    const one = { entry: entry({ rosterId: 5, teamName: 'Comets', rank: 4 }), delta: 1 }
    expect(buildDeck(story({ riser: one }), 4, 12, 3)).toBe('Comets climbed 1 spot; 4 of 12 ballots in for week 3.')
  })

  it('skips a riser the headline named (delta >= 3) and falls through to the faller', () => {
    const riser = { entry: entry({ rosterId: 5, teamName: 'Comets', rank: 2 }), delta: 4 }
    const faller = { entry: entry({ rosterId: 6, teamName: 'Rocks', rank: 9 }), delta: -2 }
    expect(buildDeck(story({ riser, faller }), 4, 12, 3)).toBe('Rocks dropped 2 spots; 4 of 12 ballots in for week 3.')
  })

  it('names a divisive team with its spread, falling back to the team name rules', () => {
    const divisive = entry({ rosterId: 7, teamName: '  ', manager: 'kieriskash', rank: 6, bestRank: 2, worstRank: 11 })
    const top = entry({ rosterId: 1 })
    expect(buildDeck(story({ top, newTop: true, divisive }), 4, 12, 3)).toBe(
      'kieriskash splits the room, 2nd to 11th; 4 of 12 ballots in for week 3.',
    )
  })

  it('drops the lead when the only story is the one the headline already told', () => {
    const divisive = entry({ rosterId: 7, teamName: 'Mud', rank: 6, bestRank: 2, worstRank: 11 })
    expect(buildDeck(story({ divisive }), 4, 12, 3)).toBe('4 of 12 ballots in for week 3.')
  })
})

describe('ballotBlockState', () => {
  const ballot = (over: Partial<BallotState>): BallotState =>
    ({
      season: 2026,
      week: 3,
      canSubmit: true,
      canCommission: false,
      commissionerKnown: true,
      memberCount: 12,
      ballotCount: 4,
      members: [{ isMe: true }, { isMe: false }],
      mine: null,
      ...over,
    }) as unknown as BallotState

  it('orders its checks: signed-out, then load-error, then loading, then membership, then closed', () => {
    expect(ballotBlockState(false, null, true)).toBe('signed-out')
    expect(ballotBlockState(true, ballot({}), true)).toBe('load-error')
    expect(ballotBlockState(true, null, false)).toBe('loading')
    expect(ballotBlockState(true, ballot({ members: [{ isMe: false }] as BallotState['members'] }), false)).toBe('not-member')
    expect(ballotBlockState(true, ballot({ members: [] }), false)).toBe('not-member')
    expect(ballotBlockState(true, ballot({ canSubmit: false }), false)).toBe('voting-closed')
    expect(ballotBlockState(true, ballot({}), false)).toBe('ok')
  })
})
