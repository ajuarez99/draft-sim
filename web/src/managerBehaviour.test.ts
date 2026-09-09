import { describe, expect, it } from 'vitest'
import { behaviourText, reachGapText } from './managerBehaviour'

// The distinction under test is the one thing basketball made urgent: reach and
// positional tilt are fitted from different evidence, and a manager can have
// one without the other. Every NBA manager in this app has tilt and no reach,
// permanently -- both completed NBA drafts predate the first NBA board
// snapshot, so no pick in them carries an adp_at_time to score against
// (multi-sport-and-rebrand.md, "Basketball has no reach signal"). Their
// reachBias is the league mean, which is 0, which the old sentence reported as
// "drafts close to the board" -- a measurement, stated as fact, that was never
// taken.

describe('behaviourText', () => {
  it('reports reach when there are scoreable picks behind it', () => {
    expect(
      behaviourText({ reachBias: 2.4, unpredictability: 1, positionalTilt: {}, picksScored: 41 }),
    ).toBe('reaches ~2.4 picks early')
  })

  it('says "drafts close to the board" only when that was actually measured', () => {
    expect(
      behaviourText({ reachBias: 0, unpredictability: 1, positionalTilt: {}, picksScored: 41 }),
    ).toBe('drafts close to the board')
  })

  it('omits the reach clause entirely with no scoreable picks', () => {
    const text = behaviourText({
      reachBias: 0,
      unpredictability: 1,
      positionalTilt: { C: 1.4, PG: 0.7 },
      picksScored: 0,
    })
    expect(text).not.toContain('board')
    expect(text).toBe('leans C · fades PG')
  })

  it('still reports tilt and unpredictability with no scoreable picks', () => {
    expect(
      behaviourText({ reachBias: 0, unpredictability: 1.4, positionalTilt: { SF: 1.3 }, picksScored: 0 }),
    ).toBe('erratic · leans SF')
  })

  it('falls back to a claim about tilt, not about reach, when there is nothing to say', () => {
    // Only reachable with the reach clause suppressed. "No clear positional
    // lean" is a real reading of a real tilt fit; "drafts close to the board"
    // would not be.
    expect(
      behaviourText({ reachBias: 0, unpredictability: 1, positionalTilt: { C: 1.01 }, picksScored: 0 }),
    ).toBe('No clear positional lean in what they have drafted')
  })
})

describe('reachGapText', () => {
  it('explains the gap for a manager with history but nothing scoreable', () => {
    const text = reachGapText({ draftsObserved: 2, picksScored: 0 })
    expect(text).toContain('2 drafts of history')
    expect(text).toContain('no board snapshot')
    expect(text).toContain('league average')
  })

  it('is singular for one draft', () => {
    expect(reachGapText({ draftsObserved: 1, picksScored: 0 })).toContain('1 draft of history, but no pick in it')
  })

  it('is silent when reach was measured', () => {
    expect(reachGapText({ draftsObserved: 3, picksScored: 60 })).toBeNull()
  })

  it('is silent for a seat with no history at all -- that is a different sentence', () => {
    expect(reachGapText({ draftsObserved: 0, picksScored: 0 })).toBeNull()
  })
})
