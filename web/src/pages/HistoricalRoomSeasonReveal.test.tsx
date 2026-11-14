import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalGameState, HistoricalPlayerCard, HistoricalResult } from '../api'
import HistoricalRoom from './HistoricalRoom'

// Spec 071: the season plays out before the results, for a game finished in this visit only.

const getHistoricalGame = vi.fn()
const historicalPick = vi.fn()
const historicalAuto = vi.fn()
const getHistoricalGameAward = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getHistoricalGame: (...a: unknown[]) => getHistoricalGame(...a),
  historicalPick: (...a: unknown[]) => historicalPick(...a),
  historicalAuto: (...a: unknown[]) => historicalAuto(...a),
  getHistoricalGameAward: (...a: unknown[]) => getHistoricalGameAward(...a),
}))

const realMatchMedia = window.matchMedia
function setReducedMotion(reduce: boolean) {
  window.matchMedia = ((q: string) => ({
    matches: q.includes('prefers-reduced-motion') ? reduce : false,
    media: q,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
  })) as unknown as typeof window.matchMedia
}

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
  historicalAuto.mockReset()
  getHistoricalGameAward.mockReset().mockRejectedValue(new Error('no award'))
  localStorage.clear()
  setReducedMotion(false)
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
  window.matchMedia = realMatchMedia
})

function card(id: number, name: string): HistoricalPlayerCard {
  return {
    id,
    name,
    positions: ['SF'],
    team: 'CHI',
    age: 28,
    rank: id,
    positionalRank: id,
    prior: { seasonLabel: '1994-95', games: 79, perGame: { pts: 29.3 }, fantasyPoints: 3010.5, bestOfTwo: '1993-94', totals: {} },
    rookiePick: null,
  }
}

function drafting(): HistoricalGameState {
  return {
    id: 5,
    sport: 'NBA',
    status: 'DRAFTING',
    season: {
      label: '1995-96',
      countedStats: ['pts'],
      excludedStats: {},
      statCoverage: { pts: 1 },
      rankingSource: 'PRIOR_SEASON_POINTS',
      rankingLabel: "ranked by last season's points",
      settingsSource: 'Settings: the Legacy Ball Knowers league.',
      dataNotes: [],
      attribution: null,
    },
    teams: 2,
    rounds: 2,
    rosterPositions: ['PG', 'SG', 'BN'],
    userSlot: 1,
    myPicks: [1, 4],
    currentPickNo: 1,
    onTheClockSlot: 1,
    isUsersTurn: true,
    picks: [],
    available: [card(1, 'Top Guy'), card(2, 'Other Guy')],
    result: null,
  }
}

// Three regular-season weeks for slot 1: win, loss, win. No playoffs (the season was too short).
const result: HistoricalResult = {
  weeks: [
    { week: 1, startDate: null, phase: 'REGULAR', matchups: [{ homeSlot: 1, awaySlot: 2, homePoints: 401.5, awayPoints: 388 }] },
    { week: 2, startDate: null, phase: 'REGULAR', matchups: [{ homeSlot: 2, awaySlot: 1, homePoints: 320, awayPoints: 310 }] },
    { week: 3, startDate: null, phase: 'REGULAR', matchups: [{ homeSlot: 1, awaySlot: 2, homePoints: 350, awayPoints: 300 }] },
  ],
  standings: [
    { slot: 1, wins: 2, losses: 1, ties: 0, pointsFor: 1061.5, place: 1 },
    { slot: 2, wins: 1, losses: 2, ties: 0, pointsFor: 1008, place: 2 },
  ],
  userFinish: 1,
  lineups: {},
  hindsight: { label: 'greedy hindsight', pointsFor: 1300, userPointsFor: 1061.5, userBeatIt: false, picks: [] },
  biggestBust: null,
  bestStealPassedOn: null,
  format: { version: 2, seasonWeeks: 3, configuredRegularSeasonWeeks: 3, regularSeasonWeeks: 3, playoffWeeks: [], unusedWeeks: 0, shortened: true },
  playoffs: null,
  finalPlaces: null,
  userOutcome: 'NO_PLAYOFFS',
  pickFinishes: null,
}

function complete(): HistoricalGameState {
  const picks = [
    { pickNo: 1, round: 1, draftSlot: 1, seatType: 'USER', player: card(1, 'Top Guy') },
    { pickNo: 2, round: 1, draftSlot: 2, seatType: 'BOT', player: card(2, 'Other Guy') },
    { pickNo: 3, round: 2, draftSlot: 2, seatType: 'BOT', player: card(3, 'Third Guy') },
    { pickNo: 4, round: 2, draftSlot: 1, seatType: 'USER', player: card(4, 'Fourth Guy') },
  ]
  return { ...drafting(), status: 'COMPLETE', currentPickNo: 5, onTheClockSlot: null, isUsersTurn: false, picks, available: [], result } as HistoricalGameState
}

async function renderRoom() {
  render(
    <MemoryRouter initialEntries={['/history/5']}>
      <Routes>
        <Route path="/history/:gameId" element={<HistoricalRoom />} />
        <Route path="/history" element={<div>landing</div>} />
      </Routes>
    </MemoryRouter>,
  )
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
}

/** Auto-finish from the room: the pick reveal is skipped, so the game is COMPLETE on the next render. */
async function finishFromTheRoom() {
  getHistoricalGame.mockResolvedValue(drafting())
  historicalAuto.mockResolvedValue(complete())
  await renderRoom()
  fireEvent.click(screen.getByRole('button', { name: 'Auto-finish the draft' }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
}

const reveal = () => screen.queryByRole('region', { name: 'The 1995-96 season' })
const squares = () => Array.from(document.querySelectorAll('.tm-reveal-week')).map((el) => el.textContent)

describe('HistoricalRoom: the season reveal', () => {
  it('plays the weeks one at a time, then the finish, and only then shows the results', async () => {
    await finishFromTheRoom()
    expect(reveal()).not.toBeNull()
    // Results (and the award request that could toast the ending) are not on screen yet.
    expect(screen.queryByRole('region', { name: 'Your finish' })).toBeNull()
    expect(getHistoricalGameAward).not.toHaveBeenCalled()

    expect(squares()).toEqual(['W', '', ''])
    expect(document.querySelector('.tm-reveal-record')?.textContent).toBe('1-0')
    expect(screen.getByText('Week 1: you 401.5, Team 2 388.0')).toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(600)
    })
    expect(squares()).toEqual(['W', 'L', ''])
    expect(document.querySelector('.tm-reveal-record')?.textContent).toBe('1-1')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(600)
    })
    expect(squares()).toEqual(['W', 'L', 'W'])
    // The record it counts up to is the standings row.
    expect(document.querySelector('.tm-reveal-record')?.textContent).toBe('2-1')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(600)
    })
    expect(screen.getByText('Regular season over: 2-1, 1st of 2.')).toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500)
    })
    const heading = screen.getByRole('heading', { name: 'You finished 1st of 2' })
    expect(heading).toHaveFocus()

    fireEvent.click(screen.getByRole('button', { name: 'See your results' }))
    expect(reveal()).toBeNull()
    expect(screen.getByRole('region', { name: 'Your finish' })).toBeInTheDocument()
    expect(getHistoricalGameAward).toHaveBeenCalledWith(5)
  })

  it('Skip to results ends it at once', async () => {
    await finishFromTheRoom()
    fireEvent.click(screen.getByRole('button', { name: 'Skip to results' }))
    expect(reveal()).toBeNull()
    expect(screen.getByRole('region', { name: 'Your finish' })).toBeInTheDocument()
  })

  it('a game opened already finished goes straight to its results, and can replay the season by hand', async () => {
    getHistoricalGame.mockResolvedValue(complete())
    await renderRoom()
    expect(reveal()).toBeNull()
    expect(screen.getByRole('region', { name: 'Your finish' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Replay the season' }))
    expect(reveal()).not.toBeNull()
    expect(squares()).toEqual(['W', '', ''])
    fireEvent.click(screen.getByRole('button', { name: 'Skip to results' }))
    expect(screen.getByRole('region', { name: 'Your finish' })).toBeInTheDocument()
  })

  it('with reduced motion on, finishing shows the results at once', async () => {
    setReducedMotion(true)
    await finishFromTheRoom()
    expect(reveal()).toBeNull()
    expect(screen.getByRole('region', { name: 'Your finish' })).toBeInTheDocument()
  })

  it('names the day on a game of the day', async () => {
    getHistoricalGame.mockResolvedValue({ ...complete(), dailyDate: '2026-11-14' })
    await renderRoom()
    expect(screen.getByRole('heading', { level: 1, name: 'You drafted 1995-96 · game of the day, Nov 14' })).toBeInTheDocument()
  })
})
