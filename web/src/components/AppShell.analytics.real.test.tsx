import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppShell from './AppShell'
import * as api from '../api'
import { __resetAnalyticsForTests, initAnalytics } from '../analytics'
import { draftSummary, resetRail } from '../testRailHelpers'
import { invalidateRailLeagues } from '../railLeague'
import { invalidateSearchIndex } from '../searchIndex'
import { setUser } from '../user'

// Spec 052 code review #1: the REAL analytics module (not mocked) driven by the real AppShell, so
// the seam between the two -- the page-view effect re-arming the league-view latch -- is covered.
// The sink is a fake window.umami, enabled the way analytics.test.ts enables the module.

const track = vi.fn()
const sent = () =>
  track.mock.calls.map((c) => {
    const p = c[0] as { name?: string; url: string }
    return p.name ? `${p.name}:${p.url}` : `pv:${p.url}`
  })

function scriptEl() {
  return document.head.querySelector('script[src*="umami"]')
}

beforeEach(() => {
  track.mockReset()
  ;(window as unknown as { umami?: unknown }).umami = { track }
  __resetAnalyticsForTests()
  vi.stubGlobal('location', { ...window.location, hostname: 'www.ballknowers.co', pathname: '/' })
  vi.stubEnv('VITE_UMAMI_WEBSITE_ID', 'site-123')
  initAnalytics()
  scriptEl()!.dispatchEvent(new Event('load'))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  scriptEl()?.remove()
  delete (window as unknown as { umami?: unknown }).umami
  resetRail()
})

describe('AppShell with the real analytics module', () => {
  it('league A -> /managers -> league A sends two league views', async () => {
    setUser({ sleeperUserId: 'U1', username: 'tester', displayName: 'Tester', avatar: null })
    invalidateRailLeagues()
    invalidateSearchIndex()
    vi.spyOn(api, 'getDrafts').mockResolvedValue([draftSummary()])
    vi.spyOn(api, 'getManagers').mockResolvedValue([])
    vi.spyOn(api, 'refreshLeague').mockResolvedValue({
      state: 'FRESH', leagueSleeperId: 'L1', season: 2026, lastSuccessAt: null, lastFailureAt: null, seasons: [],
    })
    vi.spyOn(api, 'getLeagueRefresh').mockResolvedValue({
      state: 'FRESH', leagueSleeperId: 'L1', season: 2026, lastSuccessAt: null, lastFailureAt: null, seasons: [],
    })
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/leagues/L1/power']}>
        <AppShell>
          <Link to="/managers">to-managers</Link>
          <Link to="/leagues/L1/power">to-power-1</Link>
        </AppShell>
      </MemoryRouter>,
    )
    await waitFor(() => expect(sent()).toContain('league-view:/leagues/:league/power'))
    await user.click(screen.getByText('to-managers'))
    await user.click(screen.getByText('to-power-1'))
    await waitFor(() => expect(sent().filter((s) => s.startsWith('league-view'))).toHaveLength(2))
    expect(sent()).toEqual([
      'pv:/leagues/:league/power',
      'league-view:/leagues/:league/power',
      'pv:/managers',
      'pv:/leagues/:league/power',
      'league-view:/leagues/:league/power',
    ])
  })
})
