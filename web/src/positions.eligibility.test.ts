import { describe, expect, it } from 'vitest'
import type { PlayerRef } from './api'
import { eligiblePositions, familyCode, isMultiPosition, positionLabel } from './positions'

const p = (position: PlayerRef['position'], positions?: PlayerRef['position'][]) => ({ position, positions })

describe('positionLabel / eligiblePositions', () => {
  it('Edwards PG/SG', () => expect(positionLabel(p('PG', ['PG', 'SG']), 'nba')).toBe('PG/SG'))
  it('Durant sorted SF/PF', () => expect(positionLabel(p('PF', ['PF', 'SF']), 'nba')).toBe('SF/PF'))
  it('LeBron PG/SF/PF', () => expect(positionLabel(p('PF', ['PF', 'PG', 'SF']), 'nba')).toBe('PG/SF/PF'))
  it('missing positions falls back to [position]', () => {
    expect(eligiblePositions(p('SG'), 'nba')).toEqual(['SG'])
    expect(positionLabel(p('SG'), 'nba')).toBe('SG')
  })
  it('empty positions falls back to [position]', () => expect(eligiblePositions(p('C', []), 'nba')).toEqual(['C']))
  it('football RB', () => expect(positionLabel(p('RB', ['RB']), 'nfl')).toBe('RB'))
  it('football keeps stored order', () => expect(positionLabel(p('RB', ['WR', 'RB']), 'nfl')).toBe('WR/RB'))
  it('NBA WR fallback is no position', () => {
    expect(eligiblePositions(p('WR'), 'nba')).toEqual([])
    expect(positionLabel(p('WR'), 'nba')).toBe('')
    expect(eligiblePositions(p('WR'), 'nfl')).toEqual(['WR'])
  })
  it('isMultiPosition', () => {
    expect(isMultiPosition(p('PG', ['PG', 'SG']), 'nba')).toBe(true)
    expect(isMultiPosition(p('PG', ['PG']), 'nba')).toBe(false)
    expect(isMultiPosition(p('PG'), 'nba')).toBe(false)
  })
})

describe('familyCode', () => {
  it('PG/SG -> G', () => expect(familyCode(p('PG', ['PG', 'SG']), 'nba')).toBe('G'))
  it('SF/PF -> F', () => expect(familyCode(p('PF', ['PF', 'SF']), 'nba')).toBe('F'))
  it('C/PF -> F/C', () => expect(familyCode(p('C', ['C', 'PF']), 'nba')).toBe('F/C'))
  it('PF/SF/SG -> G/F', () => expect(familyCode(p('PF', ['PF', 'SF', 'SG']), 'nba')).toBe('G/F'))
  it('PG -> PG', () => expect(familyCode(p('PG', ['PG']), 'nba')).toBe('PG'))
})
