import { describe, expect, it } from 'vitest'
import { TIER_ADP_GAP, TIER_MAX_SPAN, tierPlayers } from './tiers'

const adp = (p: { adp: number }) => p.adp
const mk = (...adps: number[]) => adps.map((a, i) => ({ id: i, adp: a }))

describe('tierPlayers', () => {
  it('does not split on a gap equal to the constant', () => {
    const t = tierPlayers(mk(10, 10 + TIER_ADP_GAP), adp)
    expect(t).toHaveLength(1)
    expect(t[0].label).toBe('Tier 1')
  })

  it('splits on a gap greater than the constant', () => {
    const t = tierPlayers(mk(10, 10 + TIER_ADP_GAP + 0.5, 11 + TIER_ADP_GAP + 0.5), adp)
    expect(t.map((x) => x.players.length)).toEqual([1, 2])
    expect(t.map((x) => x.label)).toEqual(['Tier 1', 'Tier 2'])
  })

  it('sorts by ADP regardless of input order', () => {
    const t = tierPlayers(mk(30, 10, 12), adp)
    expect(t[0].players.map((p) => p.adp)).toEqual([10, 12])
  })

  it('puts 999 in a trailing Unranked group and never measures a gap to it', () => {
    const t = tierPlayers(mk(999, 10, 999), adp)
    expect(t.map((x) => x.label)).toEqual(['Tier 1', 'Unranked'])
    expect(t[1].tier).toBeNull()
    expect(t[1].players).toHaveLength(2)
  })

  it('returns nothing for nothing', () => {
    expect(tierPlayers([], adp)).toEqual([])
  })

  /** A dense list can't chain into one tier wider than TIER_MAX_SPAN. */
  it('starts a new tier once the span from the tier start passes the cap', () => {
    const adps = [23, 26, 29, 32, 35, 38, 41, 44, 47, 50]
    const tiers = tierPlayers(adps, (a) => a)
    for (const t of tiers) expect(Math.max(...t.players) - Math.min(...t.players)).toBeLessThanOrEqual(TIER_MAX_SPAN)
    expect(tiers.length).toBeGreaterThan(1)
  })
})
