import { describe, expect, it } from 'vitest'
import type { HistoricalGameState, HistoricalResult } from './api'
import { draftScore, pickMark, shareText } from './historicalShare'

describe('pickMark', () => {
  // Lower rank is better. A sign flip here would paint every bust green, so the direction is pinned.
  it('calls a pick that finished at a better (smaller) rank "ahead"', () => {
    expect(pickMark(35, 8)).toBe('ahead')
  })
  it('calls a pick that finished at a worse (larger) rank "behind"', () => {
    expect(pickMark(43, 179)).toBe('behind')
  })
  it('calls an unchanged rank "same"', () => {
    expect(pickMark(1, 1)).toBe('same')
  })
})

describe('draftScore', () => {
  it('is your points as a share of hindsight, rounded down', () => {
    // 2479.0 / 3108.7 = 79.74%: the badge sentence says 79%, and so does this.
    expect(draftScore({ userPointsFor: 2479.0, pointsFor: 3108.7 })).toBe(79)
  })
  it('orders drafts the way their points do', () => {
    const worse = draftScore({ userPointsFor: 1500, pointsFor: 3000 })!
    const better = draftScore({ userPointsFor: 2700, pointsFor: 3000 })!
    expect(better).toBeGreaterThan(worse)
  })
  it('goes past 100 when you beat hindsight', () => {
    expect(draftScore({ userPointsFor: 3300, pointsFor: 3000 })).toBe(110)
  })
  it('is null with no hindsight total to divide by', () => {
    expect(draftScore({ userPointsFor: 100, pointsFor: 0 })).toBeNull()
  })
})

function finish(pickNo: number, drafted: number, finished: number) {
  return {
    pickNo,
    player: { id: pickNo, name: `P${pickNo}` },
    position: 'WR',
    draftedPositionRank: drafted,
    finishPositionRank: finished,
    positionPlayers: 200,
    seasonValue: 100,
    weeksPlayed: 10,
  }
}

function fixture(over: Partial<HistoricalResult> = {}): { game: HistoricalGameState; result: HistoricalResult } {
  const game = { sport: 'NFL', season: { label: '2020' }, teams: 12, rounds: 3, userSlot: 12 } as unknown as HistoricalGameState
  const result = {
    standings: [{ slot: 12, place: 1, wins: 13, losses: 1, ties: 0, pointsFor: 2014.7 }],
    userFinish: 1,
    userOutcome: 'CHAMPION',
    format: { regularSeasonWeeks: 14, playoffWeeks: [15, 16, 17], seasonWeeks: 17, unusedWeeks: 0, shortened: false },
    hindsight: { userPointsFor: 2479.0, pointsFor: 3108.7, userBeatIt: false, label: 'greedy hindsight', picks: [] },
    // Out of draft order on purpose: the squares must come out in pick order.
    pickFinishes: [finish(36, 16, 22), finish(12, 9, 1), finish(13, 1, 1)],
    ...over,
  } as unknown as HistoricalResult
  return { game, result }
}

describe('shareText', () => {
  it('prints the season, the finish, the score and one square per pick in draft order', () => {
    const { game, result } = fixture()
    expect(shareText(game, result, 'https://example.test/history')).toBe(
      ['Time Machine · 2020 Football', '🏆 Champion · 13-1', 'Draft score 79', '🟩⬜🟥', 'https://example.test/history'].join('\n'),
    )
  })
  it('leaves out the squares for a game stored before pick finishes existed', () => {
    const { game, result } = fixture({ pickFinishes: null, userOutcome: 'MISSED_PLAYOFFS', userFinish: 7 })
    const text = shareText(game, result, 'u')
    expect(text).toContain('You missed the playoffs (7th of 12) · 13-1')
    expect(text).not.toMatch(/🟩|🟥|⬜|🏆/u)
  })
})
