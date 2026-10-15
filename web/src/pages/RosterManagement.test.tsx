import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RosterManagement from './RosterManagement'
import { tradeCsv } from '../lib/tradeCsv'
import { invalidateRailLeagues } from '../railLeague'
import type {
  RosterManagement as Data,
  RosterManagementTeam,
  LeagueTransactions,
  LeagueTrade,
  TradeAsset,
  TradePlayerAsset,
  TradeSide,
  TradeVerdict,
} from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
  // PlayerLink renders a router Link; this page's tests need only its href.
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))

const getRosterManagement = vi.fn()
const getLeagueTransactions = vi.fn()
const getDrafts = vi.fn()
vi.mock('../api', () => ({
  getDrafts: (...args: unknown[]) => getDrafts(...args),
  getRosterManagement: (...args: unknown[]) => getRosterManagement(...args),
  getLeagueTransactions: (...args: unknown[]) => getLeagueTransactions(...args),
}))

function transactions(over: Partial<LeagueTransactions> = {}): LeagueTransactions {
  return {
    available: true,
    season: 2026,
    sport: 'nfl',
    rankDirection: 'LOWER_IS_BETTER',
    seasonFinal: false,
    tradeAssetsLoaded: true,
    throughWeek: 1,
    reconciliation: null,
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
    username: null,
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

  /**
   * The season-fallback announcement. The rail links league pages at the newest
   * season; when that season has not been played the page answers about an
   * earlier one, and must say so rather than quietly show a different year.
   */
  it('says which season it fell back to when the one asked for is unplayed', async () => {
    getRosterManagement.mockResolvedValue(
      data({ season: 2025, requestedSeason: 2026, weeksScored: 21 }),
    )
    render(<RosterManagement />)

    expect(await screen.findByText(/has no scored weeks yet, so this is/)).toBeInTheDocument()
    expect(screen.getByText('2026')).toBeInTheDocument()
    expect(screen.getByText('2025')).toBeInTheDocument()
  })

  it('says nothing about seasons when it did not fall back', async () => {
    getRosterManagement.mockResolvedValue(data())
    render(<RosterManagement />)

    await screen.findByRole('row', { name: /Master Bates/ })
    expect(screen.queryByText(/has no scored weeks yet, so this is/)).not.toBeInTheDocument()
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

  // specs/022 T033: waiver adds, drops and trade receipts open the player page on basketball only.
  describe('player links', () => {
    const withTrade = (sport: 'nba' | 'nfl') =>
      transactions({
        sport,
        trades: [
          {
            txId: 'tx1',
            week: 2,
            acceptedAt: '2026-01-01T00:00:00Z',
            assetsLoaded: true,
            verdict: { state: 'NO_WEEK_YET', winnerRosterId: null, margin: null, weeksCounted: 0, final: false },
            sides: [
              {
                rosterId: 1,
                teamName: 'Master Bates',
                startedTotal: 0,
                benchTotal: 0,
                weeksCounted: 0,
                assets: [
                  { kind: 'PLAYER', playerId: '77', playerName: 'Trade One', position: 'C', status: 'NO_WEEK_YET',
                    firstWeek: null, lastWeek: null, startedPoints: 0, benchPoints: 0, weeksCounted: 0,
                    duplicateWeeks: [], postMovePositionalRank: null, stretches: [] },
                  { kind: 'PLAYER', playerId: '78', playerName: 'Trade Two', position: 'PF', status: 'NO_WEEK_YET',
                    firstWeek: null, lastWeek: null, startedPoints: 0, benchPoints: 0, weeksCounted: 0,
                    duplicateWeeks: [], postMovePositionalRank: null, stretches: [] },
                ],
              },
            ],
          },
        ],
      })

    it('links added, dropped and traded players on basketball', async () => {
      getRosterManagement.mockResolvedValue(data({ sport: 'nba' }))
      getLeagueTransactions.mockResolvedValue(withTrade('nba'))
      render(<RosterManagement />)

      expect(await screen.findByRole('link', { name: 'Tyler Loop' })).toHaveAttribute('href', '/leagues/L1/players/12711')
      expect(screen.getByRole('link', { name: 'Najee Harris' })).toHaveAttribute('href', '/leagues/L1/players/2')
      expect(screen.getByRole('link', { name: 'Trade One' })).toHaveAttribute('href', '/leagues/L1/players/77')
      expect(screen.getByRole('link', { name: 'Trade Two' })).toHaveAttribute('href', '/leagues/L1/players/78')
    })

    // Spec 035 F10: an ESPN player the matcher refused renders by name, as plain text, never as a link to a page that cannot exist.
    it('shows an unmatched ESPN player by name with no link, and still links the matched ones', async () => {
      getRosterManagement.mockResolvedValue(data({ sport: 'nba' }))
      getLeagueTransactions.mockResolvedValue(
        transactions({
          sport: 'nba',
          adds: [
            {
              week: 1,
              teamName: 'Team Bravo',
              type: 'WAIVER',
              status: 'complete',
              added: { playerId: 'it035-2', playerName: 'Matched Mike', position: 'C', postMovePositionalRank: 3, weeksCounted: 1 },
              dropped: {
                playerId: 'espn:9990006', playerName: 'Zed Ghost', position: 'PF', postMovePositionalRank: null, weeksCounted: 0,
                unmatched: true, team: 'ATL',
              },
              faabBid: 12,
            },
          ],
        }),
      )
      render(<RosterManagement />)

      expect(await screen.findByRole('link', { name: 'Matched Mike' })).toHaveAttribute('href', '/leagues/L1/players/it035-2')
      const ghost = screen.getByText('Zed Ghost')
      expect(ghost.closest('a')).toBeNull()
      expect(ghost).toHaveAttribute('title', expect.stringContaining('Not matched to a known player'))
      expect(ghost).toHaveAttribute('title', expect.stringContaining('ATL'))
      expect(screen.queryByText('Unknown player')).toBeNull()
    })

    it('links a fallback-season transaction to that season league id', async () => {
      invalidateRailLeagues()
      getDrafts.mockResolvedValue([
        { sleeperLeagueId: 'L1', sleeperDraftId: 'd1', season: 2026, previousLeagueId: 'L0', leagueName: 'BK', sport: 'nba' },
        { sleeperLeagueId: 'L0', sleeperDraftId: 'd0', season: 2025, previousLeagueId: null, leagueName: 'BK', sport: 'nba' },
      ])
      getRosterManagement.mockResolvedValue(data({ sport: 'nba' }))
      getLeagueTransactions.mockResolvedValue({ ...withTrade('nba'), season: 2025 })
      render(<RosterManagement />)

      await screen.findByRole('link', { name: 'Tyler Loop' })
      await waitFor(() =>
        expect(screen.getByRole('link', { name: 'Tyler Loop' })).toHaveAttribute('href', '/leagues/L0/players/12711'),
      )
      expect(screen.getByRole('link', { name: 'Trade One' })).toHaveAttribute('href', '/leagues/L0/players/77')
      invalidateRailLeagues()
    })

    it('leaves the same names unlinked on football', async () => {
      getRosterManagement.mockResolvedValue(data())
      getLeagueTransactions.mockResolvedValue(withTrade('nfl'))
      render(<RosterManagement />)

      expect(await screen.findByText('Tyler Loop')).toBeInTheDocument()
      // Only the in-page "Trades" jump link; no player links on football.
      expect(screen.getAllByRole('link').map((a) => a.textContent)).toEqual(['Trades'])
    })
  })

  it('still renders the standings when transactions have not been ingested', async () => {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockRejectedValue(new Error('404'))
    render(<RosterManagement />)

    expect(await screen.findByRole('row', { name: /Master Bates/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /league transactions/i })).not.toBeInTheDocument()
  })
})

describe('Roster management eyebrow names the season shown', () => {
  it('reads League · <season> from the payload', async () => {
    getRosterManagement.mockResolvedValue(data({ season: 2025 }))
    render(<RosterManagement />)
    expect(await screen.findByText('League · 2025')).toBeInTheDocument()
  })

  it('uses the resolved season, not the requested one, when the server fell back', async () => {
    getRosterManagement.mockResolvedValue(data({ season: 2025, requestedSeason: 2026 }))
    render(<RosterManagement />)
    expect(await screen.findByText('League · 2025')).toBeInTheDocument()
    expect(screen.queryByText('League · 2026')).not.toBeInTheDocument()
  })

  it('is plain League while there is no payload yet', () => {
    getRosterManagement.mockReturnValue(new Promise(() => {}))
    render(<RosterManagement />)
    expect(screen.getByText('League')).toBeInTheDocument()
  })

  describe('grades (spec 013 US8)', () => {
    it('shows the grade beside the efficiency, with the early badge when the payload says so', async () => {
      getRosterManagement.mockResolvedValue(data({ gradesEarly: true, teams: [team({ grade: 'B+' })] }))
      render(<RosterManagement />)
      const row = await screen.findByRole('row', { name: /Master Bates/ })
      expect(within(row).getByText('94.7%')).toBeInTheDocument()
      expect(within(row).getByText('B+')).toBeInTheDocument()
      // One early caveat on the Efficiency header, not one per row (spec 013 parent review).
      expect(screen.getAllByText('early — this is mostly noise')).toHaveLength(1)
      expect(screen.getByRole('columnheader', { name: /Efficiency/ })).toHaveTextContent('early — this is mostly noise')
    })

    it('shows no early badge once the payload says grades are settled', async () => {
      getRosterManagement.mockResolvedValue(data({ gradesEarly: false, teams: [team({ grade: 'C' })] }))
      render(<RosterManagement />)
      const row = await screen.findByRole('row', { name: /Master Bates/ })
      expect(within(row).getByText('C')).toBeInTheDocument()
      expect(within(row).queryByText(/early/)).toBeNull()
    })

    it('adds nothing when there is no grade', async () => {
      getRosterManagement.mockResolvedValue(data({ gradesEarly: true, teams: [team({ grade: null })] }))
      render(<RosterManagement />)
      const row = await screen.findByRole('row', { name: /Master Bates/ })
      expect(within(row).getByText('94.7%')).toBeInTheDocument()
      expect(within(row).queryByText(/early/)).toBeNull()
    })
  })
})

describe('League trades (spec 033)', () => {
  const player = (id: string, name: string, over: Partial<TradePlayerAsset> = {}): TradePlayerAsset => ({
    kind: 'PLAYER', playerId: id, playerName: name, position: 'PG', status: 'COUNTED',
    firstWeek: 1, lastWeek: 3, startedPoints: 10, benchPoints: 2, weeksCounted: 3,
    duplicateWeeks: [], postMovePositionalRank: null, stretches: [], ...over,
  })
  const side = (rosterId: number, teamName: string, startedTotal: number, benchTotal: number, assets: TradeAsset[]): TradeSide => ({
    rosterId, teamName, startedTotal, benchTotal, weeksCounted: 3, assets,
  })
  const verdict = (over: Partial<TradeVerdict>): TradeVerdict => ({
    state: 'WINNER', winnerRosterId: 1, margin: 12.34, weeksCounted: 3, final: false, ...over,
  })
  const trade = (over: Partial<LeagueTrade> = {}): LeagueTrade => ({
    txId: 't1',
    week: 4,
    acceptedAt: '2026-10-14T03:30:00Z',
    assetsLoaded: true,
    verdict: verdict({}),
    sides: [
      side(1, 'Alpha', 30, 4, [player('1', 'Aaa Player')]),
      side(2, 'Bravo', 17.66, 9, [player('2', 'Bbb Player')]),
    ],
    ...over,
  })

  async function renderWith(trades: LeagueTrade[], over: Partial<LeagueTransactions> = {}) {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockResolvedValue(transactions({ trades, ...over }))
    render(<RosterManagement />)
    await screen.findByRole('heading', { name: 'League trades' })
  }

  it('names the winner, the margin to one decimal and "so far" while the season is live', async () => {
    await renderWith([trade()])
    expect(screen.getByText('Alpha +12.3 started points over 3 weeks so far')).toBeInTheDocument()
  })

  it('drops "so far" once the season is final, and says "1 week" in the singular', async () => {
    await renderWith([trade({ verdict: verdict({ weeksCounted: 1, final: true }) })], { seasonFinal: true })
    expect(screen.getByText('Alpha +12.3 started points over 1 week')).toBeInTheDocument()
  })

  it('labels started and bench totals in words for each side', async () => {
    await renderWith([trade()])
    expect(screen.getByText('started 30.0 · bench 4.0')).toBeInTheDocument()
    expect(screen.getByText('started 17.7 · bench 9.0')).toBeInTheDocument()
  })

  it('says Even for a tie with started points', async () => {
    await renderWith([trade({ verdict: verdict({ state: 'EVEN', winnerRosterId: null, margin: 0 }) })])
    expect(screen.getByText('Even')).toBeInTheDocument()
  })

  it('says neither side started what it got when every started total is zero', async () => {
    await renderWith([
      trade({
        verdict: verdict({ state: 'EVEN', winnerRosterId: null, margin: 0 }),
        sides: [side(1, 'Alpha', 0, 5, [player('1', 'Aaa Player')]), side(2, 'Bravo', 0, 3, [player('2', 'Bbb Player')])],
      }),
    ])
    expect(screen.getByText('Even: neither side started what it got')).toBeInTheDocument()
    expect(screen.queryByText(/^Even$/)).not.toBeInTheDocument()
  })

  it.each([
    ['NO_WEEK_YET', 'No week played yet'],
    ['UNDONE', 'Undone before a scored week'],
    ['NONE_COUNTED', 'Neither side got a counted week from this trade'],
    ['NOT_COMPARABLE', 'Not comparable by points'],
  ] as const)('words %s as "%s"', async (state, text) => {
    await renderWith([trade({ verdict: verdict({ state, winnerRosterId: null, margin: null, weeksCounted: 0 }) })])
    expect(screen.getByText(text)).toBeInTheDocument()
  })

  it('never renders 0-0 in any form', async () => {
    await renderWith([
      trade({ verdict: verdict({ state: 'NO_WEEK_YET', winnerRosterId: null, margin: null, weeksCounted: 0 }) }),
      trade({
        txId: 't2',
        verdict: verdict({ state: 'EVEN', winnerRosterId: null, margin: 0 }),
        sides: [side(1, 'Alpha', 0, 0, []), side(2, 'Bravo', 0, 0, [])],
      }),
    ])
    expect(document.body.textContent).not.toMatch(/0\s*[\u2013-]\s*0/)
  })

  it('shows the accepted date as a US Eastern date instead of "Week N"', async () => {
    // 03:30 UTC on Oct 14 is still Oct 13 in New York.
    await renderWith([trade()])
    expect(screen.getByText('Oct 13, 2026')).toBeInTheDocument()
    expect(screen.queryByText(/^Week 4$/)).not.toBeInTheDocument()
  })

  it('shows a status label for a player who did not count, and points for one who did', async () => {
    await renderWith([
      trade({
        sides: [
          side(1, 'Alpha', 10, 0, [
            player('1', 'Aaa Player'),
            player('3', 'Ccc Player', { status: 'LEFT_BEFORE_PLAYING', startedPoints: 0 }),
          ]),
          side(2, 'Bravo', 0, 0, [player('2', 'Bbb Player', { status: 'NEVER_PLAYED_FOR_THEM', startedPoints: 0 })]),
        ],
      }),
    ])
    expect(screen.getByText('10.0 started')).toBeInTheDocument()
    expect(screen.getByText('left before playing')).toBeInTheDocument()
    expect(screen.getByText('never played for them')).toBeInTheDocument()
  })

  it('renders a FAAB-only trade', async () => {
    await renderWith([
      trade({
        verdict: verdict({ state: 'NOT_COMPARABLE', winnerRosterId: null, margin: null, weeksCounted: 0 }),
        sides: [
          side(1, 'Alpha', 0, 0, [{ kind: 'FAAB', amount: 25, fromRosterId: 2, fromTeamName: 'Bravo' }]),
          side(2, 'Bravo', 0, 0, [{ kind: 'FAAB', amount: 5, fromRosterId: 1, fromTeamName: 'Alpha' }]),
        ],
      }),
    ])
    expect(screen.getByText('$25 FAAB from Bravo')).toBeInTheDocument()
    expect(screen.getByText('$5 FAAB from Alpha')).toBeInTheDocument()
  })

  it('renders a picks-only trade: resolved, undrafted and unmatched', async () => {
    await renderWith([
      trade({
        verdict: verdict({ state: 'NOT_COMPARABLE', winnerRosterId: null, margin: null, weeksCounted: 0 }),
        sides: [
          side(1, 'Alpha', 0, 0, [
            { kind: 'PICK', season: 2026, round: 1, originalRosterId: 2, status: 'COUNTED', pickNo: 3, player: player('9', 'Drafted Guy') },
            { kind: 'PICK', season: 2027, round: 2, originalRosterId: 2, status: 'NOT_DRAFTED_YET', pickNo: null, player: null },
          ]),
          side(2, 'Bravo', 0, 0, [
            { kind: 'PICK', season: 2026, round: 3, originalRosterId: 7, status: 'PICK_NOT_MATCHED', pickNo: null, player: null },
          ]),
        ],
      }),
    ])
    expect(screen.getByText('2026 R1 (from Bravo)')).toBeInTheDocument()
    expect(screen.getAllByText('Drafted Guy').length).toBeGreaterThan(0)
    expect(screen.getByText('2027 R2 (from Bravo)')).toBeInTheDocument()
    expect(screen.getByText('not drafted yet')).toBeInTheDocument()
    expect(screen.getByText('2026 R3 (from Roster 7)')).toBeInTheDocument()
    expect(screen.getByText('pick not matched')).toBeInTheDocument()
  })

  it('always carries the realized-points-only note, and the not-loaded note only when assets are missing', async () => {
    await renderWith([trade()])
    expect(screen.getByText('Realized started points only; no projections or trade values.')).toBeInTheDocument()
    expect(screen.queryByText('Picks and FAAB not loaded for this season yet')).not.toBeInTheDocument()
  })

  it('says picks and FAAB are not loaded when the payload says so', async () => {
    await renderWith([trade({ assetsLoaded: false })], { tradeAssetsLoaded: false })
    expect(screen.getByText('Picks and FAAB not loaded for this season yet')).toBeInTheDocument()
  })

  it('gives the page a Trades jump link to the section', async () => {
    await renderWith([trade()])
    expect(screen.getByRole('link', { name: 'Trades' })).toHaveAttribute('href', '#trades')
    expect(document.getElementById('trades')).not.toBeNull()
  })

  const stretchTrade = () =>
    trade({
      sides: [
        side(1, 'Alpha', 30, 4, [
          player('1', 'Aaa Player', {
            firstWeek: 7,
            stretches: [
              { rosterId: 2, teamName: 'Bravo', firstWeek: 1, lastWeek: 5, how: 'DRAFT', viaTxId: null,
                startedPoints: 40, benchPoints: 1, duplicateWeeks: [], leadingUnstarted: false },
              { rosterId: 1, teamName: 'Alpha', firstWeek: 6, lastWeek: 16, how: 'TRADE', viaTxId: 't1',
                startedPoints: 30, benchPoints: 4, duplicateWeeks: [7], leadingUnstarted: true },
              { rosterId: 3, teamName: 'Charlie', firstWeek: 17, lastWeek: 17, how: 'FREE_AGENT', viaTxId: 'w',
                startedPoints: 2, benchPoints: 0, duplicateWeeks: [], leadingUnstarted: false },
            ],
          }),
        ]),
        side(2, 'Bravo', 17.66, 9, [player('2', 'Bbb Player')]),
      ],
    })

  it('lists each stretch with team, weeks, how it began, and labelled points', async () => {
    await renderWith([stretchTrade()])
    expect(screen.getByText('How this was counted')).toBeInTheDocument()
    expect(screen.getByText('wk 6–16')).toBeInTheDocument()
    expect(screen.getByText('wk 17')).toBeInTheDocument()
    expect(screen.getByText('drafted')).toBeInTheDocument()
    expect(screen.getByText('trade')).toBeInTheDocument()
    expect(screen.getByText('free agent')).toBeInTheDocument()
    expect(screen.getByText('started 30.0 · bench 4.0', { selector: '.tx-stretch span' })).toBeInTheDocument()
  })

  it('marks duplicate weeks and the unstarted boundary week, and highlights the receiving side', async () => {
    await renderWith([stretchTrade()])
    expect(screen.getByText('also listed on another roster in wk 7')).toBeInTheDocument()
    expect(screen.getByText('listed before the trade took effect, not counted')).toBeInTheDocument()
    const recv = document.querySelectorAll('.tx-stretch-recv')
    expect(recv).toHaveLength(1)
    expect(recv[0].textContent).toContain('Alpha')
  })

  it('labels the leading-unstarted flag only on the receiving side stretch', async () => {
    const st = (rosterId: number, teamName: string, firstWeek: number, lastWeek: number) => ({
      rosterId, teamName, firstWeek, lastWeek, how: 'WAIVER' as const, viaTxId: null,
      startedPoints: 1, benchPoints: 0, duplicateWeeks: [], leadingUnstarted: true,
    })
    await renderWith([
      trade({
        sides: [
          // firstWeek is 8: only the Alpha stretch containing it
          // is this trade's; the earlier Alpha stint and Charlie's stint are not.
          side(1, 'Alpha', 1, 0, [
            player('1', 'Aaa Player', {
              firstWeek: 8,
              stretches: [st(3, 'Charlie', 1, 3), st(1, 'Alpha', 4, 6), st(1, 'Alpha', 7, 12)],
            }),
          ]),
          side(2, 'Bravo', 0, 0, []),
        ],
      }),
    ])
    expect(screen.getAllByText('listed before the trade took effect, not counted')).toHaveLength(1)
    const recv = document.querySelectorAll('.tx-stretch-recv')
    expect(recv).toHaveLength(1)
    expect(recv[0].textContent).toContain('wk 7–12')
    expect(recv[0].textContent).toContain('listed before')
  })

  it('words a RESTORED verdict and an ALREADY_HELD chip, and passes the status to the CSV', async () => {
    const t = trade({
      verdict: verdict({ state: 'RESTORED', winnerRosterId: null, margin: null, weeksCounted: 0 }),
      sides: [
        side(1, 'Alpha', 0, 0, [player('1', 'Aaa Player', { status: 'ALREADY_HELD', startedPoints: 0 })]),
        side(2, 'Bravo', 0, 0, []),
      ],
    })
    await renderWith([t])
    expect(
      screen.getByText('Restored an earlier trade before a scored week; no player changed hands'),
    ).toBeInTheDocument()
    expect(screen.getByText('already theirs')).toBeInTheDocument()
    expect(tradeCsv(transactions({ trades: [t] }))).toContain(',ALREADY_HELD,')
  })

  it('explains the verdict rule and states reconciliation counts as plain sentences', async () => {
    await renderWith([trade()], {
      reconciliation: {
        playerWeeksChecked: 1200, unexplainedStretches: 3, sameWeekInAndOut: 4, neverOnAScoredRoster: 2,
        examples: ['Added Foo in wk 3 and moved on before a scored week'],
      },
    })
    expect(document.body.textContent).toContain('after the trade took effect')
    expect(document.body.textContent).toContain("1200 player-weeks checked against the league's transactions.")
    expect(document.body.textContent).toContain('3 roster changes had no recorded move.')
    expect(document.body.textContent).toContain('4 pickups were added and dropped within the same week.')
    expect(document.body.textContent).toContain('2 adds never appeared on a scored roster:')
    expect(screen.getByText('Added Foo in wk 3 and moved on before a scored week')).toBeInTheDocument()
  })

  it('omits the reconciliation sentences when the payload has none', async () => {
    await renderWith([trade()], { reconciliation: null })
    expect(document.body.textContent).not.toContain('player-weeks checked')
  })

  it('downloads trades-{sport}-{season}.csv through a Blob, and hides the button with no trades', async () => {
    const create = vi.fn(() => 'blob:x')
    const revoke = vi.fn()
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    let name = ''
    const orig = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string, o?: ElementCreationOptions) => {
      const el = orig(tag, o)
      if (tag === 'a') Object.defineProperty(el, 'download', { set: (v: string) => { name = v }, get: () => name })
      return el
    })
    await renderWith([trade()], { sport: 'nba', season: 2025 })
    screen.getByRole('button', { name: 'Download CSV' }).click()
    expect(create).toHaveBeenCalledTimes(1)
    expect((create.mock.calls[0] as unknown as [Blob])[0]).toBeInstanceOf(Blob)
    expect(click).toHaveBeenCalled()
    expect(name).toBe('trades-nba-2025.csv')
    expect(revoke).toHaveBeenCalledWith('blob:x')
    vi.restoreAllMocks()
  })

  it('has no Download CSV button when there are no trades', async () => {
    getRosterManagement.mockResolvedValue(data())
    getLeagueTransactions.mockResolvedValue(transactions({ trades: [] }))
    render(<RosterManagement />)
    await screen.findByText('No trades have been made.')
    expect(screen.queryByRole('button', { name: 'Download CSV' })).not.toBeInTheDocument()
  })
})
