import { describe, expect, it } from 'vitest'
import { isForward, pickNoAt } from './snake'

// Mirrors DraftSlotReversalRoundTest.java on the backend. Two copies of snake
// math exist on purpose (the board draws from (round, slot); the engine picks
// from a pick number), so both need the same cases or they drift -- which is
// exactly what happened: the board was plain snake while the engine honoured
// reversal_round, and the 2026 NBA draft renders rounds 3-14 in the wrong
// direction as a result.

describe('plain snake (reversalRound 0)', () => {
  it('alternates direction every round', () => {
    expect(isForward(1, 0)).toBe(true)
    expect(isForward(2, 0)).toBe(false)
    expect(isForward(3, 0)).toBe(true)
  })

  it('numbers a 12-team board the way every football draft here does', () => {
    expect(pickNoAt(1, 1, 12, 0)).toBe(1)
    expect(pickNoAt(1, 12, 12, 0)).toBe(12)
    expect(pickNoAt(2, 1, 12, 0)).toBe(24)
    expect(pickNoAt(2, 12, 12, 0)).toBe(13)
    expect(pickNoAt(3, 1, 12, 0)).toBe(25)
  })
})

describe('third-round reversal (reversalRound 3)', () => {
  it('leaves rounds 1 and 2 alone and flips every round from 3 on', () => {
    expect(isForward(1, 3)).toBe(true)
    expect(isForward(2, 3)).toBe(false)
    // Round 3 would be forward under plain snake; the flip makes it reverse,
    // so slot 1 picks last in round 3 having also picked last in round 2.
    expect(isForward(3, 3)).toBe(false)
    expect(isForward(4, 3)).toBe(true)
  })

  it('agrees with the engine on the picks slot 5 of a 12-team draft owns', () => {
    // Captured live from POST /api/sims against the real 2026 NBA draft
    // (reversal_round: 3): myPicks came back [5, 20, 32, 41, 56, 65].
    expect([1, 2, 3, 4, 5, 6].map((r) => pickNoAt(r, 5, 12, 3))).toEqual([5, 20, 32, 41, 56, 65])
    // ...and plain snake, which is what the board used to draw for the same
    // draft, gives a different answer from round 3 on.
    expect([1, 2, 3, 4, 5, 6].map((r) => pickNoAt(r, 5, 12, 0))).toEqual([5, 20, 29, 44, 53, 68])
  })

  it('still covers each round exactly once', () => {
    for (const round of [3, 4]) {
      const picks = Array.from({ length: 12 }, (_, i) => pickNoAt(round, i + 1, 12, 3)).sort((a, b) => a - b)
      expect(picks).toEqual(Array.from({ length: 12 }, (_, i) => (round - 1) * 12 + i + 1))
    }
  })
})
