import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RosterManagement from './RosterManagement'
import type { RosterManagement as Data, RosterManagementTeam } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const getRosterManagement = vi.fn()
vi.mock('../api', () => ({
  getRosterManagement: (...args: unknown[]) => getRosterManagement(...args),
}))

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
})
