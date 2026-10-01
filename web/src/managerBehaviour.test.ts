import { describe, expect, it } from 'vitest'
import { ARCHETYPE_TILT_CUTOFF, archetype, behaviourText, reachGapText, relativeReachRead } from './managerBehaviour'

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
      behaviourText({ relativeReachBias: 9.4, relativeReachStdErr: 2, unpredictability: 1, positionalTilt: {}, picksScored: 41 }),
    ).toBe('9.4 picks earlier than their draft room')
  })

  it('says "drafts like the room" only when the figure is inside its standard error', () => {
    expect(
      behaviourText({ relativeReachBias: 1.5, relativeReachStdErr: 4, unpredictability: 1, positionalTilt: {}, picksScored: 41 }),
    ).toBe('drafts like the room')
  })

  it('omits the reach clause entirely with no scoreable picks', () => {
    const text = behaviourText({
      relativeReachBias: null,
      relativeReachStdErr: null,
      unpredictability: 1,
      positionalTilt: { C: 1.4, PG: 0.7 },
      picksScored: 0,
    })
    expect(text).not.toContain('room')
    expect(text).toBe('leans C · fades PG')
  })

  it('still reports tilt and unpredictability with no scoreable picks', () => {
    expect(
      behaviourText({ relativeReachBias: null, relativeReachStdErr: null, unpredictability: 1.4, positionalTilt: { SF: 1.3 }, picksScored: 0 }),
    ).toBe('erratic · leans SF')
  })

  it('falls back to a claim about tilt, not about reach, when there is nothing to say', () => {
    // Only reachable with the reach clause suppressed. "No clear positional
    // lean" is a real reading of a real tilt fit; "drafts close to the board"
    // would not be.
    expect(
      behaviourText({ relativeReachBias: null, relativeReachStdErr: null, unpredictability: 1, positionalTilt: { C: 1.01 }, picksScored: 0 }),
    ).toBe('No clear positional lean in what they have drafted')
  })
})

describe('relativeReachRead', () => {
  it('reads earlier when the manager picks earlier than the room, later when later', () => {
    expect(relativeReachRead(8, 3)).toEqual({ kind: 'early', text: '8.0 picks earlier than their draft room' })
    expect(relativeReachRead(-8, 3)).toEqual({ kind: 'late', text: '8.0 picks later than their draft room' })
  })

  it('band boundary: exactly one standard error is still "drafts like the room"', () => {
    expect(relativeReachRead(3, 3)?.kind).toBe('room')
    expect(relativeReachRead(-3, 3)?.kind).toBe('room')
    expect(relativeReachRead(3.01, 3)?.kind).toBe('early')
    expect(relativeReachRead(-3.01, 3)?.kind).toBe('late')
  })

  it('a zero standard error leaves only an exact zero in the band', () => {
    expect(relativeReachRead(0, 0)?.kind).toBe('room')
    expect(relativeReachRead(0.01, 0)?.kind).toBe('early')
  })

  it('without a standard error it does not claim "drafts like the room"', () => {
    expect(relativeReachRead(12, null)?.kind).toBe('thin')
    expect(relativeReachRead(0, null)?.text).not.toContain('drafts like the room')
  })

  it('has no read at all when there is no figure', () => {
    expect(relativeReachRead(null, null)).toBeNull()
    expect(relativeReachRead(null, 2)).toBeNull()
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

// spec 013 US9: archetype() is built on relativeReachRead, so its sign cannot drift from the reach caption.
describe('archetype', () => {
  const base = { relativeReachBias: null, relativeReachStdErr: null, unpredictability: 1, positionalTilt: {}, picksScored: 0 }

  it('early (picks earlier than the room) is a Reacher, not Waits: the sign is not inverted', () => {
    expect(archetype({ ...base, relativeReachBias: 8, relativeReachStdErr: 3, picksScored: 30 }).label).toBe('Reacher')
    expect(archetype({ ...base, relativeReachBias: -8, relativeReachStdErr: 3, picksScored: 30 }).label).toBe('Waits')
  })

  it('agrees with relativeReachRead for the same inputs', () => {
    for (const [rel, se] of [[8, 3], [-8, 3], [1, 3]] as const) {
      const kind = relativeReachRead(rel, se)?.kind
      const label = archetype({ ...base, relativeReachBias: rel, relativeReachStdErr: se, picksScored: 30 }).label
      expect(label).toBe(kind === 'early' ? 'Reacher' : kind === 'late' ? 'Waits' : 'Drafts like the room')
    }
  })

  it('inside one standard error reads "Drafts like the room"', () => {
    expect(archetype({ ...base, relativeReachBias: 1.5, relativeReachStdErr: 4, picksScored: 30 })).toEqual({
      label: 'Drafts like the room',
      basis: 'drafts like the room',
      source: 'reach',
    })
  })

  it('picksScored = 0 never gives a reach label, even with a reach number present (the NBA case)', () => {
    const a = archetype({ ...base, relativeReachBias: 12, relativeReachStdErr: 1, picksScored: 0, positionalTilt: { C: 1.4 } })
    expect(['Reacher', 'Waits', 'Drafts like the room']).not.toContain(a.label)
    expect(a.label).toBe('C early')
  })

  it('a thin read (no standard error) never gives a reach label and falls through to tilt', () => {
    const a = archetype({ ...base, relativeReachBias: 9, relativeReachStdErr: null, picksScored: 1, positionalTilt: { TE: 0.2 } })
    expect(a.label).toBe('TE late')
    const none = archetype({ ...base, relativeReachBias: 9, relativeReachStdErr: null, picksScored: 1 })
    expect(none.label).toBe('Not enough history')
  })

  it('names the strongest tilt, early for above neutral and late for below', () => {
    expect(archetype({ ...base, positionalTilt: { QB: 1.6, RB: 0.7 } }).label).toBe('QB early')
    expect(archetype({ ...base, positionalTilt: { QB: 1.3, TE: 0.2 } }).label).toBe('TE late')
  })

  it('does not label a tilt below the cutoff', () => {
    expect(archetype({ ...base, positionalTilt: { QB: 1 + ARCHETYPE_TILT_CUTOFF - 0.01 } }).label).toBe('Not enough history')
    expect(archetype({ ...base, positionalTilt: { QB: 1 + ARCHETYPE_TILT_CUTOFF } }).label).toBe('QB early')
  })

  it('says Not enough history with nothing to go on', () => {
    expect(archetype(base)).toEqual({
      label: 'Not enough history',
      basis: 'No pick of theirs can be scored for reach, and no strong positional lean.',
      source: 'none',
    })
  })
})
