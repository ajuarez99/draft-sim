import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DraftPicker from './DraftPicker'
import type { DraftSummary, SleeperLeague } from '../api'

vi.mock('react-router-dom', () => ({
  // A plain <a> passthrough is enough for <Link> -- same convention as
  // MockSetup.test.tsx's narrower mock. useNavigate backs the "Start a mock
  // draft" modal's handoff to /mock/new; unused by these From-Sleeper-only tests.
  Link: ({ to, children, ...rest }: { to: string; children: ReactNode }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
  useNavigate: () => vi.fn(),
  // DraftPicker reads location.state to know whether the rail's "Mock drafts"
  // row asked it to open the modal on arrival (AppShell.openMockModal). These
  // tests never make that request, so a stable empty location is enough -- but
  // it has to be stable: a fresh object each call would give the effect a new
  // `key` every render and reopen the modal forever.
  useLocation: () => EMPTY_LOCATION,
}))

const EMPTY_LOCATION = { key: 'test', pathname: '/', search: '', hash: '', state: null }

const getDrafts = vi.fn()
const getMockSessions = vi.fn()
const getSleeperUserLeagues = vi.fn()
const refreshPlayers = vi.fn()
const refreshLeague = vi.fn()
const ingestLeague = vi.fn()
const ingestAdp = vi.fn()
const ingestBoard = vi.fn()
vi.mock('../api', () => ({
  getDrafts: (...args: unknown[]) => getDrafts(...args),
  getMockSessions: (...args: unknown[]) => getMockSessions(...args),
  getSleeperUserLeagues: (...args: unknown[]) => getSleeperUserLeagues(...args),
  refreshPlayers: (...args: unknown[]) => refreshPlayers(...args),
  refreshLeague: (...args: unknown[]) => refreshLeague(...args),
  ingestLeague: (...args: unknown[]) => ingestLeague(...args),
  ingestAdp: (...args: unknown[]) => ingestAdp(...args),
  ingestBoard: (...args: unknown[]) => ingestBoard(...args),
}))

vi.mock('../user', () => ({
  useUser: () => ({ sleeperUserId: '42', username: 'tester', displayName: 'Tester', avatar: null }),
  clearUser: vi.fn(),
}))

const nbaLeague: SleeperLeague = {
  sleeperLeagueId: '999',
  name: 'Ball Knowers',
  sport: 'nba',
  season: 2026,
  totalRosters: 12,
  draftId: 'd1',
  status: 'pre_draft',
  previousLeagueId: null,
  ingested: false,
}

beforeEach(() => {
  getDrafts.mockReset().mockResolvedValue([])
  getMockSessions.mockReset().mockResolvedValue([])
  getSleeperUserLeagues.mockReset().mockResolvedValue([nbaLeague])
  refreshPlayers.mockReset().mockResolvedValue({ outcome: 'DONE', detail: null })
  refreshLeague.mockReset().mockResolvedValue({})
  ingestLeague.mockReset().mockResolvedValue({})
  ingestAdp.mockReset().mockResolvedValue({})
  ingestBoard.mockReset().mockResolvedValue({})
})

describe('DraftPicker "From Sleeper"', () => {
  it('lists a not-yet-ingested Sleeper league with a Set up action', async () => {
    render(<DraftPicker />)

    expect(await screen.findByText('Ball Knowers')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set up' })).toBeInTheDocument()
  })

  it('does not render the section once every Sleeper league is already ingested', async () => {
    getSleeperUserLeagues.mockResolvedValue([{ ...nbaLeague, ingested: true }])
    render(<DraftPicker />)

    await waitFor(() => expect(getSleeperUserLeagues).toHaveBeenCalled())
    expect(screen.queryByText('From Sleeper')).not.toBeInTheDocument()
  })

  it('Set up runs all five stages in order, then re-fetches both lists', async () => {
    const user = userEvent.setup()
    render(<DraftPicker />)

    await user.click(await screen.findByRole('button', { name: 'Set up' }))

    await waitFor(() => expect(refreshLeague).toHaveBeenCalledWith('999'))
    expect(ingestBoard).toHaveBeenCalledWith('nba')
    expect(refreshPlayers).toHaveBeenCalledWith('nba')
    expect(ingestLeague).toHaveBeenCalledWith('999')
    expect(ingestAdp).toHaveBeenCalledWith('nba')

    const playersOrder = refreshPlayers.mock.invocationCallOrder[0]
    const leagueOrder = ingestLeague.mock.invocationCallOrder[0]
    const adpOrder = ingestAdp.mock.invocationCallOrder[0]
    const boardOrder = ingestBoard.mock.invocationCallOrder[0]
    expect(playersOrder).toBeLessThan(leagueOrder)
    expect(leagueOrder).toBeLessThan(adpOrder)
    expect(adpOrder).toBeLessThan(boardOrder)
    expect(boardOrder).toBeLessThan(refreshLeague.mock.invocationCallOrder[0])

    // getDrafts/getSleeperUserLeagues: once on mount, once after setup succeeds.
    await waitFor(() => expect(getDrafts).toHaveBeenCalledTimes(2))
    expect(getSleeperUserLeagues).toHaveBeenCalledTimes(2)
  })

  it('a failing stage names itself and offers Retry instead of a generic error', async () => {
    ingestLeague.mockRejectedValue(new Error('sleeper unreachable'))
    const user = userEvent.setup()
    render(<DraftPicker />)

    await user.click(await screen.findByRole('button', { name: 'Set up' }))

    expect(await screen.findByText(/Reading your league's history: sleeper unreachable/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    // The failed stage must not fall through to later stages.
    expect(ingestAdp).not.toHaveBeenCalled()
    expect(ingestBoard).not.toHaveBeenCalled()
    expect(refreshLeague).not.toHaveBeenCalled()
  })

  it('a failed past-seasons refresh does not fail setup', async () => {
    refreshLeague.mockRejectedValue(new Error('refresh unavailable'))
    const user = userEvent.setup()
    render(<DraftPicker />)

    await user.click(await screen.findByRole('button', { name: 'Set up' }))

    await waitFor(() => expect(refreshLeague).toHaveBeenCalledWith('999'))
    await waitFor(() => expect(getDrafts).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })
})

describe('DraftPicker hero subtitle', () => {
  const draftRow = (over: Partial<DraftSummary>): DraftSummary => ({
    id: 1, sleeperDraftId: 'd1', leagueId: 1, leagueName: 'Football League', season: 2026,
    teams: 12, rounds: 15, status: 'complete', startTime: null, sleeperLeagueId: 'L1',
    previousLeagueId: null, sport: 'nfl', ...over,
  })
  const drafts = [
    draftRow({}),
    draftRow({ id: 2, sleeperDraftId: 'd2', leagueId: 2, leagueName: 'Hoops League', sleeperLeagueId: 'L2', sport: 'nba', rounds: 13, teams: 10 }),
  ]

  beforeEach(() => {
    getDrafts.mockResolvedValue(drafts)
    getSleeperUserLeagues.mockResolvedValue([])
    localStorage.clear()
  })

  it('names no league under "All sports"', async () => {
    render(<DraftPicker />)
    expect(await screen.findByText(/seat the real managers from any of your leagues/)).toBeInTheDocument()
    expect(document.querySelector('.page-head strong')).toBeNull()
  })

  it('names the newest league in the chosen sport, never a football one under NBA', async () => {
    localStorage.setItem('bk-sport-filter', 'nba')
    render(<DraftPicker />)
    await waitFor(() => expect(document.querySelector('.page-head strong')?.textContent).toBe('Hoops League'))
  })
})
