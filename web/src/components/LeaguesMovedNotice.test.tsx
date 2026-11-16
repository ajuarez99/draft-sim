import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LeaguesMovedNotice from './LeaguesMovedNotice'
import { clearUser, setUser } from '../user'
import { MOVED_DISMISSED_KEY, SIGNED_IN_HERE_KEY } from '../tmSignedInHere'
import { SAVED_KEY } from '../statCalcSaved'

// Spec 073 T024 / FR-019: shown only on the Time Machine site, signed in, with no "signed in here" marker
// and no dismissal.

const signIn = () => setUser({ sleeperUserId: '1', username: 'popsharky', displayName: 'Pop', avatar: null })
const notice = () => screen.queryByRole('complementary', { name: 'Your leagues moved' })
const renderNotice = () => render(<MemoryRouter><LeaguesMovedNotice /></MemoryRouter>)

beforeEach(() => {
  localStorage.clear()
  vi.stubEnv('VITE_SITE', 'timemachine')
  vi.stubEnv('VITE_LEAGUES_ORIGIN', 'https://leagues.ballknowers.co')
  signIn()
})

afterEach(() => {
  clearUser()
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('LeaguesMovedNotice', () => {
  it('shows to a browser that was signed in before the switch (no marker, not dismissed)', () => {
    renderNotice()
    expect(notice()).not.toBeNull()
    expect(screen.getByText(/Looking for your leagues\?/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'leagues.ballknowers.co' })).toHaveAttribute('href', 'https://leagues.ballknowers.co/')
  })

  it('is hidden when the person signed in on this site (marker present)', () => {
    localStorage.setItem(SIGNED_IN_HERE_KEY, '1')
    renderNotice()
    expect(notice()).toBeNull()
  })

  it('is hidden once dismissed', () => {
    localStorage.setItem(MOVED_DISMISSED_KEY, '1')
    renderNotice()
    expect(notice()).toBeNull()
  })

  it('is hidden signed out', () => {
    clearUser()
    renderNotice()
    expect(notice()).toBeNull()
  })

  it('is hidden in the combined and league builds', () => {
    for (const v of ['', 'leagues']) {
      vi.stubEnv('VITE_SITE', v)
      const { unmount } = renderNotice()
      expect(notice()).toBeNull()
      unmount()
    }
  })

  it('dismiss hides it for good', async () => {
    const user = userEvent.setup()
    const { unmount } = renderNotice()
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(notice()).toBeNull()
    expect(localStorage.getItem(MOVED_DISMISSED_KEY)).toBe('1')
    unmount()
    renderNotice()
    expect(notice()).toBeNull()
  })

  it('following the link sets the dismissed key too', async () => {
    const user = userEvent.setup()
    renderNotice()
    // jsdom cannot navigate; the click default is prevented from reaching it by a no-op handler on the document.
    document.addEventListener('click', (e) => e.preventDefault(), { once: true })
    await user.click(screen.getByRole('link', { name: 'leagues.ballknowers.co' }))
    expect(localStorage.getItem(MOVED_DISMISSED_KEY)).toBe('1')
  })

  it('middle-click and ctrl-click on the link set the dismissed key too (CR-10)', async () => {
    const user = userEvent.setup()
    const { unmount } = renderNotice()
    const link = screen.getByRole('link', { name: 'leagues.ballknowers.co' })
    link.dispatchEvent(new MouseEvent('auxclick', { bubbles: true, cancelable: true, button: 1 }))
    expect(localStorage.getItem(MOVED_DISMISSED_KEY)).toBe('1')
    localStorage.removeItem(MOVED_DISMISSED_KEY)
    unmount()
    renderNotice()
    document.addEventListener('click', (e) => e.preventDefault(), { once: true })
    await user.keyboard('{Control>}')
    await user.click(screen.getByRole('link', { name: 'leagues.ballknowers.co' }))
    await user.keyboard('{/Control}')
    expect(localStorage.getItem(MOVED_DISMISSED_KEY)).toBe('1')
  })

  it('the link carries saved formulas through the same helper the redirects use', () => {
    localStorage.setItem(
      SAVED_KEY,
      JSON.stringify({ v: 1, formulas: [{ id: 'a', name: 'Mine', expr: 'ast / tov', settings: {}, savedAt: '' }] }),
    )
    renderNotice()
    expect(screen.getByRole('link', { name: 'leagues.ballknowers.co' }).getAttribute('href')).toMatch(
      /^https:\/\/leagues\.ballknowers\.co\/#bk-carry=[A-Za-z0-9_-]+$/,
    )
  })

  it('with storage blocked it still shows, and dismiss lasts for the page load', async () => {
    const user = userEvent.setup()
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    renderNotice()
    expect(notice()).not.toBeNull()
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(notice()).toBeNull()
  })
})
