import { describe, expect, it } from 'vitest'
import type { PlayerRef } from './api'
import { computeTeamNeeds, fitSlot, openPositions } from './teamNeeds'

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
    const spare = player('SG', 10)
    const needs = computeTeamNeeds('nba', ['SG', 'G', 'F'], [player('SG', 5), spare])
    expect(needs.find((s) => s.slot === 'G')?.player).toBe(spare)
    expect(needs.find((s) => s.slot === 'F')?.player).toBeNull()
  })

  it('F takes a forward; G will not', () => {
    const spare = player('PF', 10)
    const needs = computeTeamNeeds('nba', ['PF', 'G', 'F'], [player('PF', 5), spare])
    expect(needs.find((s) => s.slot === 'F')?.player).toBe(spare)
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
    expect(fitSlot('nfl', 'RB', needs)).toBe('RB2')
  })

  it('leaves a slot the template only has once unnumbered', () => {
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [])
    expect(fitSlot('nfl', 'TE', needs)).toBe('TE')
    expect(fitSlot('nfl', 'QB', needs)).toBe('QB')
  })

  it('names FLEX once the dedicated slots at that position are full', () => {
    // Two RBs seated at RB1/RB2 -- a third is a FLEX, and saying "RB3" would
    // name a slot this league does not have. Numbered because this template
    // has two flex seats, the same reason RB is: FLEX1 and FLEX2 are as real
    // and as distinguishable as RB1 and RB2.
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, [player('RB', 1), player('RB', 2)])
    expect(fitSlot('nfl', 'RB', needs)).toBe('FLEX1')
  })

  it('counts template position, not roster size, when a player has spilled into FLEX', () => {
    // Three RBs: two dedicated slots plus FLEX1. The fourth fills FLEX2 -- a
    // count-the-roster implementation would call it RB4.
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE,
      [player('RB', 1), player('RB', 2), player('RB', 3)])
    expect(fitSlot('nfl', 'RB', needs)).toBe('FLEX2')
  })

  it('reports the most specific open slot in basketball, numbered where the template repeats', () => {
    const full = ['PG', 'SG', 'SF', 'PF', 'C'].map((p, i) => player(p, i + 1))
    // Every dedicated slot and both pooled G/F seats taken -- only UTIL is left.
    const needs = computeTeamNeeds('nba', NBA_TEMPLATE, [...full, player('PG', 6), player('SF', 7)])
    expect(fitSlot('nba', 'PG', needs)).toBe('UTIL1')
    const early = computeTeamNeeds('nba', NBA_TEMPLATE, [player('PG', 1)])
    expect(fitSlot('nba', 'PG', early)).toBe('G')
  })

  it('is null when the pick fills no starting slot -- the caller renders depth, not a need', () => {
    const roster = ['QB', 'RB', 'RB', 'WR', 'WR', 'TE', 'RB', 'WR', 'K', 'DEF']
      .map((p, i) => player(p, i + 1))
    const needs = computeTeamNeeds('nfl', NFL_TEMPLATE, roster)
    expect(needs.every((n) => n.player != null)).toBe(true)
    expect(fitSlot('nfl', 'RB', needs)).toBeNull()
  })

  it('is null for an empty template -- an unsynced league states nothing', () => {
    expect(fitSlot('nfl', 'RB', computeTeamNeeds('nfl', [], []))).toBeNull()
  })
})
