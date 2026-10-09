import { describe, expect, it } from 'vitest'
import { RISK_MAX } from './survivalBands'
import { filterLikely, joinPoolStats, shownSeason, sortDraftRows, type DraftStatRow } from './draftRoomStats'
import { board, row, win } from './testStatBoard'
import { mkPlayer } from './testLiveRoom'

const none = () => null
const names = (rs: DraftStatRow[]) => rs.map((r) => r.player.name)

// The join key is the pool player's sleeperId, not its internal id: the leaderboard rows below
// share nothing with the pool players except that string.
const pool = [mkPlayer('PG', 'Ann', 5), mkPlayer('C', 'Bob', 20), mkPlayer('SF', 'Rookie', 60)]
const lb = [
  row(pool[1].sleeperId, { name: 'Bob', fpPerGame: 40, stats: win({ pg: { pts: 10, tov: 4 } }) }),
  row(pool[0].sleeperId, { name: 'Ann', fpPerGame: 30, stats: win({ pg: { pts: 25, tov: 1 } }) }),
  row('already-drafted', { name: 'Gone', fpPerGame: 99 }),
]

describe('joinPoolStats', () => {
  it('emits exactly one row per pool player, in pool order', () => {
    const rows = joinPoolStats(pool, lb, none, none)
    expect(rows.length).toBe(pool.length)
    expect(rows.map((r) => r.player.id)).toEqual(pool.map((p) => p.id))
  })

  it('joins on sleeperId, not on the internal id', () => {
    // A leaderboard row whose id equals a pool player's internal id must not match him.
    const collide = row(String(pool[2].id), { name: 'Wrong' })
    const rows = joinPoolStats(pool, [collide, ...lb], none, none)
    expect(rows[2].stats).toBeNull()
    expect(rows[0].stats?.sleeperPlayerId).toBe(pool[0].sleeperId)
  })

  it('gives a pool player with no leaderboard row null stats and NO_SEASON_GAMES', () => {
    const [ann, , rookie] = joinPoolStats(pool, lb, none, none)
    expect(rookie.stats).toBeNull()
    expect(rookie.reason).toBe('NO_SEASON_GAMES')
    expect(ann.reason).toBeNull()
  })

  it('drops a leaderboard row whose player is not in the pool', () => {
    const rows = joinPoolStats(pool, lb, none, none)
    expect(rows.some((r) => r.stats?.sleeperPlayerId === 'already-drafted')).toBe(false)
  })

  it('passes survivalNext and fillsSlot through unchanged', () => {
    const rows = joinPoolStats(pool, lb, (p) => (p.name === 'Ann' ? 0.42 : null), (p) => (p.name === 'Bob' ? 'C' : null))
    expect(rows.map((r) => r.survivalNext)).toEqual([0.42, null, null])
    expect(rows.map((r) => r.fillsSlot)).toEqual([null, 'C', null])
  })
})

describe('sortDraftRows (preference order, not just structure)', () => {
  const rows = joinPoolStats(pool, lb, none, none)

  it('puts the higher scorer first when sorting pts descending', () => {
    expect(names(sortDraftRows(rows, { col: 'pts', dir: 'desc' }, 'perGame')).slice(0, 2)).toEqual(['Ann', 'Bob'])
    expect(names(sortDraftRows(rows, { col: 'pts', dir: 'asc' }, 'perGame')).slice(0, 2)).toEqual(['Bob', 'Ann'])
  })

  it('puts fewer turnovers first in the natural direction of tov (lower is better)', () => {
    expect(names(sortDraftRows(rows, { col: 'tov', dir: 'asc' }, 'perGame')).slice(0, 2)).toEqual(['Ann', 'Bob'])
  })

  it('puts rows with no stats last in both directions', () => {
    expect(names(sortDraftRows(rows, { col: 'pts', dir: 'desc' }, 'perGame')).at(-1)).toBe('Rookie')
    expect(names(sortDraftRows(rows, { col: 'pts', dir: 'asc' }, 'perGame')).at(-1)).toBe('Rookie')
  })

  it('puts a row with a null value in the sort column last in both directions', () => {
    const withNull = joinPoolStats(
      pool,
      [
        row(pool[0].sleeperId, { name: 'Ann', fpPerGame: 30 }),
        row(pool[1].sleeperId, { name: 'Bob', fpPerGame: null }),
        row(pool[2].sleeperId, { name: 'Rookie', fpPerGame: 10 }),
      ],
      none,
      none,
    )
    expect(names(sortDraftRows(withNull, { col: 'fp', dir: 'desc' }, 'perGame'))).toEqual(['Ann', 'Rookie', 'Bob'])
    expect(names(sortDraftRows(withNull, { col: 'fp', dir: 'asc' }, 'perGame'))).toEqual(['Rookie', 'Ann', 'Bob'])
  })

  it('breaks ties like the leaderboard: games, then name', () => {
    const tied = joinPoolStats(
      pool,
      [
        row(pool[0].sleeperId, { name: 'Zed', fpPerGame: 20, stats: win({ games: 40 }) }),
        row(pool[1].sleeperId, { name: 'Amy', fpPerGame: 20, stats: win({ games: 40 }) }),
        row(pool[2].sleeperId, { name: 'Dan', fpPerGame: 20, stats: win({ games: 70 }) }),
      ],
      none,
      none,
    )
    expect(sortDraftRows(tied, { col: 'fp', dir: 'desc' }, 'perGame').map((r) => r.stats?.name)).toEqual(['Dan', 'Amy', 'Zed'])
  })

  it('does not mutate its input', () => {
    const copy = [...rows]
    sortDraftRows(rows, { col: 'pts', dir: 'asc' }, 'perGame')
    expect(rows).toEqual(copy)
  })
})

describe('filterLikely', () => {
  const survival = new Map([
    [pool[0].id, RISK_MAX],
    [pool[1].id, RISK_MAX - 0.01],
    [pool[2].id, 0.9],
  ])
  const withSurvival = () => joinPoolStats(pool, lb, (p) => survival.get(p.id) ?? null, none)

  it('keeps exactly the rows with survivalNext >= RISK_MAX', () => {
    expect(names(filterLikely(withSurvival(), true))).toEqual(['Ann', 'Rookie'])
  })

  it('returns everything when off', () => {
    expect(filterLikely(withSurvival(), false)).toHaveLength(3)
  })

  it('keeps all rows when survivalNext is null for every row (filter unavailable, not empty)', () => {
    expect(filterLikely(joinPoolStats(pool, lb, none, none), true)).toHaveLength(3)
  })

  it('drops a null-survival row when other rows have survival', () => {
    const rows = joinPoolStats(pool, lb, (p) => (p.id === pool[0].id ? 0.9 : null), none)
    expect(names(filterLikely(rows, true))).toEqual(['Ann'])
  })
})

describe('shownSeason', () => {
  it('labels start-year numbering with an en dash', () => {
    expect(shownSeason(board([])).seasonLabel).toBe('2025–26')
    expect(shownSeason(board([], { season: 1999 })).seasonLabel).toBe('1999–00')
  })

  it('reports a fallback exactly when requestedSeason is set', () => {
    expect(shownSeason(board([])).fellBack).toBe(false)
    expect(shownSeason(board([], { requestedSeason: 2026 })).fellBack).toBe(true)
  })

  it('has no scoring label from an older backend (undefined) or on null', () => {
    expect(shownSeason(board([])).scoringLabel).toBeNull()
    expect(shownSeason(board([], { scoringSeason: null })).scoringLabel).toBeNull()
    expect(shownSeason(board([], { scoringSeason: 2025 })).scoringLabel).toContain('2025–26')
  })

  it('flags changed scoring only on an explicit false', () => {
    expect(shownSeason(board([])).scoringChanged).toBe(false)
    expect(shownSeason(board([], { scoringMatchesRequested: null })).scoringChanged).toBe(false)
    expect(shownSeason(board([], { scoringMatchesRequested: true })).scoringChanged).toBe(false)
    expect(shownSeason(board([], { scoringMatchesRequested: false })).scoringChanged).toBe(true)
  })
})
