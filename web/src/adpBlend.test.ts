import { describe, expect, it } from 'vitest'
import {
  FULL_TRUST_GAP_DEFAULT,
  GAP_THRESHOLD_DEFAULT,
  TRUST_DEFAULT,
  YAHOO_MIN_PCT_DEFAULT,
  blendOrder,
  blendValue,
  blendWeight,
  gap,
  gapText,
  rescale,
  sortByBlendRank,
  sortRows,
  tintOf,
  trustedGap,
  yahooReliable,
  type AdpRow,
} from './adpBlend'

function row(over: Partial<AdpRow> = {}): AdpRow {
  return {
    name: 'Player', team: 'BOS', yearsExp: 3,
    dynasty: null, redraft: null, yahooAdp: null, yahooCost: null, yahooPctDrafted: null,
    ...over,
  }
}

// The measured shapes (2026-10-23): a veteran Yahoo takes far earlier, a rookie dynasty takes far earlier.
const durantLike = row({ name: 'Durant-like', dynasty: 80, redraft: 30, yahooAdp: 20, yahooPctDrafted: 1.0 })
const dybantsaLike = row({ name: 'Dybantsa-like', yearsExp: 0, dynasty: 5, redraft: 40, yahooAdp: 90, yahooPctDrafted: 0.8 })
const lebronLike = row({ name: 'LeBron-like', dynasty: 157.3, redraft: 60, yahooAdp: 38.9, yahooPctDrafted: 1.0 })

describe('gap', () => {
  it('is Yahoo ADP minus dynasty ADP, negative when Yahoo takes him earlier', () => {
    expect(gap(durantLike)).toBe(-60)
    expect(gap(dybantsaLike)).toBe(85)
  })

  it('is null when either side is missing, never 0 or a guess', () => {
    expect(gap(row({ dynasty: null, yahooAdp: 10 }))).toBeNull()
    expect(gap(row({ dynasty: 10, yahooAdp: null }))).toBeNull()
  })
})

describe('yahooReliable', () => {
  it('needs a Yahoo value drafted in at least the minimum share of leagues', () => {
    expect(yahooReliable(row({ yahooAdp: 50, yahooPctDrafted: 0.5 }), 0.5)).toBe(true)
    expect(yahooReliable(row({ yahooAdp: 50, yahooPctDrafted: 0.49 }), 0.5)).toBe(false)
  })

  it('does not trust a value with no drafted share at all', () => {
    expect(yahooReliable(row({ yahooAdp: 50, yahooPctDrafted: null }), 0.5)).toBe(false)
    expect(yahooReliable(row({ yahooAdp: null, yahooPctDrafted: 1 }), 0.5)).toBe(false)
  })

  it('uses 0.5 as the hand-set default', () => {
    expect(YAHOO_MIN_PCT_DEFAULT).toBe(0.5)
  })
})

describe('tintOf', () => {
  it('tints a Durant-like veteran green: Yahoo takes him much earlier than dynasty', () => {
    expect(tintOf(durantLike, GAP_THRESHOLD_DEFAULT, YAHOO_MIN_PCT_DEFAULT)).toBe('yahoo')
  })

  it('tints a Dybantsa-like rookie red: dynasty takes him much earlier than Yahoo', () => {
    expect(tintOf(dybantsaLike, GAP_THRESHOLD_DEFAULT, YAHOO_MIN_PCT_DEFAULT)).toBe('sleeper')
  })

  it('tints a LeBron-like player green: 100% drafted, Yahoo 38.9 against dynasty 157.3', () => {
    expect(gap(lebronLike)).toBeCloseTo(-118.4, 5)
    expect(tintOf(lebronLike, GAP_THRESHOLD_DEFAULT, YAHOO_MIN_PCT_DEFAULT)).toBe('yahoo')
  })

  it('never tints a player Yahoo drafted in under 50% of leagues, however large the raw gap', () => {
    const thin = row({ dynasty: 200, yahooAdp: 100, yahooPctDrafted: 0.3 })
    expect(gap(thin)).toBe(-100)
    expect(tintOf(thin, 1, 0.5)).toBeNull()
    expect(trustedGap(thin, 0.5)).toBeNull()
    expect(tintOf(row({ dynasty: 5, yahooAdp: 119, yahooPctDrafted: 0.49 }), GAP_THRESHOLD_DEFAULT, 0.5)).toBeNull()
  })

  it('raising the gap threshold removes a tint (US1 AS3)', () => {
    const p = row({ dynasty: 70, yahooAdp: 40, yahooPctDrafted: 0.9 })   // gap -30
    expect(tintOf(p, 20, 0.5)).toBe('yahoo')
    expect(tintOf(p, 40, 0.5)).toBeNull()
  })

  it('tints exactly at the threshold and not one pick short of it', () => {
    expect(tintOf(row({ dynasty: 50, yahooAdp: 30, yahooPctDrafted: 1 }), 20, 0.5)).toBe('yahoo')
    expect(tintOf(row({ dynasty: 50, yahooAdp: 30.5, yahooPctDrafted: 1 }), 20, 0.5)).toBeNull()
    expect(tintOf(row({ dynasty: 30, yahooAdp: 50, yahooPctDrafted: 1 }), 20, 0.5)).toBe('sleeper')
  })

  it('does not tint when a side is missing', () => {
    expect(tintOf(row({ dynasty: null, yahooAdp: 5, yahooPctDrafted: 1 }), 20, 0.5)).toBeNull()
    expect(tintOf(row({ dynasty: 5, yahooAdp: null, yahooPctDrafted: 1 }), 20, 0.5)).toBeNull()
  })
})

describe('gapText', () => {
  it('spells the gap out in words, with its direction', () => {
    expect(gapText(durantLike, 0.5)).toBe('Yahoo 60.0 picks earlier')
    expect(gapText(dybantsaLike, 0.5)).toBe('Yahoo 85.0 picks later')
    expect(gapText(row({ dynasty: 10, yahooAdp: 10, yahooPctDrafted: 1 }), 0.5)).toBe('level')
  })

  it('says why there is no comparison instead of printing a number', () => {
    expect(gapText(row({ dynasty: 10, yahooAdp: 100, yahooPctDrafted: 0.2 }), 0.5)).toBe('not compared')
    expect(gapText(row({ dynasty: null, yahooAdp: 100, yahooPctDrafted: 1 }), 0.5)).toBe('no dynasty ADP')
    expect(gapText(row({ dynasty: 10, yahooAdp: null }), 0.5)).toBe('not ranked by Yahoo')
  })
})

describe('sortRows', () => {
  const rows = [
    row({ name: 'B', dynasty: 20, redraft: 30, yahooAdp: 10, yahooPctDrafted: 1 }),          // gap -10
    row({ name: 'A', dynasty: 20, redraft: 25, yahooAdp: 70, yahooPctDrafted: 1 }),          // gap +50
    row({ name: 'C', dynasty: 5, redraft: null, yahooAdp: null }),                            // no yahoo
    row({ name: 'D', dynasty: null, redraft: 3, yahooAdp: 4, yahooPctDrafted: 1 }),           // no dynasty
    row({ name: 'E', dynasty: 8, redraft: 9, yahooAdp: 60, yahooPctDrafted: 0.2 }),           // unreliable yahoo
  ]
  const names = (rs: AdpRow[]) => rs.map((r) => r.name)

  it('sorts the gap either way, signed, with rows that have no trusted gap last in both directions', () => {
    expect(names(sortRows(rows, 'gap', 'asc', 0.5)).slice(0, 2)).toEqual(['B', 'A'])
    expect(names(sortRows(rows, 'gap', 'desc', 0.5)).slice(0, 2)).toEqual(['A', 'B'])
    // C (no yahoo), D (no dynasty), E (unreliable) have no trusted gap: last either way, in tie-break order.
    expect(names(sortRows(rows, 'gap', 'asc', 0.5)).slice(2)).toEqual(['C', 'E', 'D'])
    expect(names(sortRows(rows, 'gap', 'desc', 0.5)).slice(2)).toEqual(['C', 'E', 'D'])
  })

  it('breaks ties by dynasty, then redraft, then name, and keeps that order when the direction flips', () => {
    const tied = [
      row({ name: 'Z', dynasty: 10, redraft: 40 }),
      row({ name: 'M', dynasty: 10, redraft: 20 }),
      row({ name: 'A', dynasty: 5, redraft: 90 }),
      row({ name: 'B', dynasty: 10, redraft: 20 }),
    ]
    // sort by team: every team is BOS, so the whole order is the tie-break: dyn 5, then dyn 10 by redraft, then name.
    expect(names(sortRows(tied, 'team', 'asc', 0.5))).toEqual(['A', 'B', 'M', 'Z'])
    expect(names(sortRows(tied, 'team', 'desc', 0.5))).toEqual(['A', 'B', 'M', 'Z'])
  })

  it('sorts a numeric column ascending or descending with missing values last', () => {
    expect(names(sortRows(rows, 'dynasty', 'asc', 0.5))).toEqual(['C', 'E', 'A', 'B', 'D'])
    expect(names(sortRows(rows, 'dynasty', 'desc', 0.5))).toEqual(['A', 'B', 'E', 'C', 'D'])
  })

  it('does not reorder the input', () => {
    const copy = [...rows]
    sortRows(rows, 'name', 'desc', 0.5)
    expect(rows).toEqual(copy)
  })
})

// ---- Spec 046 US2: the blend and Blend $ -----------------------------------------------------------------------

const full = (name: string, dynasty: number | null, yahooAdp: number | null, over: Partial<AdpRow> = {}) =>
  row({ name, dynasty, yahooAdp, yahooPctDrafted: yahooAdp == null ? null : 1, ...over })

/** Deterministic pseudo-random numbers, so the property loops below are reproducible. */
function lcg(seed: number) {
  let x = seed
  return () => {
    x = (x * 1664525 + 1013904223) % 4294967296
    return x / 4294967296
  }
}

function population(n: number): AdpRow[] {
  const rnd = lcg(46)
  const out: AdpRow[] = []
  for (let i = 0; i < n; i++) {
    const dyn = Math.round(rnd() * 200 * 10) / 10 + 1
    const kind = rnd()
    // a spread of: Yahoo earlier, Yahoo later, Yahoo level, thin-sample Yahoo, no Yahoo, no dynasty
    const yahoo = kind < 0.4 ? dyn - rnd() * 80 : kind < 0.7 ? dyn + rnd() * 80 : kind < 0.8 ? dyn : null
    const pct = kind >= 0.7 && kind < 0.75 ? 0.2 : 1
    out.push(row({
      name: `P${String(i).padStart(2, '0')}`,
      dynasty: kind > 0.97 ? null : dyn,
      redraft: rnd() * 150,
      yahooAdp: yahoo == null ? null : Math.max(1, yahoo),
      yahooPctDrafted: yahoo == null ? null : pct,
    }))
  }
  return out
}

const favoured = (p: AdpRow) =>
  p.dynasty != null && p.yahooAdp != null && p.yahooPctDrafted != null && p.yahooPctDrafted >= 0.5 && p.yahooAdp < p.dynasty

describe('blendValue', () => {
  it('moves dynasty toward Yahoo by trust * min(|gap| / fullGap, 1)', () => {
    const p = full('P', 60, 20)   // gap -40: past fullGap 30, so the weight is trust itself
    expect(blendWeight(p, 0.75, 30, 0.5)).toBeCloseTo(0.75)
    expect(blendValue(p, 0.75, 30, 0.5)).toBeCloseTo(60 + 0.75 * (20 - 60))
    const small = full('S', 50, 40)   // gap -10: a third of fullGap, so a third of the trust
    expect(blendWeight(small, 0.6, 30, 0.5)).toBeCloseTo(0.2)
    expect(blendValue(small, 0.6, 30, 0.5)).toBeCloseTo(50 - 0.2 * 10)
  })

  it('is dynasty-only when Yahoo is unranked or under the drafted-share line, and null with no dynasty ADP', () => {
    expect(blendValue(full('N', 40, null), 1, 30, 0.5)).toBe(40)
    expect(blendValue(row({ dynasty: 40, yahooAdp: 5, yahooPctDrafted: 0.3 }), 1, 30, 0.5)).toBe(40)
    expect(blendValue(row({ dynasty: 40, yahooAdp: 5, yahooPctDrafted: null }), 1, 30, 0.5)).toBe(40)
    expect(blendValue(full('Y', null, 5), 1, 30, 0.5)).toBeNull()
  })

  it('counts a Yahoo value at exactly the line (>= minPct)', () => {
    expect(blendValue(row({ dynasty: 40, yahooAdp: 10, yahooPctDrafted: 0.5 }), 1, 30, 0.5)).toBeCloseTo(10)
  })
})

describe('blendOrder', () => {
  it('at trust 0 is the dynasty order, ties broken by redraft then name, and players with no dynasty ADP are not ranked', () => {
    const rows = [
      full('Zed', 10, 1, { redraft: 5 }),
      full('Amy', 10, 99, { redraft: 5 }),
      full('Bob', 10, 50, { redraft: 2 }),
      full('Cy', 3, 80),
      full('NoDyn', null, 1),
      full('Dee', 30, null),
    ]
    const ranked = blendOrder(rows, 0, FULL_TRUST_GAP_DEFAULT, 0.5)
    expect(ranked.map((b) => b.row.name)).toEqual(['Cy', 'Bob', 'Amy', 'Zed', 'Dee'])
    expect(ranked.map((b) => b.rank)).toEqual([1, 2, 3, 4, 5])
  })

  it('breaks an exact tie of blended values the same way: dynasty, redraft, name', () => {
    // Both blend to 20 (gaps of 20 reach fullGap 20, so the weight is trust 0.5); dynasty 10 beats 30.
    const a = full('A', 30, 10)
    const b = full('B', 10, 30)
    const ranked = blendOrder([a, b], 0.5, 20, 0.5)
    expect(ranked.map((x) => x.blend)).toEqual([20, 20])
    expect(ranked.map((x) => x.row.name)).toEqual(['B', 'A'])
  })

  it('gives a Yahoo-favoured player a blended VALUE that never rises as trust rises', () => {
    const fav = population(120).filter(favoured)
    expect(fav.length).toBeGreaterThan(20)
    for (const p of fav) {
      let prev = Infinity
      for (let t = 0; t <= 1.0001; t += 0.05) {
        const v = blendValue(p, t, 30, 0.5)!
        expect(v).toBeLessThanOrEqual(prev + 1e-9)
        prev = v
      }
    }
  })

  it('never lets a Yahoo-favoured player lose ground to a player Yahoo does not favour as trust rises', () => {
    const pop = population(120)
    const grid = [0, 0.25, 0.5, 0.75, 1]
    const positions = grid.map((t) => new Map(blendOrder(pop, t, 30, 0.5).map((b) => [b.row, b.rank])))
    let pairs = 0
    for (const f of pop.filter(favoured)) {
      for (const n of pop.filter((p) => p.dynasty != null && !favoured(p))) {
        for (let g = 1; g < grid.length; g++) {
          // ahead of him at the lower trust => still ahead at the higher
          if (positions[g - 1].get(f)! < positions[g - 1].get(n)!) {
            expect(positions[g].get(f)!).toBeLessThan(positions[g].get(n)!)
          }
          pairs++
        }
      }
    }
    expect(pairs).toBeGreaterThan(1000)
  })

  it('EXPECTED, not a bug: one Yahoo-favoured player can slip BEHIND another as trust rises (research R6 counterexample)', () => {
    // Do not "fix" this by clamping ranks: only the blended VALUE is monotonic in trust. P has a tiny gap so
    // the proportional weight barely moves him; Q has a huge gap and moves nearly the whole way, past P.
    const p = full('P', 12, 11)
    const q = full('Q', 40, 2)
    const at0 = blendOrder([p, q], 0, 30, 0.5).map((b) => b.row.name)
    const at75 = blendOrder([p, q], TRUST_DEFAULT, 30, 0.5)
    expect(at0).toEqual(['P', 'Q'])
    expect(at75.map((b) => b.row.name)).toEqual(['Q', 'P'])   // P's rank got later although P is Yahoo-favoured
    expect(at75[0].blend).toBeCloseTo(11.5)
    expect(at75[1].blend).toBeCloseTo(11.975)
    // ...and P's VALUE still did not rise: 12 -> 11.975.
    expect(blendValue(p, TRUST_DEFAULT, 30, 0.5)!).toBeLessThanOrEqual(12)
  })
})

describe('sortByBlendRank', () => {
  it('sorts by blended rank either way with unranked rows last', () => {
    const a = full('A', 5, null)
    const b = full('B', 9, null)
    const noDyn = full('NoDyn', null, 3)
    const rows = [noDyn, b, a]
    const rankOf = new Map(blendOrder(rows, 0.75, 30, 0.5).map((x) => [x.row, x]))
    expect(sortByBlendRank(rows, rankOf, 'asc').map((r) => r.name)).toEqual(['A', 'B', 'NoDyn'])
    expect(sortByBlendRank(rows, rankOf, 'desc').map((r) => r.name)).toEqual(['B', 'A', 'NoDyn'])
  })
})

describe('rescale', () => {
  const curve = [80.9, 77, 70.7, 40, 22.5, 10.2, 5, 3.3, 2, 1.4, 1, 1]

  it('adds up to teams x budget within half a dollar per roster spot after whole-dollar rounding', () => {
    for (const [teams, rounds, budget] of [[10, 23, 200], [12, 13, 200], [8, 15, 100], [4, 3, 50]]) {
      const out = rescale(curve, teams, rounds, budget)!
      const n = teams * rounds
      expect(out).toHaveLength(n)
      const sum = out.reduce((s, v) => s + v, 0)
      expect(Math.abs(sum - teams * budget)).toBeLessThanOrEqual(0.5 * n)
      expect(out.every((v) => Number.isInteger(v) && v >= 1)).toBe(true)
    }
  })

  it('keeps the curve descending and pads the unranked tail with $1', () => {
    const out = rescale(curve, 10, 23, 200)!
    for (let i = 1; i < out.length; i++) expect(out[i]).toBeLessThanOrEqual(out[i - 1])
    expect(out[out.length - 1]).toBe(1)
  })

  it('floors a $0 cost at $1 before scaling, so it contributes no surplus', () => {
    const withZero = rescale([50, 0, 0.4], 2, 2, 100)!
    const flooredAlready = rescale([50, 1, 1], 2, 2, 100)!
    expect(withZero).toEqual(flooredAlready)
    expect(withZero.every((v) => v >= 1)).toBe(true)
  })

  it('is null, never a divide by zero, for an empty curve or one with no surplus over $1', () => {
    expect(rescale([], 10, 23, 200)).toBeNull()
    expect(rescale([1, 1, 0.5], 10, 23, 200)).toBeNull()
  })

  it('is null when the draft has no budget, teams or rounds, or a budget that cannot give each spot $1', () => {
    expect(rescale(curve, 10, 23, null)).toBeNull()
    expect(rescale(curve, null, 23, 200)).toBeNull()
    expect(rescale(curve, 10, null, 200)).toBeNull()
    expect(rescale(curve, 10, 23, 0)).toBeNull()
    expect(rescale(curve, 10, 23, 10)).toBeNull()   // 10 x $10 < 230 spots x $1
  })
})
