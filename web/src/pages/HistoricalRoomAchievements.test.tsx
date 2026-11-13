import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalGameState, HistoricalPlayerCard } from '../api'
import HistoricalRoom from './HistoricalRoom'

const getHistoricalGame = vi.fn()
const historicalPick = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getHistoricalGame: (...a: unknown[]) => getHistoricalGame(...a),
  historicalPick: (...a: unknown[]) => historicalPick(...a),
}))

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})
beforeEach(() => {
  getHistoricalGame.mockReset()
  historicalPick.mockReset()
  localStorage.clear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const card = (id: number, name: string): HistoricalPlayerCard => ({
  id, name, positions: ['SF'], team: 'CHI', age: 28, rank: id, positionalRank: id, prior: null, rookiePick: null,
})

const season = {
  label: '1995-96', countedStats: ['pts'], excludedStats: {}, statCoverage: { pts: 1 },
  rankingSource: 'PRIOR_SEASON_POINTS', rankingLabel: "ranked by last season's points", settingsSource: 'x',
  dataNotes: [], attribution: null,
}

const tick = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })

/** Two teams, you are slot 2 (picks 2 and 3); the computer makes pick 4, which ends the draft. */
function states() {
  const bot1 = card(21, 'Bot Pick')
  const mine2 = card(1, 'Top Guy')
  const mine3 = card(22, 'Mine Three')
  const bot4 = card(23, 'Bot Last')
  const base = { id: 5, sport: 'NBA', season, teams: 2, rounds: 2, rosterPositions: ['PG', 'BN'], userSlot: 2, myPicks: [2, 3] }
  const start = {
    ...base, status: 'DRAFTING', currentPickNo: 2, onTheClockSlot: 2, isUsersTurn: true, result: null,
    picks: [{ pickNo: 1, round: 1, draftSlot: 1, seatType: 'BOT', player: bot1 }],
    available: [mine2, mine3, bot4],
  } as unknown as HistoricalGameState
  const end = {
    ...base, status: 'COMPLETE', currentPickNo: 5, onTheClockSlot: null, isUsersTurn: false, available: [],
    picks: [
      { pickNo: 1, round: 1, draftSlot: 1, seatType: 'BOT', player: bot1 },
      { pickNo: 2, round: 1, draftSlot: 2, seatType: 'USER', player: mine2 },
      { pickNo: 3, round: 2, draftSlot: 2, seatType: 'USER', player: mine3 },
      { pickNo: 4, round: 2, draftSlot: 1, seatType: 'BOT', player: bot4 },
    ],
    result: {
      weeks: [],
      standings: [
        { slot: 2, wins: 1, losses: 1, ties: 0, pointsFor: 688, place: 2 },
        { slot: 1, wins: 2, losses: 0, ties: 0, pointsFor: 711.5, place: 1 },
      ],
      userFinish: 2,
      lineups: {},
      hindsight: { label: 'greedy hindsight, bots unchanged', pointsFor: 900, userPointsFor: 688, userBeatIt: false, picks: [] },
      biggestBust: null,
      bestStealPassedOn: null,
    },
  } as unknown as HistoricalGameState
  return { start, end }
}

describe('HistoricalRoom: achievements (spec 064)', () => {
  it('requests no award and no profile until the results branch mounts', async () => {
    const calls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        calls.push(String(input))
        return Promise.resolve(new Response('{}', { status: 500 }))
      }),
    )
    const { start, end } = states()
    localStorage.setItem('bk.tm.revealSpeed', 'NORMAL')
    vi.useFakeTimers()
    getHistoricalGame.mockResolvedValue(start)
    historicalPick.mockResolvedValue(end)
    render(
      <MemoryRouter initialEntries={['/history/5']}>
        <Routes>
          <Route path="/history/:gameId" element={<HistoricalRoom />} />
        </Routes>
      </MemoryRouter>,
    )
    await tick(0)
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'Draft' })[0])
    await tick(0)
    // The server already says COMPLETE, but the reveal is still playing pick 4.
    expect(screen.queryByText('You drafted 1995-96')).not.toBeInTheDocument()
    expect(calls.filter((c) => c.includes('/award') || c.includes('/profile'))).toEqual([])

    await tick(1000)
    expect(screen.getByText('You drafted 1995-96')).toBeInTheDocument()
    expect(calls.some((c) => c.includes('/api/history/games/5/award'))).toBe(true)
    expect(calls.some((c) => c.includes('/api/history/profile'))).toBe(true)
  })
})
