import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppShell from './AppShell'
import * as api from '../api'
import { draftSummary, resetRail } from '../testRailHelpers'
import { invalidateRailLeagues } from '../railLeague'
import { invalidateSearchIndex } from '../searchIndex'
import { clearUser, currentUserId, setUser } from '../user'

// Spec 052: what AppShell tells the analytics module. The module itself is mocked, so these
// tests are about WHEN AppShell calls it (one view per route change, league views only for
// the league the current path names), not about what it sends.
const trackPageView = vi.fn()
const trackLeagueView = vi.fn()
vi.mock('../analytics', () => ({
  trackPageView: (...a: unknown[]) => trackPageView(...a),
  trackLeagueView: (...a: unknown[]) => trackLeagueView(...a),
}))

const USER = { sleeperUserId: 'U1', username: 'tester', displayName: 'Tester', avatar: null }

function Nav() {
  const loc = useLocation()
  return (
    <div>
      <span data-testid="at">{loc.pathname}</span>
      <Link to="/managers">to-managers</Link>
      <Link to="/managers?x=1">to-managers-query</Link>
      <Link to="/leagues/L1/power">to-power-1</Link>
      <Link to="/leagues/L2/power">to-power-2</Link>
      <Link to="/mock/new">to-mock-new</Link>
    </div>
  )
}

function setup(path: string, drafts = [draftSummary()]) {
  invalidateRailLeagues()
  invalidateSearchIndex()
  vi.spyOn(api, 'getDrafts').mockResolvedValue(drafts)
  vi.spyOn(api, 'getManagers').mockResolvedValue([])
  vi.spyOn(api, 'refreshLeague').mockResolvedValue({
    state: 'FRESH', leagueSleeperId: 'L1', season: 2026, lastSuccessAt: null, lastFailureAt: null, seasons: [],
  })
  vi.spyOn(api, 'getLeagueRefresh').mockResolvedValue({
    state: 'FRESH', leagueSleeperId: 'L1', season: 2026, lastSuccessAt: null, lastFailureAt: null, seasons: [],
  })
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppShell>
        <Nav />
      </AppShell>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  trackPageView.mockReset()
  trackLeagueView.mockReset()
})
afterEach(resetRail)

describe('page views', () => {
  it('A -> B -> B?x=1 -> C records three views; the query string is not a new view', async () => {
    setUser(USER)
    const user = userEvent.setup()
    setup('/')
    await user.click(screen.getByText('to-managers'))
    await user.click(screen.getByText('to-managers-query'))
    await user.click(screen.getByText('to-mock-new'))

    expect(trackPageView.mock.calls).toEqual([
      ['/', true],
      ['/managers', true],
      ['/mock/new', true],
    ])
  })

  it('signing in on the same path records a second view', () => {
    clearUser()
    setup('/managers')
    expect(trackPageView).toHaveBeenLastCalledWith('/managers', false)
    act(() => setUser(USER))
    expect(trackPageView).toHaveBeenLastCalledWith('/managers', true)
    expect(trackPageView).toHaveBeenCalledTimes(2)
  })

  it('a signed-out visit to a league path is reported signed out (the module files it under /(landing))', () => {
    clearUser()
    setup('/leagues/123/power')
    expect(trackPageView).toHaveBeenCalledTimes(1)
    expect(trackPageView).toHaveBeenCalledWith('/leagues/123/power', false)
    expect(trackLeagueView).not.toHaveBeenCalled()
  })
})

describe('league views', () => {
  it('sends the lineage current league and sport once the rail resolves for the path', async () => {
    setUser(USER)
    setup('/leagues/L1/power', [draftSummary({ sport: 'nba', sleeperLeagueId: 'L1' })])
    await waitFor(() => expect(trackLeagueView).toHaveBeenCalledTimes(1))
    expect(trackLeagueView).toHaveBeenCalledWith('/leagues/L1/power', true, 'nba', 'L1')
  })

  it('a rail that resolves late for page A after navigating to page B (another league) reports B only', async () => {
    setUser(USER)
    const user = userEvent.setup()
    const drafts = [
      draftSummary({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', sport: 'nfl' }),
      draftSummary({ id: 2, leagueId: 2, sleeperLeagueId: 'L2', sleeperDraftId: 'D2', sport: 'nba' }),
    ]
    // Hold the first resolution so A's rail answer lands after the navigation to B.
    let release!: (d: typeof drafts) => void
    invalidateRailLeagues()
    invalidateSearchIndex()
    vi.spyOn(api, 'getDrafts').mockImplementation(() => new Promise((r) => (release = r)))
    vi.spyOn(api, 'getManagers').mockResolvedValue([])
    render(
      <MemoryRouter initialEntries={['/leagues/L1/power']}>
        <AppShell>
          <Nav />
        </AppShell>
      </MemoryRouter>,
    )
    await user.click(screen.getByText('to-power-2'))
    await act(async () => release(drafts))
    await waitFor(() => expect(trackLeagueView).toHaveBeenCalled())
    for (const call of trackLeagueView.mock.calls) {
      expect(call).toEqual(['/leagues/L2/power', true, 'nba', 'L2'])
    }
  })

  it('the rail still holding league L1 for one render after navigating to L2 never labels L2 with L1', async () => {
    setUser(USER)
    const user = userEvent.setup()
    setup('/leagues/L1/power', [
      draftSummary({ sleeperLeagueId: 'L1', sleeperDraftId: 'D1', sport: 'nfl' }),
      draftSummary({ id: 2, leagueId: 2, sleeperLeagueId: 'L2', sleeperDraftId: 'D2', sport: 'nba' }),
    ])
    await waitFor(() => expect(trackLeagueView).toHaveBeenCalledWith('/leagues/L1/power', true, 'nfl', 'L1'))
    await user.click(screen.getByText('to-power-2'))
    await waitFor(() => expect(trackLeagueView).toHaveBeenCalledWith('/leagues/L2/power', true, 'nba', 'L2'))
    expect(trackLeagueView.mock.calls).toEqual([
      ['/leagues/L1/power', true, 'nfl', 'L1'],
      ['/leagues/L2/power', true, 'nba', 'L2'],
    ])
  })

  it('stale rail state from league L1 never attributes to a path naming no league', async () => {
    setUser(USER)
    const user = userEvent.setup()
    setup('/leagues/L1/power')
    await waitFor(() => expect(trackLeagueView).toHaveBeenCalledTimes(1))
    await user.click(screen.getByText('to-managers'))
    await user.click(screen.getByText('to-mock-new'))
    expect(trackLeagueView).toHaveBeenCalledTimes(1)
  })

  it('signing in on a league deep link resolves the rail and sends one league view (review #2)', async () => {
    clearUser()
    invalidateRailLeagues()
    invalidateSearchIndex()
    // /api/drafts is scoped to the signed-in user: empty while signed out, the list once signed in.
    const getDrafts = vi
      .spyOn(api, 'getDrafts')
      .mockImplementation(async () => (currentUserId() ? [draftSummary()] : []))
    vi.spyOn(api, 'getManagers').mockResolvedValue([])
    vi.spyOn(api, 'refreshLeague').mockResolvedValue({
      state: 'FRESH', leagueSleeperId: 'L1', season: 2026, lastSuccessAt: null, lastFailureAt: null, seasons: [],
    })
    vi.spyOn(api, 'getLeagueRefresh').mockResolvedValue({
      state: 'FRESH', leagueSleeperId: 'L1', season: 2026, lastSuccessAt: null, lastFailureAt: null, seasons: [],
    })
    render(
      <MemoryRouter initialEntries={['/leagues/L1/power']}>
        <AppShell>
          <Nav />
        </AppShell>
      </MemoryRouter>,
    )
    // Let the signed-out resolve (two empty fetches) finish and cache [].
    await waitFor(() => expect(getDrafts).toHaveBeenCalledTimes(2))
    expect(trackLeagueView).not.toHaveBeenCalled()
    act(() => setUser(USER))
    await waitFor(() => expect(trackLeagueView).toHaveBeenCalledTimes(1))
    expect(trackLeagueView).toHaveBeenCalledWith('/leagues/L1/power', true, 'nfl', 'L1')
  })

  it('a page with no league sends no league view', async () => {
    setUser(USER)
    setup('/managers')
    await waitFor(() => expect(trackPageView).toHaveBeenCalled())
    expect(trackLeagueView).not.toHaveBeenCalled()
  })
})
