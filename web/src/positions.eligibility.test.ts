import { describe, expect, it } from 'vitest'
import type { PlayerRef } from './api'
import { eligiblePositions, familyCode, isMultiPosition, leadHueStyle, multiVars, positionLabel, posPill } from './positions'

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
  it('football ignores a stored list: primary only (review R2)', () => expect(positionLabel(p('RB', ['WR', 'RB']), 'nfl')).toBe('RB'))
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

describe('football is single-position by rule (review R2)', () => {
  it('an NFL player listed at two positions is his primary only', () => {
    expect(eligiblePositions(p('TE', ['TE', 'WR']), 'nfl')).toEqual(['TE'])
    expect(isMultiPosition(p('TE', ['TE', 'WR']), 'nfl')).toBe(false)
    expect(posPill(p('TE', ['TE', 'WR']), 'nfl')).toEqual({ className: 'pos TE', style: undefined })
  })
})

describe('multiVars colours', () => {
  it('four positions get four colours and quarter stops (review N2)', () => {
    const s = multiVars(p('PF', ['PF', 'PG', 'SF', 'SG']), 'nba') as Record<string, string>
    expect(s['--pos-a']).toBe('var(--pg)')
    expect(s['--pos-b']).toBe('var(--sg)')
    expect(s['--pos-c']).toBe('var(--sf)')
    expect(s['--pos-d']).toBe('var(--pf)')
    expect([s['--pos-s1'], s['--pos-s2'], s['--pos-s3']]).toEqual(['25%', '50%', '75%'])
  })
  it('two and three positions leave --pos-d unset', () => {
    const two = multiVars(p('PG', ['PG', 'SG']), 'nba') as Record<string, string>
    expect(two['--pos-d']).toBeUndefined()
    expect(two['--pos-s1']).toBe('50%')
    const three = multiVars(p('PF', ['PF', 'PG', 'SF']), 'nba') as Record<string, string>
    expect(three['--pos-d']).toBeUndefined()
    expect([three['--pos-s1'], three['--pos-s2'], three['--pos-s3']]).toEqual(['34%', '67%', '100%'])
  })
})

describe('an NBA player with no position gets no football colours (review N3)', () => {
  const none = p('WR')
  it('pill is a bare `pos`, not `pos WR`', () => expect(posPill(none, 'nba')).toEqual({ className: 'pos', style: undefined }))
  it('no lead hue', () => expect(leadHueStyle(none, 'nba')).toBeUndefined())
  it('football WR is unchanged', () => {
    expect(posPill(none, 'nfl').className).toBe('pos WR')
    expect(leadHueStyle(none, 'nfl')).toEqual({ '--lead-hue': 'var(--wr)' })
  })
})
