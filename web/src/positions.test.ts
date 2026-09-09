import { describe, expect, it } from 'vitest'
import { POSITIONS_BY_SPORT, filterPositions } from './positions'

/**
 * positions.ts mirrors domain/Position.java's `forSport` order. A mirror with
 * no test is how the board ended up drawing plain snake while the engine
 * reversed round 3 (see snake.test.ts) -- same risk class, so the same kind of
 * test: assert against what the running backend actually produced, not against
 * a re-derivation of the same rule.
 *
 * Captured live 2026-09-09 from `GET /api/board?sport=X&limit=900` against the
 * real player pool -- 848 football rows and 535 basketball rows, every distinct
 * `position` value in each:
 *
 *   nfl -> DEF, K, QB, RB, TE, WR
 *   nba -> C, PF, PG, SF, SG
 */
const POSITIONS_ON_THE_REAL_BOARD = {
  nfl: ['DEF', 'K', 'QB', 'RB', 'TE', 'WR'],
  nba: ['C', 'PF', 'PG', 'SF', 'SG'],
} as const

describe('POSITIONS_BY_SPORT', () => {
  it('covers exactly the positions the real board serves, per sport', () => {
    for (const sport of ['nfl', 'nba'] as const) {
      expect([...POSITIONS_BY_SPORT[sport]].sort()).toEqual([...POSITIONS_ON_THE_REAL_BOARD[sport]])
    }
  })

  it('keeps the two sports disjoint', () => {
    // Not a style preference: class names are built from the raw position
    // string in seven files, and a filter chip row asks for ONE sport's list.
    // A football board offering a `C` filter would be a bug, so an accidental
    // overlap is one too.
    const overlap = POSITIONS_BY_SPORT.nfl.filter((p) => POSITIONS_BY_SPORT.nba.includes(p))
    expect(overlap).toEqual([])
  })

  it('preserves Position.forSport order, which the UI renders in', () => {
    // Not alphabetical -- this is the enum's own declaration order, and chip
    // rows read down it. Sorting it would silently reorder every filter row.
    expect(POSITIONS_BY_SPORT.nfl).toEqual(['QB', 'RB', 'WR', 'TE', 'K', 'DEF'])
    expect(POSITIONS_BY_SPORT.nba).toEqual(['PG', 'SG', 'SF', 'PF', 'C'])
  })
})

describe('filterPositions', () => {
  it('leads with ALL and then the sport, and never mixes sports', () => {
    expect(filterPositions('nfl')).toEqual(['ALL', 'QB', 'RB', 'WR', 'TE', 'K', 'DEF'])
    expect(filterPositions('nba')).toEqual(['ALL', 'PG', 'SG', 'SF', 'PF', 'C'])
  })
})
