import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LiveState, RealPick } from '../api'
import { mkLive, mkPlayer, mkRealPick, mkSeats } from '../testLiveRoom'
import LiveDraftView from './LiveDraftView'

/**
 * The feed's fit clause is a fact about landed picks, so it must not wait on a
 * simulation. Before spec 012 the page only trusted its landed list once a
 * projection had returned, so opening mid-draft showed names but no fit for as
 * long as the first run took.
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

let liveValue: LiveState | null = null
vi.mock('../useLiveDraft', () => ({
  useLiveDraft: () => ({ live: liveValue, connected: true, secondsSinceContact: 0, error: null }),
}))

// Captures what the grid is handed, so the board overlay can be asserted
// without rendering 210 cells.
const boardSeen = vi.hoisted(() => ({ last: [] as { pickNo: number; player: { name: string } }[] }))
vi.mock('../components/DraftBoard', () => ({
  default: (props: { board: { pickNo: number; player: { name: string } }[] }) => {
    boardSeen.last = props.board
    return null
  },
}))
vi.mock('../components/AvailabilityPanel', () => ({ default: () => null }))
vi.mock('../components/LiveStatusBar', () => ({ default: () => null }))
vi.mock('../components/PlayerCard', () => ({ default: () => null }))
vi.mock('../components/SeatPopover', () => ({ default: () => null }))

const TEAMS = 14

function draftThrough(n: number): RealPick[] {
  const picks: RealPick[] = []
  for (let i = 1; i <= n; i++) {
    // Slot 2 owns picks 2, 27 and 30: an RB, a WR, then an RB that fills RB2.
    // Everyone else takes K/DEF: a run of any real position would take the
    // feed's trailing slot and hide the fit clause under test.
    const pos = i === 2 || i === 30 ? 'RB' : i === 27 ? 'WR' : i % 2 ? 'K' : 'DEF'
    picks.push(mkRealPick(i, TEAMS, mkPlayer(pos, `Player ${i}`)))
  }
  return picks
}

function renderRoom() {
  return render(
    <MemoryRouter initialEntries={['/live/d1']}>
      <Routes>
        <Route path="/live/:draftId" element={<LiveDraftView />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  getRealDraftBoard.mockReset()
  getSeats.mockReset()
  getDrafts.mockReset()
  streamSimulationQuietly.mockReset()
  getSeats.mockResolvedValue(mkSeats(TEAMS))
  getDrafts.mockResolvedValue([])
  // A simulation that never comes back: nothing below may depend on it.
  streamSimulationQuietly.mockReturnValue(new Promise(() => {}))
})

describe('the landed list comes from facts', () => {
  it('shows the newest pick\'s fit at pick 30 before any simulation resolves', async () => {
    const all = draftThrough(30)
    liveValue = mkLive(all, TEAMS)
    getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 15, status: 'drafting', picks: all })

    renderRoom()

    // recentPicks holds only picks 19-30; the fit needs slot 2's pick at
    // number 2, which only the board fetch has.
    await waitFor(() => expect(screen.getByText('Fills RB2')).toBeTruthy())
    expect(streamSimulationQuietly).not.toHaveBeenCalled()
  })

  it('paints every landed pick on the board before any simulation resolves', async () => {
    // Found in live verification (T042): the grid overlaid only the state
    // frame's last dozen, so a room opened at pick 30 showed rounds 1-2 blank
    // until the first projection returned, despite knowing every pick.
    const all = draftThrough(30)
    liveValue = mkLive(all, TEAMS)
    getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 15, status: 'drafting', picks: all })

    renderRoom()

    await waitFor(() => expect(boardSeen.last.length).toBe(30))
    expect(boardSeen.last[0]).toMatchObject({ pickNo: 1, player: { name: 'Player 1' } })
    expect(streamSimulationQuietly).not.toHaveBeenCalled()
  })

  it('says nothing about fit while the seat\'s earlier picks are unknown', async () => {
    const all = draftThrough(30)
    liveValue = mkLive(all, TEAMS)
    getRealDraftBoard.mockRejectedValue(new Error('404'))

    renderRoom()

    await waitFor(() => expect(getRealDraftBoard).toHaveBeenCalled())
    // Names are facts and still render; the clause is not stated from a roster
    // that is missing its round-1 pick.
    expect(screen.getByText('Player 30')).toBeTruthy()
    expect(screen.queryByText(/^Fills /)).toBeNull()
    expect(screen.queryByText('Depth')).toBeNull()
  })

  it('a hole in another seat\'s picks does not mute the fit clause', async () => {
    const all = draftThrough(30).filter((p) => p.pickNo !== 5) // slot 5's pick
    liveValue = mkLive(draftThrough(30), TEAMS)
    getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 15, status: 'drafting', picks: all })

    renderRoom()

    await waitFor(() => expect(screen.getByText('Fills RB2')).toBeTruthy())
  })
})
