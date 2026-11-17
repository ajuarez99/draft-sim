import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { lastClaimNote, noteClaim } from '../guest'
import { clearUser, currentUserId } from '../user'
import SignInForm from './SignInForm'

// Spec 074 T032 (PR-18): signing in moves a guest's games to the Sleeper user BEFORE anything can refetch, because setUser
// is what makes every page re-read the user. Sign-in is only the Sleeper username.

const api = vi.hoisted(() => ({ getSleeperUser: vi.fn(), claimGuestGames: vi.fn() }))
vi.mock('../api', () => api)

const found = { sleeperUserId: '77', username: 'popsharky', displayName: 'Pop', avatar: null }

async function signIn() {
  await userEvent.type(screen.getByLabelText(/Your Sleeper username/), 'popsharky')
  await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
}

beforeEach(() => {
  clearUser()
  noteClaim(null)
  api.getSleeperUser.mockReset().mockResolvedValue(found)
  api.claimGuestGames.mockReset()
})
afterEach(() => clearUser())

describe('SignInForm', () => {
  it('claims the guest games first, for the user it found, and only then signs the user in', async () => {
    let release!: (v: { moved: number; leftBehind: number }) => void
    api.claimGuestGames.mockReturnValue(new Promise((r) => (release = r)))
    render(<SignInForm notes={[]} />)
    await signIn()
    await waitFor(() => expect(api.claimGuestGames).toHaveBeenCalledWith('77'))
    // The claim is still in flight: nothing has been told the user exists, so nothing has refetched under the new identity.
    expect(currentUserId()).toBeNull()
    release({ moved: 2, leftBehind: 0 })
    await waitFor(() => expect(currentUserId()).toBe('77'))
    expect(lastClaimNote()).toEqual({ moved: 2, leftBehind: 0 })
  })

  it('a claim that fails does not stop the sign-in', async () => {
    api.claimGuestGames.mockRejectedValue(new Error('network'))
    render(<SignInForm notes={[]} />)
    await signIn()
    await waitFor(() => expect(currentUserId()).toBe('77'))
    expect(screen.queryByText(/Couldn’t reach Sleeper/)).toBeNull()
  })

  it('a name Sleeper does not know claims nothing', async () => {
    api.getSleeperUser.mockResolvedValue(null)
    render(<SignInForm notes={[]} />)
    await signIn()
    expect(await screen.findByText(/No Sleeper user called/)).toBeInTheDocument()
    expect(api.claimGuestGames).not.toHaveBeenCalled()
  })

  it('focuses its field by default, and not when asked not to (a form below the fold would scroll a phone to it)', () => {
    const a = render(<SignInForm notes={[]} />)
    expect(screen.getByLabelText(/Your Sleeper username/)).toHaveFocus()
    a.unmount()
    render(<SignInForm notes={[]} autoFocus={false} />)
    expect(screen.getByLabelText(/Your Sleeper username/)).not.toHaveFocus()
  })
})
