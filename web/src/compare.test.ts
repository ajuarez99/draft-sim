import { describe, expect, it } from 'vitest'
import { TOTAL_ROW, WINDOW_ORDER, boxRows, markRow, windowNotes, windowRows, type Row } from './compare'
import type { PlayerStatsPage, PlayerWindow } from './api'
import { OK_QUAL, rate, statsPage, win } from './testPlayerStats'

/**
 * Bug class #1 (AGENTS.md): a marking rule can be well-formed and pointed backwards, and structural tests don't
 * notice. Each direction below is a preference-ordering assertion run both ways round, so flipping a row's
 * direction (or the comparison in markRow) fails here.
 */

const rows: Row[] = [...WINDOW_ORDER.flatMap((w) => windowRows(w)), TOTAL_ROW, ...WINDOW_ORDER.flatMap((w) => boxRows(w))]
const row = (key: string) => {
  const r = rows.find((x) => x.key === key)
  if (!r) throw new Error(`no row ${key}`)
  return r
}

const fantasy = (season: number, l10 = season, l5 = season, total = 1800): PlayerStatsPage['fantasy'] => ({
  ...statsPage().fantasy!,
  fpPerGame: { SEASON: season, LAST_10: l10, LAST_5: l5 },
  seasonTotal: total,
})

/** A page whose SEASON window is `w`; the last-N windows stay full and fresh. */
const withSeason = (w: PlayerWindow, over: Partial<PlayerStatsPage> = {}) =>
  statsPage({ windows: { SEASON: w, LAST_10: win({ games: 10 }), LAST_5: win({ games: 5 }) }, ...over })

describe('markRow preference ordering', () => {
  it('the higher fantasy points per game wins, whichever side it is on', () => {
    const hi = statsPage({ fantasy: fantasy(30, 31, 32) })
    const lo = statsPage({ fantasy: fantasy(25, 26, 27) })
    expect(markRow(row('SEASON.fp'), hi, lo).mark).toBe('A')
    expect(markRow(row('SEASON.fp'), lo, hi).mark).toBe('B')
    expect(markRow(row('LAST_10.fp'), hi, lo).mark).toBe('A')
  })

  it('the higher counting stat and minutes win', () => {
    const a = withSeason(win({ pg: { pts: 25, reb: 4 }, minutesPerGame: 34 }))
    const b = withSeason(win({ pg: { pts: 20, reb: 9 }, minutesPerGame: 30 }))
    expect(markRow(row('SEASON.pts'), a, b).mark).toBe('A')
    expect(markRow(row('SEASON.reb'), a, b).mark).toBe('B')
    expect(markRow(row('SEASON.mpg'), a, b).mark).toBe('A')
    expect(markRow(row('SEASON.mpg'), b, a).mark).toBe('B')
  })

  it('the LOWER turnover rate wins', () => {
    const careful = withSeason(win({ adv: { tovPct: rate(10) } }))
    const loose = withSeason(win({ adv: { tovPct: rate(15) } }))
    expect(markRow(row('SEASON.tovPct'), careful, loose).mark).toBe('A')
    expect(markRow(row('SEASON.tovPct'), loose, careful).mark).toBe('B')
  })
})

describe('markRow: rows that are never marked', () => {
  it('two values that print the same are unmarked (12.04 vs 12.03 are both "12.0")', () => {
    const a = withSeason(win({ pg: { pts: 12.04 } }))
    const b = withSeason(win({ pg: { pts: 12.03 } }))
    expect(markRow(row('SEASON.pts'), a, b)).toEqual({ mark: null, withheld: {} })
  })

  it('a 1-of-1 three-point percentage against 38 of 100 is unmarked (F4)', () => {
    const a = withSeason(win({ shooting: { fgPct: rate(44), tpPct: rate(100), ftPct: rate(70) }, totals: { ...win().totals, tpm: 1, tpa: 1 } }))
    const b = withSeason(win({ shooting: { fgPct: rate(44), tpPct: rate(38), ftPct: rate(70) }, totals: { ...win().totals, tpm: 38, tpa: 100 } }))
    expect(markRow(row('SEASON.tpPct'), a, b).mark).toBeNull()
    expect(markRow(row('SEASON.tpPct'), b, a).mark).toBeNull()
    expect(row('SEASON.tpPct').sub?.(a)).toBe('1-1')
    expect(row('SEASON.tpPct').sub?.(b)).toBe('38-100')
  })

  it('turnovers per game, 0.6 against 3.4, are unmarked (F5)', () => {
    const a = withSeason(win({ pg: { tov: 0.6 } }))
    const b = withSeason(win({ pg: { tov: 3.4 } }))
    expect(markRow(row('SEASON.tov'), a, b).mark).toBeNull()
    expect(markRow(row('SEASON.tov'), b, a).mark).toBeNull()
    expect(markRow(row('SEASON.pf'), a, b).mark).toBeNull()
  })

  it('games in the season, 4 against 3, are unmarked (F6), and so is the season total', () => {
    const a = withSeason(win({ games: 4 }))
    const b = withSeason(win({ games: 3 }))
    expect(markRow(row('SEASON.games'), a, b).mark).toBeNull()
    const big = statsPage({ fantasy: fantasy(30, 31, 32, 2400) })
    expect(markRow(TOTAL_ROW, big, statsPage()).mark).toBeNull()
  })

  it('a Rate with a reason reads as missing, so the row is unmarked', () => {
    const a = withSeason(win({ adv: { tovPct: rate(null, 'NO_TEAM_ROW') } }))
    const b = withSeason(win({ adv: { tovPct: rate(12) } }))
    expect(markRow(row('SEASON.tovPct'), a, b).mark).toBeNull()
    expect(markRow(row('SEASON.tovPct'), b, a).mark).toBeNull()
  })
})

describe('markRow: windows that cannot be compared are withheld, with the reason', () => {
  it('a LAST_10 with 3 games is withheld as "3 of 10 games"', () => {
    const short = statsPage({
      windows: { SEASON: win(), LAST_10: win({ games: 3 }), LAST_5: win({ games: 5 }) },
      windowQualification: { ...OK_QUAL, LAST_10: { qualified: false, reason: 'NOT_QUALIFIED', lastN: 10 } },
    })
    const m = markRow(row('LAST_10.fp'), short, statsPage({ fantasy: fantasy(1, 5, 5) }))
    expect(m.mark).toBeNull()
    expect(m.withheld).toEqual({ A: '3 of 10 games' })
  })

  it('a stale LAST_5 is withheld, naming how far behind it is', () => {
    const stale = statsPage({
      windows: { SEASON: win(), LAST_10: win({ games: 10 }), LAST_5: win({ games: 5, lastGameDate: '2026-03-01' }) },
      windowQualification: { ...OK_QUAL, LAST_5: { qualified: false, reason: 'NOT_QUALIFIED_STALE', lastN: 5 } },
    })
    const m = markRow(row('LAST_5.pts'), statsPage(), stale)
    expect(m.mark).toBeNull()
    expect(m.withheld.B).toBe('last game 42 days before the newest')
    expect(m.withheld.A).toBeUndefined()
  })

  it('a stale LAST_10 that is also under the minutes floor is withheld: the server says NOT_QUALIFIED but stale (review B2)', () => {
    const benched = statsPage({
      windows: { SEASON: win(), LAST_10: win({ games: 10, minutes: 120, minutesPerGame: 12, lastGameDate: '2026-01-01' }), LAST_5: win({ games: 5 }) },
      windowQualification: { ...OK_QUAL, LAST_10: { qualified: false, reason: 'NOT_QUALIFIED', lastN: 10, stale: true } },
    })
    const m = markRow(row('LAST_10.fp'), benched, statsPage({ fantasy: fantasy(1, 5, 5) }))
    expect(m.mark).toBeNull()
    expect(m.withheld.A).toMatch(/^last game /)
    expect(m.withheld.B).toBeUndefined()
  })

  it('an older backend with no `stale` still withholds on the STALE reason', () => {
    const old = statsPage({
      windows: { SEASON: win(), LAST_10: win({ games: 10 }), LAST_5: win({ games: 5, lastGameDate: '2026-03-01' }) },
      windowQualification: { ...OK_QUAL, LAST_5: { qualified: false, reason: 'NOT_QUALIFIED_STALE', lastN: 5 } },
    })
    expect(markRow(row('LAST_5.pts'), statsPage(), old).withheld.B).toMatch(/^last game /)
  })

  it('an empty window reads "no games", and a short window names its games before its minutes (review S2)', () => {
    const none = withSeason(win({ games: 0, perGame: null, minutes: 0, minutesPerGame: 0, smallSample: true }))
    expect(markRow(row('SEASON.fp'), none, statsPage()).withheld).toEqual({ A: 'no games' })
    const short = statsPage({
      windows: { SEASON: win(), LAST_10: win({ games: 3, minutes: 84, smallSample: true }), LAST_5: win({ games: 5 }) },
      windowQualification: { ...OK_QUAL, LAST_10: { qualified: false, reason: 'NOT_QUALIFIED', lastN: 10 } },
    })
    expect(markRow(row('LAST_10.fp'), short, statsPage()).withheld).toEqual({ A: '3 of 10 games' })
    expect(windowNotes(short, statsPage(), 'LAST_10')).toEqual({ A: '3 of 10 games' })
    expect(windowNotes(short, statsPage({ dataAsOf: '2026-01-01T00:00:00Z' }), 'LAST_10')).toEqual({})
  })

  it('a small-sample SEASON window is withheld, with its minutes', () => {
    const tiny = withSeason(win({ smallSample: true, minutes: 84, pg: { pts: 40 } }))
    const m = markRow(row('SEASON.pts'), tiny, statsPage())
    expect(m.mark).toBeNull()
    expect(m.withheld).toEqual({ A: 'small sample (84 min)' })
  })

  it('a response with no windowQualification (an older backend) withholds last-N rows only', () => {
    const old = (id: string, pts: number) => {
      const p = statsPage({
        id,
        windows: { SEASON: win({ pg: { pts } }), LAST_10: win({ games: 10, pg: { pts } }), LAST_5: win({ games: 5, pg: { pts } }) },
      })
      delete p.windowQualification
      return p
    }
    const a = old('a', 25)
    const b = old('b', 20)
    expect(markRow(row('SEASON.pts'), a, b).mark).toBe('A')
    const m = markRow(row('LAST_10.pts'), a, b)
    expect(m.mark).toBeNull()
    expect(m.withheld.A).toBeDefined()
    expect(m.withheld.B).toBeDefined()
  })
})

describe('markRow: the two pages must describe the same data', () => {
  it('a dataAsOf mismatch withholds every row', () => {
    const a = statsPage({ fantasy: fantasy(40) })
    const b = statsPage({ dataAsOf: '2026-04-14T03:00:00Z' })
    for (const r of rows) expect(markRow(r, a, b).mark, r.key).toBeNull()
    expect(markRow(row('SEASON.fp'), a, statsPage()).mark).toBe('A')
  })

  it('a season mismatch withholds every row', () => {
    const a = statsPage({ fantasy: fantasy(40) })
    expect(markRow(row('SEASON.fp'), a, statsPage({ season: 2024 })).mark).toBeNull()
  })

  it('an unavailable or missing page marks nothing', () => {
    expect(markRow(row('SEASON.fp'), statsPage(), null).mark).toBeNull()
    expect(markRow(row('SEASON.fp'), statsPage(), statsPage({ available: false, reason: 'NOT_BASKETBALL' })).mark).toBeNull()
  })
})
