import { render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { expect, it, vi } from 'vitest'
import PowerRankings from './PowerRankings'

vi.mock('react-router-dom', () => ({
  Link: ({ to, children, ...rest }: { to: string; children: ReactNode }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
  useParams: () => ({ sleeperLeagueId: '1389361939561332736' }),
}))

vi.mock('../user', () => ({
  useUser: () => ({ sleeperUserId: '1', username: 'popsharky', displayName: 'popsharky', avatar: null }),
}))

// The one call under test answers a 200 whose body predates 90274e7's
// nflState -> sportState rename -- exactly what api.ballknowers.co was still
// serving on 2026-09-15 while the deployed frontend already read sportState.
const getPowerRankings = vi.fn().mockResolvedValue({
  sleeperLeagueId: '1389361939561332736',
  nflState: { week: 2, season: '2026', seasonStartDate: '2026-09-09', started: true },
  entries: [],
})
vi.mock('../api', () => ({
  ALL_POWER_RANKING_KINDS: ['COMPUTED_REALIZED', 'COMMISSIONER', 'MEMBER'],
  getPowerRankings: (...args: unknown[]) => getPowerRankings(...args),
  getBallot: () => Promise.resolve(null),
  getLeagueHistory: () => Promise.resolve({ seasons: [] }),
  computePowerRankings: vi.fn(),
  saveCommissionerRanking: vi.fn(),
  submitBallot: vi.fn(),
}))

it('says the server is stale instead of throwing on the missing sportState', async () => {
  render(<PowerRankings />)

  await waitFor(() => expect(screen.getByText(/older build/)).toBeTruthy())
  // The page header is still there: this is the page's own error state, not a
  // boundary catching a TypeError out of the `season` useMemo.
  expect(screen.getByText('Power rankings')).toBeTruthy()
})
