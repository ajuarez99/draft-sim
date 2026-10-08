import { describe, expect, it } from 'vitest'
import {
  COLUMNS,
  GROUPS,
  LEADER_CATEGORIES,
  NO_FILTERS,
  applyFilters,
  categoryLeaders,
  columnLabel,
  compareRows,
  defaultDir,
  formatValue,
  hasKnownOwnership,
  resolveSort,
  sortOffered,
  sortRows,
  toggleSort,
} from './statLeaderboard'
import { owner, rate, row, win } from './testStatBoard'

const ids = (rs: { sleeperPlayerId: string }[]) => rs.map((r) => r.sleeperPlayerId)

describe('comparator (I4)', () => {
  const rows = [
    row('c', { name: 'Zed', fpPerGame: 30 }),
    row('a', { name: 'Amy', fpPerGame: 30 }),
    row('b', { name: 'Amy', fpPerGame: 30 }),
    row('d', { name: 'Dan', fpPerGame: 30, stats: win({ games: 70 }) }),
    row('e', { name: 'Eve', fpPerGame: 45 }),
  ]

  it('breaks ties on games desc, then name asc, then id asc, whatever the input order', () => {
    const want = ['e', 'd', 'a', 'b', 'c']
    expect(ids(sortRows(rows, { col: 'fp', dir: 'desc' }, 'perGame'))).toEqual(want)
    expect(ids(sortRows([...rows].reverse(), { col: 'fp', dir: 'desc' }, 'perGame'))).toEqual(want)
  })

  it('puts missing values last in both directions', () => {
    const rs = [row('x', { fpPerGame: null }), row('y', { fpPerGame: 5 }), row('z', { fpPerGame: 9 })]
    expect(ids(sortRows(rs, { col: 'fp', dir: 'desc' }, 'perGame'))).toEqual(['z', 'y', 'x'])
    expect(ids(sortRows(rs, { col: 'fp', dir: 'asc' }, 'perGame'))).toEqual(['y', 'z', 'x'])
  })

  it('puts a rate with a reason last, not first as a zero', () => {
    const rs = [
      row('n', { stats: win({ shooting: { fgPct: rate(null, 'NO_ATTEMPTS'), tpPct: rate(1), ftPct: rate(1) } }) }),
      row('p', { stats: win({ shooting: { fgPct: rate(40), tpPct: rate(1), ftPct: rate(1) } }) }),
    ]
    expect(ids(sortRows(rs, { col: 'fgPct', dir: 'asc' }, 'perGame'))).toEqual(['p', 'n'])
  })

  it('orders text columns A to Z and keeps a missing name last', () => {
    const rs = [row('1', { name: null }), row('2', { name: 'Bo' }), row('3', { name: 'Al' })]
    expect(ids(sortRows(rs, { col: 'name', dir: 'asc' }, 'perGame'))).toEqual(['3', '2', '1'])
  })

  it('does not mutate its input', () => {
    const copy = [...rows]
    sortRows(rows, { col: 'fp', dir: 'asc' }, 'perGame')
    expect(rows).toEqual(copy)
  })

  it('is a total order: comparing a row with itself is 0', () => {
    expect(compareRows(COLUMNS.fp, 'desc', 'perGame')(rows[0], rows[0])).toBe(0)
  })
})

describe('direction', () => {
  it('starts best-first: high for most, low for TOV%, ranks, pick and ADP, A-Z for text', () => {
    expect(defaultDir(COLUMNS.pts)).toBe('desc')
    expect(defaultDir(COLUMNS.tovPct)).toBe('asc')
    expect(defaultDir(COLUMNS.leagueRank)).toBe('asc')
    expect(defaultDir(COLUMNS.pick)).toBe('asc')
    expect(defaultDir(COLUMNS.adp)).toBe('asc')
    expect(defaultDir(COLUMNS.name)).toBe('asc')
  })

  it('a header click flips the same column and resets direction on a new one', () => {
    expect(toggleSort({ col: 'pts', dir: 'desc' }, 'pts')).toEqual({ col: 'pts', dir: 'asc' })
    expect(toggleSort({ col: 'pts', dir: 'asc' }, 'tovPct')).toEqual({ col: 'tovPct', dir: 'asc' })
    expect(toggleSort({ col: 'pts', dir: 'asc' }, 'reb')).toEqual({ col: 'reb', dir: 'desc' })
  })
})

describe('column groups and modes', () => {
  it('declares the five groups with the columns the spec names', () => {
    const labels = (g: string) => GROUPS.find((x) => x.id === g)!.columns.map((c) => c.label)
    expect(labels('basic')).toEqual(['GP', 'MIN', 'PTS', 'REB', 'AST', 'STL', 'BLK', '3PM', 'TO', 'FG%', 'FT%'])
    expect(labels('shooting')).toEqual(['FG%', '3P%', 'FT%', 'TS%', 'eFG%', 'FTr', '3PAr'])
    expect(labels('advanced')).toEqual(['USG', 'MIN%', 'AST%', 'ORB%', 'DRB%', 'TRB%', 'STL%', 'BLK%', 'TOV%', 'GmSc'])
    expect(labels('fantasy')).toEqual(['FP/G', 'Rank', 'Pos rank', 'Pts rank', 'Move', 'VOR'])
    expect(labels('draft')).toEqual(['Pick', 'Rd', 'Drafted by', 'Board ADP', 'Draft value', 'Rank'])
  })

  it('mode changes counting columns only', () => {
    const r = row('m')
    expect(COLUMNS.pts.value(r, 'perGame')).toBe(10)
    expect(COLUMNS.pts.value(r, 'totals')).toBe(600)
    expect(COLUMNS.pts.value(r, 'per36')).toBe(12)
    expect(COLUMNS.ts.value(r, 'totals')).toBe(55)
    expect(COLUMNS.fp.value(r, 'totals')).toBe(30)
    expect(formatValue(COLUMNS.pts, 600, 'totals')).toBe('600')
    expect(formatValue(COLUMNS.pts, 10, 'perGame')).toBe('10.0')
  })

  it('a per-36 null (no minutes) is a missing value', () => {
    expect(COLUMNS.pts.value(row('n', { stats: win({ per36: null }) }), 'per36')).toBeNull()
  })

  it('switching group keeps a sort the new group still has, else uses its default', () => {
    expect(resolveSort('shooting', { col: 'ts', dir: 'asc' })).toEqual({ col: 'ts', dir: 'asc' })
    expect(resolveSort('basic', { col: 'name', dir: 'desc' })).toEqual({ col: 'name', dir: 'desc' })
    expect(resolveSort('draft', { col: 'pts', dir: 'asc' })).toEqual({ col: 'pick', dir: 'asc' })
    expect(resolveSort('fantasy', { col: 'leagueRank', dir: 'desc' })).toEqual({ col: 'leagueRank', dir: 'desc' })
  })

  it('does not offer a sort outside the group', () => {
    expect(sortOffered('basic', 'usg')).toBe(false)
    expect(sortOffered('advanced', 'usg')).toBe(true)
    expect(sortOffered('advanced', 'name')).toBe(true)
  })
})

describe('filters', () => {
  const off = { qualifiedOnly: false, sortCol: 'fp' }
  const on = { qualifiedOnly: true, sortCol: 'fp' }
  const rs = [
    row('1', { positions: ['PG', 'SG'], team: 'LAL', ownership: owner({ rosterId: 1 }) }),
    row('2', { positions: ['C'], team: 'DEN', ownership: owner({ rosterId: 2, ownerName: 'B' }) }),
    row('3', { positions: ['C'], team: 'LAL', ownership: owner({ state: 'FREE_AGENT', rosterId: null, ownerName: null }) }),
    row('4', {
      positions: ['PF'], team: null, qualified: false, reason: 'NOT_QUALIFIED',
      ownership: owner({ state: 'FREE_AGENT', rosterId: null }),
    }),
  ]

  it('filters by position (any listed position), team and availability', () => {
    expect(ids(applyFilters(rs, { ...NO_FILTERS, position: 'SG' }, off))).toEqual(['1'])
    expect(ids(applyFilters(rs, { ...NO_FILTERS, team: 'LAL' }, off))).toEqual(['1', '3'])
    expect(ids(applyFilters(rs, { ...NO_FILTERS, availability: { kind: 'free' } }, off))).toEqual(['3', '4'])
    expect(ids(applyFilters(rs, { ...NO_FILTERS, availability: { kind: 'rostered' } }, off))).toEqual(['1', '2'])
  })

  it('filters to one manager roster by rosterId', () => {
    expect(ids(applyFilters(rs, { ...NO_FILTERS, availability: { kind: 'roster', rosterId: 2 } }, off))).toEqual(['2'])
    expect(ids(applyFilters(rs, { ...NO_FILTERS, availability: { kind: 'roster', rosterId: 99 } }, off))).toEqual([])
  })

  it('qualification applies to rate and rank columns only (FR-025), not counting, totals or text sorts', () => {
    for (const col of ['fp', 'leagueRank', 'positionRank', 'rankMove', 'vor', 'fgPct', 'ts', 'usg', 'tovPct', 'gameScore']) {
      expect(ids(applyFilters(rs, NO_FILTERS, { qualifiedOnly: true, sortCol: col })), col).toEqual(['1', '2', '3'])
    }
    for (const col of ['pts', 'reb', 'gp', 'min', 'name', 'pick', 'round', 'manager', 'adp', 'draftValue']) {
      expect(ids(applyFilters(rs, NO_FILTERS, { qualifiedOnly: true, sortCol: col })), col).toEqual(['1', '2', '3', '4'])
    }
  })

  it('an unknown sort column falls back to the name, which shows everyone', () => {
    expect(ids(applyFilters(rs, NO_FILTERS, { qualifiedOnly: true, sortCol: 'nope' }))).toEqual(['1', '2', '3', '4'])
  })

  it('when the board fell back, ownership filters read currentOwnership, not the stale season', () => {
    const fell = [
      row('a', { ownership: owner({ rosterId: 1 }), currentOwnership: owner({ state: 'FREE_AGENT', rosterId: null, ownerName: null }) }),
      row('b', { ownership: owner({ state: 'FREE_AGENT', rosterId: null }), currentOwnership: owner({ rosterId: 7, ownerName: 'Z' }) }),
    ]
    expect(ids(applyFilters(fell, { ...NO_FILTERS, availability: { kind: 'free' } }, off))).toEqual(['a'])
    expect(ids(applyFilters(fell, { ...NO_FILTERS, availability: { kind: 'rostered' } }, off))).toEqual(['b'])
    expect(ids(applyFilters(fell, { ...NO_FILTERS, availability: { kind: 'roster', rosterId: 7 } }, off))).toEqual(['b'])
    expect(ids(applyFilters(fell, { ...NO_FILTERS, availability: { kind: 'roster', rosterId: 1 } }, off))).toEqual([])
  })

  it('knows when no row has known ownership (NOT_DRAFTED, UNAVAILABLE)', () => {
    const nobody = [row('a', { currentOwnership: owner({ state: 'NOT_DRAFTED', rosterId: null, ownerName: null, asOf: null }) })]
    expect(hasKnownOwnership(nobody)).toBe(false)
    expect(hasKnownOwnership([row('a', { ownership: owner({ state: 'UNAVAILABLE', rosterId: null }) })])).toBe(false)
    expect(hasKnownOwnership(rs)).toBe(true)
  })

  it('qualification on drops unqualified rows on a rate sort; off keeps them', () => {
    expect(ids(applyFilters(rs, NO_FILTERS, on))).toEqual(['1', '2', '3'])
    expect(ids(applyFilters(rs, NO_FILTERS, off))).toEqual(['1', '2', '3', '4'])
  })
})

describe('stat leaders', () => {
  const pts = LEADER_CATEGORIES.find((c) => c.id === 'pts')!
  const many = Array.from({ length: 8 }, (_, i) => row(String(i), { stats: win({ pg: { pts: 10 + i } }) }))

  it('returns the top five, best first, with exact values', () => {
    const top = categoryLeaders(many, pts)
    expect(top.map((l) => l.row.sleeperPlayerId)).toEqual(['7', '6', '5', '4', '3'])
    expect(top[0].value).toBe(17)
  })

  it('skips unqualified and stale rows however good their numbers', () => {
    const rs = [
      row('hot', { qualified: false, reason: 'NOT_QUALIFIED', stats: win({ pg: { pts: 99 } }) }),
      row('stale', { qualified: false, reason: 'NOT_QUALIFIED_STALE', stats: win({ pg: { pts: 98 } }) }),
      row('ok', { stats: win({ pg: { pts: 20 } }) }),
    ]
    expect(categoryLeaders(rs, pts).map((l) => l.row.sleeperPlayerId)).toEqual(['ok'])
  })

  it('covers the nine categories and ignores the table mode', () => {
    expect(LEADER_CATEGORIES.map((c) => c.label)).toEqual([
      'Points', 'Rebounds', 'Assists', 'Steals', 'Blocks', 'Threes', 'True shooting %', 'Usage', 'Fantasy pts / game',
    ])
    expect(LEADER_CATEGORIES.every((c) => c.mode === 'perGame')).toBe(true)
  })

  it('leaves out a player with no value in the category', () => {
    const ts = LEADER_CATEGORIES.find((c) => c.id === 'ts')!
    const rs = [row('n', { stats: win({ adv: { ts: rate(null, 'NO_ATTEMPTS') } }) }), row('p')]
    expect(categoryLeaders(rs, ts).map((l) => l.row.sleeperPlayerId)).toEqual(['p'])
  })
})

describe('rank and move formatting', () => {
  it('the rank move is whole places with a sign, no decimal', () => {
    expect(formatValue(COLUMNS.rankMove, 7, 'perGame')).toBe('+7')
    expect(formatValue(COLUMNS.rankMove, -77, 'perGame')).toBe('−77')
    expect(formatValue(COLUMNS.rankMove, 0, 'perGame')).toBe('0')
    expect(formatValue(COLUMNS.vor, 7, 'perGame')).toBe('+7.0')   // VOR is real points, one decimal
  })

  it('rank headers name a non-season window; others never change', () => {
    expect(columnLabel(COLUMNS.leagueRank, 'last 10')).toBe('Rank (last 10)')
    expect(columnLabel(COLUMNS.positionRank, 'last 5')).toBe('Pos rank (last 5)')
    expect(columnLabel(COLUMNS.rankMove, 'last 10')).toBe('Move (last 10)')
    expect(columnLabel(COLUMNS.leagueRank, null)).toBe('Rank')
    expect(columnLabel(COLUMNS.fp, 'last 10')).toBe('FP/G')
  })

  it('the draft value tooltip says season total, not per week', () => {
    expect(COLUMNS.draftValue.title).toMatch(/summed over the league’s counted weeks/)
    expect(COLUMNS.draftValue.title).not.toMatch(/per counted week/)
  })
})
