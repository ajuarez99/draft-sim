import { describe, expect, it } from 'vitest'
import { categoryNames, provenanceLine, reasonText, staleLine } from './projectionCopy'
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

  it('reads the age against the clock it is given', () => {
    const p = { source: 'sleeper', sourceModifiedAt: daysAgo(2), fetchedAt: daysAgo(0) }
    expect(provenanceLine(p, NOW)).toBe('Source: sleeper · updated 2 days ago · accuracy for this league not measured')
    expect(provenanceLine(null, NOW)).toBe('Accuracy for this league not measured')
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
