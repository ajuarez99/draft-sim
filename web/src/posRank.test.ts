import { describe, expect, it } from 'vitest'
import type { PlayerRef } from './api'
import { posRank, posRankOrAdp } from './posRank'

const p = (position: PlayerRef['position'], positions: PlayerRef['position'][] | undefined, positionalRank: number, adp = 12.4) => ({
  position,
  positions,
  positionalRank,
  adp,
})

describe('posRank (spec 025 A3: no rank on any basketball badge)', () => {
  it('Edwards -> PG/SG', () => expect(posRank(p('PG', ['PG', 'SG'], 5), 'nba')).toBe('PG/SG'))
  it('Jokic, rank 2 -> bare C (no rank number)', () => expect(posRank(p('C', ['C'], 2), 'nba')).toBe('C'))
  it('Banchero stored as PF4 reads PF', () => expect(posRank(p('PF', ['PF'], 4), 'nba')).toBe('PF'))
  it('football keeps the rank: RB4', () => expect(posRank(p('RB', undefined, 4), 'nfl')).toBe('RB4'))
  it('a 999 rank is the bare label, both sports', () => {
    expect(posRank(p('RB', undefined, 999), 'nfl')).toBe('RB')
    expect(posRank(p('SG', ['SG'], 999), 'nba')).toBe('SG')
  })
  it('posRankOrAdp: basketball is "ADP n" (the pill already says the position); football RB4 / ADP fallback', () => {
    expect(posRankOrAdp(p('C', ['C'], 2), 'nba')).toBe('ADP 12')
    expect(posRankOrAdp(p('PG', ['PG', 'SG'], 999, 3.6), 'nba')).toBe('ADP 4')
    expect(posRankOrAdp(p('RB', undefined, 4), 'nfl')).toBe('RB4')
    expect(posRankOrAdp(p('RB', undefined, 999), 'nfl')).toBe('ADP 12')
  })
})
