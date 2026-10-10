import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlayerRef, RealPick, SimulationResult } from '../api'
import SeatPopover from '../components/SeatPopover'
import { mkLive, mkPlayer, mkRealPick, mkSeat, mkSeats } from '../testLiveRoom'
import LiveDraftView from './LiveDraftView'

/**
 * The scarcity meter, the room read and their card / popover extras as the live
 * page wires them (spec 012 T034, T038).
 */

const getRealDraftBoard = vi.fn()
const getSeats = vi.fn()
const getDrafts = vi.fn()
const getDraftPool = vi.fn()
const streamSimulationQuietly = vi.fn()
vi.mock('../api', () => ({
  getRealDraftBoard: (...a: unknown[]) => getRealDraftBoard(...a),
  getSeats: (...a: unknown[]) => getSeats(...a),
  getDrafts: (...a: unknown[]) => getDrafts(...a),
  getDraftPool: (...a: unknown[]) => getDraftPool(...a),
  getManagers: () => Promise.resolve([]),
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
vi.mock('../components/PlayerCard', () => ({ default: () => null }))
// A stand-in whose only job is to open a seat's popover.
vi.mock('../components/LiveStatusBar', () => ({
  default: ({ onSeatClick }: { onSeatClick: (slot: number) => void }) => (
    <button onClick={() => onSeatClick(2)}>open seat 2</button>
  ),
}))

const TEAMS = 4
const DEBOUNCE = 1500

// 40 = 4 teams x 10 NFL starters. 5 RB so a few picks make RB visibly scarce.
const POOL: PlayerRef[] = [
  ...Array.from({ length: 5 }, (_, i) => mkPlayer('RB', `RB${i + 1}`, i + 1)),
  ...Array.from({ length: 12 }, (_, i) => mkPlayer('WR', `WR${i + 1}`, i + 6)),
  ...Array.from({ length: 8 }, (_, i) => mkPlayer('QB', `QB${i + 1}`, i + 20)),
  ...Array.from({ length: 8 }, (_, i) => mkPlayer('TE', `TE${i + 1}`, i + 30)),
  ...Array.from({ length: 4 }, (_, i) => mkPlayer('K', `K${i + 1}`, i + 40)),
  ...Array.from({ length: 3 }, (_, i) => mkPlayer('DEF', `DEF${i + 1}`, i + 50)),
]
const byName = (n: string) => POOL.find((p) => p.name === n)!
// Who lands, in pick order: RB, WR, RB, then (raised by a test) RB.
const ORDER = ['RB1', 'WR1', 'RB2', 'RB3']

const all: RealPick[] = []
function draftThrough(n: number): RealPick[] {
  while (all.length < n) {
    const i = all.length + 1
    all.push(mkRealPick(i, TEAMS, byName(ORDER[i - 1])))
  }
  return all.slice(0, n)
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

/** A projection pinned to pick 3: the undrafted RBs all survive to pick 8 (2.04). */
function projection(): SimulationResult {
  return {
    board: [],
    myPicks: [8],
    teams: TEAMS,
    availability: POOL.map((player) => ({ player, survivalByPick: { '8': 1 } })),
  } as unknown as SimulationResult
}

async function renderRoom(initialPicks: number, firstRun?: SimulationResult) {
  const picks = draftThrough(initialPicks)
  store.live = mkLive(picks, TEAMS)
  getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 15, status: 'drafting', picks })
  if (firstRun) streamSimulationQuietly.mockResolvedValueOnce(firstRun)
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

const chip = (pos: string) => screen.getByText(pos, { selector: '.scarcity-pos' }).closest('li') as HTMLElement

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false })
  all.length = 0
  localStorage.clear()
  for (const m of [getRealDraftBoard, getSeats, getDrafts, getDraftPool, streamSimulationQuietly]) m.mockReset()
  getSeats.mockResolvedValue(mkSeats(TEAMS))
  getDrafts.mockResolvedValue([])
  getDraftPool.mockResolvedValue(POOL)
  streamSimulationQuietly.mockReturnValue(new Promise(() => {}))
})

afterEach(() => {
  vi.useRealTimers()
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
})

describe('scarcity meter on the live page', () => {
  it('counts "left now" as the mocked pool minus the landed picks', async () => {
    await renderRoom(3)
    expect(getDraftPool).toHaveBeenCalledWith('d1', 40)
    expect(chip('RB')).toHaveTextContent('3 / 5') // RB1, RB2 gone
    expect(chip('WR')).toHaveTextContent('11 / 12') // WR1 gone
    expect(chip('QB')).toHaveTextContent('8 / 8')
  })

  it('shows "no board built yet" when the pool fetch rejects, and the rest of the page still renders', async () => {
    getDraftPool.mockRejectedValue(new Error('404'))
    await renderRoom(3)
    expect(screen.getByText('no board built yet')).toBeInTheDocument()
    expect(screen.queryByText('Room read')).toBeInTheDocument()
    expect(screen.getByText('Your team')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('shows the expected count at my next pick from a projection pinned to the current state', async () => {
    await renderRoom(3, projection())
    expect(chip('RB')).toHaveTextContent(/~3 at 2\.04/)
  })

  it('omits the expected count once the newest stamp is older than the highest landed pick', async () => {
    await renderRoom(3, projection())
    expect(chip('RB')).toHaveTextContent(/at 2\.04/)
    // A fourth pick lands; its run has not started (debounce), so the only
    // stamp is asOfPick 3 against a highest landed of 4.
    pushLive(draftThrough(4))
    expect(chip('RB')).not.toHaveTextContent(/at 2\.04/)
    expect(chip('RB')).toHaveTextContent('2 / 5')
  })
})

describe('room read and card extras', () => {
  it('renders the collapsed Room read panel', async () => {
    await renderRoom(3)
    // Spec 024 FR-001c: Room read is a toggle in the compact row that opens a popover.
    const toggle = screen.getByRole('button', { name: 'Room read' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(document.querySelector('.compact-popover')).toBeNull()
  })

  it('the pick card shows the drafting manager on-brand line and the scarcity line', async () => {
    await renderRoom(3)
    pushLive(draftThrough(4))
    const card = screen.getByRole('dialog', { name: /RB3/ })
    // Pick 4 is slot 4's first: one RB in its mix.
    expect(card.querySelector('.pick-card-onbrand')).toHaveTextContent('1 RB')
    // RB: 5 in the pool, 3 gone, so 2 left -- at or under SCARCE_LEFT.
    expect(card.querySelector('.pick-card-scarcity')).toHaveTextContent('RB: 2 of 5 starter-pool players left')
  })

  it('the pick card shows no scarcity line for a plentiful position', async () => {
    await renderRoom(1)
    pushLive(draftThrough(2)) // WR1 -> 11 of 12 WR left
    const card = screen.getByRole('dialog', { name: /WR1/ })
    expect(card.querySelector('.pick-card-scarcity')).toBeNull()
  })

  it('the seat popover carries the on-brand line when opened from the live room', async () => {
    await renderRoom(3)
    // The real SeatPopover is unmocked here.
    fireEvent.click(screen.getByText('open seat 2'))
    await flush()
    expect(document.querySelector('.seat-onbrand .onbrand-line')).not.toBeNull()
  })
})

describe('SeatPopover without the optional prop', () => {
  it('renders as before, with no on-brand line', async () => {
    render(
      <SeatPopover
        seat={mkSeat(2)}
        sport="nfl"
        isMe={false}
        onChanged={() => {}}
        onClose={() => {}}
        onMakeMine={() => {}}
      />,
    )
    await flush()
    expect(document.querySelector('.onbrand-line')).toBeNull()
    expect(document.querySelector('.seat-onbrand')).toBeNull()
  })
})
