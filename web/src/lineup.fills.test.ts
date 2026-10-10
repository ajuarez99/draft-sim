import { describe, expect, it } from 'vitest'
import type { PlayerRef } from './api'
import { canJoin, seatLineup } from './lineup'
import { openAfter, fillsFor, summarize } from './pickInsight'
import { fitSlot, computeTeamNeeds, makeFitFor } from './teamNeeds'
import fixture from './__fixtures__/nba-lineup-parity.json'

/**
 * Code-review B1 (spec 025): "Fills X" must name the slot that goes from open to
 * filled when the lineup is RE-SEATED with the newcomer -- i.e. the slot the
 * team strip then shows as taken -- not merely an open slot he is eligible for.
 */

type FxPlayer = { id: number; positions: string[]; adp: number }
type FxCase = { name: string; players: FxPlayer[] }
const fx = fixture as { template: string[]; cases: FxCase[] }
const TEMPLATE = [...fx.template, 'BN', 'BN', 'BN', 'BN', 'BN']
const BITS = ['PG', 'SG', 'SF', 'PF', 'C']

function ref(p: FxPlayer): PlayerRef {
  return {
    id: p.id,
    sleeperId: String(p.id),
    name: `P${p.id}`,
    position: p.positions[0] as PlayerRef['position'],
    positions: p.positions as PlayerRef['positions'],
    team: null,
    adp: p.adp,
    positionalRank: 1,
  }
}

function probe(mask: number): PlayerRef {
  const positions = BITS.filter((_, b) => (mask & (1 << b)) !== 0)
  return ref({ id: 900000 + mask, positions, adp: 9999 })
}

/** Indices of slots empty before and filled after re-seating with `extra` appended. */
function newlyFilled(players: PlayerRef[], extra: PlayerRef): number[] {
  const before = seatLineup(TEMPLATE, players)
  const after = seatLineup(TEMPLATE, [...players, extra])
  return before.slots.map((_, i) => i).filter((i) => before.seats[i] == null && after.seats[i] != null)
}

describe('B1: the named slot is the one the re-seated lineup newly fills', () => {
  it("the review's case: pure PG on the roster, then a PG/SG -> names the slot the strip fills", () => {
    const pg = ref({ id: 1, positions: ['PG'], adp: 5 })
    const pgsg = ref({ id: 2, positions: ['PG', 'SG'], adp: 20 })
    const lineup = seatLineup(TEMPLATE, [pg])
    const r = canJoin(lineup, pgsg)
    expect(r.ok).toBe(true)

    const filled = newlyFilled([pg], pgsg)
    expect(filled).toEqual([r.index])
    expect(lineup.slots[r.index]).toBe(r.slot)

    const afterOpen = openAfter('nba', TEMPLATE, [pg], pgsg)
    // The named slot kind is no longer open in the strip once the pick lands.
    const beforeOpen = computeTeamNeeds('nba', TEMPLATE, [pg]).filter((n) => n.player == null)
    const countOf = (xs: string[], s: string) => xs.filter((x) => x === s).length
    expect(countOf(afterOpen, r.slot!)).toBe(countOf(beforeOpen.map((n) => n.slot), r.slot!) - 1)
  })

  it('the live card never lists the named slot as still open (single-instance slots)', () => {
    const pg = ref({ id: 1, positions: ['PG'], adp: 5 })
    const pgsg = ref({ id: 2, positions: ['PG', 'SG'], adp: 20 })
    const fills = fillsFor('nba', TEMPLATE, [pg], pgsg)
    const open = openAfter('nba', TEMPLATE, [pg], pgsg)
    expect(fills).not.toBeNull()
    expect(open).not.toContain(fills)
    expect(summarize('nba', fills, open, [pg, pgsg])).not.toMatch(new RegExp(`Fills ${fills}\\..*Still open:.*\\b${fills}\\b`))
  })

  it('the mirror case: an SF on the roster, then a PF/SF', () => {
    const sf = ref({ id: 1, positions: ['SF'], adp: 5 })
    const pfsf = ref({ id: 2, positions: ['SF', 'PF'], adp: 20 })
    const fills = fillsFor('nba', TEMPLATE, [sf], pfsf)
    const open = openAfter('nba', TEMPLATE, [sf], pfsf)
    expect(fills).not.toBeNull()
    expect(open).not.toContain(fills)
  })

  it('fitSlot numbers by the same index (second UTIL when the second UTIL is the one filled)', () => {
    // Fill every dedicated/pooled slot so only the two UTILs are left.
    const roster = [
      ref({ id: 1, positions: ['PG'], adp: 1 }),
      ref({ id: 2, positions: ['SG'], adp: 2 }),
      ref({ id: 3, positions: ['PG'], adp: 3 }),
      ref({ id: 4, positions: ['SF'], adp: 4 }),
      ref({ id: 5, positions: ['PF'], adp: 5 }),
      ref({ id: 6, positions: ['SF'], adp: 6 }),
      ref({ id: 7, positions: ['C'], adp: 7 }),
      ref({ id: 8, positions: ['C'], adp: 8 }),
    ]
    const needs = computeTeamNeeds('nba', TEMPLATE, roster)
    expect(needs.filter((n) => n.player == null).map((n) => n.slot)).toEqual(['UTIL'])
    const cand = ref({ id: 9, positions: ['C'], adp: 30 })
    expect(fitSlot('nba', cand, needs)).toBe('UTIL2')
    // and the bare-name tag agrees about the kind
    expect(makeFitFor('nba', needs)(cand)).toBe('Fills UTIL')
  })

  for (const c of fx.cases) {
    it(`property: ${c.name} -- for all 32 masks, ok => the named slot is newly filled`, () => {
      const players = c.players.map(ref)
      const lineup = seatLineup(TEMPLATE, players)
      for (let m = 0; m < 32; m++) {
        const p = probe(m)
        const r = canJoin(lineup, p)
        if (!r.ok) {
          expect(r.slot).toBeNull()
          continue
        }
        const filled = newlyFilled(players, p)
        expect(filled, `mask ${m}`).toEqual([r.index])
        expect(lineup.slots[r.index]).toBe(r.slot)
        expect(lineup.seats[r.index]).toBeNull()
      }
    })
  }
})
