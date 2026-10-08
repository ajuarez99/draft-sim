import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PlayerTrends from './PlayerTrends'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import type { PlayerTrends as Trends, TrendRow } from '../api'
import { invalidateRailLeagues } from '../railLeague'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
  // PlayerLink renders a router Link; the page under test needs only its href.
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))

const getPlayerTrends = vi.fn()
const getDrafts = vi.fn()
vi.mock('../api', () => ({
  getDrafts: (...args: unknown[]) => getDrafts(...args),
  getPlayerTrends: (...args: unknown[]) => getPlayerTrends(...args),
}))

function row(over: Partial<TrendRow> = {}): TrendRow {
  return {
    sleeperPlayerId: '1',
    name: 'Ann Guard',
    positions: ['PG'],
    team: 'DEN',
    games: 40,
    lastGameDate: '2026-04-10',
    recentMin: 34.2,
    seasonMin: 28.1,
    minDelta: 6.1,
    role: 'RISER',
    recentUsg: 27.3,
    seasonUsg: 24,
    ptsPerMin: 1.2,
    seasonPts: 38,
    formPts: 41.6,
    missedTeamGames: 0,
    rostered: false,
    rosteredBy: null,
    gamesThisWeek: 4,
    gamesNextWeek: 3,
    ...over,
  }
}

function data(over: Partial<Trends> = {}): Trends {
  return {
    sport: 'nba',
    season: 2026,
    available: true,
    reason: null,
    rolesSeason: 2025,
    rolesFallback: true,
    streamingSeason: 2025,
    streamingFallback: true,
    windows: { recentGames: 3, formGames: 5, formMinGames: 3, minSeasonGames: 5, recencyDays: 14 },
    roleThresholdMinutes: 6,
    currentWeek: 1,
    risersTotal: 12,
    fallersTotal: 1,
    risersFreeAgentTotal: 8,
    risersRosteredTotal: 4,
    fallersFreeAgentTotal: 1,
    fallersRosteredTotal: 0,
    excludedStale: 41,
    excludedNoTeam: 86,
    staleReferenceDate: '2026-04-12',
    risers: [row({ sleeperPlayerId: '1', name: 'Ann Guard' }), row({ sleeperPlayerId: '2', name: 'Bo Wing', rostered: true, rosteredBy: 'Casey' })],
    fallers: [row({ sleeperPlayerId: '3', name: 'Cy Big', role: 'FALLER', minDelta: -7, rostered: false })],
    streamingReason: null,
    rostersFetchedAt: '2026-10-11T02:10:00Z',
    streaming: [row({ sleeperPlayerId: '9', name: 'Stream Guy', missedTeamGames: 2 })],
    oneGameCredit: null,
    ...over,
  }
}

function show(d: Trends) {
  getPlayerTrends.mockResolvedValue(d)
  return render(
    <LeagueDataVersionProvider>
      <PlayerTrends />
    </LeagueDataVersionProvider>,
  )
}

beforeEach(() => {
  getPlayerTrends.mockReset()
  getDrafts.mockReset()
  invalidateRailLeagues()
})

describe('PlayerTrends', () => {
  // specs/022 T033
  it('links every player name to the player page on basketball', async () => {
    show(data())
    expect(await screen.findByRole('link', { name: 'Stream Guy' })).toHaveAttribute('href', '/leagues/L1/players/9')
    expect(screen.getByRole('link', { name: 'Ann Guard' })).toHaveAttribute('href', '/leagues/L1/players/1')
    expect(screen.getByRole('link', { name: 'Cy Big' })).toHaveAttribute('href', '/leagues/L1/players/3')
  })

  it('leaves names unlinked for a sport without player pages', async () => {
    show(data({ sport: 'nfl' }))
    expect(await screen.findByText('Stream Guy')).toBeInTheDocument()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('leads a fallen-back list with the earlier season and why', async () => {
    show(data())
    expect(
      (await screen.findAllByText(/2025 season \(2026 is too early: fewer than half the teams have played [35] games\)/)).length,
    ).toBeGreaterThan(0)
    expect(screen.getByText(/end of the 2025 season/)).toBeInTheDocument()
  })

  it('quotes each list’s own cutover: 3 games for streaming, 5 for risers and fallers', async () => {
    show(data())
    await screen.findByText('Stream Guy')
    expect(screen.getAllByText(/have played 3 games/)).toHaveLength(1)
    expect(screen.getAllByText(/have played 5 games/)).toHaveLength(1)
    expect(screen.getByText(/have played 3 games/).closest('section')).toHaveAttribute('aria-label', 'Streaming candidates')
    expect(screen.getByText(/have played 5 games/).closest('section')).toHaveAttribute('aria-label', 'Risers and fallers')
  })

  it('shows the streaming table with its labels and the missed-games line', async () => {
    show(data())
    expect(await screen.findByText('Stream Guy')).toBeInTheDocument()
    expect(screen.getByText(/not on a roster \(includes players on waivers\)/i)).toBeInTheDocument()
    expect(screen.getByText('Missed last 2 team games')).toBeInTheDocument()
    expect(screen.getByText(/Games this week \(incl\. played\) \/ next/)).toBeInTheDocument()
  })

  it('shows a reason sentence and no streaming table when streamingReason is set', async () => {
    show(
      data({
        streamingReason: 'NOT_DRAFTED',
        streaming: [],
        oneGameCredit: { code: 'ONE_GAME_CREDITED', share: 0.99, seasonMeasured: 2025 },
        risers: [row({ rostered: null })],
        fallers: [],
      }),
    )
    expect(await screen.findByText(/hasn’t drafted yet/)).toBeInTheDocument()
    expect(screen.queryByText(/Season avg/)).not.toBeInTheDocument()
    expect(screen.queryByText(/measured \+4%/)).not.toBeInTheDocument()
  })

  it('does not split into free agents and rostered when ownership is unknown', async () => {
    show(
      data({
        streamingReason: 'ROSTERS_NOT_LOADED',
        streaming: [],
        risers: [row({ rostered: null })],
        fallers: [row({ sleeperPlayerId: '3', name: 'Cy Big', rostered: null })],
      }),
    )
    expect(await screen.findByText(/isn’t known for this season/)).toBeInTheDocument()
    expect(screen.queryByText('Free agents')).not.toBeInTheDocument()
    expect(screen.queryByText('Rostered')).not.toBeInTheDocument()
  })

  it('splits free agents and rostered, with owners, when ownership is known', async () => {
    show(data({ fallers: [] }))
    // both lists are split, so each shows both groups (the empty fallers list says "None." per group)
    expect((await screen.findAllByText('Free agents')).length).toBe(2)
    expect(screen.getAllByText('Rostered').length).toBe(2)
    expect(screen.getByText('Casey')).toBeInTheDocument()
    expect(screen.getByText('+7 more')).toBeInTheDocument()
    expect(screen.getByText('+3 more')).toBeInTheDocument()
    expect(screen.getByText(/41 not shown: no game in the 14 days before Apr 12, 2026\. 86 not shown: no NBA team\./)).toBeInTheDocument()
    expect(screen.getAllByText(/Usage rate \(pooled over the window\)/).length).toBeGreaterThan(0)
  })

  it('shows points per minute on trend rows and when the rosters were fetched', async () => {
    show(data())
    await screen.findByText('Stream Guy')
    expect(screen.getAllByText('1.20 pts/min, season').length).toBeGreaterThan(0)
    expect(screen.getByText(/Rosters as of Oct 1[01], 2026\./)).toBeInTheDocument()
  })

  it('labels a completed season’s role lists as the end of that season', async () => {
    show(
      data({
        rolesFallback: false,
        rolesSeason: 2025,
        season: 2025,
        streamingReason: 'SEASON_COMPLETE',
        streaming: [],
        risers: [row({ rostered: null })],
        fallers: [],
      }),
    )
    expect(await screen.findByText(/end of the 2025 season/)).toBeInTheDocument()
  })

  it('shows the one-game note only when oneGameCredit is present', async () => {
    const { unmount } = show(data({ oneGameCredit: { code: 'ONE_GAME_CREDITED', share: 0.99, seasonMeasured: 2025 } }))
    expect(await screen.findByText(/99% of multi-game weeks in 2025/)).toBeInTheDocument()
    unmount()
    show(data())
    await screen.findByText('Stream Guy')
    expect(screen.queryByText(/multi-game weeks/)).not.toBeInTheDocument()
  })

  it('explains an unavailable page', async () => {
    show(data({ available: false, reason: 'NO_GAMES' }))
    expect(await screen.findByText(/No games have been played yet/)).toBeInTheDocument()
  })
})

/** Two seasons of one league: L0 is 2025, L1 (the route's id) is 2026. */
const lineageDrafts = [
  { sleeperLeagueId: 'L1', sleeperDraftId: 'd1', season: 2026, previousLeagueId: 'L0', leagueName: 'BK', sport: 'nba' },
  { sleeperLeagueId: 'L0', sleeperDraftId: 'd0', season: 2025, previousLeagueId: null, leagueName: 'BK', sport: 'nba' },
]

describe('PlayerTrends links follow the season each table was measured in', () => {
  beforeEach(() => getDrafts.mockResolvedValue(lineageDrafts))

  it('links fallback-season rows to the earlier season league id', async () => {
    show(data())      // season 2026, roles and streaming both fell back to 2025
    await screen.findByRole('link', { name: 'Stream Guy' })
    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'Stream Guy' })).toHaveAttribute('href', '/leagues/L0/players/9')
      expect(screen.getByRole('link', { name: 'Ann Guard' })).toHaveAttribute('href', '/leagues/L0/players/1')
      expect(screen.getByRole('link', { name: 'Cy Big' })).toHaveAttribute('href', '/leagues/L0/players/3')
    })
  })

  it('uses each table own season when only one fell back', async () => {
    show(data({ streamingSeason: 2026, streamingFallback: false }))
    await screen.findByRole('link', { name: 'Stream Guy' })
    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'Ann Guard' })).toHaveAttribute('href', '/leagues/L0/players/1')
    })
    expect(screen.getByRole('link', { name: 'Stream Guy' })).toHaveAttribute('href', '/leagues/L1/players/9')
  })
})
