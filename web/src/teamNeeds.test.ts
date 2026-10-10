import { describe, expect, it } from 'vitest'
import type { PlayerRef } from './api'
import { computeTeamNeeds, fitSlot, makeFitFor, needLabel, openPositions, openSlotFor } from './teamNeeds'

/**
 * teamNeeds.ts's SLOT_ELIGIBILITY mirrors FootballRules.isEligible and
 * BasketballRules.isEligible (backend sport/*.java). It was the last untested
 * mirror of a backend rule after snake.ts got one, and the untested version of
 * this exact pattern has already failed once in this repo -- DraftBoard drew
 * plain snake while the engine reversed round 3, and nothing caught it.
 *
 * The templates below are the REAL Sleeper `roster_positions` for both
 * leagues, read live 2026-09-09 from `GET /api/drafts/{id}/seats`:
 *
 *   (Foot) Ball Knowers (NFL) and West Coast Fantasy Football (NFL), identical:
 *     QB RB RB WR WR TE FLEX FLEX K DEF BN*5
 *   Ball Knowers (NBA):
 *     PG SG G SF PF F C UTIL UTIL BN*5
 */
const NFL_TEMPLATE = ['QB', 'RB', 'RB', 'WR', 'WR', 'TE', 'FLEX', 'FLEX', 'K', 'DEF',
  'BN', 'BN', 'BN', 'BN', 'BN']
const NBA_TEMPLATE = ['PG', 'SG', 'G', 'SF', 'PF', 'F', 'C', 'UTIL', 'UTIL',
  'BN', 'BN', 'BN', 'BN', 'BN']

let nextId = 1
function player(position: string, adp: number): PlayerRef {
  const id = nextId++
  return {
    id,
    sleeperId: String(id),
    name: `${position}${id}`,
    position: position as PlayerRef['position'],
    team: null,
    adp,
    positionalRank: 1,
  }
}

describe('every slot the real leagues actually use is modelled', () => {
  // The drift alarm, and the reason this file exists. An unrecognized slot
  // string renders open and is never fillable by design (computeTeamNeeds'
  // own comment) -- which is the right behaviour for a SUPER_FLEX nobody has
  // modelled, and completely silent. If Sleeper starts returning a slot these
  // leagues use and this file does not know, the needs strip quietly stops
  // being able to fill it and nothing else says so.
  it('football: the empty roster reports every starter slot as open', () => {
    const open = openPositions('nfl', computeTeamNeeds('nfl', NFL_TEMPLATE, []))
    expect([...open].sort()).toEqual(['DEF', 'FLEX', 'K', 'QB', 'RB', 'TE', 'WR'])
  })

  it('basketball: the empty roster reports every starter slot as open', () => {
    const open = openPositions('nba', computeTeamNeeds('nba', NBA_TEMPLATE, []))
    expect([...open].sort()).toEqual(['C', 'F', 'G', 'PF', 'PG', 'SF', 'SG', 'UTIL'])
  })

  it('drops BN from the strip in both sports -- bench is not a need', () => {
    expect(computeTeamNeeds('nfl', NFL_TEMPLATE, []).map((s) => s.slot)).not.toContain('BN')
    expect(computeTeamNeeds('nba', NBA_TEMPLATE, []).map((s) => s.slot)).not.toContain('BN')
    expect(computeTeamNeeds('nfl', NFL_TEMPLATE, [])).toHaveLength(10)
    expect(computeTeamNeeds('nba', NBA_TEMPLATE, [])).toHaveLength(9)
  })
})

describe('football eligibility mirrors FootballRules.isEligible', () => {
  it('seats a dedicated position in its own slot', () => {
    const qb = player('QB', 30)
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [qb])
    expect(needs.find((s) => s.slot === 'QB')?.player).toBe(qb)
    expect(openPositions('nfl', needs).has('QB')).toBe(false)
  })

  it('spills a third RB into FLEX -- RB/WR/TE are flex-eligible, QB/K/DEF are not', () => {
    const rbs = [player('RB', 1), player('RB', 2), player('RB', 3)]
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, rbs)
    const seated = needs.filter((s) => s.player).map((s) => s.slot)
    expect(seated).toEqual(['RB', 'RB', 'FLEX'])
  })

  it('does not spill a second QB into FLEX', () => {
    const qbs = [player('QB', 1), player('QB', 2)]
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, qbs)
    expect(needs.filter((s) => s.player).map((s) => s.slot)).toEqual(['QB'])
    expect(openPositions('nfl', needs).has('FLEX')).toBe(true)
  })

  it('fills dedicated slots best-ADP-first, matching the engine tie-break', () => {
    // RosterState.at() sorts by ADP before FootballRules.startingLineupValue()
    // reads it, and FLEX is filled greedily by value -- so the worse ADP is the
    // one that spills, not the one drafted later.
    const early = player('WR', 5)
    const late = player('WR', 90)
    const middle = player('WR', 40)
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [late, early, middle])
    expect(needs.filter((s) => s.slot === 'WR').map((s) => s.player)).toEqual([early, middle])
    expect(needs.find((s) => s.slot === 'FLEX')?.player).toBe(late)
  })
})

describe('basketball eligibility mirrors BasketballRules.isEligible', () => {
  it('G takes a guard that a dedicated slot could not seat; F will not', () => {
    const star = player('SG', 5)
    const spare = player('SG', 10)
    const needs = computeTeamNeeds('nba', ['SG', 'G', 'F'], [star, spare])
    // Both guards start. WHICH of the two sits in SG vs G is the backend's augmenting-path
    // order (the later one bumps the earlier into G -- BasketballRules.tryAssign), not a
    // best-ADP-first rule; only the set is meaningful, and it is what the parity fixture pins.
    expect(needs.find((s) => s.slot === 'SG')?.player).not.toBeNull()
    expect(needs.find((s) => s.slot === 'G')?.player).not.toBeNull()
    expect(new Set(needs.filter((s) => s.player).map((s) => s.player?.id))).toEqual(new Set([star.id, spare.id]))
    expect(needs.find((s) => s.slot === 'F')?.player).toBeNull()
  })

  it('F takes a forward; G will not', () => {
    const star = player('PF', 5)
    const spare = player('PF', 10)
    const needs = computeTeamNeeds('nba', ['PF', 'G', 'F'], [star, spare])
    // Same augmenting-path caveat as the guard case above.
    expect(needs.find((s) => s.slot === 'PF')?.player).not.toBeNull()
    expect(needs.find((s) => s.slot === 'F')?.player).not.toBeNull()
    expect(new Set(needs.filter((s) => s.player).map((s) => s.player?.id))).toEqual(new Set([star.id, spare.id]))
    expect(needs.find((s) => s.slot === 'G')?.player).toBeNull()
  })

  it('UTIL takes any of the five, including a centre no other pooled slot wants', () => {
    const c = player('C', 12)
    const needs = computeTeamNeeds('nba', ['G', 'F', 'UTIL'], [c])
    expect(needs.find((s) => s.slot === 'G')?.player).toBeNull()
    expect(needs.find((s) => s.slot === 'F')?.player).toBeNull()
    expect(needs.find((s) => s.slot === 'UTIL')?.player).toBe(c)
  })

  it('never seats one player twice across overlapping pooled slots', () => {
    // G, F and UTIL all draw from overlapping position sets, unlike football's
    // single FLEX pool -- the bug this guards is one PG filling G *and* UTIL.
    const pg = player('PG', 3)
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, [pg])
    const seated = needs.filter((s) => s.player?.id === pg.id).map((s) => s.slot)
    expect(seated).toEqual(['PG'])
  })

  it('walks pooled slots in template order, most specific first', () => {
    // A PG matches both G and UTIL. G comes first in the real template, so the
    // more specific slot claims him and UTIL stays open for the next player.
    const needs = computeTeamNeeds('nba', ['G', 'UTIL'], [player('PG', 3)])
    expect(needs[0].player).not.toBeNull()
    expect(needs[1].player).toBeNull()
  })

  it('fills the real nine-slot lineup from a real-shaped roster', () => {
    const roster = [
      player('PG', 1), player('SG', 2), player('SF', 3),
      player('PF', 4), player('C', 5), player('PG', 6), player('SF', 7),
    ]
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, roster)
    // Seven players, nine starting slots: everyone starts, two slots open.
    expect(needs.filter((s) => s.player).length).toBe(7)
    expect(needs.filter((s) => s.player == null).map((s) => s.slot).sort()).toEqual(['UTIL', 'UTIL'])
  })
})

describe('a slot nobody has modelled', () => {
  it('renders open but is never fillable, and never counts as a need', () => {
    // SUPER_FLEX is the live example: not in either league today, and the
    // resolution on record is "informational only, never fillable".
    const needs = computeTeamNeeds('nfl', ['QB', 'SUPER_FLEX'], [player('QB', 1), player('QB', 2)])
    expect(needs.find((s) => s.slot === 'SUPER_FLEX')?.player).toBeNull()
    expect(openPositions('nfl', needs).has('SUPER_FLEX')).toBe(false)
  })
})

/**
 * fitSlot backs the live room's pick announcement ("Fills RB2"). It is the
 * numbering that is worth pinning: the number comes from the slot's position
 * in the TEMPLATE, not from counting what the team already has, and those two
 * readings disagree the moment a player spills into FLEX.
 */
describe('naming the slot a pick fills', () => {
  it('numbers a repeated slot by which one is still open', () => {
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [player('RB', 1)])
    expect(fitSlot('nfl', player('RB', 99), needs)).toBe('RB2')
  })

  it('leaves a slot the template only has once unnumbered', () => {
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [])
    expect(fitSlot('nfl', player('TE', 99), needs)).toBe('TE')
    expect(fitSlot('nfl', player('QB', 99), needs)).toBe('QB')
  })

  it('names FLEX once the dedicated slots at that position are full', () => {
    // Two RBs seated at RB1/RB2 -- a third is a FLEX, and saying "RB3" would
    // name a slot this league does not have. Numbered because this template
    // has two flex seats, the same reason RB is: FLEX1 and FLEX2 are as real
    // and as distinguishable as RB1 and RB2.
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [player('RB', 1), player('RB', 2)])
    expect(fitSlot('nfl', player('RB', 99), needs)).toBe('FLEX1')
  })

  it('counts template position, not roster size, when a player has spilled into FLEX', () => {
    // Three RBs: two dedicated slots plus FLEX1. The fourth fills FLEX2 -- a
    // count-the-roster implementation would call it RB4.
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE,
      [player('RB', 1), player('RB', 2), player('RB', 3)])
    expect(fitSlot('nfl', player('RB', 99), needs)).toBe('FLEX2')
  })

  it('reports the most specific open slot in basketball, numbered where the template repeats', () => {
    const full = ['PG', 'SG', 'SF', 'PF', 'C'].map((p, i) => player(p, i + 1))
    // Every dedicated slot and both pooled G/F seats taken -- only UTIL is left.
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, [...full, player('PG', 6), player('SF', 7)])
    expect(fitSlot('nba', player('PG', 99), needs)).toBe('UTIL1')
    const early = computeTeamNeeds('nba', NBA_TEMPLATE, [player('PG', 1)])
    expect(fitSlot('nba', player('PG', 99), early)).toBe('G')
  })

  it('is null when the pick fills no starting slot -- the caller renders depth, not a need', () => {
    const roster = ['QB', 'RB', 'RB', 'WR', 'WR', 'TE', 'RB', 'WR', 'K', 'DEF']
      .map((p, i) => player(p, i + 1))
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, roster)
    expect(needs.every((n) => n.player != null)).toBe(true)
    expect(fitSlot('nfl', player('RB', 99), needs)).toBeNull()
  })

  it('is null for an empty template -- an unsynced league states nothing', () => {
    expect(fitSlot('nfl', player('RB', 99), computeTeamNeeds('nfl', [], []))).toBeNull()
  })
})

/**
 * Spec 025 US3: multi-position players. `multi` gives the player a `positions`
 * list; `position` stays the alphabetical-first entry, as on the wire.
 */
function multi(positions: string[], adp: number): PlayerRef {
  return { ...player(positions[0], adp), positions: positions as PlayerRef['positions'] }
}

describe('basketball lineup with multi-position players (spec 025 US3)', () => {
  it('seats a PG/SG and then a pure PG both -- the old two-pass greedy seated one fewer', () => {
    // The PG/SG is drafted first and the old pass 1 gave him the PG slot by his first
    // position; the later pure PG then spilled to G. Here the template has no G/UTIL, so
    // the old code left the pure PG on the bench.
    const roster = [multi(['PG', 'SG'], 5), multi(['PG'], 20)]
    const needs = computeTeamNeeds('nba', ['PG', 'SG'], roster)
    expect(needs.map((n) => n.player?.id)).toEqual([roster[1].id, roster[0].id])
  })

  it('the real template: PG/SG then a pure PG leaves G and UTIL open', () => {
    const roster = [multi(['PG', 'SG'], 5), multi(['PG'], 20)]
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, roster)
    expect(needs.filter((n) => n.player).length).toBe(2)
    expect(needs.filter((n) => !n.player).map((n) => n.slot)).toEqual(['G', 'SF', 'PF', 'F', 'C', 'UTIL', 'UTIL'])
  })

  it('a second C with C filled and UTIL open fills UTIL, not the filled C (A1)', () => {
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, [multi(['C'], 3)])
    expect(fitSlot('nba', multi(['C'], 12), needs)).toBe('UTIL1')
    const full = computeTeamNeeds('nba', ['PG', 'SG', 'G', 'SF', 'PF', 'F', 'C'], [multi(['C'], 3)])
    expect(fitSlot('nba', multi(['C'], 12), full)).toBeNull()
  })

  it('a multi-position player fills the slot the re-seated lineup newly fills (code-review B1)', () => {
    // The re-run seats the PG/SG at PG and shifts the pure PG to G, so G (not SG)
    // is the slot that goes open -> filled; SG stays open in the strip.
    const roster = [multi(['PG'], 3)]
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, roster)
    expect(openSlotFor('nba', multi(['PG', 'SG'], 10), needs)).toBe('G')
    expect(needLabel('nba', multi(['PG', 'SG'], 10), needs)).toBe('Fills G')
    const after = computeTeamNeeds('nba', NBA_TEMPLATE, [...roster, multi(['PG', 'SG'], 10)])
    expect(after.filter((n) => n.player == null).map((n) => n.slot)).not.toContain('G')
  })

  it('makeFitFor agrees with needLabel and caches per player', () => {
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, [multi(['PG'], 3)])
    const fit = makeFitFor('nba', needs)
    const sg = multi(['SG'], 9)
    expect(fit(sg)).toBe(needLabel('nba', sg, needs))
    expect(fit(sg)).toBe('Fills SG')
  })

  it('football fit uses the first listed position, as before', () => {
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [])
    expect(needLabel('nfl', player('WR', 99), needs)).toBe('Fills WR')
  })
})
