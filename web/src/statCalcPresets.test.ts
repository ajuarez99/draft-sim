import { describe, expect, it } from 'vitest'
import { PRESETS } from './statCalcPresets'
import { canonical, inferUnits, parse, validate } from './statFormula'

/** specs/032 T019. */
describe('PRESETS', () => {
  it('has the five presets of research R11, with unique ids', () => {
    expect(PRESETS.map((p) => p.name)).toEqual([
      'Usage value', 'Assist-to-turnover', 'Stocks per 36', 'Efficient volume', 'Fantasy points per usage point',
    ])
    expect(new Set(PRESETS.map((p) => p.id)).size).toBe(5)
  })

  for (const p of PRESETS) {
    describe(p.name, () => {
      const r = parse(p.expr)
      it('parses, in canonical spelling', () => {
        expect(r.ok).toBe(true)
        if (r.ok) expect(canonical(r.ast)).toBe(p.expr)
      })
      it('mixesUnits matches what inferUnits finds', () => {
        if (r.ok) expect(p.mixesUnits).toBe(inferUnits(r.ast, p.mode).length > 0)
      })
      it('is valid with standardize off', () => {
        if (r.ok) expect(validate(r.ast, { standardize: false })).toEqual([])
      })
      it('has a description', () => expect(p.description.length).toBeGreaterThan(20))
    })
  }

  it('Usage value says the brackets were added, to which request, and the precedence reading', () => {
    const d = PRESETS[0].description
    expect(d).toContain('Brackets were added')
    expect(d).toContain('usage rate + vorp / turnovers')
    expect(d).toContain('USG + (VOR ÷ TO)')
  })

  it('the two usage presets say usage measures role, not quality; the others do not need to', () => {
    for (const id of ['usage-value', 'fp-per-usage']) {
      expect(PRESETS.find((p) => p.id === id)!.description).toContain('role, not his quality')
    }
  })

  it('Stocks per 36 is a per 36 preset', () => {
    expect(PRESETS.find((p) => p.id === 'stocks-36')!.mode).toBe('per36')
  })
})
