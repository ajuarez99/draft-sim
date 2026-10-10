import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlayerRef, PredictedPick, RealPick, SimulationResult } from '../api'
import { ApiError } from '../apiError'
import { mkLive, mkPlayer, mkRealPick, mkSeats } from '../testLiveRoom'
import LiveDraftView from './LiveDraftView'

/**
 * The card's projection-dependent rows as the page wires them (spec 012 T026):
 * "updating" until a run covering the pick lands, the landed-player filter,
 * the model share from a PRE-pick run, and freezing (DM-6).
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

const all: RealPick[] = []
function draftThrough(n: number): RealPick[] {
  while (all.length < n) {
    const i = all.length + 1
    all.push(mkRealPick(i, TEAMS, mkPlayer(i % 2 ? 'K' : 'DEF', `Player ${i}`)))
  }
  return all.slice(0, n)
}

function cell(pickNo: number, top: PlayerRef, p: number, alts: [PlayerRef, number][] = []): PredictedPick {
  return {
    pickNo,
    round: Math.ceil(pickNo / TEAMS),
    slot: 1,
    manager: 'Mgr',
    avatarId: null,
    player: top,
    probability: p,
    isModal: true,
    alternatives: alts.map(([player, probability]) => ({ player, probability })),
  }
}

function result(cells: PredictedPick[]): SimulationResult {
  return { board: cells, myPicks: [], teams: TEAMS } as unknown as SimulationResult
}

function pushLive(picks: RealPick[]) {
  store.live = mkLive(picks, TEAMS)
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

/** A run the test resolves (or refuses) by hand. */
function deferred() {
  let resolve!: (r: SimulationResult) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<SimulationResult>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function renderRoom(initialPicks: number, firstRun: SimulationResult) {
  const picks = draftThrough(initialPicks)
  store.live = mkLive(picks, TEAMS)
  getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 15, status: 'drafting', picks })
  streamSimulationQuietly.mockResolvedValueOnce(firstRun)
  render(
    <MemoryRouter initialEntries={['/live/d1']}>
      <Routes>
        <Route path="/live/:draftId" element={<LiveDraftView />} />
      </Routes>
    </MemoryRouter>,
  )
  await flush()
  await flush()
  await advance(DEBOUNCE)
  await flush()
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
})

afterEach(() => {
  vi.useRealTimers()
})

// Plain snake, 4 teams: pick 6 is slot 3's, and that seat's next pick is 11.
describe('card projection rows', () => {
  it('shows a pre-pick model share, then Updating, then a likely next that skips landed players', async () => {
    const gone = draftThrough(5)[0].player // already landed at pick 1
    // Pick 6's player must be the same one the pre-pick run knew about.
    const p6 = mkPlayer('DEF', 'Player 6')
    const six = mkRealPick(6, TEAMS, p6)
    const pre = result([cell(6, mkPlayer('WR', 'Someone Else'), 0.5, [[p6, 0.22]]), cell(11, mkPlayer('WR', 'Stale Guy'), 0.9)])
    await renderRoom(5, pre)

    const run2 = deferred()
    streamSimulationQuietly.mockReturnValueOnce(run2.promise)
    all.push(six)
    pushLive([...all.slice(0, 5), six])
    const card = screen.getByRole('dialog')
    // Fact + pre-pick share are there at once; nothing projected for pick 11 yet.
    expect(within(card).getByText(/Model had this at 22%/)).toBeTruthy()
    expect(within(card).getByText('Likely next @ 3.03')).toBeTruthy()
    expect(within(card).queryByText('Stale Guy')).toBeNull()

    await advance(DEBOUNCE)
    expect(within(screen.getByRole('dialog')).getByText('Updating…')).toBeTruthy()

    await act(async () => {
      run2.resolve(result([cell(11, gone, 0.5, [[mkPlayer('WR', 'Likely Guy'), 0.3]])]))
    })
    const after = within(screen.getByRole('dialog'))
    expect(after.getByText('Likely Guy')).toBeTruthy()
    expect(after.getByText('30%')).toBeTruthy()
    expect(after.queryByText('Player 1')).toBeNull()
  })

  it('freezes a ready card: a later projection does not rewrite an earlier pick', async () => {
    const p6 = mkPlayer('DEF', 'Player 6')
    draftThrough(5)
    const six = mkRealPick(6, TEAMS, p6)
    await renderRoom(5, result([]))

    const run2 = deferred()
    streamSimulationQuietly.mockReturnValueOnce(run2.promise)
    pushLive([...all.slice(0, 5), six])
    await advance(DEBOUNCE)
    await act(async () => {
      run2.resolve(result([cell(11, mkPlayer('WR', 'First Answer'), 0.4)]))
    })
    expect(screen.getByText('First Answer')).toBeTruthy()

    // Pick 7 lands and a newer run says something different about pick 11.
    const seven = mkRealPick(7, TEAMS, mkPlayer('K', 'Player 7'))
    const run3 = deferred()
    streamSimulationQuietly.mockReturnValueOnce(run3.promise)
    pushLive([...all.slice(0, 5), six, seven])
    await advance(DEBOUNCE)
    await act(async () => {
      run3.resolve(result([cell(11, mkPlayer('WR', 'Second Answer'), 0.6)]))
    })

    // Older rows sit behind the compact row's ticker toggle (spec 024 FR-001c).
    fireEvent.click(screen.getByRole('button', { name: /^▾ Last/ }))
    fireEvent.click(screen.getByRole('button', { name: /Player 6/ }))
    const card = within(screen.getByRole('dialog'))
    expect(card.getByText('First Answer')).toBeTruthy()
    expect(card.queryByText('Second Answer')).toBeNull()
  })

  it('a refused run freezes the card as busy', async () => {
    draftThrough(5)
    const six = mkRealPick(6, TEAMS, mkPlayer('DEF', 'Player 6'))
    await renderRoom(5, result([]))

    const run2 = deferred()
    streamSimulationQuietly.mockReturnValueOnce(run2.promise)
    pushLive([...all.slice(0, 5), six])
    await advance(DEBOUNCE)
    await act(async () => {
      run2.reject(new ApiError(429, 'busy'))
    })
    expect(screen.getByText('projection server busy, will retry next pick')).toBeTruthy()
  })
})


