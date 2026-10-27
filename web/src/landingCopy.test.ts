import { describe, expect, it } from 'vitest'
import { DESTINATION_GROUPS, LEAGUE_DESTINATIONS } from './destinations'
import {
  allCopyStrings, description, featureSports, groups, headingOf, platformsNote, title,
} from './landingCopy'

// Spec 050. The landing copy is hand-written, so these tests are what stop it drifting
// from the product (SC-008) or sliding back into claims it shouldn't make (FR-001/004).
describe('landingCopy', () => {
  it('every feature points at a destination that exists (SC-008)', () => {
    const keys = new Set(LEAGUE_DESTINATIONS.map((d) => d.key as string))
    for (const f of groups.flatMap((g) => g.features)) {
      expect(f.destination === 'managers' || keys.has(f.destination), f.name).toBe(true)
    }
  })

  it('every league feature sits in the same group as its rail destination (FR-003)', () => {
    for (const g of groups) {
      for (const f of g.features) {
        if (f.destination === 'managers') continue // documented exception: the rail files it under Menu
        const d = LEAGUE_DESTINATIONS.find((x) => x.key === f.destination)
        expect(d?.group, `${f.name} -> ${f.destination}`).toBe(g.key)
      }
    }
  })

  it('the groups are the four non-home rail groups, in rail order', () => {
    const expected = DESTINATION_GROUPS.filter((g) => g.key !== 'home').map((g) => g.key)
    expect(groups.map((g) => g.key)).toEqual(expected)
    for (const g of groups) expect(headingOf(g.key)).not.toBe('')
  })

  it('avoids the old simulator framing and any accuracy, usage or testimonial claim', () => {
    for (const s of allCopyStrings()) {
      const lower = s.toLowerCase()
      for (const banned of ['monte carlo', 'simulat', 'accura', 'million', 'trusted by', '#1']) {
        expect(lower, `"${s}" contains "${banned}"`).not.toContain(banned)
      }
    }
  })

  it('keeps the search-result lengths', () => {
    expect(description.length).toBeLessThanOrEqual(160)
    expect(title.length).toBeLessThanOrEqual(65)
    for (const f of groups.flatMap((g) => g.features)) expect(f.line.length, f.name).toBeLessThanOrEqual(110)
  })

  it('the title names both sports and Sleeper', () => {
    expect(title).toContain('Fantasy Football')
    expect(title).toContain('Basketball')
    expect(title).toContain('Sleeper')
  })

  it('is Sleeper-first, with ESPN only as the "some season pages" footnote (FR-009)', () => {
    expect(platformsNote).toContain('Sleeper')
    expect(platformsNote.replace('Some season pages also work for ESPN leagues', '')).not.toContain('ESPN')
    for (const s of allCopyStrings().filter((x) => x !== platformsNote)) expect(s).not.toContain('ESPN')
  })

  it('basketball-only destinations are tagged NBA only (FR-009a)', () => {
    for (const destination of ['schedule', 'trends', 'players', 'compare', 'stats', 'adp'] as const) {
      expect(featureSports({ destination }), destination).toEqual(['nba'])
    }
    expect(featureSports({ destination: 'managers' })).toEqual(['nfl', 'nba'])
    expect(featureSports({ destination: 'power' })).toEqual(['nfl', 'nba'])
  })
})
