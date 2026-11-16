import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TimeMachineSignIn from './TimeMachineSignIn'
import LeaguesMovedNotice from '../components/LeaguesMovedNotice'
import { clearUser, useUser } from '../user'
import { SIGNED_IN_HERE_KEY } from '../tmSignedInHere'

// Spec 073 CR-06 / FR-019: signing in on the Time Machine site writes the "signed in here" marker BEFORE the
// user is stored (storing the user mounts the signed-in screen, which reads it). Only the network call is mocked.

const getSleeperUser = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getSleeperUser: (...a: unknown[]) => getSleeperUser(...a),
}))

const FOUND = { sleeperUserId: '1', username: 'popsharky', displayName: 'Pop', avatar: null }

// What App does: the front door while signed out, the signed-in screen (here just the notice) once a user exists.
function Harness() {
  const user = useUser()
  return user ? <LeaguesMovedNotice /> : <TimeMachineSignIn />
}

const notice = () => screen.queryByRole('complementary', { name: 'Your leagues moved' })

async function submit(name: string) {
  const u = userEvent.setup()
  await u.type(screen.getByLabelText('Your Sleeper username'), name)
  await u.click(screen.getByRole('button', { name: 'Continue' }))
}

beforeEach(() => {
  localStorage.clear()
  clearUser()
  getSleeperUser.mockReset()
  vi.stubEnv('VITE_SITE', 'timemachine')
})
afterEach(() => {
  clearUser()
  vi.unstubAllEnvs()
})

describe('Time Machine sign-in and the "your leagues moved" notice', () => {
  it('writes the marker before the user is set, so the notice never shows to someone who signed in here', async () => {
    getSleeperUser.mockResolvedValue(FOUND)
    let markerWhenUserAppeared: string | null | undefined
    function Spy() {
      const user = useUser()
      if (user && markerWhenUserAppeared === undefined) markerWhenUserAppeared = localStorage.getItem(SIGNED_IN_HERE_KEY)
      return null
    }
    render(<MemoryRouter><Harness /><Spy /></MemoryRouter>)
    await submit('popsharky')
    await waitFor(() => expect(screen.queryByLabelText('Your Sleeper username')).not.toBeInTheDocument())
    expect(markerWhenUserAppeared).toBe('1')
    expect(localStorage.getItem(SIGNED_IN_HERE_KEY)).toBe('1')
    expect(notice()).toBeNull()
  })

  it('a name Sleeper does not know writes no marker and signs nobody in', async () => {
    getSleeperUser.mockResolvedValue(null)
    render(<MemoryRouter><Harness /></MemoryRouter>)
    await submit('nobody')
    expect(await screen.findByText(/No Sleeper user called/)).toBeInTheDocument()
    expect(localStorage.getItem(SIGNED_IN_HERE_KEY)).toBeNull()
  })

  it('an unreachable Sleeper writes no marker', async () => {
    getSleeperUser.mockRejectedValue(new Error('down'))
    render(<MemoryRouter><Harness /></MemoryRouter>)
    await submit('popsharky')
    expect(await screen.findByText(/Couldn’t reach Sleeper/)).toBeInTheDocument()
    expect(localStorage.getItem(SIGNED_IN_HERE_KEY)).toBeNull()
  })
})
