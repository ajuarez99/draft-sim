import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mkLive, mkSeats } from '../testLiveRoom'
import DraftView from './DraftView'
import LiveDraftView from './LiveDraftView'
import FormatSummary from '../components/FormatSummary'

/*
 * Spec 046 FR-015 / FR-014: an auction draft has no pick order, so the two snake-order rooms send it to the ADP
 * page BEFORE any simulation can start; a snake draft is untouched; and the rooms link to the ADP page (NBA only).
 */

const getSeats = vi.fn()
const getDrafts = vi.fn()
const getRealDraftBoard = vi.fn()
const streamSimulation = vi.fn()
const streamSimulationQuietly = vi.fn()
vi.mock('../api', () => ({
  getSeats: (...a: unknown[]) => getSeats(...a),
  getDrafts: (...a: unknown[]) => getDrafts(...a),
  getRealDraftBoard: (...a: unknown[]) => getRealDraftBoard(...a),
  streamSimulation: (...a: unknown[]) => streamSimulation(...a),
  streamSimulationQuietly: (...a: unknown[]) => streamSimulationQuietly(...a),
  getDraftPool: () => Promise.resolve([]),
  createMockSessionFromDraft: vi.fn(),
  setReversalRound: vi.fn(),
  getTargets: () => Promise.resolve({ players: [], missing: [] }),
  putTargets: () => Promise.resolve({ players: [], missing: [] }),
}))

vi.mock('../useLiveDraft', () => ({
  STALE_AFTER_SECONDS: 25,
  useLiveDraft: () => ({ live: mkLive([], 10), connected: true, secondsSinceContact: 0, error: null }),
}))

vi.mock('../components/DraftBoard', () => ({ default: () => null }))
vi.mock('../components/AvailabilityPanel', () => ({ default: () => null }))
vi.mock('../components/PlayerCard', () => ({ default: () => null }))
vi.mock('../components/SeatPopover', () => ({ default: () => null }))

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/drafts/:draftId" element={<DraftView />} />
        <Route path="/drafts/:draftId/live" element={<LiveDraftView />} />
        <Route path="/leagues/:id/adp" element={<p>ADP page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  localStorage.clear()
  getSeats.mockReset()
  getDrafts.mockReset().mockResolvedValue([])
  getRealDraftBoard.mockReset().mockResolvedValue({ draftId: 'd1', teams: 10, rounds: 23, status: 'drafting', picks: [] })
  streamSimulation.mockReset().mockReturnValue(new Promise(() => {}))
  streamSimulationQuietly.mockReset().mockReturnValue(new Promise(() => {}))
})

const auctionSeats = mkSeats(10, { sport: 'nba', draftType: 'auction', sleeperLeagueId: 'L1', rounds: 23 })

describe('an auction draft opened at an old snake-order URL', () => {
  it('/drafts/:id redirects to the league ADP page without starting a simulation', async () => {
    getSeats.mockResolvedValue(auctionSeats)
    renderAt('/drafts/d1')
    expect(await screen.findByText('ADP page')).toBeInTheDocument()
    expect(streamSimulation).not.toHaveBeenCalled()
    expect(streamSimulationQuietly).not.toHaveBeenCalled()
  })

  it('/drafts/:id/live redirects too, and never re-simulates', async () => {
    getSeats.mockResolvedValue(auctionSeats)
    renderAt('/drafts/d1/live')
    expect(await screen.findByText('ADP page')).toBeInTheDocument()
    // Past the resimulation debounce, to prove it was gated and not merely slow.
    await new Promise((r) => setTimeout(r, 400))
    expect(streamSimulationQuietly).not.toHaveBeenCalled()
  })
})

describe('a snake draft is unchanged', () => {
  it('/drafts/:id stays on the room', async () => {
    getSeats.mockResolvedValue(mkSeats(10, { sport: 'nba', draftType: 'snake', sleeperLeagueId: 'L1' }))
    renderAt('/drafts/d1')
    await waitFor(() => expect(getSeats).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 100))
    expect(screen.queryByText('ADP page')).not.toBeInTheDocument()
  })

  it('/drafts/:id/live stays on the room and still simulates', async () => {
    getSeats.mockResolvedValue(mkSeats(10, { sport: 'nba', draftType: 'snake', sleeperLeagueId: 'L1' }))
    renderAt('/drafts/d1/live')
    await waitFor(() => expect(streamSimulationQuietly).toHaveBeenCalled(), { timeout: 3000 })
    expect(screen.queryByText('ADP page')).not.toBeInTheDocument()
  })

  it('a football auction keeps the old routes (the ADP page is basketball-only)', async () => {
    getSeats.mockResolvedValue(mkSeats(10, { sport: 'nfl', draftType: 'auction', sleeperLeagueId: 'L1' }))
    renderAt('/drafts/d1/live')
    await waitFor(() => expect(streamSimulationQuietly).toHaveBeenCalled(), { timeout: 3000 })
    expect(screen.queryByText('ADP page')).not.toBeInTheDocument()
  })
})

describe('the draft room links to the ADP page (FR-014)', () => {
  it('FormatSummary carries the link when it is given one, and not otherwise', () => {
    const { rerender } = render(
      <MemoryRouter>
        <FormatSummary teams={10} rounds={13} draftType="snake" adpHref="/leagues/L1/adp" />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: 'ADP & prices' })).toHaveAttribute('href', '/leagues/L1/adp')
    rerender(
      <MemoryRouter>
        <FormatSummary teams={10} rounds={13} draftType="snake" />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('link', { name: 'ADP & prices' })).not.toBeInTheDocument()
  })

  it('an NBA live room shows it, a football one does not', async () => {
    getSeats.mockResolvedValue(mkSeats(10, { sport: 'nba', draftType: 'snake', sleeperLeagueId: 'L1' }))
    const nba = renderAt('/drafts/d1/live')
    expect(await screen.findByRole('link', { name: 'ADP & prices' })).toHaveAttribute('href', '/leagues/L1/adp')
    nba.unmount()

    getSeats.mockResolvedValue(mkSeats(10, { sport: 'nfl', draftType: 'snake', sleeperLeagueId: 'L1' }))
    renderAt('/drafts/d1/live')
    await waitFor(() => expect(getSeats).toHaveBeenCalledTimes(2))
    await new Promise((r) => setTimeout(r, 100))
    expect(screen.queryByRole('link', { name: 'ADP & prices' })).not.toBeInTheDocument()
  })
})
