import { useState } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { headline, title as landingTitle } from './landingCopy'
import { isCompactChrome } from './components/AppShell'
import { clearUser, setUser, type BkUser } from './user'
import * as api from './api'
import { invalidateRailLeagues } from './railLeague'
import { invalidateLeagueAvailability } from './useLeagueAvailability'

// App's route table imports every page unconditionally, so anything that
// would fetch on mount needs a stub -- same convention as
// DraftPicker.test.tsx. Only the pages these tests actually mount need one:
// DraftPicker (signed-in "/") and PowerRankings (signed-in deep link before
// sign-out). Every other page only ever renders while signed out, at which
// point App doesn't mount the route table at all.
vi.mock('./pages/DraftPicker', () => ({
  default: () => <div>draft picker</div>,
}))
// Stamps each mount with a serial number, so a test can tell "the same instance
// re-rendered" from "a fresh one mounted" -- the difference a route key makes.
const powerMounts = vi.hoisted(() => ({ n: 0 }))
vi.mock('./pages/PowerRankings', () => ({
  default: function PowerRankingsStub() {
    const [instance] = useState(() => ++powerMounts.n)
    return <div data-instance={instance}>power rankings</div>
  },
}))
// specs/022: stamped like the Power rankings stub, so a test can tell a remount from a re-render.
const playerMounts = vi.hoisted(() => ({ n: 0 }))
vi.mock('./pages/PlayerPage', () => ({
  default: function PlayerPageStub() {
    const [instance] = useState(() => ++playerMounts.n)
    return <div data-instance={instance}>player page</div>
  },
}))
// specs/022 US4: stamped the same way, to prove the stats route is keyed by league.
const statsMounts = vi.hoisted(() => ({ n: 0 }))
vi.mock('./pages/StatLeaderboard', () => ({
  default: function StatLeaderboardStub() {
    const [instance] = useState(() => ++statsMounts.n)
    return <div data-instance={instance}>stat leaderboard</div>
  },
}))
// Mounted by the draft-room test below; the real one fetches on mount.
vi.mock('./pages/DraftView', () => ({
  default: () => <div>draft view</div>,
}))

// SignIn imports getSleeperUser from here; the real module would hit the
// network the instant a click on Continue happened. Not exercised in these
// tests, but stubbed the same way SignIn.test.tsx does it.
// Spec 035: league pages sit behind PlatformGate, which reads the cached league list (getDrafts) and, for a
// league that is not a known Sleeper one, the availability endpoint (getLeagueAvailability).
const availabilityFor = (key: string, unavailable: Record<string, { code: string; message: string }> = {}): api.LeagueAvailability => ({
  leagueKey: key,
  platform: Object.keys(unavailable).length > 0 ? 'ESPN' : 'SLEEPER',
  unavailable,
  unmatchedPlayers: 0,
  lastRefresh: null,
  canReplaceCookies: false,
  unmatched: [],
})
vi.mock('./api', () => ({
  getSleeperUser: vi.fn(),
  getDrafts: vi.fn(async () => []),
  getLeagueAvailability: vi.fn(async (key: string) => availabilityFor(key)),
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

    // Sign out sits behind the avatar now: a deliberate second tap, never next to the nav.
    await user.click(screen.getByRole('button', { name: /^account:/i }))
    await user.click(screen.getByRole('menuitem', { name: /sign out/i }))

    expect(screen.getByRole('heading', { level: 1, name: headline })).toBeInTheDocument()
    expect(screen.getByLabelText(/your sleeper username/i)).toBeInTheDocument()
    // Regex, not the string '/' -- toHaveTextContent does a substring match,
    // so the plain string would also pass against '/leagues/1/power' and this
    // assertion (the only one guarding the navigate) could never fail.
    expect(screen.getByTestId('location-probe')).toHaveTextContent(/^\/$/)
  })

  it('the tab title is the short name when signed in and the landing title when signed out (spec 050)', async () => {
    const { unmount } = render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>)
    expect(document.title).toBe(landingTitle)
    unmount()
    setUser(sampleUser)
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>)
    expect(await screen.findByText('draft picker')).toBeInTheDocument()
    expect(document.title).toBe('Ball Knowers')
  })

  it('a deep link while signed out shows the username screen, not the page it points to', () => {
    render(
      <MemoryRouter initialEntries={['/drafts/abc']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { level: 1, name: headline })).toBeInTheDocument()
  })

  it('a junk path while signed out shows the username screen, not the "Nothing here" fallback', () => {
    render(
      <MemoryRouter initialEntries={['/nonsense']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { level: 1, name: headline })).toBeInTheDocument()
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

  // These two replace a pair that guarded the opposite arrangement: the nav shipped Home-only, so
  // the old tests asserted that "/" had no nav chrome and every other route did. That split is the
  // thing claude/site-wide-shell-propagation.md removed -- there is one shell now, and these assert
  // the invariant rather than the old one.
  it('renders no top bar while signed out -- SignIn is the whole screen', () => {
    render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Managers' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^account:/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Skip to content' })).not.toBeInTheDocument()
  })

  it.each([['/'], ['/leagues/1/power']])(
    'renders exactly one top bar, with the same global links, on %s',
    async (path) => {
      setUser(sampleUser)
      render(
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>,
      )

      // getAllBy + length, not getBy: getBy already throws on a duplicate, but the failure this
      // guards (a bar from the shell AND a bar from the page) reads as "found multiple elements"
      // rather than as the two-shell regression it would actually be.
      expect(await screen.findAllByRole('navigation', { name: 'Main' })).toHaveLength(1)
      const main = screen.getByRole('navigation', { name: 'Main' })
      expect(within(main).getAllByRole('link').map((l) => l.textContent)).toEqual(['Leagues', 'Managers', 'Time Machine'])
      expect(within(main).getByRole('button', { name: 'Mock drafts' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /^account:/i })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Jump to a league, page or manager' })).toBeInTheDocument()
    },
  )

  it('keeps the Managers link on /managers, and sends the league in router state when there is one', async () => {
    setUser(sampleUser)
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )
    const link = await screen.findByRole('link', { name: 'Managers' })
    expect(link.getAttribute('href')).toBe('/managers')
  })

  it('makes the skip link the first focusable element and aims it at <main id="main">', async () => {
    setUser(sampleUser)
    const user = userEvent.setup()
    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )
    await screen.findByText('draft picker')
    await user.tab()
    const skip = screen.getByRole('link', { name: 'Skip to content' })
    expect(document.activeElement).toBe(skip)
    expect(skip.getAttribute('href')).toBe('#main')
    expect(container.querySelector('main#main')).not.toBeNull()

    await user.keyboard('{Enter}')
    expect(document.activeElement).toBe(container.querySelector('main#main'))
  })

  it('has no collapse toggle, and ignores the retired bk-rail storage key', async () => {
    setUser(sampleUser)
    localStorage.setItem('bk-rail', 'collapsed')
    render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /(expand|collapse) navigation/i })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Managers' })).toBeInTheDocument()
  })
})

// The compact top bar (global links fold into the menu button) is for a draft room or a mock: the
// paths the rail used to collapse on. A mock's seat setup is a form, not a room.
describe('isCompactChrome', () => {
  it.each([
    ['/drafts/abc', true],
    ['/drafts/abc/board', true],
    ['/drafts/abc/live', true],
    ['/mock/4', true],
    ['/mock/new', false],
    ['/leagues/1/power', false],
    ['/', false],
    ['/managers', false],
  ])('%s -> %s', (path, expected) => {
    expect(isCompactChrome(path)).toBe(expected)
  })
})

describe('league routes remount on a league change', () => {
  // Spec 011: the rail's year links switch seasons on the SAME route
  // (/leagues/<2026>/power -> /leagues/<2025>/power). Unkeyed, React Router
  // reuses the page component, so the old season's data and in-flight effects
  // outlive the switch.
  it('going from one league id to another on the same page path mounts a fresh page', async () => {
    setUser(sampleUser)
    const user = userEvent.setup()
    function GoTo() {
      const navigate = useNavigate()
      return <button onClick={() => navigate('/leagues/2/power')}>go to 2025</button>
    }
    render(
      <MemoryRouter initialEntries={['/leagues/1/power']}>
        <App />
        <GoTo />
      </MemoryRouter>,
    )

    const before = (await screen.findByText('power rankings')).getAttribute('data-instance')
    await user.click(screen.getByRole('button', { name: 'go to 2025' }))

    expect(screen.getByText('power rankings').getAttribute('data-instance')).not.toBe(before)
  })

  // specs/022 N4: the player page is keyed on BOTH ids -- another player in the same
  // league, and the same player in another season, are each a fresh page.
  it.each([
    ['another player', '/leagues/1/players/9'],
    ['another season', '/leagues/2/players/8'],
  ])('mounts a fresh player page for %s', async (_what, target) => {
    setUser(sampleUser)
    const user = userEvent.setup()
    function GoTo() {
      const navigate = useNavigate()
      return <button onClick={() => navigate(target)}>go</button>
    }
    render(
      <MemoryRouter initialEntries={['/leagues/1/players/8']}>
        <App />
        <GoTo />
      </MemoryRouter>,
    )

    const before = (await screen.findByText('player page')).getAttribute('data-instance')
    await user.click(screen.getByRole('button', { name: 'go' }))

    expect(screen.getByText('player page').getAttribute('data-instance')).not.toBe(before)
  })

  // specs/022 US4 T055: /leagues/:id/stats renders the leaderboard and is keyed by league.
  it('routes /leagues/:id/stats to the leaderboard and mounts a fresh one for another season', async () => {
    setUser(sampleUser)
    const user = userEvent.setup()
    function GoTo() {
      const navigate = useNavigate()
      return <button onClick={() => navigate('/leagues/2/stats')}>go</button>
    }
    render(
      <MemoryRouter initialEntries={['/leagues/1/stats']}>
        <App />
        <GoTo />
      </MemoryRouter>,
    )

    const before = (await screen.findByText('stat leaderboard')).getAttribute('data-instance')
    await user.click(screen.getByRole('button', { name: 'go' }))

    expect(screen.getByText('stat leaderboard').getAttribute('data-instance')).not.toBe(before)
  })

  // Spec 035 T042: an ESPN league's stats page is not mounted at all; the platform message takes its place.
  it('shows the platform message instead of the stats page for an ESPN league, and mounts it for a Sleeper one', async () => {
    setUser(sampleUser)
    invalidateRailLeagues()
    invalidateLeagueAvailability()
    const summary = (key: string, platform: 'SLEEPER' | 'ESPN') =>
      ({
        id: 1, sleeperDraftId: `${key}-d`, leagueId: 1, leagueName: `League ${key}`, season: 2026, teams: 12, rounds: 15,
        status: 'complete', startTime: null, sleeperLeagueId: key, previousLeagueId: null, sport: 'nba', platform, draftType: 'snake',
      }) satisfies api.DraftSummary
    vi.mocked(api.getDrafts).mockResolvedValue([summary('espn-1', 'ESPN'), summary('sleeper-1', 'SLEEPER')])
    vi.mocked(api.getLeagueAvailability).mockImplementation(async (key: string) =>
      availabilityFor(key, key === 'espn-1'
        ? { stats: { code: 'SCORING_UNTRANSLATED', message: 'This league scoring has rules Ball Knowers cannot translate yet.' } }
        : {}),
    )
    const { unmount } = render(
      <MemoryRouter initialEntries={['/leagues/espn-1/stats']}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByText('Not available for ESPN leagues yet.')).toBeTruthy()
    expect(screen.getByText(/cannot translate yet/)).toBeTruthy()
    expect(screen.queryByText('stat leaderboard')).toBeNull()
    unmount()

    render(
      <MemoryRouter initialEntries={['/leagues/sleeper-1/stats']}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByText('stat leaderboard')).toBeTruthy()
    // A Sleeper league never asks the availability endpoint: the platform came off the league list.
    expect(vi.mocked(api.getLeagueAvailability)).not.toHaveBeenCalledWith('sleeper-1')
  })

  it('treats a failed availability read as available: the server refuses if it has to', async () => {
    setUser(sampleUser)
    invalidateRailLeagues()
    invalidateLeagueAvailability()
    vi.mocked(api.getDrafts).mockResolvedValue([])
    vi.mocked(api.getLeagueAvailability).mockRejectedValue(new Error('network'))
    render(
      <MemoryRouter initialEntries={['/leagues/3/stats']}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByText('stat leaderboard')).toBeTruthy()
  })
})
