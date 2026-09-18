import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WeeklyReport from './WeeklyReport'
import type { WeeklyReport as Data } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const getWeeklyReport = vi.fn()
vi.mock('../api', () => ({
  getWeeklyReport: (...args: unknown[]) => getWeeklyReport(...args),
}))

function data(over: Partial<Data> = {}): Data {
  return {
    available: true,
    season: 2026,
    week: 1,
    sport: 'nfl',
    matchups: [
      {
        home: { rosterId: 1, teamName: 'Dart has hit anotha Bower', avatarId: null, record: '1-0', points: 157.4 },
        away: { rosterId: 2, teamName: 'Justice for Wags', avatarId: null, record: '0-1', points: 147.9 },
      },
    ],
    topPerformers: [
      { playerId: '1', playerName: 'Caleb Williams', position: 'QB', teamName: 'Prayer Circle', points: 37.26 },
    ],
    awards: [
      {
        kind: 'SELF_INFLICTED_WOUND',
        teamName: 'Justice for Wags',
        detail: 'Deebo Samuel outscored Carnell Tate by 10.20. Justice for Wags lost by 9.50.',
      },
    ],
    awardsOmitted: [],
    ...over,
  }
}

describe('Weekly report', () => {
  beforeEach(() => getWeeklyReport.mockReset())

  /** US5.1: both teams, both records, both final scores. */
  it('shows every matchup with records and final scores', async () => {
    getWeeklyReport.mockResolvedValue(data())
    render(<WeeklyReport />)

    // Scoped to the matchups panel: a team that also won an award appears
    // twice on this page, which is realistic rather than a bug.
    const heading = await screen.findByRole('heading', { name: /matchups/i })
    const panel = heading.closest('section') as HTMLElement

    expect(within(panel).getByText('Dart has hit anotha Bower')).toBeInTheDocument()
    expect(within(panel).getByText('Justice for Wags')).toBeInTheDocument()
    expect(within(panel).getByText('(1-0)')).toBeInTheDocument()
    expect(within(panel).getByText('(0-1)')).toBeInTheDocument()
    expect(within(panel).getByText('157.40')).toBeInTheDocument()
    expect(within(panel).getByText('147.90')).toBeInTheDocument()
  })

  /** US5.2: ranked by points actually scored, with the owning team named. */
  it('lists top performers with their team', async () => {
    getWeeklyReport.mockResolvedValue(data())
    render(<WeeklyReport />)

    expect(await screen.findByText('Caleb Williams')).toBeInTheDocument()
    expect(screen.getByText('Prayer Circle')).toBeInTheDocument()
    expect(screen.getByText('37.26')).toBeInTheDocument()
  })

  /** US5.3: the award names the specific players involved. */
  it('renders an award with a readable title and its detail', async () => {
    getWeeklyReport.mockResolvedValue(data())
    render(<WeeklyReport />)

    expect(await screen.findByText('Self-inflicted wound')).toBeInTheDocument()
    expect(screen.getByText(/Deebo Samuel outscored Carnell Tate by 10.20/)).toBeInTheDocument()
  })

  /**
   * US5.4. The honesty case: an award that could not be computed says so. A
   * missing award is otherwise indistinguishable from nobody qualifying.
   */
  it('explains an award it could not work out', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        awards: [],
        awardsOmitted: [{ kind: 'SELF_INFLICTED_WOUND', reason: 'STARTERS_NOT_STORED' }],
      }),
    )
    render(<WeeklyReport />)

    expect(await screen.findByText(/could not be worked out for this week/)).toBeInTheDocument()
    expect(screen.getByText(/naming the swap would be a guess/)).toBeInTheDocument()
  })

  it('says a week has not been scored rather than showing an empty board', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        available: false,
        reason: 'week 9 has not been scored for this league',
        week: 9,
        matchups: [],
        topPerformers: [],
        awards: [],
      }),
    )
    render(<WeeklyReport />)

    expect(await screen.findByText(/Week 9 has not been scored/)).toBeInTheDocument()
  })

  /** US5.5: basketball renders the same shapes. */
  it('renders a basketball week', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        sport: 'nba',
        matchups: [
          {
            home: { rosterId: 1, teamName: 'Hoop Dreams', avatarId: null, record: '1-0', points: 228 },
            away: { rosterId: 2, teamName: 'Rim Reapers', avatarId: null, record: '0-1', points: 211 },
          },
        ],
      }),
    )
    render(<WeeklyReport />)

    expect(await screen.findByText('Hoop Dreams')).toBeInTheDocument()
    expect(screen.getByText('228.00')).toBeInTheDocument()
  })
})
