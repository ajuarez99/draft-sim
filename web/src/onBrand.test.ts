import { describe, expect, it } from 'vitest'
import type { PlayerRef, Provenance, RealPick, Seat } from './api'
import { onBrandReads } from './onBrand'

let nextId = 1
function player(position: string, adp: number): PlayerRef {
  const id = nextId++
  return { id, sleeperId: String(id), name: `P${id}`, position: position as PlayerRef['position'], team: 'SEA', adp, positionalRank: id }
}

function seat(slot: number, provenance: Provenance, reachBias: number, positionalTilt: Record<string, number> = {}): Seat {
  return {
    slot, managerId: slot, manager: `M${slot}`, avatarId: null, provenance, reachBias,
    relativeReachBias: null, relativeReachStdErr: null, unpredictability: 0.5, positionalTilt,
    note: null, draftsObserved: 1, picksScored: 10,
  }
}

/** A pick at pickNo whose ADP is pickNo + reach, i.e. adpDelta = reach. */
function pick(slot: number, pickNo: number, position: string, reach: number): RealPick {
  return { pickNo, round: 1, slot, manager: `M${slot}`, avatarId: null, player: player(position, pickNo + reach) }
}

describe('reach verdict', () => {
  it('profile +8 and draft-so-far +10 reads on', () => {
    const picks = [pick(1, 20, 'WR', 10), pick(1, 21, 'WR', 10), pick(1, 22, 'WR', 10)]
    const [r] = onBrandReads([seat(1, 'FITTED', 8)], picks)
    expect(r.reachSoFar).toBe(10)
    expect(r.reachVerdict).toBe('on')
    expect(r.reachLabel).toBe('vs board ADP')
  })

  it('profile +8 against -6 reads off', () => {
    const picks = [pick(1, 20, 'WR', -6), pick(1, 21, 'WR', -6), pick(1, 22, 'WR', -6)]
    expect(onBrandReads([seat(1, 'FITTED', 8)], picks)[0].reachVerdict).toBe('off')
  })

  it('a profile that reaches +10 against a draft-so-far of +0.7 reads off, not on', () => {
    // Found live (T042): same sign, but +0.7 is no reach at all. A shared sign
    // inside the noise band is not agreement.
    const picks = [pick(1, 20, 'WR', 1), pick(1, 21, 'WR', 1), pick(1, 22, 'WR', 0)]
    expect(onBrandReads([seat(1, 'FITTED', 10)], picks)[0].reachVerdict).toBe('off')
  })

  it('both within +-3 reads on even with opposite signs', () => {
    const picks = [pick(1, 20, 'WR', -2), pick(1, 21, 'WR', -2), pick(1, 22, 'WR', -2)]
    expect(onBrandReads([seat(1, 'FITTED', 2)], picks)[0].reachVerdict).toBe('on')
  })

  it('a neutral seat gets no verdict, with a reason', () => {
    const picks = [pick(1, 20, 'WR', 10), pick(1, 21, 'WR', 10), pick(1, 22, 'WR', 10)]
    const [r] = onBrandReads([seat(1, 'NEUTRAL', 0)], picks)
    expect(r.reachVerdict).toBeNull()
    expect(r.reachReason).toBe('no history')
  })

  it('two picks give no verdict', () => {
    const picks = [pick(1, 20, 'WR', 10), pick(1, 21, 'WR', 10)]
    const [r] = onBrandReads([seat(1, 'FITTED', 8, { RB: 1.3 })], picks)
    expect(r.reachVerdict).toBeNull()
    expect(r.reachReason).toBe('too early')
    expect(r.leanVerdict).toBeNull()
    expect(r.leanReason).toBe('too early')
  })

  it('a stated seat is labelled "vs what you entered"', () => {
    expect(onBrandReads([seat(1, 'STATED', 5)], [])[0].reachLabel).toBe('vs what you entered')
  })

  it('ignores picks with no ADP (999 sentinel)', () => {
    const p = pick(1, 20, 'WR', 0)
    p.player.adp = 999
    expect(onBrandReads([seat(1, 'FITTED', 5)], [p])[0].reachSoFar).toBeNull()
  })
})

describe('lean verdict', () => {
  it('RB lean with RB share above the room reads on, never off', () => {
    const mine = [pick(1, 1, 'RB', 0), pick(1, 13, 'RB', 0), pick(1, 25, 'WR', 0)]
    const others = [pick(2, 2, 'WR', 0), pick(2, 14, 'WR', 0), pick(2, 26, 'RB', 0), pick(3, 3, 'WR', 0), pick(3, 15, 'TE', 0)]
    const [r] = onBrandReads([seat(1, 'FITTED', 0, { RB: 1.4, WR: 0.8 })], [...mine, ...others])
    expect(r.lean).toEqual({ position: 'RB', tilt: 1.4 })
    expect(r.leanShare).toBeCloseTo(2 / 3)
    expect(r.roomShare).toBeCloseTo(3 / 8)
    expect(r.leanVerdict).toBe('on')
  })

  it('reads off when the seat is at or below the room share', () => {
    const mine = [pick(1, 1, 'WR', 0), pick(1, 13, 'WR', 0), pick(1, 25, 'WR', 0)]
    const others = [pick(2, 2, 'RB', 0), pick(2, 14, 'RB', 0)]
    expect(onBrandReads([seat(1, 'FITTED', 0, { RB: 1.4 })], [...mine, ...others])[0].leanVerdict).toBe('off')
  })

  it('a tilt under the threshold is no lean', () => {
    const r = onBrandReads([seat(1, 'FITTED', 0, { RB: 1.05 })], [])[0]
    expect(r.lean).toBeNull()
    expect(r.leanReason).toBe('no lean')
  })

  it('a stated seat has lean null even if a tilt is present', () => {
    const r = onBrandReads([seat(1, 'STATED', 0, { RB: 1.5 })], [])[0]
    expect(r.lean).toBeNull()
    expect(r.leanVerdict).toBeNull()
    expect(r.leanReason).toBe('lean not fitted')
  })

  it('reports the mix as counts', () => {
    const picks = [pick(1, 1, 'RB', 0), pick(1, 13, 'RB', 0), pick(1, 25, 'WR', 0)]
    expect(onBrandReads([seat(1, 'FITTED', 0)], picks)[0].mix).toEqual({ RB: 2, WR: 1 })
  })
})
