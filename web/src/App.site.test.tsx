import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation, useParams } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { goTo } from './goTo'
import { clearUser, setUser, type BkUser } from './user'
import { headline as leagueHeadline } from './landingCopy'
import { headline as tmHeadline } from './tmLandingCopy'
import { invalidateRailLeagues } from './railLeague'

// Spec 073, contracts/site-routes.md: the route table, row by row, for each build mode, signed in and
// signed out, with a query string and a hash. VITE_SITE is stubbed per case: currentSite() reads it at call time.

vi.mock('./goTo', () => ({ goTo: vi.fn() }))
vi.mock('./pages/DraftPicker', () => ({ default: () => <div>league picker</div> }))
vi.mock('./pages/LeagueHistory', () => ({ default: () => <div>league history page</div> }))
vi.mock('./pages/ManagerHistory', () => ({ default: () => <div>manager history page</div> }))
vi.mock('./pages/HistoricalDraft', () => ({ default: () => <div>tm home page</div> }))
vi.mock('./pages/HistoricalRoom', () => ({
  default: function RoomStub() {
    const { gameId } = useParams()
    return <div>tm room page {gameId}</div>
  },
}))
vi.mock('./api', () => ({
  getSleeperUser: vi.fn(),
  getDrafts: vi.fn(async () => []),
  getLeagueAvailability: vi.fn(),
}))

const TM_ORIGIN = 'http://tm.test'
const LG_ORIGIN = 'http://leagues.test'

const user: BkUser = { sleeperUserId: '1', username: 'popsharky', displayName: 'popsharky', avatar: null }

function Probe() {
  const l = useLocation()
  return <p data-testid="loc">{`${l.pathname}${l.search}${l.hash}`}</p>
}

function renderAt(entry: string, signedIn: boolean) {
  if (signedIn) setUser(user)
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <App />
      <Probe />
    </MemoryRouter>,
  )
}

const where = () => screen.getByTestId('loc').textContent

function mode(site: string | undefined) {
  if (site) vi.stubEnv('VITE_SITE', site)
  vi.stubEnv('VITE_TM_ORIGIN', TM_ORIGIN)
  vi.stubEnv('VITE_LEAGUES_ORIGIN', LG_ORIGIN)
}

beforeEach(() => {
  clearUser()
  localStorage.clear()
  invalidateRailLeagues()
  vi.mocked(goTo).mockReset()
})

afterEach(() => {
  clearUser()
  vi.unstubAllEnvs()
})

const BOTH = [true, false] as const

describe('timemachine', () => {
  beforeEach(() => mode('timemachine'))

  it('/ is the Time Machine home when signed in, the front door when signed out', () => {
    renderAt('/', true)
    expect(screen.getByText('tm home page')).toBeInTheDocument()
  })

  it('signed out, / is the Time Machine front door, not the league landing', () => {
    renderAt('/', false)
    expect(screen.getByRole('heading', { level: 1, name: tmHeadline })).toBeInTheDocument()
    expect(screen.queryByText(leagueHeadline)).toBeNull()
  })

  it('/game/:gameId is the keyed room signed in', () => {
    renderAt('/game/7', true)
    expect(screen.getByText('tm room page 7')).toBeInTheDocument()
  })

  it('signed out, a game link shows the front door at the SAME url and the game after sign-in (FR-013)', () => {
    renderAt('/game/7?x=1#y', false)
    expect(screen.getByRole('heading', { level: 1, name: tmHeadline })).toBeInTheDocument()
    expect(where()).toBe('/game/7?x=1#y')
    act(() => setUser(user))
    expect(screen.getByText('tm room page 7')).toBeInTheDocument()
    expect(where()).toBe('/game/7?x=1#y')
  })

  it('anything else is the not-found page, with no league wording', () => {
    renderAt('/nope', true)
    expect(screen.getByRole('heading', { name: 'Nothing here' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Time Machine' })).toHaveAttribute('href', '/')
    expect(screen.queryByText(/league/i)).toBeNull()
  })

  it.each(BOTH)('/history goes to / in-app, keeping query and hash (%s)', (signedIn) => {
    renderAt('/history?x=1#y', signedIn)
    expect(where()).toBe('/?x=1#y')
    expect(goTo).not.toHaveBeenCalled()
  })

  it.each(BOTH)('/history/:gameId goes to /game/:gameId in-app, keeping query and hash (%s)', (signedIn) => {
    renderAt('/history/5?x=1#y', signedIn)
    expect(where()).toBe('/game/5?x=1#y')
    expect(goTo).not.toHaveBeenCalled()
  })

  const FORWARDED = [
    '/leagues/9/power',
    '/leagues/9/history',
    '/leagues/9',
    '/drafts/3',
    '/drafts/3/live',
    '/mock/new',
    '/mock/abc',
    '/managers',
    '/managers/3/history',
    '/managers/3/versus/4',
    '/admin/digest',
  ]
  it.each(FORWARDED.flatMap((p) => BOTH.map((s) => [p, s] as const)))(
    '%s is forwarded to the league site with path, query and hash (%s)',
    (path, signedIn) => {
      renderAt(`${path}?x=1#y`, signedIn)
      expect(goTo).toHaveBeenCalledTimes(1)
      expect(goTo).toHaveBeenCalledWith(`${LG_ORIGIN}${path}?x=1#y`)
    },
  )

  it('/leagues/:id/history and /managers/:id/history are league pages, never the Time Machine /history rows', () => {
    renderAt('/leagues/9/history', true)
    expect(screen.queryByText('tm home page')).toBeNull()
    expect(screen.queryByText(/tm room page/)).toBeNull()
    expect(goTo).toHaveBeenCalledWith(`${LG_ORIGIN}/leagues/9/history`)
  })

  it('a forwarded league link carries saved formulas in the fragment, after the link own fragment', () => {
    localStorage.setItem(
      'bk.statCalc.saved.v1',
      JSON.stringify({ v: 1, formulas: [{ id: 'a', name: 'Mine', expr: 'ast / tov', settings: {}, savedAt: '' }] }),
    )
    renderAt('/leagues/9/stats/calculator?x=1#frag', true)
    const url = vi.mocked(goTo).mock.calls[0][0]
    expect(url).toMatch(new RegExp(`^${LG_ORIGIN}/leagues/9/stats/calculator\\?x=1#bk-carry=[A-Za-z0-9_-]+;frag$`))
  })

  it('a forward to the Time Machine origin itself shows not-found instead of redirecting in a loop', () => {
    vi.stubEnv('VITE_LEAGUES_ORIGIN', window.location.origin)
    renderAt('/leagues/9/power', true)
    expect(goTo).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Nothing here' })).toBeInTheDocument()
  })
})

describe('leagues', () => {
  beforeEach(() => mode('leagues'))

  it('/ is the league picker signed in, the league landing signed out', () => {
    renderAt('/', true)
    expect(screen.getByText('league picker')).toBeInTheDocument()
    expect(screen.queryByText('tm home page')).toBeNull()
  })

  it('signed out, / is today\'s league landing', () => {
    renderAt('/', false)
    expect(screen.getByRole('heading', { level: 1, name: leagueHeadline })).toBeInTheDocument()
  })

  it('league pages (including the ones ending in /history) render here, not as redirects', () => {
    renderAt('/leagues/9/history', true)
    expect(screen.getByText('league history page')).toBeInTheDocument()
    expect(goTo).not.toHaveBeenCalled()
  })

  it.each(BOTH)('/history goes to the Time Machine site\'s /history with query and hash (%s)', (signedIn) => {
    renderAt('/history?x=1#y', signedIn)
    expect(goTo).toHaveBeenCalledTimes(1)
    expect(goTo).toHaveBeenCalledWith(`${TM_ORIGIN}/history?x=1#y`)
  })

  it.each(BOTH)('/history/:gameId goes to the Time Machine site\'s /history/:gameId (%s)', (signedIn) => {
    renderAt('/history/5?x=1#y', signedIn)
    expect(goTo).toHaveBeenCalledWith(`${TM_ORIGIN}/history/5?x=1#y`)
  })

  it('does not carry formulas back to the Time Machine site (that direction carries nothing)', () => {
    localStorage.setItem('bk.statCalc.saved.v1', JSON.stringify({ v: 1, formulas: [{ id: 'a', name: 'n', expr: 'pts', settings: {}, savedAt: '' }] }))
    renderAt('/history', true)
    expect(vi.mocked(goTo).mock.calls[0][0]).toBe(`${TM_ORIGIN}/history`)
  })

  it('/game/:gameId is not found (signed in)', () => {
    renderAt('/game/5', true)
    expect(screen.getByRole('heading', { name: 'Nothing here' })).toBeInTheDocument()
    expect(goTo).not.toHaveBeenCalled()
  })

  it('a dev server with the Time Machine origin unset shows the target as text and does not navigate', () => {
    vi.stubEnv('VITE_TM_ORIGIN', '')
    renderAt('/history/5', true)
    expect(goTo).not.toHaveBeenCalled()
    expect(screen.getByText(/Local build/)).toBeInTheDocument()
  })
})

describe('combined (VITE_SITE unset)', () => {
  beforeEach(() => mode(undefined))

  it('/history is the Time Machine home and /history/:gameId the room, signed in', () => {
    renderAt('/history', true)
    expect(screen.getByText('tm home page')).toBeInTheDocument()
  })

  it('/history/:gameId is the room', () => {
    renderAt('/history/5', true)
    expect(screen.getByText('tm room page 5')).toBeInTheDocument()
  })

  it.each(BOTH)('/game/:gameId goes to /history/:gameId in-app, keeping query and hash (%s)', (signedIn) => {
    renderAt('/game/5?x=1#y', signedIn)
    expect(where()).toBe('/history/5?x=1#y')
    expect(goTo).not.toHaveBeenCalled()
  })

  it('signed out at /history, the league landing is shown at the same URL', () => {
    renderAt('/history', false)
    expect(screen.getByRole('heading', { level: 1, name: leagueHeadline })).toBeInTheDocument()
    expect(where()).toBe('/history')
  })

  it('/, league pages and not-found behave as before', () => {
    renderAt('/leagues/9/history', true)
    expect(screen.getByText('league history page')).toBeInTheDocument()
  })

  it('anything else is not found', () => {
    renderAt('/nope', true)
    expect(screen.getByRole('heading', { name: 'Nothing here' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to your leagues' })).toBeInTheDocument()
  })
})

describe('document title', () => {
  it('timemachine: the Time Machine title signed out, "Time Machine" signed in', () => {
    mode('timemachine')
    renderAt('/', false)
    expect(document.title).toContain('Time Machine')
    expect(document.title).not.toBe('Time Machine')
    act(() => setUser(user))
    expect(document.title).toBe('Time Machine')
  })

  it('combined: unchanged, "Ball Knowers" signed in', () => {
    mode(undefined)
    renderAt('/', true)
    expect(document.title).toBe('Ball Knowers')
  })
})
