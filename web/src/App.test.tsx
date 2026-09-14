import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { clearUser, setUser, type BkUser } from './user'

// App's route table imports every page unconditionally, so anything that
// would fetch on mount needs a stub -- same convention as
// DraftPicker.test.tsx. Only the pages these tests actually mount need one:
// DraftPicker (signed-in "/") and PowerRankings (signed-in deep link before
// sign-out). Every other page only ever renders while signed out, at which
// point App doesn't mount the route table at all.
vi.mock('./pages/DraftPicker', () => ({
  default: () => <div>draft picker</div>,
}))
vi.mock('./pages/PowerRankings', () => ({
  default: () => <div>power rankings</div>,
}))
// Mounted by the rail-collapse test below; the real one fetches on mount.
vi.mock('./pages/DraftView', () => ({
  default: () => <div>draft view</div>,
}))

// SignIn imports getSleeperUser from here; the real module would hit the
// network the instant a click on Continue happened. Not exercised in these
// tests, but stubbed the same way SignIn.test.tsx does it.
vi.mock('./api', () => ({
  getSleeperUser: vi.fn(),
}))

const sampleUser: BkUser = {
  sleeperUserId: '1122386008709910528',
  username: 'popsharky',
  displayName: 'popsharky',
  avatar: null,
}

// Sibling of <App> inside the same MemoryRouter, so tests can assert on the
// URL a click on Sign out lands on -- App has no observable route indicator
// of its own once the whole table is gated.
function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location-probe">{location.pathname}</div>
}

beforeEach(() => {
  localStorage.clear()
  // localStorage.clear() alone doesn't touch user.ts's module-level `current`
  // -- it's only ever written by setUser/clearUser, which is the real point
  // of using the real module here. Reset it explicitly between tests.
  clearUser()
})

describe('App sign-out gating', () => {
  it('signing out from a deep page shows the username screen and resets the URL to /', async () => {
    setUser(sampleUser)
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
        <LocationProbe />
      </MemoryRouter>,
    )

    expect(await screen.findByText('power rankings')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /sign out/i }))

    expect(screen.getByRole('heading', { name: /who are you\?/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/your sleeper username/i)).toBeInTheDocument()
    // Regex, not the string '/' -- toHaveTextContent does a substring match,
    // so the plain string would also pass against '/leagues/1/power' and this
    // assertion (the only one guarding the navigate) could never fail.
    expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/$/)
  })

  it('a deep link while signed out shows the username screen, not the page it points to', () => {
    render(
      <MemoryRouter initialEntries={['/drafts/abc']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: /who are you\?/i })).toBeInTheDocument()
  })

  it('a junk path while signed out shows the username screen, not the "Nothing here" fallback', () => {
    render(
      <MemoryRouter initialEntries={['/nonsense']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: /who are you\?/i })).toBeInTheDocument()
    expect(screen.queryByText(/nothing here/i)).not.toBeInTheDocument()
  })

  it('signed in at / still renders the picker (the gate did not break the normal case)', async () => {
    setUser(sampleUser)
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByText('draft picker')).toBeInTheDocument()
  })

  // These two replace a pair that guarded the opposite arrangement: the rail
  // shipped Home-only, so the old tests asserted that "/" had no nav chrome
  // and every other route did. That split is the thing
  // claude/site-wide-shell-propagation.md removed -- there is one shell now,
  // and these assert the new invariant rather than the old one.
  it('renders no rail while signed out -- SignIn is the whole screen', () => {
    render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Home' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /sign out/i })).not.toBeInTheDocument()
  })

  it.each([['/'], ['/leagues/1/power']])(
    'renders exactly one rail, with the same nav, on %s',
    async (path) => {
      setUser(sampleUser)
      render(
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>,
      )

      // getAllBy + length, not getBy: getBy already throws on a duplicate, but
      // the failure this guards (a rail from the shell AND a rail from the
      // page) reads as "found multiple elements" rather than as the two-shell
      // regression it would actually be.
      expect(await screen.findAllByRole('navigation', { name: 'Main' })).toHaveLength(1)
      expect(screen.getByRole('link', { name: 'Managers' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument()
    },
  )

  it('collapses the rail by default in a draft room and not on a content page', async () => {
    setUser(sampleUser)
    const { unmount } = render(
      <MemoryRouter initialEntries={['/drafts/abc']}>
        <App />
      </MemoryRouter>,
    )
    // aria-expanded on the toggle is the collapsed state made observable --
    // asserting on the class would just restate the implementation.
    expect(await screen.findByRole('button', { name: /expand navigation/i })).toBeInTheDocument()
    unmount()

    render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByRole('button', { name: /collapse navigation/i })).toBeInTheDocument()
  })

  it('an explicit collapse choice outranks the route default and survives a remount', async () => {
    setUser(sampleUser)
    const user = userEvent.setup()
    const { unmount } = render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: /collapse navigation/i }))
    expect(screen.getByRole('button', { name: /expand navigation/i })).toBeInTheDocument()
    unmount()

    // Same content route, which defaults to expanded -- so an expanded rail
    // here would mean the stored choice was ignored, not that nothing broke.
    render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByRole('button', { name: /expand navigation/i })).toBeInTheDocument()
  })
})
