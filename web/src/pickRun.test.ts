import { describe, expect, it } from 'vitest'
import { positionRun, runCovers, runLabel } from './pickRun'
import fixture from './__fixtures__/nba-2025-picks.json'

const p = (...positions: string[]) => positions.map((position) => ({ position }))

describe('positionRun', () => {
  it('finds a run in the most recent window', () => {
    expect(positionRun(p('QB', 'RB', 'WR', 'WR', 'WR', 'WR'), 6, 4, 'nfl')).toEqual({ position: 'WR', count: 4, window: 6 })
  })

  it('ignores anything before the window', () => {
    // Six WRs, but only two of them are in the last six picks.
    expect(positionRun(p('WR', 'WR', 'WR', 'WR', 'RB', 'TE', 'QB', 'RB', 'WR', 'WR'), 6, 4, 'nfl')).toBeNull()
  })

  it('returns null below the threshold', () => {
    expect(positionRun(p('WR', 'WR', 'WR', 'RB', 'QB', 'TE'), 6, 4, 'nfl')).toBeNull()
  })

  it('never reports a run on K or DEF', () => {
    expect(positionRun(p('K', 'K', 'K', 'K', 'K', 'K'), 6, 4, 'nfl')).toBeNull()
    expect(positionRun(p('DEF', 'DEF', 'DEF', 'DEF', 'DEF', 'DEF'), 6, 4, 'nfl')).toBeNull()
  })

  it('waits for a full window rather than claiming a sample it does not have', () => {
    expect(positionRun(p('WR', 'WR', 'WR', 'WR'), 6, 4, 'nfl')).toBeNull()
    expect(positionRun([], 6, 4, 'nfl')).toBeNull()
  })

  it('picks the strongest run when two positions both clear the threshold', () => {
    expect(positionRun(p('RB', 'RB', 'RB', 'RB', 'RB', 'WR'), 6, 1, 'nfl')).toEqual({
      position: 'RB',
      count: 5,
      window: 6,
    })
  })

  it('honours a caller-supplied window and threshold', () => {
    expect(positionRun(p('TE', 'TE', 'QB'), 3, 2, 'nfl')).toEqual({ position: 'TE', count: 2, window: 3 })
  })

  it('scopes runnable positions to the given sport', () => {
    // C is a real, runnable basketball position -- unlike football's K/DEF,
    // basketball has no late-round dump position, so nothing is excluded.
    expect(positionRun(p('C', 'C', 'C', 'C', 'PG', 'SG'), 6, 4, 'nba')).toEqual({
      position: 'C',
      count: 4,
      window: 6,
    })
    // The same six picks read as football (as football) never match --
    // "C" and "PG"/"SG" aren't football positions at all.
    expect(positionRun(p('C', 'C', 'C', 'C', 'PG', 'SG'), 6, 4, 'nfl')).toBeNull()
  })
})

// ---- Spec 025 US4 (A2): NBA runs count families --------------------------------

describe('positionRun, NBA families (spec 025 A2)', () => {
  const mp = (...ps: string[][]) => ps.map((positions) => ({ position: positions[0], positions }))

  it('reads four single-family forwards in six as a Forward run', () => {
    const picks = mp(['PG'], ['SF'], ['PF'], ['PF'], ['SF', 'PF'], ['C'])
    expect(positionRun(picks, 6, 4, 'nba')).toEqual({ position: 'F', count: 4, window: 6 })
  })

  it('counts a guard pair like PG and SG together', () => {
    const picks = mp(['PG'], ['SG'], ['PG', 'SG'], ['SG'], ['C'], ['PF'])
    expect(positionRun(picks, 6, 4, 'nba')).toEqual({ position: 'G', count: 4, window: 6 })
  })

  it('counts a pick that spans families (SG/SF) toward none', () => {
    const picks = mp(['SG', 'SF'], ['SG', 'SF'], ['SF'], ['PF'], ['SG', 'SF'], ['C'])
    // Only SF and PF are single-family forwards: 2, below the threshold of 4.
    expect(positionRun(picks, 6, 4, 'nba')).toBeNull()
    // Under the old alphabetical-first rule this would have been read as a run.
    const many = mp(['SG', 'SF'], ['SG', 'SF'], ['SG', 'SF'], ['SG', 'SF'], ['C'], ['C'])
    expect(positionRun(many, 6, 4, 'nba')).toBeNull()
  })

  it('football stays per-position and ignores positions[]', () => {
    const picks = [
      { position: 'WR', positions: ['WR'] },
      { position: 'WR' },
      { position: 'WR' },
      { position: 'WR' },
      { position: 'RB' },
      { position: 'TE' },
    ]
    expect(positionRun(picks, 6, 4, 'nfl')).toEqual({ position: 'WR', count: 4, window: 6 })
  })

  it('runLabel names the family for NBA and the position for football', () => {
    expect(runLabel({ position: 'G', count: 4, window: 6 }, 'nba')).toBe('guards')
    expect(runLabel({ position: 'F', count: 4, window: 6 }, 'nba')).toBe('forwards')
    expect(runLabel({ position: 'C', count: 4, window: 6 }, 'nba')).toBe('centers')
    expect(runLabel({ position: 'RB', count: 4, window: 6 }, 'nfl')).toBe('RB')
  })

  it('runFamilyMembers maps a run to the positions it covers', () => {
    expect(runCovers({ position: 'G', count: 4, window: 6 }, 'PG', 'nba')).toBe(true)
    expect(runCovers({ position: 'G', count: 4, window: 6 }, 'SF', 'nba')).toBe(false)
    expect(runCovers({ position: 'RB', count: 4, window: 6 }, 'RB', 'nfl')).toBe(true)
  })

  it('finds exactly 26 run windows (0 ties) in the real 2025 NBA draft sequence', () => {
    const seq = (fixture as { pickNo: number; positions: string[] }[])
      .slice()
      .sort((a, b) => a.pickNo - b.pickNo)
      .map((x) => ({ position: x.positions[0], positions: x.positions }))
    expect(seq).toHaveLength(168)
    let windows = 0
    let total = 0
    for (let end = 6; end <= seq.length; end++) {
      total++
      if (positionRun(seq.slice(end - 6, end), 6, 4, 'nba')) windows++
    }
    expect(total).toBe(163)
    expect(windows).toBe(26)
  })
})
