import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DraftPicker from './DraftPicker'
import type { SleeperLeague } from '../api'

vi.mock('react-router-dom', () => ({
  // DraftPicker only ever uses <Link>, never navigate/params -- a plain <a>
  // passthrough is enough, same convention as MockSetup.test.tsx's narrower mock.
  Link: ({ to, children, ...rest }: { to: string; children: ReactNode }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
}))

const getDrafts = vi.fn()
const getMockSessions = vi.fn()
const getSleeperUserLeagues = vi.fn()
const ingestPlayers = vi.fn()
const ingestLeague = vi.fn()
const ingestAdp = vi.fn()
const ingestBoard = vi.fn()
vi.mock('../api', () => ({
  getDrafts: (...args: unknown[]) => getDrafts(...args),
  getMockSessions: (...args: unknown[]) => getMockSessions(...args),
  getSleeperUserLeagues: (...args: unknown[]) => getSleeperUserLeagues(...args),
  ingestPlayers: (...args: unknown[]) => ingestPlayers(...args),
  ingestLeague: (...args: unknown[]) => ingestLeague(...args),
  ingestAdp: (...args: unknown[]) => ingestAdp(...args),
  ingestBoard: (...args: unknown[]) => ingestBoard(...args),
}))

vi.mock('../user', () => ({
  useUser: () => ({ sleeperUserId: '42', username: 'tester', displayName: 'Tester', avatar: null }),
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
  ingestPlayers.mockReset().mockResolvedValue({})
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

  it('Set up runs all four ingest stages in order, then re-fetches both lists', async () => {
    const user = userEvent.setup()
    render(<DraftPicker />)

    await user.click(await screen.findByRole('button', { name: 'Set up' }))

    await waitFor(() => expect(ingestBoard).toHaveBeenCalledWith('nba'))
    expect(ingestPlayers).toHaveBeenCalledWith('nba')
    expect(ingestLeague).toHaveBeenCalledWith('999')
    expect(ingestAdp).toHaveBeenCalledWith('nba')

    const playersOrder = ingestPlayers.mock.invocationCallOrder[0]
    const leagueOrder = ingestLeague.mock.invocationCallOrder[0]
    const adpOrder = ingestAdp.mock.invocationCallOrder[0]
    const boardOrder = ingestBoard.mock.invocationCallOrder[0]
    expect(playersOrder).toBeLessThan(leagueOrder)
    expect(leagueOrder).toBeLessThan(adpOrder)
    expect(adpOrder).toBeLessThan(boardOrder)

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
  })
})
