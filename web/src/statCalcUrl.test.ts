import { describe, expect, it } from 'vitest'
import { AXIS_FORMULA, DEFAULT_STATE, defaultCmp, readCalcState, readFilters, stripRoster, writeCalcState, writeFilters, type CalcState } from './statCalcUrl'
import { owner, row } from './testStatBoard'

/** specs/032 T014, contract C2. */

const rows = [
  row('1', { team: 'DEN', positions: ['C'] }),
  row('2', { team: 'LAL', positions: ['PG', 'SG'], ownership: owner({ rosterId: 3, ownerName: 'Dee' }) }),
]
const read = (qs: string, r = rows) => readCalcState(new URLSearchParams(qs), r)

describe('readCalcState', () => {
  it('defaults: highest first, qualified on, standardize off, 15 bars, season, per game', () => {
    const { state, dropped } = read('')
    expect(state).toEqual(DEFAULT_STATE)
    expect(state).toMatchObject({ w: 'SEASON', m: 'perGame', top: 15, q: true, z: false, dir: 'desc' })
    expect(dropped).toEqual([])
  })

  it('reads every parameter', () => {
    const { state } = read('f=ast+%2F+tov&n=Mine&w=LAST_10&m=per36&top=30&q=0&z=1&dir=asc&pos=C&team=DEN&own=r:1')
    expect(state).toEqual({
      f: 'ast / tov', n: 'Mine', w: 'LAST_10', m: 'per36', top: 30, q: false, z: true, dir: 'asc',
      filters: { position: 'C', team: 'DEN', availability: { kind: 'roster', rosterId: 1 } },
      chart: 'ranked', split: false, cmp: 'SEASON', chg: 'up', x: '', y: '',
    })
  })

  it('clamps top to 5..50, falls back on junk, and cuts a long name to 40 characters', () => {
    expect(read('top=2').state.top).toBe(5)
    expect(read('top=500').state.top).toBe(50)
    expect(read('top=abc').state.top).toBe(15)
    expect(read('top=').state.top).toBe(15)
    expect(read(`n=${'x'.repeat(60)}`).state.n).toHaveLength(40)
    expect(read('w=BOGUS&m=nope&dir=up').state).toMatchObject({ w: 'SEASON', m: 'perGame', dir: 'desc' })
  })

  it('drops an unknown team, position and roster, and names each', () => {
    const { state, dropped } = read('team=ZZZ&pos=XX&own=r:99')
    expect(state.filters).toEqual({ position: null, team: null, availability: { kind: 'all' } })
    expect(dropped).toEqual(['position XX', 'team ZZZ', 'roster 99'])
  })

  it('keeps a roster that exists, and a team that exists', () => {
    expect(read('own=r:3&team=LAL').state.filters).toMatchObject({ team: 'LAL', availability: { kind: 'roster', rosterId: 3 } })
  })

  it('drops free/rostered when no ownership is known, and a malformed own value', () => {
    const unknown = rows.map((r) => ({ ...r, ownership: owner({ state: 'UNAVAILABLE', rosterId: null }) }))
    expect(read('own=free', unknown).dropped).toEqual(['availability free'])
    expect(read('own=r:abc').dropped).toEqual(['availability r:abc'])
    expect(read('own=free').dropped).toEqual([])
  })

  it('takes the filters as written while the rows are still loading', () => {
    const { state, dropped } = readCalcState(new URLSearchParams('team=ZZZ'), null)
    expect(state.filters.team).toBe('ZZZ')
    expect(dropped).toEqual([])
  })
})

describe('writeCalcState', () => {
  const full: CalcState = {
    f: '(usg + vor) / tov', n: 'Usage value', w: 'LAST_5', m: 'totals', top: 25, q: false, z: true, dir: 'asc',
    filters: { position: 'PG', team: 'LAL', availability: { kind: 'roster', rosterId: 3 } },
    chart: 'scatter', split: true, cmp: 'PREV', chg: 'down', x: 'usg', y: 'f',
  }

  it('writes only what differs from the default', () => {
    expect(writeCalcState(DEFAULT_STATE).toString()).toBe('')
    expect(writeCalcState({ ...DEFAULT_STATE, f: 'pts' }).toString()).toBe('f=pts')
  })

  it('round-trips every field', () => {
    expect(read(writeCalcState(full).toString()).state).toEqual(full)
    expect(read(writeCalcState({ ...full, filters: { position: null, team: null, availability: { kind: 'free' } } }).toString()).state.filters.availability).toEqual({ kind: 'free' })
  })

  it('writes a + inside f as %2B and reads it back', () => {
    const qs = writeCalcState({ ...DEFAULT_STATE, f: 'usg + vor' }).toString()
    expect(qs).toContain('%2B')
    expect(qs).not.toMatch(/f=usg\+vor/)
    expect(read(qs).state.f).toBe('usg + vor')
  })
})

describe('chart, x and y (US4, FR-018)', () => {
  it('default to the ranked chart with no axes', () => {
    const { state } = read('f=pts')
    expect(state.chart).toBe('ranked')
    expect([state.x, state.y]).toEqual(['', ''])
  })

  it('reads chart=scatter with its axes; any other chart value is ranked', () => {
    const { state } = read('chart=scatter&x=usg&y=f')
    expect([state.chart, state.x, state.y]).toEqual(['scatter', 'usg', 'f'])
    expect(read('chart=bogus&x=usg').state.chart).toBe('ranked')
  })

  it('writes the axes only on the scatter', () => {
    expect(writeCalcState({ ...DEFAULT_STATE, chart: 'scatter', x: 'usg', y: 'ts' }).toString()).toBe('chart=scatter&x=usg&y=ts')
    expect(writeCalcState({ ...DEFAULT_STATE, chart: 'scatter' }).toString()).toBe('chart=scatter')
    // specs/032 code review B10: the axes survive a toggle to Ranked and back
    expect(writeCalcState({ ...DEFAULT_STATE, chart: 'ranked', x: 'usg', y: 'ts' }).toString()).toBe('x=usg&y=ts')
  })

  it('keeps the other params and round-trips the formula sentinel', () => {
    const s = { ...DEFAULT_STATE, f: 'ast / tov', chart: 'scatter' as const, x: AXIS_FORMULA, y: 'usg' }
    const back = read(writeCalcState(s).toString()).state
    expect(back).toEqual(s)
    expect(AXIS_FORMULA).toBe('f')
  })
})

describe('own=mine (specs/042 T008, F9)', () => {
  const mineRows = [row('1', { ownership: owner({ rosterId: 1, isMe: true }) }), row('2')]

  it('round-trips through the calculator state', () => {
    const s = { ...DEFAULT_STATE, filters: { position: null, team: null, availability: { kind: 'mine' as const } } }
    expect(writeCalcState(s).toString()).toBe('own=mine')
    expect(read('own=mine', mineRows).state).toEqual(s)
    expect(read('own=mine', mineRows).dropped).toEqual([])
  })

  it('is dropped, and named, when no row is the viewer’s', () => {
    const { state, dropped } = read('own=mine')
    expect(state.filters.availability).toEqual({ kind: 'all' })
    expect(dropped).toEqual(['availability mine'])
  })

  it('is taken as written while the rows are loading, and survives stripRoster', () => {
    expect(readCalcState(new URLSearchParams('own=mine'), null).state.filters.availability).toEqual({ kind: 'mine' })
    expect(stripRoster(new URLSearchParams('own=mine&f=pts')).toString()).toBe('own=mine&f=pts')
  })

  it('shares the filter encoding with the leaderboard', () => {
    const p = new URLSearchParams()
    writeFilters({ position: 'C', team: 'DEN', availability: { kind: 'mine' } }, p)
    expect(p.toString()).toBe('pos=C&team=DEN&own=mine')
    expect(readFilters(p, null).filters).toEqual({ position: 'C', team: 'DEN', availability: { kind: 'mine' } })
  })
})

describe('stripRoster', () => {
  it('removes own=r:* and nothing else', () => {
    expect(stripRoster(new URLSearchParams('f=pts&own=r:4&team=DEN')).toString()).toBe('f=pts&team=DEN')
    expect(stripRoster(new URLSearchParams('own=free&f=pts')).toString()).toBe('own=free&f=pts')
  })
  it('does not change the params it is given', () => {
    const p = new URLSearchParams('own=r:4')
    stripRoster(p)
    expect(p.get('own')).toBe('r:4')
  })
})

describe('US5 chart, split, cmp and chg (C2)', () => {
  it('reads the three new charts; an unknown chart is ranked', () => {
    for (const c of ['spread', 'change', 'parts', 'scatter'] as const) expect(read(`chart=${c}`).state.chart).toBe(c)
    expect(read('chart=nope').state.chart).toBe('ranked')
  })
  it('split=pos is the only split; anything else is off', () => {
    expect(read('chart=spread&split=pos').state.split).toBe(true)
    expect(read('split=team').state.split).toBe(false)
  })
  it('cmp defaults to LAST_10 from the season and to SEASON from a recent window', () => {
    expect(defaultCmp('SEASON')).toBe('LAST_10')
    expect(read('').state.cmp).toBe('LAST_10')
    expect(read('w=LAST_5').state.cmp).toBe('SEASON')
    expect(read('w=LAST_10').state.cmp).toBe('SEASON')
  })
  it('a cmp equal to the current window falls back to the default; junk does too', () => {
    expect(read('w=LAST_10&cmp=LAST_10').state.cmp).toBe('SEASON')
    expect(read('cmp=SEASON').state.cmp).toBe('LAST_10')
    expect(read('cmp=bogus').state.cmp).toBe('LAST_10')
    expect(read('cmp=LAST_5').state.cmp).toBe('LAST_5')
    expect(read('w=LAST_10&cmp=PREV').state.cmp).toBe('PREV')
  })
  it('chg is up unless it says down', () => {
    expect(read('').state.chg).toBe('up')
    expect(read('chg=down').state.chg).toBe('down')
    expect(read('chg=sideways').state.chg).toBe('up')
  })
  it('writes only non-defaults, and every field round-trips', () => {
    expect(writeCalcState({ ...DEFAULT_STATE, chart: 'change' }).toString()).toBe('chart=change')
    expect(writeCalcState({ ...DEFAULT_STATE, chart: 'change', cmp: 'PREV', chg: 'down' }).toString()).toBe('chart=change&cmp=PREV&chg=down')
    expect(writeCalcState({ ...DEFAULT_STATE, chart: 'spread', split: true }).toString()).toBe('chart=spread&split=pos')
    for (const s of [
      { ...DEFAULT_STATE, chart: 'parts' as const },
      { ...DEFAULT_STATE, chart: 'change' as const, cmp: 'LAST_5' as const, chg: 'down' as const },
      { ...DEFAULT_STATE, w: 'LAST_10' as const, cmp: 'PREV' as const, chart: 'change' as const },
      { ...DEFAULT_STATE, w: 'LAST_5' as const, cmp: 'LAST_10' as const },
      { ...DEFAULT_STATE, chart: 'spread' as const, split: true },
    ]) expect(read(writeCalcState(s).toString()).state).toEqual(s)
  })
  it('a cmp equal to the window is not written', () => {
    expect(writeCalcState({ ...DEFAULT_STATE, w: 'LAST_5', cmp: 'LAST_5' }).toString()).toBe('w=LAST_5')
  })
})
