import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MockSetup from './MockSetup'

const navigate = vi.fn()
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
}))

const createMockSession = vi.fn()
vi.mock('../api', () => ({
  createMockSession: (...args: unknown[]) => createMockSession(...args),
}))

beforeEach(() => {
  navigate.mockClear()
  createMockSession.mockReset()
})

describe('MockSetup', () => {
  it('defaults to 10 teams, slot 1, and creates a session with those values', async () => {
    createMockSession.mockResolvedValue({ id: 42 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(10, 1)
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

    expect(createMockSession).toHaveBeenCalledWith(8, 5)
  })

  it('resets an out-of-range slot when teams shrinks below it', async () => {
    createMockSession.mockResolvedValue({ id: 9 })
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.selectOptions(screen.getByLabelText('teams'), '14')
    await user.selectOptions(screen.getByLabelText('your slot'), '12')
    await user.selectOptions(screen.getByLabelText('teams'), '8')
    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(createMockSession).toHaveBeenCalledWith(8, 1)
  })

  it('shows an error message and re-enables the form when creation fails', async () => {
    createMockSession.mockRejectedValue(new Error('board is empty — run ingest first'))
    const user = userEvent.setup()
    render(<MockSetup />)

    await user.click(screen.getByRole('button', { name: /start mock draft/i }))

    expect(await screen.findByText('board is empty — run ingest first')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start mock draft/i })).not.toBeDisabled()
  })
})
