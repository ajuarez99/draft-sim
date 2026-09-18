import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SeasonForecast from './SeasonForecast'
import type { SeasonForecast as Data, ForecastTeam } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const getSeasonForecast = vi.fn()
vi.mock('../api', () => ({
  getSeasonForecast: (...args: unknown[]) => getSeasonForecast(...args),
}))

function team(over: Partial<ForecastTeam> = {}): ForecastTeam {
  return {
    rosterId: 1,
    managerId: 10,
    teamName: 'Master Bates',
    avatarId: null,
    playoffOdds: 95.4,
    averageWins: 9.92,
    projectedPoints: 1840.2,
    winRange: { p10: 8, p90: 12 },
    averageSeed: 2.5,
    seedOnePct: 41.0,
    seedOdds: { '1': 0.41, '2': 0.19 },
    ...over,
  }
}

function data(over: Partial<Data> = {}): Data {
  return {
    available: true,
    season: 2026,
    week: 1,
    iterations: 10000,
    model: 'shrunk-normal-v1',
    teams: [team()],
    ...over,
  }
}

describe('Season forecast', () => {
  beforeEach(() => getSeasonForecast.mockReset())

  it('shows odds, average wins, range, seed and No. 1 odds', async () => {
    getSeasonForecast.mockResolvedValue(data())
    render(<SeasonForecast />)

    const row = await screen.findByRole('row', { name: /Master Bates/ })
    expect(within(row).getByText('95.4%')).toBeInTheDocument()
    expect(within(row).getByText('9.92')).toBeInTheDocument()
    expect(within(row).getByText('8–12')).toBeInTheDocument()
    expect(within(row).getByText('#2.5')).toBeInTheDocument()
    expect(within(row).getByText('41.0%')).toBeInTheDocument()
  })

  it('says the numbers come from a stored snapshot, not a fresh run', async () => {
    getSeasonForecast.mockResolvedValue(data())
    render(<SeasonForecast />)

    expect(await screen.findByText(/10,000 simulated seasons/)).toBeInTheDocument()
    expect(screen.getByText(/match the playoff odds shown on the power rankings/)).toBeInTheDocument()
  })

  /**
   * US4.3. The existing refusal for a league this app cannot seed must survive
   * the new endpoint, and must say WHY rather than render a dash.
   */
  it('explains an unmodelled seeding scheme rather than showing a dash', async () => {
    getSeasonForecast.mockResolvedValue(
      data({ available: false, reason: 'UNMODELLED_SEEDING', teams: [] }),
    )
    render(<SeasonForecast />)

    expect(await screen.findByText(/seeds its playoffs in a way this app doesn't model/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  /** US4.4, and a different sentence from the one above on purpose. */
  it('explains a season with no scored week', async () => {
    getSeasonForecast.mockResolvedValue(
      data({ available: false, reason: 'NO_SCORED_WEEKS', teams: [] }),
    )
    render(<SeasonForecast />)

    expect(await screen.findByText(/No week has been scored yet/)).toBeInTheDocument()
  })

  /**
   * A snapshot from before distributions were stored has no range. That is not
   * a range of zero, and the row must not draw one.
   */
  it('says so when a snapshot has no stored distribution', async () => {
    getSeasonForecast.mockResolvedValue(
      data({ teams: [team({ winRange: { p10: null, p90: null }, averageSeed: null })] }),
    )
    render(<SeasonForecast />)

    const row = await screen.findByRole('row', { name: /Master Bates/ })
    expect(within(row).getByText('no distribution stored')).toBeInTheDocument()
    expect(within(row).getByText('—')).toBeInTheDocument()
  })

  /** US4.5: an NBA league forecasts through the same path. */
  it('renders a basketball league', async () => {
    getSeasonForecast.mockResolvedValue(
      data({ teams: [team({ teamName: 'Hoop Dreams', playoffOdds: 62.5, averageWins: 11.4 })] }),
    )
    render(<SeasonForecast />)

    const row = await screen.findByRole('row', { name: /Hoop Dreams/ })
    expect(within(row).getByText('62.5%')).toBeInTheDocument()
    expect(within(row).getByText('11.40')).toBeInTheDocument()
  })
})
