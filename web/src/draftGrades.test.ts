import { describe, expect, it } from 'vitest'
import { legendText, ordinal, productionLabel, reasonSentence, signedPoints, valueTint } from './draftGrades'

const base = { productionBasis: 'WEEKLY_AVERAGE_GAME' as const, countedWeeks: [1, 2, 3], weeksCounted: 3, weeksMissingGameData: [] as number[] }

describe('reasonSentence', () => {
  it('says what is wrong without operator language', () => {
    expect(reasonSentence('DRAFT_NOT_COMPLETE')).toContain("This draft isn't finished")
    expect(reasonSentence('NO_SCORED_WEEKS')).toContain('No weeks have been scored yet')
    expect(reasonSentence('NO_SCORED_WEEKS', [1, 2])).toBe(
      "Weeks 1, 2 are scored, but their game-by-game stats aren't loaded yet, so there's nothing to grade.",
    )
    expect(reasonSentence('NO_SCORED_WEEKS', [4])).not.toContain('No weeks have been scored')
    expect(reasonSentence('NO_SCORED_WEEKS', [1, 2])).not.toMatch(/ingest|\/api\//i)
    for (const r of ['NOT_CONFIGURED', 'DRAFT_NOT_COMPLETE', 'NO_SCORED_WEEKS', null] as const) {
      const s = reasonSentence(r)
      expect(s).not.toMatch(/ingest/i)
      expect(s).not.toContain('/api/')
    }
  })
})

describe('productionLabel', () => {
  it('names the NBA averaging and the NFL plain sum', () => {
    expect(productionLabel('WEEKLY_AVERAGE_GAME')).toBe("season points, counting each week's average game")
    expect(productionLabel('WEEKLY_GAME')).toBe('season points')
  })
})

describe('legendText', () => {
  it('NBA states only what was measured, then the week count', () => {
    const t = legendText(base)
    expect(t).toContain("Season points, counting each week's average game.")
    expect(t).toContain('In the basketball leagues measured so far, Sleeper credited one game per starter per week.')
    expect(t).toContain('Counts 3 weeks.')
  })
  it('NFL has no basketball sentence', () => {
    const t = legendText({ ...base, productionBasis: 'WEEKLY_GAME' })
    expect(t).not.toContain('basketball')
  })
  it('lists the weeks when they are not contiguous', () => {
    expect(legendText({ ...base, countedWeeks: [1, 2, 4], weeksCounted: 3 })).toContain('Counts weeks 1, 2, 4.')
  })
  it('names weeks missing game data', () => {
    expect(legendText({ ...base, weeksMissingGameData: [4] })).toContain('week 4')
  })
})

describe('valueTint', () => {
  it('is relative to the draft spread: an NBA-like spread does not saturate every cell', () => {
    // 168 values from -300 to +300 points.
    const vals = Array.from({ length: 168 }, (_, i) => -300 + (600 * i) / 167)
    const tints = vals.map((v) => valueTint(v, vals))
    const saturated = tints.filter((t) => t >= 1).length
    expect(saturated).toBeLessThan(vals.length * 0.25)
    expect(tints.every((t) => t >= 0 && t <= 1)).toBe(true)
    expect(valueTint(0, vals)).toBe(0)
    expect(valueTint(150, vals)).toBeGreaterThan(0.3)
    expect(valueTint(150, vals)).toBeLessThan(0.8)
  })
  it('handles empty and all-zero input', () => {
    expect(valueTint(5, [])).toBe(0)
    expect(valueTint(0, [0, 0])).toBe(0)
  })
})

describe('formatting', () => {
  it('signs points with a real minus', () => {
    expect(signedPoints(12.34)).toBe('+12.3')
    expect(signedPoints(-8)).toBe('−8.0')
    expect(signedPoints(0)).toBe('0.0')
  })
  it('ordinals', () => {
    expect([1, 2, 3, 4, 11, 12, 13, 21, 22, 103].map(ordinal)).toEqual(['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd', '103rd'])
  })
})
