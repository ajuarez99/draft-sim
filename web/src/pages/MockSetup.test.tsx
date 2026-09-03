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

describe('MockSetup', () => {
  it('defaults to 10 teams, slot 1, and creates a session with those values', async () => {
    createMockSession.mockResolvedValue({ id: 42 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(10, 1, {})
  })

  it('navigates to the new session on success', async () => {
    createMockSession.mockResolvedValue({ id: 42 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(navigate).toHaveBeenCalledWith('/mock/42')
  })

  it('passes the selected team size and slot through to the request', async () => {
    createMockSession.mockResolvedValue({ id: 7 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.selectOptions(screen.getByLabelText('teams'), '8')
    await user.selectOptions(screen.getByLabelText('your slot'), '5')
    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(8, 5, {})
  })

  it('resets an out-of-range slot when teams shrinks below it', async () => {
    createMockSession.mockResolvedValue({ id: 9 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.selectOptions(screen.getByLabelText('teams'), '14')
    await user.selectOptions(screen.getByLabelText('your slot'), '12')
    await user.selectOptions(screen.getByLabelText('teams'), '8')
    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(8, 1, {})
  })

  it('shows an error message and re-enables the form when creation fails', async () => {
    createMockSession.mockRejectedValue(new Error('board is empty — run ingest first'))
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(await screen.findByText('board is empty — run ingest first')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start mock draft/i })).not.toBeDisabled()
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
      await user.click(screen.getByRole('button', { name: /start mock draft/i }))

      expect(createMockSession).toHaveBeenCalledWith(10, 1, { 2: 42 })
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
      await user.selectOptions(screen.getByLabelText('teams'), '14')
      await user.selectOptions(screen.getByLabelText('your slot'), '9')
      const seat1 = await screen.findByLabelText('seat 1')
      await user.selectOptions(seat1, '42')
      await user.selectOptions(screen.getByLabelText('teams'), '8')
      await user.click(screen.getByRole('button', { name: /start mock draft/i }))

      expect(createMockSession).toHaveBeenCalledWith(8, 1, {})
    })

    it('drops an assigned manager seat when the user claims that slot instead', async () => {
      createMockSession.mockResolvedValue({ id: 12 })
      const user = userEvent.setup()
      render(<MockSetup />)

      const seat2 = await screen.findByLabelText('seat 2')
      await user.selectOptions(seat2, '42')
      await user.selectOptions(screen.getByLabelText('your slot'), '2')
      await user.click(screen.getByRole('button', { name: /start mock draft/i }))

      expect(createMockSession).toHaveBeenCalledWith(10, 2, {})
    })
  })
})
