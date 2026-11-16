import { describe, expect, it } from 'vitest'
import { allCopyStrings, description, steps, title } from './tmLandingCopy'
import { siteCopy } from './siteCopy'
import { makeConfig } from './site'
import { NEUTRAL_SCORING, howToPlaySteps } from './historicalCopy'
import { LEAGUE_WORDS } from './leagueWords'

// Spec 073 FR-004 / SC-001: no league wording where a Time Machine visitor can read it.

describe('tmLandingCopy', () => {
  it('has no league, mock-draft or manager wording', () => {
    for (const s of allCopyStrings()) expect(s, `"${s}"`).not.toMatch(LEAGUE_WORDS)
  })

  it('avoids accuracy, usage or testimonial claims (nothing backs them)', () => {
    for (const s of allCopyStrings()) {
      const lower = s.toLowerCase()
      for (const banned of ['accura', 'million', 'trusted by', '#1', 'monte carlo']) {
        expect(lower, `"${s}" contains "${banned}"`).not.toContain(banned)
      }
    }
  })

  it('keeps search-result lengths and names the game and Sleeper', () => {
    expect(title.length).toBeLessThanOrEqual(70)
    expect(description.length).toBeGreaterThan(70)
    expect(description.length).toBeLessThanOrEqual(160)
    expect(title).toContain('Time Machine')
    expect(description).toContain('Sleeper')
  })

  it('its how-to-play steps are the shared list, with the sport-neutral scoring line', () => {
    expect(steps).toEqual(howToPlaySteps(NEUTRAL_SCORING))
    expect(steps).toHaveLength(3)
  })
})

describe('siteCopy', () => {
  it('timemachine speaks of Time Machine, never of leagues', () => {
    const c = siteCopy(makeConfig({ VITE_SITE: 'timemachine' }))
    for (const s of [c.homeLabel, c.notFoundBody ?? '', c.signInNote, c.analyticsLine]) expect(s).not.toMatch(LEAGUE_WORDS)
    expect(c.homeLabel).toBe('Time Machine')
  })

  it('combined and leagues keep the wording the single site had', () => {
    for (const v of [undefined, 'leagues']) {
      const c = siteCopy(makeConfig({ VITE_SITE: v }))
      expect(c.homeLabel).toBe('your leagues')
      expect(c.notFoundBody).toBeNull()
      expect(c.signInNote).toContain('see your own leagues')
      expect(c.analyticsLine).toContain('which league')
    }
  })
})
