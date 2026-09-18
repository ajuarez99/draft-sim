import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RosterManagement from './RosterManagement'
import type { RosterManagement as Data, RosterManagementTeam, LeagueTransactions } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const getRosterManagement = vi.fn()
const getLeagueTransactions = vi.fn()
vi.mock('../api', () => ({
  getRosterManagement: (...args: unknown[]) => getRosterManagement(...args),
  getLeagueTransactions: (...args: unknown[]) => getLeagueTransactions(...args),
}))

function transactions(over: Partial<LeagueTransactions> = {}): LeagueTransactions {
  return {
    available: true,
    season: 2026,
    sport: 'nfl',
    rankDirection: 'LOWER_IS_BETTER',
    byManager: [
      { managerId: 10, teamName: 'Master Bates', counts: { WAIVER: 2, FREE_AGENT: 1 }, total: 3 },
    ],
    trades: [],
    adds: [
      {
        week: 1,
        teamName: 'Torta Pounder with Cheese',
        type: 'WAIVER',
        status: 'complete',
        added: { playerId: '12711', playerName: 'Tyler Loop', position: 'K', postMovePositionalRank: 2, weeksCounted: 1 },
        dropped: { playerId: '2', playerName: 'Najee Harris', position: 'RB', postMovePositionalRank: null, weeksCounted: 0 },
        faabBid: 0,
      },
    ],
    ...over,
  }
}

function team(over: Partial<RosterManagementTeam> = {}): RosterManagementTeam {
  return {
    rosterId: 1,
    managerId: 10,
    teamName: 'Master Bates',
    avatarId: null,
    totalPoints: 164.96,
    potentialPoints: 174.16,
    efficiency: 0.947,
    weeksCounted: 1,
    weeksExcluded: [],
    ...over,
  }
}

function data(over: Partial<Data> = {}): Data {
  return {
    available: true,
    season: 2026,
    sport: 'nfl',
    weeksScored: 1,
    teams: [team()],
    ...over,
  }
}

describe('Roster management', () => {
  beforeEach(() => {
    getRosterManagement.mockReset()
    getLeagueTransactions.mockReset()
    // The standings half must render whether or not transactions have been
    // ingested, so the default for these cases is "not available".
    getLeagueTransactions.mockResolvedValue(
      transactions({ available: false, reason: 'none ingested', byManager: [], adds: [] }),
    )
  })

  it('shows total, potential and efficiency for each team', async () => {
    getRosterManagement.mockResolvedValue(data())
    render(<RosterManagement />)

    const row = await screen.findByRole('row', { name: /Master Bates/ })
    expect(within(row).getByText('164.96')).toBeInTheDocument()
    expect(within(row).getByText('174.16')).toBeInTheDocument()
    expect(within(row).getByText('94.7%')).toBeInTheDocument()
  })

  /**
   * The exact number sits beside its own mark. A bar whose value the reader
   * has to estimate off a shared axis is the thing this page exists not to do.
   */
  it('spells out how much was left on the bench beside the bar', async () => {
    getRosterManagement.mockResolvedValue(data())
    render(<RosterManagement />)

    const row = await screen.findByRole('row', { name: /Master Bates/ })
    expect(within(row).getByText(/9\.20 left on the bench/)).toBeInTheDocument()
  })

  it('says "perfect" rather than "0.00 left" when the lineup was optimal', async () => {
    getRosterManagement.mockResolvedValue(
      data({ teams: [team({ totalPoints: 150, potentialPoints: 150, efficiency: 1 })] }),
    )
    render(<RosterManagement />)

    const row = await screen.findByRole('row', { name: /Master Bates/ })
    expect(within(row).getByText('perfect')).toBeInTheDocument()
  })

  /**
   * US2.4. A team with no potential has no efficiency. Rendering 100% for it
   * would be the most flattering possible wrong answer, so the cell must not
   * show a percentage at all.
   */
  it('renders a dash, never 100%, when there is no potential to divide by', async () => {
    getRosterManagement.mockResolvedValue(
      data({
        teams: [team({ totalPoints: 0, potentialPoints: 0, efficiency: null, weeksCounted: 0 })],
      }),
    )
    render(<RosterManagement />)

    const row = await screen.findByRole('row', { name: /Master Bates/ })
    expect(within(row).getByText('—')).toBeInTheDocument()
    expect(within(row).queryByText(/100\.0%/)).not.toBeInTheDocument()
  })

  /** US2.4: no scored weeks is a stated reason, not a table of zeros. */
  it('explains itself when the league has no scored weeks', async () => {
    getRosterManagement.mockResolvedValue(
      data({ available: false, reason: 'no scored weeks yet for this league', teams: [], weeksScored: 0 }),
    )
    render(<RosterManagement />)

    expect(await screen.findByText(/Nothing to measure yet/)).toBeInTheDocument()
    expect(screen.getByText(/no scored weeks yet for this league/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  /**
   * FR-007 / US2.5. An excluded week is left out of potential rather than
   * counted as zero -- which the reader has to be told, or a short season
   * silently reads as a full one.
   */
  it('names the weeks excluded from potential', async () => {
    getRosterManagement.mockResolvedValue(
      data({ weeksScored: 3, teams: [team({ weeksCounted: 2, weeksExcluded: [2] })] }),
    )
    render(<RosterManagement />)

    await waitFor(() => expect(screen.getByText(/Week 2 had no per-player scoring/)).toBeInTheDocument())
    expect(screen.getByText(/rather than counted as zero/)).toBeInTheDocument()
  })

  it('says nothing about exclusions when there are none', async () => {
    getRosterManagement.mockResolvedValue(data())
    render(<RosterManagement />)

    await screen.findByRole('row', { name: /Master Bates/ })
    expect(screen.queryByText(/left out of potential/)).not.toBeInTheDocument()
  })

  /** SC-002: the page is not football-only. */
  it('renders a basketball league through the same path', async () => {
    getRosterManagement.mockResolvedValue(
      data({ sport: 'nba', teams: [team({ teamName: 'Hoop Dreams', totalPoints: 228, potentialPoints: 240, efficiency: 0.95 })] }),
    )
    render(<RosterManagement />)

    const row = await screen.findByRole('row', { name: /Hoop Dreams/ })
    expect(within(row).getByText('228.00')).toBeInTheDocument()
    expect(within(row).getByText('95.0%')).toBeInTheDocument()
  })

  // ---- US6: transactions, as sections of this same page ----

  it('breaks transactions out by type with the count on each segment', async () => {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockResolvedValue(transactions())
    render(<RosterManagement />)

    const heading = await screen.findByRole('heading', { name: /league transactions/i })
    const panel = heading.closest('section') as HTMLElement
    expect(within(panel).getByText('2')).toBeInTheDocument()
    expect(within(panel).getByText('1')).toBeInTheDocument()
    expect(within(panel).getByText(/Waiver claims/)).toBeInTheDocument()
  })

  /** US6.4: a real answer, not an empty chart. */
  it('says no trades have been made rather than rendering nothing', async () => {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockResolvedValue(transactions({ trades: [] }))
    render(<RosterManagement />)

    expect(await screen.findByText('No trades have been made.')).toBeInTheDocument()
  })

  /** US6.3: the direction is stated, because "2.0" meaning good is not obvious. */
  it('states that a lower rank is better and how many weeks it covers', async () => {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockResolvedValue(transactions())
    render(<RosterManagement />)

    expect(await screen.findByText('Tyler Loop')).toBeInTheDocument()
    expect(screen.getByText(/lower is better/i)).toBeInTheDocument()
    expect(screen.getByText(/1 wk/)).toBeInTheDocument()
  })

  /** An ungraded add is ungraded, not bad. */
  it('marks an add with no played week as ungraded', async () => {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockResolvedValue(
      transactions({
        adds: [
          {
            week: 3,
            teamName: 'Master Bates',
            type: 'FREE_AGENT',
            status: 'complete',
            added: { playerId: '9', playerName: 'Someone New', position: 'WR', postMovePositionalRank: null, weeksCounted: 0 },
            dropped: null,
            faabBid: null,
          },
        ],
      }),
    )
    render(<RosterManagement />)

    expect(await screen.findByText('ungraded')).toBeInTheDocument()
  })

  it('still renders the standings when transactions have not been ingested', async () => {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockRejectedValue(new Error('404'))
    render(<RosterManagement />)

    expect(await screen.findByRole('row', { name: /Master Bates/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /league transactions/i })).not.toBeInTheDocument()
  })
})
