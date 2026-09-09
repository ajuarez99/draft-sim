import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SignIn from './SignIn'

const getSleeperUser = vi.fn()
vi.mock('../api', () => ({
  getSleeperUser: (...args: unknown[]) => getSleeperUser(...args),
}))

const setUser = vi.fn()
vi.mock('../user', () => ({
  setUser: (...args: unknown[]) => setUser(...args),
}))

beforeEach(() => {
  getSleeperUser.mockReset()
  setUser.mockReset()
})

const sampleUser = {
  sleeperUserId: '1122386008709910528',
  username: 'popsharky',
  displayName: 'popsharky',
  avatar: 'e1d4ebf9ea0760f248119d4ec2ac5a63',
}

describe('SignIn', () => {
  it('a real username stores the user, with no navigation call needed', async () => {
    getSleeperUser.mockResolvedValue(sampleUser)
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(getSleeperUser).toHaveBeenCalledWith('popsharky')
    expect(setUser).toHaveBeenCalledWith(sampleUser)
  })

  it('an unknown username shows the "no such user" state, not a generic error', async () => {
    getSleeperUser.mockResolvedValue(null)
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'not-a-real-user')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(await screen.findByText(/no sleeper user called/i)).toBeInTheDocument()
    expect(setUser).not.toHaveBeenCalled()
  })

  it('a network failure shows the unreachable state with a retry, distinct from a typo', async () => {
    getSleeperUser.mockRejectedValue(new Error('network down'))
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(await screen.findByText(/couldn.t reach sleeper/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  it('a backend that 404s the route is unreachable, not a misspelled username', async () => {
    // The regression this exists for: getSleeperUser used to map any 404 to
    // "no such user", so a frontend deployed ahead of its backend told a
    // visitor with a perfectly valid username to check their spelling -- on
    // the gate, with no retry offered. getSleeperUser now throws on a 404 and
    // reserves null for "Sleeper genuinely has no such name", so the two
    // outcomes land in different states here.
    getSleeperUser.mockRejectedValue(new Error('HTTP 404'))
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(await screen.findByText(/couldn.t reach sleeper/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
    expect(screen.queryByText(/check the spelling/i)).not.toBeInTheDocument()
  })

  it('accepts a pasted Sleeper profile URL, extracting just the username', async () => {
    getSleeperUser.mockResolvedValue(sampleUser)
    const user = userEvent.setup()
    render(<SignIn />)

    await user.type(screen.getByLabelText(/your sleeper username/i), 'https://sleeper.com/user/popsharky')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    expect(getSleeperUser).toHaveBeenCalledWith('popsharky')
  })
})
