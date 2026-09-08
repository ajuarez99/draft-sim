import { describe, expect, it } from 'vitest'
import { positionRun } from './pickRun'

const p = (...positions: string[]) => positions.map((position) => ({ position }))

describe('positionRun', () => {
  it('finds a run in the most recent window', () => {
    expect(positionRun(p('QB', 'RB', 'WR', 'WR', 'WR', 'WR'))).toEqual({ position: 'WR', count: 4, window: 6 })
  })

  it('ignores anything before the window', () => {
    // Six WRs, but only two of them are in the last six picks.
    expect(positionRun(p('WR', 'WR', 'WR', 'WR', 'RB', 'TE', 'QB', 'RB', 'WR', 'WR'))).toBeNull()
  })

  it('returns null below the threshold', () => {
    expect(positionRun(p('WR', 'WR', 'WR', 'RB', 'QB', 'TE'))).toBeNull()
  })

  it('never reports a run on K or DEF', () => {
    expect(positionRun(p('K', 'K', 'K', 'K', 'K', 'K'))).toBeNull()
    expect(positionRun(p('DEF', 'DEF', 'DEF', 'DEF', 'DEF', 'DEF'))).toBeNull()
  })

  it('waits for a full window rather than claiming a sample it does not have', () => {
    expect(positionRun(p('WR', 'WR', 'WR', 'WR'))).toBeNull()
    expect(positionRun([])).toBeNull()
  })

  it('picks the strongest run when two positions both clear the threshold', () => {
    expect(positionRun(p('RB', 'RB', 'RB', 'RB', 'RB', 'WR'), 6, 1)).toEqual({
      position: 'RB',
      count: 5,
      window: 6,
    })
  })

  it('honours a caller-supplied window and threshold', () => {
    expect(positionRun(p('TE', 'TE', 'QB'), 3, 2)).toEqual({ position: 'TE', count: 2, window: 3 })
  })

  it('scopes runnable positions to the given sport', () => {
    // C is a real, runnable basketball position -- unlike football's K/DEF,
    // basketball has no late-round dump position, so nothing is excluded.
    expect(positionRun(p('C', 'C', 'C', 'C', 'PG', 'SG'), 6, 4, 'nba')).toEqual({
      position: 'C',
      count: 4,
      window: 6,
    })
    // The same six picks read as football (default sport) never match --
    // "C" and "PG"/"SG" aren't football positions at all.
    expect(positionRun(p('C', 'C', 'C', 'C', 'PG', 'SG'))).toBeNull()
  })
})
