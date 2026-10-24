import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Cell, missingReason } from './statCells'
import { COLUMNS } from './statLeaderboard'
import { board, rate, row, win } from './testStatBoard'

/** specs/032 R15: one answer to "why is this cell empty?", shared by the table and the calculator. */
describe('missingReason', () => {
  it('a rate column gives its own RateReason', () => {
    const r = row('a', { stats: win({ adv: { usg: rate(null, 'NO_TEAM_ROW'), ts: rate(null, 'NO_ATTEMPTS') } }) })
    expect(missingReason(COLUMNS.usg, r, 'perGame')).toBe('NO_TEAM_ROW')
    expect(missingReason(COLUMNS.ts, r, 'perGame')).toBe('NO_ATTEMPTS')
  })

  it('a rate column with null value and null reason is UNKNOWN_NULL, not a guess', () => {
    const r = row('a', { stats: win({ adv: { usg: rate(null) } }) })
    expect(missingReason(COLUMNS.usg, r, 'perGame')).toBe('UNKNOWN_NULL')
  })

  it('a counting column in per 36 with no per36 block is NO_MINUTES', () => {
    const r = row('a', { stats: { ...win(), per36: null } })
    expect(missingReason(COLUMNS.pts, r, 'per36')).toBe('NO_MINUTES')
  })

  it('a counting column on a zero-game row is NO_GAMES', () => {
    const r = row('a', { stats: win({ games: 0, minutesPerGame: 0 }) })
    expect(missingReason(COLUMNS.min, { ...r, stats: { ...r.stats, minutesPerGame: null as unknown as number } }, 'perGame')).toBe('NO_GAMES')
    expect(missingReason(COLUMNS.pts, r, 'perGame')).toBe('NO_GAMES')
  })

  it('a zero-game row is NO_GAMES for any column, not only counting ones (FP/G, game score, rates)', () => {
    const r = row('a', { fpPerGame: null, stats: win({ games: 0, gameScorePerGame: null as unknown as number }) })
    expect(missingReason(COLUMNS.fp, r, 'perGame')).toBe('NO_GAMES')
    expect(missingReason(COLUMNS.gameScore, r, 'perGame')).toBe('NO_GAMES')
  })

  it('rank and VOR columns on an unqualified row give the row reason', () => {
    const stale = row('a', { qualified: false, reason: 'NOT_QUALIFIED_STALE', leagueRank: null, valueOverReplacement: null })
    const short = row('b', { qualified: false, reason: 'NOT_QUALIFIED', leagueRank: null, valueOverReplacement: null })
    for (const id of ['leagueRank', 'positionRank', 'pointsRank', 'rankMove', 'vor']) {
      expect(missingReason(COLUMNS[id], stale, 'perGame'), id).toBe('NOT_QUALIFIED_STALE')
      expect(missingReason(COLUMNS[id], short, 'perGame'), id).toBe('NOT_QUALIFIED')
    }
  })

  it('an unqualified row with no reason on file still says NOT_QUALIFIED', () => {
    const r = row('a', { qualified: false, reason: null, leagueRank: null })
    expect(missingReason(COLUMNS.leagueRank, r, 'perGame')).toBe('NOT_QUALIFIED')
  })

  it('VOR null on a qualified row is NO_REPLACEMENT_LEVEL', () => {
    const r = row('a', { valueOverReplacement: null, vorPosition: null })
    expect(missingReason(COLUMNS.vor, r, 'perGame')).toBe('NO_REPLACEMENT_LEVEL')
  })

  it('positionRank null on a qualified row is NO_POSITION', () => {
    const r = row('a', { positionRank: null, positions: [] })
    expect(missingReason(COLUMNS.positionRank, r, 'perGame')).toBe('NO_POSITION')
  })

  it('other null ranks on a qualified row are UNKNOWN_NULL', () => {
    const r = row('a', { pointsRank: null })
    expect(missingReason(COLUMNS.pointsRank, r, 'perGame')).toBe('UNKNOWN_NULL')
  })

  it('fpPerGame is not qualification-gated (F3): an unqualified row with null FP/G is UNKNOWN_NULL', () => {
    const r = row('a', { qualified: false, reason: 'NOT_QUALIFIED', fpPerGame: null })
    expect(missingReason(COLUMNS.fp, r, 'perGame')).toBe('UNKNOWN_NULL')
  })
})

describe('Cell uses missingReason', () => {
  const d = board([])
  it('a qualified row with no position rank no longer says "not qualified"', () => {
    const r = row('a', { positionRank: null })
    render(<Cell col={COLUMNS.positionRank} row={r} d={d} mode="perGame" />)
    const el = screen.getByLabelText('He has no listed position, so there is no position rank.')
    expect(el).toBeTruthy()
  })

  it('an unqualified row still reads its own reason', () => {
    const r = row('a', { qualified: false, reason: 'NOT_QUALIFIED', leagueRank: null })
    render(<Cell col={COLUMNS.leagueRank} row={r} d={d} mode="perGame" />)
    expect(screen.getByLabelText('Hasn’t played enough games or minutes to be ranked.')).toBeTruthy()
  })

  // Spec 046 US4: a pick from an auction has a buyer but no pick slot. "Undrafted" would be a lie.
  it('an auction pick (null pickNo and round) says "auction" in the pick cell, and a dash in the round cell', () => {
    const r = row('a', { draft: { pickNo: null, round: null, managerName: 'Dunk Tank' } })
    const { container } = render(<Cell col={COLUMNS.pick} row={r} d={d} mode="perGame" />)
    expect(container.textContent).toBe('auction')
    expect(screen.getByLabelText(/no pick number or round/)).toBeTruthy()
    render(<Cell col={COLUMNS.round} row={r} d={d} mode="perGame" />)
    expect(screen.getAllByLabelText(/no pick number or round/)).toHaveLength(2)
    expect(screen.queryByText('undrafted')).toBeNull()
  })

  it('a player with no pick in a completed snake draft is still "undrafted"', () => {
    const r = row('a', { draft: null })
    const { container } = render(<Cell col={COLUMNS.pick} row={r} d={d} mode="perGame" />)
    expect(container.textContent).toBe('undrafted')
  })
})
