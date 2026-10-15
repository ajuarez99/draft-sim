import { describe, expect, it } from 'vitest'
import type { AccuracySummary } from './api'
import { accuracyClause, categoryNames, leanWords, projectionNotes, provenanceLine, reasonText, staleLine } from './projectionCopy'
import { formatIsoDate } from './pages/LeagueHome'

const NOW = new Date('2026-10-20T12:00:00Z')
const daysAgo = (n: number) => new Date(NOW.getTime() - n * 86_400_000).toISOString()

describe('projectionCopy', () => {
  it('names the scored categories the projection cannot produce, and shows an unknown key raw', () => {
    expect(categoryNames(['bonus_ast_15p', 'bonus_pt_40p', 'bonus_pt_50p', 'bonus_reb_20p', 'ff', 'tf', 'x_new'])).toEqual([
      '15+ assist bonus',
      '40+ point bonus',
      '50+ point bonus',
      '20+ rebound bonus',
      'flagrant fouls',
      'technical fouls',
      'x_new',
    ])
  })

  it('words basketball reason codes but leaves football on the server sentence', () => {
    expect(reasonText('PROJECTIONS_NOT_LOADED', 'raw', 'PER_WEEK')).toMatch(/haven't loaded/)
    expect(reasonText('PROJECTIONS_NOT_LOADED', 'no projections stored for nfl 2026', 'REST_OF_SEASON')).toBe(
      'no projections stored for nfl 2026',
    )
    expect(reasonText('SEASON_OVER', 'The season is over.', 'PER_WEEK')).toBe('The season is over.')
    expect(reasonText(null, null, 'PER_WEEK')).toBeNull()
  })

  it('reads the age against the clock it is given, and names RotoWire as the source', () => {
    const p = { source: 'rotowire', sourceModifiedAt: daysAgo(2), fetchedAt: daysAgo(0) }
    expect(provenanceLine(p, null, NOW)).toBe(
      'RotoWire projection via Sleeper · updated 2 days ago · accuracy for this league not measured',
    )
    expect(provenanceLine(null, null, NOW)).toBe('Accuracy for this league not measured')
  })

  it('names any other source as it came', () => {
    const p = { source: 'fantasypros', sourceModifiedAt: null, fetchedAt: null }
    expect(provenanceLine(p, null, NOW)).toBe('Fantasypros projection via Sleeper · accuracy for this league not measured')
  })

  describe('measured accuracy (spec 036)', () => {
    const base: AccuracySummary = {
      status: 'MEASURED', n: 612, minSamples: 500, mae: 7.26, lean: -2.1, unit: 'GAME',
      kind: 'SEASON_AVERAGE', label: 'Season average (what the rankings use)',
    }
    const p = { source: 'rotowire', sourceModifiedAt: daysAgo(0), fetchedAt: null }

    it('prints the typical miss with its sample count, a game at a time for basketball', () => {
      expect(provenanceLine(p, base, NOW)).toBe(
        'RotoWire projection via Sleeper · updated today · off by ±7.3 pts a game on average (612 games, season-average line)',
      )
    })

    it('says a week for football player-weeks', () => {
      const nfl: AccuracySummary = { ...base, unit: 'PLAYER_WEEK', kind: 'GAME', mae: 4.04, n: 410 }
      expect(accuracyClause(nfl)).toBe('off by ±4.0 pts a week on average (410 player-weeks)')
    })

    it('prints counts and NEVER a figure while it is too early', () => {
      const early: AccuracySummary = { ...base, status: 'TOO_EARLY', n: 212, mae: null, lean: null }
      const line = accuracyClause(early)
      expect(line).toBe('accuracy: too early to measure (212 of 500 games)')
      expect(line).not.toMatch(/±|off by/)
    })

    it('falls back to the not-measured wording for no snapshots and for a failed read', () => {
      expect(accuracyClause({ ...base, status: 'NO_SNAPSHOTS', n: 0, mae: null, lean: null, label: null })).toBe(
        'accuracy for this league not measured',
      )
      expect(accuracyClause(null)).toBe('accuracy for this league not measured')
    })

    it('rides inside projectionNotes, from the object the block carries', () => {
      const notes = projectionNotes({
        unit: 'PER_WEEK', basisNote: null, provenance: p, unprojectedCategories: [], staleAfterDays: 3, accuracy: base,
      }, NOW)
      expect(notes[0]).toMatch(/off by ±7\.3 pts a game/)
    })
  })

  describe('leanWords', () => {
    it('says high or low in words and carries the sign in the word, not a colour', () => {
      expect(leanWords(2.1)).toBe('runs 2.1 high')
      expect(leanWords(-2.14)).toBe('runs 2.1 low')
    })
    it('does not claim a direction for a lean that rounds to zero', () => {
      expect(leanWords(0.04)).toBe('runs even')
      expect(leanWords(-0.04)).toBe('runs even')
    })
  })

  it('flags staleness only past the threshold', () => {
    expect(staleLine({ source: null, sourceModifiedAt: daysAgo(3), fetchedAt: null }, 3, NOW)).toBeNull()
    expect(staleLine({ source: null, sourceModifiedAt: daysAgo(4), fetchedAt: null }, 3, NOW)).toBe('Projections are 4 days old.')
    expect(staleLine(null, 3, NOW)).toBeNull()
  })
})

describe('formatIsoDate', () => {
  it('formats by splitting the string, so no time zone can move the day', () => {
    expect(formatIsoDate('2026-10-20')).toBe('Oct 20')
    expect(formatIsoDate('2026-01-05')).toBe('Jan 5')
  })
  it('hands back whatever it cannot parse', () => {
    expect(formatIsoDate('soon')).toBe('soon')
  })
})

describe('categoryNames bucket families', () => {
  it('collapses a bucket family to one label', () => {
    expect(categoryNames(['pts_allow_0', 'pts_allow_35p', 'fgm_50_59', 'fgm_60p', 'fgmiss_30_39', 'yds_allow_350_399'])).toEqual([
      'points-allowed brackets', 'field goals by distance', 'missed field goals by distance', 'yards-allowed brackets',
    ])
  })
})
