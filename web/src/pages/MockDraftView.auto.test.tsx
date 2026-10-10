import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockPick, MockSessionState } from '../api'
import { ApiError } from '../apiError'
import { mkPlayer } from '../testLiveRoom'
import MockDraftView from './MockDraftView'

const getMockSession = vi.fn()
const autoMock = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getMockSession: (...a: unknown[]) => getMockSession(...a),
  autoMock: (...a: unknown[]) => autoMock(...a),
  getTargets: () => Promise.resolve({ players: [], missing: [] }),
  putTargets: () => Promise.resolve({ players: [], missing: [] }),
}))

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})
beforeEach(() => {
  getMockSession.mockReset()
  autoMock.mockReset()
})

const taken = mkPlayer('RB', 'Auto Picked Guy', 3)

function session(over: Partial<MockSessionState> = {}): MockSessionState {
  const pick = (pickNo: number, source: MockPick['source'], player = mkPlayer('WR', `Pick ${pickNo}`, pickNo)): MockPick => ({
    pickNo,
    round: 1,
    draftSlot: pickNo,
    seatType: source === 'BOT' ? 'BOT' : 'USER',
    source,
    player,
  })
  return {
    id: 7,
    sport: 'nfl',
    status: 'IN_PROGRESS',
    teams: 2,
    rounds: 2,
    rosterPositions: ['RB', 'WR', 'BN'],
    userSlot: 1,
    myPicks: [1, 4],
    seats: [
      { slot: 1, type: 'USER', managerId: null, manager: 'You', avatarId: null },
      { slot: 2, type: 'BOT', managerId: null, manager: 'Bot', avatarId: null },
    ],
    picks: [pick(1, 'BOT'), pick(2, 'AUTO', taken)],
    available: [mkPlayer('QB', 'Still Here', 20)],
    currentPickNo: 3,
    onTheClockSlot: 1,
    isUsersTurn: true,
    sourceDraftId: null,
    forkedAtPickNo: null,
    reversalRound: 0,
    ...over,
  } as MockSessionState
}

function renderRoom() {
  return render(
    <MemoryRouter initialEntries={['/mock/7']}>
      <Routes>
        <Route path="/mock/:sessionId" element={<MockDraftView />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('MockDraftView auto controls', () => {
  it('offers Auto-pick on your turn and Auto-finish always, and tags the auto pick in the feed', async () => {
    getMockSession.mockResolvedValue(session())
    const { container } = renderRoom()
    expect(await screen.findByRole('button', { name: 'Auto-pick' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Auto-finish the draft' })).toBeEnabled()
    // The AUTO pick is the user's (counted as USER) and carries the feed tag.
    const tag = container.querySelector('.pick-feed-auto')
    expect(tag).not.toBeNull()
  })

  it('Auto-finish disables itself while the request runs, then shows the returned state', async () => {
    getMockSession.mockResolvedValue(session())
    let finish!: (s: MockSessionState) => void
    autoMock.mockReturnValue(new Promise<MockSessionState>((r) => (finish = r)))
    renderRoom()
    await userEvent.click(await screen.findByRole('button', { name: 'Auto-finish the draft' }))
    expect(autoMock).toHaveBeenCalledWith(7, 'FINISH')
    expect(await screen.findByRole('button', { name: 'Finishing…' })).toBeDisabled()
    finish(session({ status: 'COMPLETE', isUsersTurn: false, onTheClockSlot: null, available: [] }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Auto-finish the draft' })).toBeDisabled())
    expect(screen.queryByRole('button', { name: 'Auto-pick' })).toBeNull()
    expect(screen.getByText('Draft complete')).toBeInTheDocument()
  })

  it('Auto-pick is not offered when it is not your turn', async () => {
    getMockSession.mockResolvedValue(session({ isUsersTurn: false, onTheClockSlot: 2 }))
    renderRoom()
    await screen.findByRole('button', { name: 'Auto-finish the draft' })
    expect(screen.queryByRole('button', { name: 'Auto-pick' })).toBeNull()
  })

  it('hides both controls when an older backend answers 404', async () => {
    getMockSession.mockResolvedValue(session())
    autoMock.mockRejectedValue(new ApiError(404))
    renderRoom()
    await userEvent.click(await screen.findByRole('button', { name: 'Auto-pick' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Auto-finish the draft' })).toBeNull())
    expect(screen.queryByRole('button', { name: 'Auto-pick' })).toBeNull()
    // Not a page error: the 404 is the old backend, not a missing mock.
    expect(screen.queryByText(/HTTP 404/)).toBeNull()
  })
})
