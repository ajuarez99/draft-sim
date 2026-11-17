import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { goTo } from './goTo'
import * as api from './api'
import { trackPageView } from './analytics'
import { clearUser, setUser, type BkUser } from './user'
import { isUnlocked, lock, startSession, SESSION_KEY } from './leaguesLock'
import { headline as leagueHeadline } from './landingCopy'
import { invalidateRailLeagues } from './railLeague'

// The league site's front-door lock (spec 073 follow-up, 2026-11-16), through the real App. Only the
// network-facing api module, the redirect helper and page-view tracking are mocked.

vi.mock('./goTo', () => ({ goTo: vi.fn() }))
vi.mock('./analytics', async (orig) => ({ ...(await orig<typeof import('./analytics')>()), trackPageView: vi.fn() }))
vi.mock('./pages/DraftPicker', () => ({ default: () => <div>league picker</div> }))
vi.mock('./pages/LeagueHistory', () => ({ default: () => <div>league history page</div> }))
vi.mock('./pages/HistoricalDraft', () => ({ default: () => <div>tm home page</div> }))
vi.mock('./api', () => ({
  getSleeperUser: vi.fn(),
  getDrafts: vi.fn(async () => []),
  getLeagueAvailability: vi.fn(),
  unlockLeagueSite: vi.fn(),
  renewLeagueSession: vi.fn(async () => true),
}))

const TM_ORIGIN = 'http://tm.test'
const user: BkUser = { sleeperUserId: '1', username: 'popsharky', displayName: 'popsharky', avatar: null }
const check = vi.mocked(api.unlockLeagueSite)
// What the real unlockLeagueSite does on a 200: store the session, then answer 'ok'.
const accept = () =>
  check.mockImplementation(async () => {
    startSession('sess-tok', 300)
    return 'ok'
  })

function Probe() {
  const l = useLocation()
  return <p data-testid="loc">{`${l.pathname}${l.search}${l.hash}`}</p>
}

function renderAt(entry: string, signedIn = false) {
  if (signedIn) setUser(user)
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <App />
      <Probe />
    </MemoryRouter>,
  )
}
const where = () => screen.getByTestId('loc').textContent
const lockHeading = () => screen.queryByRole('heading', { level: 1, name: 'Ball Knowers' })

async function enterToken(token: string) {
  const u = userEvent.setup()
  await u.type(screen.getByLabelText('Admin token'), token)
  await u.click(screen.getByRole('button', { name: 'Unlock' }))
}

function mode(site: string | undefined) {
  if (site) vi.stubEnv('VITE_SITE', site)
  vi.stubEnv('VITE_TM_ORIGIN', TM_ORIGIN)
}

beforeEach(() => {
  lock()
  clearUser()
  localStorage.clear()
  sessionStorage.clear()
  invalidateRailLeagues()
  vi.mocked(goTo).mockReset()
  vi.mocked(trackPageView).mockReset()
  check.mockReset()
})
afterEach(() => {
  lock()
  clearUser()
  vi.unstubAllEnvs()
})

const BOTH = [false, true] as const

describe('leagues build, locked', () => {
  beforeEach(() => mode('leagues'))

  it.each(BOTH)('at / shows only the lock screen (signed in: %s)', (signedIn) => {
    renderAt('/', signedIn)
    expect(lockHeading()).toBeInTheDocument()
    expect(screen.getByText('This site is private.')).toBeInTheDocument()
    expect(screen.getByLabelText('Admin token')).toHaveAttribute('type', 'password')
    expect(screen.getByRole('button', { name: 'Unlock' })).toBeInTheDocument()
    expect(screen.queryByText(leagueHeadline)).toBeNull()
    expect(screen.queryByText('league picker')).toBeNull()
    expect(screen.queryByLabelText(/sleeper username/i)).toBeNull()
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(screen.queryByRole('banner')).toBeNull()
  })

  it.each(BOTH)('at a deep league URL shows only the lock screen, and leaves the URL alone (signed in: %s)', (signedIn) => {
    renderAt('/leagues/9/history?x=1#y', signedIn)
    expect(lockHeading()).toBeInTheDocument()
    expect(screen.queryByText('league history page')).toBeNull()
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(where()).toBe('/leagues/9/history?x=1#y')
  })

  it('the lock screen has no links and no league or Sleeper wording', () => {
    const { container } = renderAt('/')
    expect(container.querySelectorAll('a')).toHaveLength(0)
    expect(container.textContent).not.toMatch(/league|sleeper|draft|manager/i)
  })

  it('the tab title is the bare name, not the marketing title', () => {
    renderAt('/')
    expect(document.title).toBe('Ball Knowers')
  })

  it('a wrong token stays locked and says so', async () => {
    check.mockResolvedValue('wrong')
    renderAt('/leagues/9/history')
    await enterToken('nope')
    expect(await screen.findByRole('alert')).toHaveTextContent("That token wasn't accepted.")
    expect(check).toHaveBeenCalledWith('nope')
    expect(lockHeading()).toBeInTheDocument()
    expect(isUnlocked()).toBe(false)
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('an unreachable server stays locked and says so', async () => {
    check.mockResolvedValue('unreachable')
    renderAt('/')
    await enterToken('x')
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't reach the server. Try again.")
    expect(lockHeading()).toBeInTheDocument()
    expect(isUnlocked()).toBe(false)
  })

  it('while checking the button is disabled and reads Checking…', async () => {
    let resolve!: (v: 'ok') => void
    check.mockReturnValue(new Promise((r) => (resolve = r)))
    renderAt('/')
    await enterToken('x')
    expect(screen.getByRole('button', { name: 'Checking…' })).toBeDisabled()
    await act(async () => resolve('ok'))
  })

  it('the button stays off until something is typed', () => {
    renderAt('/')
    expect(screen.getByRole('button', { name: 'Unlock' })).toBeDisabled()
  })

  it('the right token, signed out: the same URL now shows the sign-in landing', async () => {
    accept()
    renderAt('/leagues/9/history?x=1#y')
    await enterToken('good')
    expect(await screen.findByRole('heading', { level: 1, name: leagueHeadline })).toBeInTheDocument()
    expect(lockHeading()).toBeNull()
    expect(where()).toBe('/leagues/9/history?x=1#y')
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!)).toMatchObject({ token: 'sess-tok', windowSeconds: 300 })
    expect(sessionStorage.getItem('bk.adminToken')).toBeNull()
  })

  it('the right token, signed in: the requested page renders', async () => {
    accept()
    renderAt('/leagues/9/history', true)
    await enterToken('good')
    expect(await screen.findByText('league history page')).toBeInTheDocument()
    expect(where()).toBe('/leagues/9/history')
  })

  it('the token is never written to localStorage', async () => {
    accept()
    renderAt('/')
    await enterToken('good-token-value')
    await screen.findByRole('heading', { level: 1, name: leagueHeadline })
    expect(JSON.stringify({ ...localStorage })).not.toContain('good-token-value')
  })

  it.each(BOTH)('/history and /history/:gameId still forward to the Time Machine site while locked (signed in: %s)', (signedIn) => {
    renderAt('/history?x=1#y', signedIn)
    expect(goTo).toHaveBeenCalledWith(`${TM_ORIGIN}/history?x=1#y`)
    expect(lockHeading()).toBeNull()
  })

  it('/history/5 forwards too', () => {
    renderAt('/history/5')
    expect(goTo).toHaveBeenCalledWith(`${TM_ORIGIN}/history/5`)
  })

  it('sends no page view while locked, and one once unlocked', async () => {
    accept()
    renderAt('/leagues/9/history')
    await enterToken('good')
    await screen.findByRole('heading', { level: 1, name: leagueHeadline })
    // The page view that fired is for the real site, after the unlock, never for the lock screen.
    expect(vi.mocked(trackPageView).mock.calls.length).toBeGreaterThan(0)
    lock()
    vi.mocked(trackPageView).mockReset()
    await waitFor(() => expect(lockHeading()).toBeInTheDocument())
    expect(trackPageView).not.toHaveBeenCalled()
  })

  it('a locked visit alone sends nothing', () => {
    renderAt('/leagues/9/power', true)
    expect(trackPageView).not.toHaveBeenCalled()
  })
})

describe('leagues build, the idle line', () => {
  beforeEach(() => mode('leagues'))

  it('is absent on a first visit', () => {
    renderAt('/')
    expect(screen.queryByText(/without activity/)).toBeNull()
  })

  it('appears after an idle lock, derived from the session window, above the form', async () => {
    startSession('sess-tok', 300)
    renderAt('/leagues/9/history', true)
    expect(await screen.findByText('league history page')).toBeInTheDocument()
    act(() => lock('idle'))
    const line = await screen.findByText('Locked after 5 minutes without activity.')
    const form = screen.getByLabelText('Admin token').closest('form')!
    expect(line.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByText('league history page')).toBeNull()
  })

  it('uses the window it was issued with (120s reads 2 minutes)', async () => {
    startSession('sess-tok', 120)
    renderAt('/', true)
    act(() => lock('idle'))
    expect(await screen.findByText('Locked after 2 minutes without activity.')).toBeInTheDocument()
  })

  it('is absent after an explicit sign-out', async () => {
    startSession('sess-tok', 300)
    renderAt('/leagues/9/history', true)
    await screen.findByText('league history page')
    const u = userEvent.setup()
    await u.click(screen.getByRole('button', { name: /^account:/i }))
    await u.click(screen.getByRole('menuitem', { name: /sign out/i }))
    expect(await screen.findByLabelText('Admin token')).toBeInTheDocument()
    expect(screen.queryByText(/without activity/)).toBeNull()
  })

  it('an expired session on screen is replaced by the lock screen once the watcher ticks', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      startSession('sess-tok', 300)
      renderAt('/leagues/9/history', true)
      expect(await screen.findByText('league history page')).toBeInTheDocument()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 60_000 + 61_000)
      })
      expect(screen.queryByText('league history page')).toBeNull()
      expect(screen.getByText('Locked after 5 minutes without activity.')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('leagues build, unlocked', () => {
  beforeEach(() => {
    mode('leagues')
    startSession('sess-tok', 300)
  })

  it('signing out re-locks and clears the flag and the stored token', async () => {
    renderAt('/leagues/9/history', true)
    expect(await screen.findByText('league history page')).toBeInTheDocument()
    const u = userEvent.setup()
    await u.click(screen.getByRole('button', { name: /^account:/i }))
    await u.click(screen.getByRole('menuitem', { name: /sign out/i }))
    expect(await screen.findByLabelText('Admin token')).toBeInTheDocument()
    expect(screen.queryByText(leagueHeadline)).toBeNull()
    expect(isUnlocked()).toBe(false)
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    expect(sessionStorage.getItem('bk.adminToken')).toBeNull()
  })
})

describe.each([
  ['combined', undefined],
  ['timemachine', 'timemachine'],
] as const)('%s build has no lock', (_name, site) => {
  beforeEach(() => mode(site))

  it('signed out, no session: the normal front door, never the lock, and the unlock is never asked', () => {
    // T041 (spec 074): on the Redraft build "the normal front door" is the home page for a guest, whose way in is the bar's
    // "Sign in" link (the username form is a panel at the end of the real home page, which this file stubs out).
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    renderAt('/')
    expect(screen.queryByLabelText('Admin token')).toBeNull()
    expect(screen.queryByText('This site is private.')).toBeNull()
    if (site === 'timemachine') expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    else expect(screen.getByLabelText(/your sleeper username/i)).toBeInTheDocument()
    expect(check).not.toHaveBeenCalled()
  })

  it('signed in, no session: the app renders', () => {
    renderAt('/', true)
    expect(screen.queryByLabelText('Admin token')).toBeNull()
    expect(screen.getByText(site === 'timemachine' ? 'tm home page' : 'league picker')).toBeInTheDocument()
  })

  it('signing out does not touch a stored session', async () => {
    localStorage.setItem(SESSION_KEY, 'keep-me')
    renderAt('/', true)
    if (site === 'timemachine') return // the Time Machine shell has its own sign-out; the league lock is not part of it
    const u = userEvent.setup()
    await u.click(await screen.findByRole('button', { name: /^account:/i }))
    await u.click(screen.getByRole('menuitem', { name: /sign out/i }))
    expect(localStorage.getItem(SESSION_KEY)).toBe('keep-me')
  })
})
