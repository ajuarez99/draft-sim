import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mkLive, mkPlayer, mkRealPick, mkSeats } from '../testLiveRoom'
import LiveDraftView from './LiveDraftView'

/**
 * Spec 052: "Continue as a mock" in the live room records mock-started (from live) once the
 * session exists, and nothing when creating it fails. Scaffolding follows LiveDraftView.card.test.tsx.
 */

const trackAction = vi.fn()
vi.mock('../analytics', () => ({
  trackAction: (...a: unknown[]) => trackAction(...a),
}))

const getRealDraftBoard = vi.fn()
const getSeats = vi.fn()
const getDrafts = vi.fn()
const streamSimulationQuietly = vi.fn()
const createMockSessionFromDraft = vi.fn()
vi.mock('../api', () => ({
  getRealDraftBoard: (...a: unknown[]) => getRealDraftBoard(...a),
  getSeats: (...a: unknown[]) => getSeats(...a),
  getDrafts: (...a: unknown[]) => getDrafts(...a),
  streamSimulationQuietly: (...a: unknown[]) => streamSimulationQuietly(...a),
  createMockSessionFromDraft: (...a: unknown[]) => createMockSessionFromDraft(...a),
  getTargets: () => Promise.resolve({ players: [], missing: [] }),
  putTargets: () => Promise.resolve({ players: [], missing: [] }),
}))

const store = vi.hoisted(() => ({ live: null as unknown }))
vi.mock('../useLiveDraft', () => ({
  useLiveDraft: () => ({ live: store.live, connected: true, secondsSinceContact: 0, error: null }),
}))

vi.mock('../components/DraftBoard', () => ({ default: () => null }))
vi.mock('../components/AvailabilityPanel', () => ({ default: () => null }))
vi.mock('../components/LiveStatusBar', () => ({ default: () => null }))
vi.mock('../components/PlayerCard', () => ({ default: () => null }))
vi.mock('../components/SeatPopover', () => ({ default: () => null }))

const TEAMS = 4

async function renderRoom() {
  const picks = [mkRealPick(1, TEAMS, mkPlayer('K', 'Player 1'))]
  store.live = mkLive(picks, TEAMS)
  getRealDraftBoard.mockResolvedValue({ draftId: 'd1', teams: TEAMS, rounds: 15, status: 'drafting', picks })
  render(
    <MemoryRouter initialEntries={['/live/d1']}>
      <Routes>
        <Route path="/live/:draftId" element={<LiveDraftView />} />
        <Route path="/mock/:id" element={<p>mock room</p>} />
      </Routes>
    </MemoryRouter>,
  )
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

beforeEach(() => {
  trackAction.mockReset()
  createMockSessionFromDraft.mockReset()
  getSeats.mockReset().mockResolvedValue(mkSeats(TEAMS))
  getDrafts.mockReset().mockResolvedValue([])
  streamSimulationQuietly.mockReset().mockReturnValue(new Promise(() => {}))
})

describe('LiveDraftView mock-started analytics', () => {
  it('counts once after the session is created', async () => {
    createMockSessionFromDraft.mockResolvedValue({ id: 'abc' })
    await renderRoom()
    await userEvent.setup().click(screen.getByRole('button', { name: /continue as a mock/i }))
    expect(await screen.findByText('mock room')).toBeInTheDocument()
    expect(trackAction).toHaveBeenCalledTimes(1)
    expect(trackAction).toHaveBeenCalledWith('mock-started', { from: 'live' })
  })

  it('counts nothing when creating the session fails', async () => {
    createMockSessionFromDraft.mockRejectedValue(new Error('fork failed'))
    await renderRoom()
    await userEvent.setup().click(screen.getByRole('button', { name: /continue as a mock/i }))
    await screen.findByText(/fork failed/)
    expect(trackAction).not.toHaveBeenCalled()
  })
})
