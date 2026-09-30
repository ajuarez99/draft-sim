import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ExpectedWins from './ExpectedWins'
import type { ExpectedWins as Data, ExpectedWinsTeam } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const getExpectedWins = vi.fn()
vi.mock('../api', () => ({
  getExpectedWins: (...args: unknown[]) => getExpectedWins(...args),
}))

function team(over: Partial<ExpectedWinsTeam> = {}): ExpectedWinsTeam {
  return {
    rosterId: 1,
    managerId: 10,
    teamName: 'jpelwell',
    username: null,
    avatarId: null,
    expectedWins: 0.45,
    actualWins: 1,
    winsAboveExpected: 0.55,
    strengthOfSchedule: -12.4,
    luckSource: 'SWING_WEEKS',
    swingWeeks: [
      { week: 1, result: 'WON', points: 146.16, weeklyRank: 7, opponent: 'She Hocken on my Staff' },
    ],
    ...over,
  }
}

function data(over: Partial<Data> = {}): Data {
  return {
    available: true,
    season: 2026,
    sport: 'nfl',
    weeksScored: 1,
    leagueAveragePpg: 130.1,
    teams: [team()],
    ...over,
  }
}

describe('Expected wins', () => {
  beforeEach(() => getExpectedWins.mockReset())

  it('shows actual and expected wins with the gap spelled out', async () => {
    getExpectedWins.mockResolvedValue(data())
    render(<ExpectedWins />)

    const row = await screen.findByRole('row', { name: /jpelwell/ })
    expect(within(row).getByText('1')).toBeInTheDocument()
    expect(within(row).getByText('0.45')).toBeInTheDocument()
    expect(within(row).getByText('+0.55')).toBeInTheDocument()
  })

  /**
   * US3.3. The sign convention is stated, not left for the reader to infer
   * from a column of signed numbers.
   */
  it('states which direction of schedule number means harder', async () => {
    getExpectedWins.mockResolvedValue(data())
    render(<ExpectedWins />)

    expect(await screen.findByText(/Positive means a harder schedule/)).toBeInTheDocument()
  })

  /** US3.4: swing weeks are named, with the rank that makes them a swing. */
  it('names the specific week when luck came from swing weeks', async () => {
    getExpectedWins.mockResolvedValue(data())
    render(<ExpectedWins />)

    expect(await screen.findByText(/Week 1/)).toBeInTheDocument()
    expect(screen.getByText(/7th that week/)).toBeInTheDocument()
    expect(screen.getByText(/She Hocken on my Staff/)).toBeInTheDocument()
  })

  /** US3.4: the other branch, and never both. */
  it('explains consistent opponent scoring instead of listing weeks', async () => {
    getExpectedWins.mockResolvedValue(
      data({
        teams: [team({ luckSource: 'CONSISTENT_OPPONENT_SCORING', swingWeeks: [] })],
      }),
    )
    render(<ExpectedWins />)

    expect(await screen.findByText(/No single week did this/)).toBeInTheDocument()
    expect(screen.queryByText(/that week\)/)).not.toBeInTheDocument()
  })

  describe.each([
    ['luck + / SOS + (disagree)', 0.45, 7.17, /schedule ran the other way \(\+7\.2, harder\)/, /lower-scoring|higher-scoring|fewer points|more points/],
    ['luck - / SOS - (disagree)', -0.6, -5.3, /schedule ran the other way \(-5\.3, easier\)/, /lower-scoring|higher-scoring|fewer points|more points/],
    ['luck + / SOS - (agree)', 0.5, -6.2, /averaged 6\.2 fewer points than the league/, /ran the other way/],
    ['luck - / SOS + (agree)', -0.5, 6.2, /averaged 6\.2 more points than the league/, /ran the other way/],
    ['SOS near zero', 0.3, 0.4, /close to average/, /fewer points|more points|ran the other way/],
  ])('no-swing copy, %s', (_n, wae, sos, yes, no) => {
    it('names only what the numbers support', async () => {
      getExpectedWins.mockResolvedValue(
        data({
          teams: [team({ winsAboveExpected: wae, strengthOfSchedule: sos, luckSource: 'CONSISTENT_OPPONENT_SCORING', swingWeeks: [] })],
        }),
      )
      render(<ExpectedWins />)
      expect(await screen.findByText(yes)).toBeInTheDocument()
      expect(screen.queryByText(no)).not.toBeInTheDocument()
    })
  })

  it('explains itself when no games have been played', async () => {
    getExpectedWins.mockResolvedValue(
      data({ available: false, reason: 'no completed games for this league yet', teams: [], weeksScored: 0 }),
    )
    render(<ExpectedWins />)

    expect(await screen.findByText(/No games to measure yet/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  /** US3.5: nothing here is football-shaped. */
  it('renders a basketball league identically', async () => {
    getExpectedWins.mockResolvedValue(
      data({
        sport: 'nba',
        leagueAveragePpg: 228.4,
        teams: [team({ teamName: 'Hoop Dreams', winsAboveExpected: -0.8, luckSource: 'CONSISTENT_OPPONENT_SCORING', swingWeeks: [] })],
      }),
    )
    render(<ExpectedWins />)

    const row = await screen.findByRole('row', { name: /Hoop Dreams/ })
    expect(within(row).getByText('-0.80')).toBeInTheDocument()
    expect(screen.getByText(/228.40 points per game/)).toBeInTheDocument()
  })
})

describe('Expected wins eyebrow names the season shown', () => {
  it('reads League · <season> from the payload', async () => {
    getExpectedWins.mockResolvedValue(data({ season: 2025 }))
    render(<ExpectedWins />)
    expect(await screen.findByText('League · 2025')).toBeInTheDocument()
  })

  it('uses the resolved season, not the requested one, when the server fell back', async () => {
    getExpectedWins.mockResolvedValue(data({ season: 2025, requestedSeason: 2026 }))
    render(<ExpectedWins />)
    expect(await screen.findByText('League · 2025')).toBeInTheDocument()
    expect(screen.queryByText('League · 2026')).not.toBeInTheDocument()
  })

  it('is plain League while there is no payload yet', () => {
    getExpectedWins.mockReturnValue(new Promise(() => {}))
    render(<ExpectedWins />)
    expect(screen.getByText('League')).toBeInTheDocument()
  })
})
