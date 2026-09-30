import { describe, expect, it } from 'vitest'
import type { PlayerRef, RealPick, SimulationResult } from './api'
import { positionScarcity, projectedFromPick } from './scarcity'

const TEAMS = 12
const STARTERS = 10
const S = TEAMS * STARTERS // 120

const CYCLE = ['RB', 'WR', 'QB', 'TE', 'K', 'DEF'] as const

function mk(id: number, position: string): PlayerRef {
  return { id, sleeperId: String(id), name: `P${id}`, position: position as PlayerRef['position'], team: 'SEA', adp: id, positionalRank: id }
}

/** 130 players, board order; positions cycle RB,WR,QB,TE,K,DEF. */
const POOL: PlayerRef[] = Array.from({ length: 130 }, (_, i) => mk(i + 1, CYCLE[i % CYCLE.length]))

function land(pool: PlayerRef[], n: number, startNo = 1): RealPick[] {
  return pool.slice(0, n).map((player, i) => ({ pickNo: startNo + i, round: 1, slot: 1, manager: 'm', avatarId: null, player }))
}

function result(survival: Record<number, number>, pickNo: number): SimulationResult {
  return {
    availability: Object.entries(survival).map(([id, s]) => ({
      player: mk(Number(id), 'RB'),
      survivalByPick: { [String(pickNo)]: s },
    })),
  } as unknown as SimulationResult
}

const base = {
  sport: 'nfl' as const,
  pool: POOL,
  teams: TEAMS,
  startersPerTeam: STARTERS,
  landed: [] as RealPick[],
  postPickResult: null,
  myNextPick: null,
  slotKnown: false,
}

describe('positionScarcity', () => {
  it('counts the top S of the pool per position, K and DEF absent for football', () => {
    const r = positionScarcity(base)
    expect(r.S).toBe(S)
    expect(r.rows.map((x) => x.position)).toEqual(['QB', 'RB', 'WR', 'TE'])
    // 120 players cycling 6 positions = 20 each
    for (const row of r.rows) {
      expect(row.poolSize).toBe(20)
      expect(row.leftNow).toBe(20)
      expect(row.expectedAtNext).toBeNull()
      expect(row.running).toBe(false)
    }
    expect(r.definition).toBe("Starter pool: the board's top 120 (12 teams × 10 starters)")
  })

  it('has all five positions for basketball', () => {
    const nba = Array.from({ length: 130 }, (_, i) => mk(i + 1, ['PG', 'SG', 'SF', 'PF', 'C'][i % 5]))
    const r = positionScarcity({ ...base, sport: 'nba', pool: nba })
    expect(r.rows.map((x) => x.position)).toEqual(['PG', 'SG', 'SF', 'PF', 'C'])
  })

  it('reduces leftNow by landed picks, by player id', () => {
    const r = positionScarcity({ ...base, landed: land(POOL, 3) }) // RB, WR, QB gone
    const byPos = Object.fromEntries(r.rows.map((x) => [x.position, x.leftNow]))
    expect(byPos).toEqual({ QB: 19, RB: 19, WR: 19, TE: 20 })
  })

  it('does not count a landed player outside the starter pool against it', () => {
    const r = positionScarcity({ ...base, landed: land(POOL.slice(125), 1, 1) })
    expect(r.rows.every((x) => x.leftNow === 20)).toBe(true)
  })

  it('gate is off at 76 undrafted and on at 75', () => {
    const at = (undrafted: number) => {
      const landed = land(POOL, S - undrafted)
      const myNext = S - undrafted + 5
      // every undrafted pool player survives with 0.5
      const surv: Record<number, number> = {}
      for (const p of POOL.slice(S - undrafted, S)) surv[p.id] = 0.5
      return positionScarcity({ ...base, landed, postPickResult: result(surv, myNext), myNextPick: myNext, slotKnown: true })
    }
    const off = at(76)
    expect(off.rows.every((x) => x.expectedAtNext === null)).toBe(true)
    expect(off.gatedByDepth).toBe(true)
    const on = at(75)
    expect(on.rows.every((x) => x.expectedAtNext !== null)).toBe(true)
    expect(on.gatedByDepth).toBe(false)
  })

  it('expected value equals a hand-summed fixture', () => {
    // 75 undrafted: players 46..120. Pick the survival values for three RBs
    // (id%6==1 -> RB): 49, 55, 61.
    const landed = land(POOL, 45)
    const surv: Record<number, number> = { 49: 0.9, 55: 0.5, 61: 0.25, 50: 0.7 /* a WR */ }
    const r = positionScarcity({ ...base, landed, postPickResult: result(surv, 50), myNextPick: 50, slotKnown: true })
    const rb = r.rows.find((x) => x.position === 'RB')!
    const wr = r.rows.find((x) => x.position === 'WR')!
    const te = r.rows.find((x) => x.position === 'TE')!
    expect(rb.expectedAtNext).toBeCloseTo(0.9 + 0.5 + 0.25, 10)
    expect(wr.expectedAtNext).toBeCloseTo(0.7, 10)
    expect(te.expectedAtNext).toBe(0)
  })

  it('is null for an unknown seat, a missing projection, or a missing next pick', () => {
    const landed = land(POOL, 60)
    const pr = result({ 70: 0.5 }, 70)
    expect(positionScarcity({ ...base, landed, postPickResult: pr, myNextPick: 70, slotKnown: false }).rows[0].expectedAtNext).toBeNull()
    expect(positionScarcity({ ...base, landed, postPickResult: null, myNextPick: 70, slotKnown: true }).rows[0].expectedAtNext).toBeNull()
    expect(positionScarcity({ ...base, landed, postPickResult: pr, myNextPick: null, slotKnown: true }).rows[0].expectedAtNext).toBeNull()
  })

  it('marks the running position using the feed rule (4 of the last 6)', () => {
    const rbs = POOL.filter((p) => p.position === 'RB')
    const others = POOL.filter((p) => p.position === 'WR').slice(0, 2)
    const landed = land([...others, ...rbs.slice(0, 4)], 6)
    const r = positionScarcity({ ...base, landed })
    expect(r.rows.filter((x) => x.running).map((x) => x.position)).toEqual(['RB'])
  })
})

describe('projectedFromPick', () => {
  it('is S - 75 + 1, at least 1', () => {
    expect(projectedFromPick(120)).toBe(46)
    expect(projectedFromPick(60)).toBe(1)
  })
})
