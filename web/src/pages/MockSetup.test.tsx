import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MockSetup from './MockSetup'

const navigate = vi.fn()
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
}))

const createMockSession = vi.fn()
const getManagers = vi.fn()
vi.mock('../api', () => ({
  createMockSession: (...args: unknown[]) => createMockSession(...args),
  getManagers: (...args: unknown[]) => getManagers(...args),
}))

beforeEach(() => {
  navigate.mockClear()
  createMockSession.mockReset()
  getManagers.mockReset()
  getManagers.mockResolvedValue([])
})

// The seat strip labels a seat by its round-1 pick, not by "seat N" -- e.g.
// under 8 teams, slot 5 is pick "1.05". These helpers turn "click the seat
// at this slot" (what the old tests did via the "Your slot" select) into
// "click the button with this pick's accessible name" (what a seat-strip
// click looks like now).
function pickLabel(slot: number, teams: number) {
  const round = Math.ceil(slot / teams)
  const inRound = slot - (round - 1) * teams
  return `${round}.${String(inRound).padStart(2, '0')}`
}

async function claimSeat(user: ReturnType<typeof userEvent.setup>, slot: number, teams: number) {
  await user.click(screen.getByRole('button', { name: `Take pick ${pickLabel(slot, teams)} as your seat` }))
}

async function setTeams(user: ReturnType<typeof userEvent.setup>, teams: number) {
  await user.click(screen.getByRole('button', { name: String(teams) }))
}

describe('MockSetup', () => {
  it('defaults to 10 teams, slot 1, and creates a session with those values', async () => {
    createMockSession.mockResolvedValue({ id: 42 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start the draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(10, 1, {})
  })

  it('navigates to the new session on success', async () => {
    createMockSession.mockResolvedValue({ id: 42 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start the draft/i }))

    expect(navigate).toHaveBeenCalledWith('/mock/42')
  })

  it('passes the selected team size and slot through to the request', async () => {
    createMockSession.mockResolvedValue({ id: 7 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await setTeams(user, 8)
    await claimSeat(user, 5, 8)
    await user.click(screen.getByRole('button', { name: /start the draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(8, 5, {})
  })

  it('marks the claimed seat as pressed and shows "You" on its face', async () => {
    const user = userEvent.setup()
    render(<MockSetup />)

    await claimSeat(user, 3, 10)

    const seat3 = screen.getByRole('button', { name: 'Your seat, pick 1.03' })
    expect(seat3).toHaveAttribute('aria-pressed', 'true')
    // the seat vacated by the claim reverts to offering itself again
    expect(screen.getByRole('button', { name: 'Take pick 1.01 as your seat' })).toBeInTheDocument()
  })

  it('resets an out-of-range slot when teams shrinks below it', async () => {
    createMockSession.mockResolvedValue({ id: 9 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await setTeams(user, 14)
    await claimSeat(user, 12, 14)
    await setTeams(user, 8)
    await user.click(screen.getByRole('button', { name: /start the draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(8, 1, {})
  })

  it('shows an error message and re-enables the form when creation fails', async () => {
    createMockSession.mockRejectedValue(new Error('board is empty — run ingest first'))
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start the draft/i }))

    expect(await screen.findByText('board is empty — run ingest first')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start the draft/i })).not.toBeDisabled()
  })

  describe('with real managers available', () => {
    const dave = {
      managerId: 42,
      manager: 'Dave',
      provenance: 'FITTED',
      effectiveReachBias: 3.2,
      empiricalReachBias: 3.2,
      unpredictability: 1,
      positionalTilt: {},
      note: null,
      draftsObserved: 2,
      picksScored: 30,
      stated: { reachBias: null, unpredictability: null, note: null },
    }

    beforeEach(() => {
      getManagers.mockResolvedValue([dave])
    })

    it('assigns a manager to a non-user seat and includes it in the request', async () => {
      createMockSession.mockResolvedValue({ id: 11 })
      const user = userEvent.setup()
      render(<MockSetup />)

      const seat2 = await screen.findByLabelText('seat 2')
      await user.selectOptions(seat2, '42')
      await user.click(screen.getByRole('button', { name: /start the draft/i }))

      expect(createMockSession).toHaveBeenCalledWith(10, 1, { 2: 42 })
    })

    it('shows the assigned manager\'s name on the seat face', async () => {
      const user = userEvent.setup()
      render(<MockSetup />)

      const seat2 = await screen.findByLabelText('seat 2')
      await user.selectOptions(seat2, '42')

      const face = screen.getByRole('button', { name: 'Take pick 1.02 as your seat' })
      expect(face).toHaveTextContent('Dave')
    })

    it('does not offer a manager seat at the user\'s own slot', async () => {
      render(<MockSetup />)

      await screen.findByLabelText('seat 2')
      expect(screen.queryByLabelText('seat 1')).not.toBeInTheDocument()
    })

    it('drops a manager seat that collides with userSlot after a team-count shrink resets it', async () => {
      createMockSession.mockResolvedValue({ id: 13 })
      const user = userEvent.setup()
      render(<MockSetup />)

      // teams=14, userSlot=9 puts userSlot out of range once teams shrinks to
      // 8 -- it resets to 1, which must also evict any manager on seat 1.
      await setTeams(user, 14)
      await claimSeat(user, 9, 14)
      const seat1 = await screen.findByLabelText('seat 1')
      await user.selectOptions(seat1, '42')
      await setTeams(user, 8)
      await user.click(screen.getByRole('button', { name: /start the draft/i }))

      expect(createMockSession).toHaveBeenCalledWith(8, 1, {})
    })

    it('drops an assigned manager seat when the user claims that slot instead', async () => {
      createMockSession.mockResolvedValue({ id: 12 })
      const user = userEvent.setup()
      render(<MockSetup />)

      const seat2 = await screen.findByLabelText('seat 2')
      await user.selectOptions(seat2, '42')
      await claimSeat(user, 2, 10)
      await user.click(screen.getByRole('button', { name: /start the draft/i }))

      expect(createMockSession).toHaveBeenCalledWith(10, 2, {})
    })
  })
})
