import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../api'
import { invalidateRailLeagues } from '../railLeague'
import { invalidateLeagueAvailability } from '../useLeagueAvailability'
import PlatformGate from './PlatformGate'

/*
 * Spec 035 T042. The gate decides from the cached league list first, so a Sleeper league (including the live
 * draft room) costs no availability request, and asks the server only for an ESPN or an unknown league.
 */

function summary(draftId: string, leagueKey: string, platform: 'SLEEPER' | 'ESPN'): api.DraftSummary {
  return {
    id: 1, sleeperDraftId: draftId, leagueId: 1, leagueName: 'L', season: 2026, teams: 12, rounds: 15,
    status: 'complete', startTime: null, sleeperLeagueId: leagueKey, previousLeagueId: null, sport: 'nba', platform, draftType: 'snake',
  }
}

const availability = (key: string, unavailable: api.LeagueAvailability['unavailable']): api.LeagueAvailability => ({
  leagueKey: key, platform: 'ESPN', unavailable, unmatchedPlayers: 0, lastRefresh: null, canReplaceCookies: false, unmatched: [],
})

function renderGate(props: { page: string; leagueKey?: string; draftId?: string }) {
  return render(
    <MemoryRouter>
      <PlatformGate {...props}>
        <div>the page</div>
      </PlatformGate>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  invalidateRailLeagues()
  invalidateLeagueAvailability()
})
afterEach(() => vi.restoreAllMocks())

describe('PlatformGate', () => {
  it('lets a Sleeper draft straight through without asking the availability endpoint', async () => {
    vi.spyOn(api, 'getDrafts').mockResolvedValue([summary('D1', 'L1', 'SLEEPER')])
    const ask = vi.spyOn(api, 'getLeagueAvailability')
    renderGate({ page: 'draftRoom', draftId: 'D1' })
    expect(await screen.findByText('the page')).toBeInTheDocument()
    expect(ask).not.toHaveBeenCalled()
  })

  it('replaces an ESPN draft room with the platform message, using the server reason', async () => {
    vi.spyOn(api, 'getDrafts').mockResolvedValue([summary('E1-draft', 'E1', 'ESPN')])
    const ask = vi.spyOn(api, 'getLeagueAvailability').mockResolvedValue(
      availability('E1', { draftRoom: { code: 'PLATFORM_NOT_SUPPORTED', message: 'Live and simulated drafts follow Sleeper drafts only.' } }),
    )
    renderGate({ page: 'draftRoom', draftId: 'E1-draft' })
    expect(await screen.findByText('Not available for ESPN leagues yet.')).toBeInTheDocument()
    expect(screen.getByText('Live and simulated drafts follow Sleeper drafts only.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to league home' })).toHaveAttribute('href', '/leagues/E1')
    expect(screen.queryByText('the page')).toBeNull()
    expect(ask).toHaveBeenCalledWith('E1')
  })

  it('renders an ESPN page the league CAN show', async () => {
    vi.spyOn(api, 'getDrafts').mockResolvedValue([summary('E1-draft', 'E1', 'ESPN')])
    vi.spyOn(api, 'getLeagueAvailability').mockResolvedValue(availability('E1', {}))
    renderGate({ page: 'analysis', leagueKey: 'E1' })
    expect(await screen.findByText('the page')).toBeInTheDocument()
  })

  it('renders nothing while it does not know, so the page never fetches ahead of the answer', async () => {
    vi.spyOn(api, 'getDrafts').mockResolvedValue([summary('E1-draft', 'E1', 'ESPN')])
    vi.spyOn(api, 'getLeagueAvailability').mockImplementation(() => new Promise(() => undefined))
    renderGate({ page: 'stats', leagueKey: 'E1' })
    await Promise.resolve()
    expect(screen.queryByText('the page')).toBeNull()
  })

  it('fails open when the league is unknown and the availability read fails', async () => {
    vi.spyOn(api, 'getDrafts').mockResolvedValue([])
    vi.spyOn(api, 'getLeagueAvailability').mockRejectedValue(new Error('network'))
    renderGate({ page: 'stats', leagueKey: 'X' })
    expect(await screen.findByText('the page')).toBeInTheDocument()
  })

  it('does not gate a draft the league list does not know', async () => {
    vi.spyOn(api, 'getDrafts').mockResolvedValue([])
    const ask = vi.spyOn(api, 'getLeagueAvailability')
    renderGate({ page: 'draftRoom', draftId: 'nope' })
    expect(await screen.findByText('the page')).toBeInTheDocument()
    expect(ask).not.toHaveBeenCalled()
  })
})
