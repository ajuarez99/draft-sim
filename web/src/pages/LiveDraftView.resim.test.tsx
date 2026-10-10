import { act, render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LiveState, RealPick, SimulationResult } from '../api'
import { ApiError } from '../apiError'
import { mkLive, mkPlayer, mkRealPick, mkSeats } from '../testLiveRoom'
import LiveDraftView from './LiveDraftView'

/**
 * Per-pick background re-projection (spec 012, T019-T021): a landed pick
 * schedules one debounced run, the run carries an explicit startState only when
 * the landed list is provably whole, and a 429 is silent.
 */

const getRealDraftBoard = vi.fn()
const getSeats = vi.fn()
const getDrafts = vi.fn()
const streamSimulationQuietly = vi.fn()
vi.mock('../api', () => ({
  getRealDraftBoard: (...a: unknown[]) => getRealDraftBoard(...a),
  getSeats: (...a: unknown[]) => getSeats(...a),
  getDrafts: (...a: unknown[]) => getDrafts(...a),
  streamSimulationQuietly: (...a: unknown[]) => streamSimulationQuietly(...a),
  createMockSessionFromDraft: vi.fn(),
  // Spec 024: the room loads the user's targets; these tests have none.
  getTargets: () => Promise.resolve({ players: [], missing: [] }),
  putTargets: () => Promise.resolve({ players: [], missing: [] }),
}))

const store = vi.hoisted(() => ({
  live: null as unknown,
  listeners: new Set<() => void>(),
}))
vi.mock('../useLiveDraft', async () => {
  const React = await import('react')
  return {
    useLiveDraft: () => {
      const [, force] = React.useReducer((x: number) => x + 1, 0)
      React.useEffect(() => {
        store.listeners.add(force)
        return () => {
          store.listeners.delete(force)
        }
      }, [])
      return { live: store.live, connected: true, secondsSinceContact: 0, error: null }
    },
  }
})

vi.mock('../components/DraftBoard', () => ({ default: () => null }))
vi.mock('../components/AvailabilityPanel', () => ({ default: () => null }))
vi.mock('../components/LiveStatusBar', () => ({ default: () => null }))
vi.mock('../components/PlayerCard', () => ({ default: () => null }))
vi.mock('../components/SeatPopover', () => ({ default: () => null }))

const TEAMS = 4
const DEBOUNCE = 1500
const RESULT = { board: [], myPicks: [], teams: TEAMS } as unknown as SimulationResult

const all: RealPick[] = []
function draftThrough(n: number): RealPick[] {
  while (all.length < n) {
    const i = all.length + 1
    all.push(mkRealPick(i, TEAMS, mkPlayer(i % 2 ? 'K' : 'DEF', `Player ${i}`)))
  }
  return all.slice(0, n)
}

function pushLive(picks: RealPick[], over: Partial<LiveState> = {}) {
  store.live = mkLive(picks, TEAMS, over)
  act(() => store.listeners.forEach((f) => f()))
}

async function flush() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

async function advance(ms: number) {
  await act(async () => {
    vi.advanceTimersByTime(ms)
  })
}

async function renderRoom(initialPicks: number, boardPicks?: RealPick[], live?: LiveState) {
  const picks = draftThrough(initialPicks)
  store.live = live ?? mkLive(picks, TEAMS)
  getRealDraftBoard.mockResolvedValue({
    draftId: 'd1',
    teams: TEAMS,
    rounds: 15,
    status: 'drafting',
    picks: boardPicks ?? picks,
  })
  render(
    <MemoryRouter initialEntries={['/live/d1']}>
      <Routes>
        <Route path="/live/:draftId" element={<LiveDraftView />} />
      </Routes>
    </MemoryRouter>,
  )
  await flush()
  await flush()
}

/** Let the opening run (seats + board load) fire and settle, then forget it. */
async function settleFirstRun() {
  await advance(DEBOUNCE)
  await flush()
  streamSimulationQuietly.mockClear()
}

function lastRequest() {
  const calls = streamSimulationQuietly.mock.calls
  return calls[calls.length - 1][0] as { startState?: Record<number, string> }
}

function maxKey(o: Record<number, string>) {
  return Math.max(...Object.keys(o).map(Number))
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false })
  all.length = 0
  localStorage.clear()
  getRealDraftBoard.mockReset()
  getSeats.mockReset()
  getDrafts.mockReset()
  streamSimulationQuietly.mockReset()
  getSeats.mockResolvedValue(mkSeats(TEAMS))
  getDrafts.mockResolvedValue([])
  streamSimulationQuietly.mockResolvedValue(RESULT)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('per-pick re-projection', () => {
  it('a new landed pick triggers exactly one debounced run', async () => {
    await renderRoom(4)
    await settleFirstRun()
    pushLive(draftThrough(5))
    await advance(DEBOUNCE - 100)
    expect(streamSimulationQuietly).not.toHaveBeenCalled()
    await advance(200)
    expect(streamSimulationQuietly).toHaveBeenCalledTimes(1)
  })

  it('a burst of three picks inside the debounce is one run that knows all three', async () => {
    await renderRoom(4)
    await settleFirstRun()
    pushLive(draftThrough(5))
    await advance(400)
    pushLive(draftThrough(6))
    await advance(400)
    pushLive(draftThrough(7))
    await advance(DEBOUNCE + 100)
    expect(streamSimulationQuietly).toHaveBeenCalledTimes(1)
    expect(maxKey(lastRequest().startState!)).toBe(7)
  })

  it('sends startState equal to the landed list when it is whole; its max is the stamp', async () => {
    await renderRoom(6)
    await advance(DEBOUNCE)
    const expected = Object.fromEntries(draftThrough(6).map((p) => [p.pickNo, p.player.sleeperId]))
    expect(lastRequest().startState).toEqual(expected)
    // asOfPick is exactly this prefix's highest pickNo (no separate readout
    // exists until the card consumes it).
    expect(maxKey(lastRequest().startState!)).toBe(6)
  })

  it('sends no startState when a pick is missing', async () => {
    const picks = draftThrough(6)
    // The frame carries only 4-6 and the board fetch lacks pick 2.
    await renderRoom(6, picks.filter((p) => p.pickNo !== 2), mkLive(picks.slice(3), TEAMS, { picksMade: 6 }))
    await advance(DEBOUNCE)
    expect(streamSimulationQuietly).toHaveBeenCalled()
    expect('startState' in lastRequest()).toBe(false)
  })

  it('sends no startState when a landed pick has no sleeperId', async () => {
    const picks = draftThrough(6).map((p) =>
      p.pickNo === 3 ? { ...p, player: { ...p.player, sleeperId: '' } } : p,
    )
    await renderRoom(6, picks, mkLive(picks, TEAMS))
    await advance(DEBOUNCE)
    expect(streamSimulationQuietly).toHaveBeenCalled()
    expect('startState' in lastRequest()).toBe(false)
  })

  it('a 429 shows no error banner, is not retried here, and the next pick tries again', async () => {
    await renderRoom(4)
    await settleFirstRun()
    streamSimulationQuietly.mockRejectedValueOnce(new ApiError(429, 'busy', 2))
    pushLive(draftThrough(5))
    await advance(DEBOUNCE + 100)
    await flush()
    expect(streamSimulationQuietly).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.error')).toBeNull()
    await advance(10_000)
    expect(streamSimulationQuietly).toHaveBeenCalledTimes(1)

    pushLive(draftThrough(6))
    await advance(DEBOUNCE + 100)
    expect(streamSimulationQuietly).toHaveBeenCalledTimes(2)
  })

  it('any other failure still raises the page error', async () => {
    await renderRoom(4)
    await settleFirstRun()
    streamSimulationQuietly.mockRejectedValueOnce(new ApiError(500, 'boom'))
    pushLive(draftThrough(5))
    await advance(DEBOUNCE + 100)
    await flush()
    expect(document.querySelector('.error')?.textContent).toContain('boom')
  })
})
