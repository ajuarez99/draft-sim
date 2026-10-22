import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PlayerPage from './PlayerPage'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import type {
  LockInReport,
  Pct,
  PlayerCounting,
  PlayerGameLogRow,
  PlayerOwnership,
  PlayerStatsPage,
  PlayerWindow,
  Rate,
} from '../api'

const getPlayerStats = vi.fn()
const fetchLockIns = vi.fn()
vi.mock('../api', () => ({
  getPlayerStats: (...args: unknown[]) => getPlayerStats(...args),
  fetchLockIns: (...args: unknown[]) => fetchLockIns(...args),
}))

/** What the server answers for a league that is not a lock-in league: the tab is simply not there. */
const NOT_LOCK_IN = { status: 'GAME_MODE', reason: 'not lock-in mode' } as unknown as LockInReport

const zero: PlayerCounting = {
  pts: 0, reb: 0, oreb: 0, dreb: 0, ast: 0, stl: 0, blk: 0, tov: 0, pf: 0, fgm: 0, fga: 0, tpm: 0, tpa: 0, ftm: 0, fta: 0,
}
const perGame: PlayerCounting = { ...zero, pts: 10.9, reb: 9.4, ast: 1.2, stl: 0.6, blk: 2.1, tov: 1.5, fgm: 4, fga: 6, ftm: 2.9, fta: 5 }
const rate = (value: number | null, reason: Rate['reason'] = null): Rate => ({ value, reason })

function win(over: Partial<PlayerWindow> = {}): PlayerWindow {
  return {
    games: 76,
    firstGameDate: '2025-10-22',
    lastGameDate: '2026-04-12',
    minutes: 2100,
    minutesPerGame: 27.6,
    perGame,
    totals: { ...perGame, pts: 828 },
    per36: { ...zero, pts: 14.2, reb: 12.3, ast: 1.6 },
    shooting: { fgPct: rate(66.4), tpPct: rate(null, 'NO_ATTEMPTS'), ftPct: rate(58.1) },
    gameScorePerGame: 14.8,
    plusMinusPerGame: 2.4,
    smallSample: false,
    advanced: {
      ts: rate(66.4), efg: rate(66.4), ftr: rate(83.3), tpar: rate(0), usg: rate(13), minutesShare: rate(57.5),
      astPct: rate(7.3), orbPct: rate(12), drbPct: rate(28), trbPct: rate(20), stlPct: rate(0.9), blkPct: rate(4.8),
      tovPct: rate(18),
    },
    ...over,
  }
}

function owner(over: Partial<PlayerOwnership> = {}): PlayerOwnership {
  return {
    state: 'ROSTERED', rosterId: 4, ownerName: 'Dunk Tank', avatarId: null, isMe: false,
    asOf: { kind: 'WEEK', fetchedAt: null, week: 18 }, ...over,
  }
}

function game(over: Partial<PlayerGameLogRow> = {}): PlayerGameLogRow {
  return {
    gameId: 'g1', date: '2026-04-12', week: 25, team: 'MIN', opponent: 'DEN', isHome: true, minutes: 31,
    line: { ...zero, pts: 12, reb: 11, ast: 2, stl: 1, blk: 3, tov: 2, fgm: 5, fga: 7, ftm: 2, fta: 4 },
    plusMinus: 8, gameScore: 17.1, fantasyPoints: 31.5, ...over,
  }
}

const unranked = (reason: 'NOT_QUALIFIED' | 'NOT_QUALIFIED_STALE') => ({
  leagueRank: null, positionRank: null, pointsRank: null, rankMove: null,
  groupSize: 299, positionGroupSize: null, position: null, reason,
})

function page(over: Partial<PlayerStatsPage> = {}): PlayerStatsPage {
  return {
    sport: 'nba',
    season: 2025,
    requestedSeason: null,
    available: true,
    reason: null,
    dataAsOf: '2026-04-13T03:00:00Z',
    seasons: [
      { season: 2025, sleeperLeagueId: 'L25', hasGames: true },
      { season: 2024, sleeperLeagueId: 'L24', hasGames: true },
    ],
    currentOwnership: null,
    player: { sleeperPlayerId: '1350', name: 'Rudy Gobert', positions: ['C'], team: 'MIN', known: true },
    teamsThisSeason: ['MIN'],
    teamGamesMissed: 0,
    ownership: owner(),
    windows: { SEASON: win(), LAST_10: win({ games: 10 }), LAST_5: win({ games: 5, smallSample: true }) },
    percentiles: {},
    fantasy: {
      fpPerGame: { SEASON: 22.56, LAST_10: 24.1, LAST_5: 25 },
      ranks: { leagueRank: 40, positionRank: 12, pointsRank: 152, rankMove: 112, groupSize: 299, positionGroupSize: 60, position: 'C', reason: null },
      breakdown: [
        { key: 'reb', points: 872, share: 0.5 },
        { key: 'pts', points: 415, share: 0.24 },
        { key: 'bonus_pt_40p', points: 3, share: null },
        { key: 'to', points: -120, share: -0.07 },
      ],
      seasonTotal: 1714,
    },
    gameLog: [game(), game({ gameId: 'g0', date: '2026-04-10', team: 'LAL', opponent: 'PHX', isHome: false, fantasyPoints: 20 })],
    qualification: { minGamesShare: 0.5, minGames: 42, maxTeamGames: 83, minMinutesPerGame: 15, recencyDays: 14 },
    status: null,
    nextGame: null,
    nextGameReason: 'NO_GAMES_LEFT',
    upcoming: [],
    scheduleAsOf: '2026-10-20T09:00:00Z',
    ...over,
  }
}

/**
 * specs/042 T012: the body is in tabs now (`?tab=`), so a test about the season line or the advanced table opens
 * the Stats tab first and one about the game log opens Game log. Declared at the top of the test; nothing else
 * about those tests changed.
 */
let startTab: string | null = null
const onTab = (t: 'overview' | 'stats' | 'log' | 'schedule' | 'lock-ins') => {
  startTab = t
}

function mount(base = '/leagues/L25/players/1350') {
  const url = startTab ? `${base}${base.includes('?') ? '&' : '?'}tab=${startTab}` : base
  return render(
    <MemoryRouter initialEntries={[url]}>
      <LeagueDataVersionProvider>
        <Routes>
          <Route path="/leagues/:sleeperLeagueId/players/:sleeperPlayerId" element={<PlayerPage />} />
        </Routes>
      </LeagueDataVersionProvider>
    </MemoryRouter>,
  )
}

function show(d: PlayerStatsPage, url?: string) {
  getPlayerStats.mockResolvedValue(d)
  return mount(url)
}

beforeEach(() => {
  getPlayerStats.mockReset()
  fetchLockIns.mockReset()
  fetchLockIns.mockResolvedValue(NOT_LOCK_IN)
  startTab = null
})

describe('PlayerPage', () => {
  it('asks for the player in the league named by the URL', async () => {
    onTab('stats')
    show(page())
    expect(await screen.findByRole('region', { name: 'Season line' })).toBeInTheDocument()
    expect(getPlayerStats).toHaveBeenCalledWith('L25', '1350')
  })

  it('labels real and fantasy figures apart, and shows rank, move and group sizes', async () => {
    onTab('stats')
    show(page())
    const line = await screen.findByRole('region', { name: 'Season line' })
    expect(within(line).getByText('Real stat')).toBeInTheDocument()
    expect(within(line).getAllByText('This league’s fantasy').length).toBeGreaterThan(0)
    expect(within(line).getByText('22.56')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))   // the ranks card is on the Overview tab
    const ranks = screen.getByRole('region', { name: 'Ranks' })
    expect(within(ranks).getByText('#40')).toBeInTheDocument()
    expect(within(ranks).getByText('of 299')).toBeInTheDocument()
    expect(within(ranks).getByText('#12')).toBeInTheDocument()
    expect(within(ranks).getByText('of 60')).toBeInTheDocument()
    expect(within(ranks).getByText('▲ 112')).toBeInTheDocument()
    expect(
      within(ranks).getByText('By real points per game he’s #152 of 299; in this league’s scoring he’s #40 — 112 places higher.'),
    ).toBeInTheDocument()
  })

  it('shows a traded player with the team of each night, newest first, and his teams', async () => {
    onTab('log')
    show(page({ teamsThisSeason: ['LAL', 'MIN'], teamGamesMissed: 6 }))
    const log = await screen.findByRole('region', { name: 'Game log' })
    const rows = within(log).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByText('Apr 12')).toBeInTheDocument()
    expect(within(rows[0]).getByText('MIN')).toBeInTheDocument()
    expect(within(rows[0]).getByText('vs DEN')).toBeInTheDocument()
    expect(within(rows[1]).getByText('LAL')).toBeInTheDocument()
    expect(within(rows[1]).getByText('@ PHX')).toBeInTheDocument()
    expect(screen.getByText('Played for LAL, MIN in 2025–26.')).toBeInTheDocument()
    expect(screen.getByText('Missed 6 of his team’s games in 2025–26.')).toBeInTheDocument()
  })

  it('shows the breakdown with a negative category and a dash for a null share', async () => {
    show(page())
    const card = await screen.findByRole('region', { name: 'Scoring breakdown' })
    const items = within(card).getAllByRole('listitem')
    expect(items).toHaveLength(4)
    expect(items[0]).toHaveTextContent('Rebounds')
    expect(items[0]).toHaveTextContent('+872.0')
    expect(items[0]).toHaveTextContent('50.0%')
    expect(items[2]).toHaveTextContent('40-point game bonus')
    expect(items[2]).toHaveTextContent('—')
    expect(items[3]).toHaveTextContent('Turnovers')
    expect(items[3]).toHaveTextContent('−120.0')
    expect(items[3]).toHaveTextContent('−7.0%')
    expect(items[3]).not.toHaveTextContent('-')
  })

  it('says why a percentage is a dash, once, under the season line', async () => {
    onTab('stats')
    show(page())
    const line = await screen.findByRole('region', { name: 'Season line' })
    expect(within(line).getAllByText(/No attempts in these games, so there is no percentage\./)).toHaveLength(1)
  })

  it('shows makes and attempts beneath each shooting percentage', async () => {
    onTab('stats')
    const totals: PlayerCounting = { ...zero, fgm: 348, fga: 510, ftm: 120.0, fta: 206 }
    show(page({ windows: { SEASON: win({ totals }) } }))
    const line = await screen.findByRole('region', { name: 'Season line' })
    const row = within(line).getByRole('row', { name: /^Season/ })
    expect(within(row).getByText('66.4%')).toBeInTheDocument()
    expect(within(row).getByText('348-510')).toBeInTheDocument()
    expect(within(row).getByText('120-206')).toBeInTheDocument()
  })

  it('states NO_ATTEMPTS as words with the sentence as title, never 0 or a bare dash', async () => {
    onTab('stats')
    const totals: PlayerCounting = { ...zero, fgm: 348, fga: 510, ftm: 120, fta: 206 }
    show(page({ windows: { SEASON: win({ totals }) } }))
    const line = await screen.findByRole('region', { name: 'Season line' })
    const row = within(line).getByRole('row', { name: /^Season/ })
    const cell = within(row).getByText('no att.')
    expect(cell).toHaveAttribute('title', 'No attempts in these games, so there is no percentage.')
    expect(cell).toHaveAttribute('aria-label', 'No attempts in these games, so there is no percentage.')
    expect(within(row).queryByText('0.0%')).not.toBeInTheDocument()
    expect(within(row).queryByText('0-0')).not.toBeInTheDocument()
  })

  it('words a rank that fell with the numbers', async () => {
    show(
      page({
        fantasy: {
          ...page().fantasy!,
          ranks: { leagueRank: 117, positionRank: 12, pointsRank: 40, rankMove: -77, groupSize: 299, positionGroupSize: 60, position: 'C', reason: null },
        },
      }),
    )
    expect(
      await screen.findByText('By real points per game he’s #40 of 299; in this league’s scoring he’s #117 — 77 places lower.'),
    ).toBeInTheDocument()
    expect(screen.getByText('▼ 77')).toBeInTheDocument()
  })

  it('says the same in both when the move is zero', async () => {
    show(
      page({
        fantasy: {
          ...page().fantasy!,
          ranks: { leagueRank: 40, positionRank: 12, pointsRank: 40, rankMove: 0, groupSize: 299, positionGroupSize: 60, position: 'C', reason: null },
        },
      }),
    )
    expect(
      await screen.findByText('By real points per game he’s #40 of 299; in this league’s scoring he’s #40 — the same in both.'),
    ).toBeInTheDocument()
  })

  it('says the data was refreshed, not that stats run through that date', async () => {
    show(page())
    expect(await screen.findByText(/^Data refreshed Apr \d+, 2026\.$/)).toBeInTheDocument()
    expect(screen.queryByText(/Stats through/)).not.toBeInTheDocument()
  })

  it('writes game-log minutes with one decimal', async () => {
    onTab('log')
    show(page({ gameLog: [game({ minutes: 34 }), game({ gameId: 'g0', minutes: 28.5 })] }))
    const log = await screen.findByRole('region', { name: 'Game log' })
    expect(within(log).getByText('34.0')).toBeInTheDocument()
    expect(within(log).getByText('28.5')).toBeInTheDocument()
  })

  it('gives a player with no games this season a sentence, not an empty table or zeros', async () => {
    show(
      page({
        reason: 'NO_PLAYER_GAMES',
        teamsThisSeason: [],
        windows: {
          SEASON: win({
            games: 0, firstGameDate: null, lastGameDate: null, perGame: null, per36: null, minutes: 0,
            minutesPerGame: 0, gameScorePerGame: null, plusMinusPerGame: null, totals: zero,
          }),
        },
        fantasy: {
          fpPerGame: { SEASON: null, LAST_10: null, LAST_5: null },
          ranks: unranked('NOT_QUALIFIED'),
          breakdown: [],
          seasonTotal: 0,
        },
        gameLog: [],
      }),
    )
    expect(await screen.findByText('He hasn’t played a game this season.')).toBeInTheDocument()
    expect(screen.getByText('Rudy Gobert', { selector: 'h2' })).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Ranks' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Game log' })).not.toBeInTheDocument()
  })

  it('explains a fallback season, labels both owners apart, and lists a season with no games', async () => {
    show(
      page({
        requestedSeason: 2026,
        seasons: [
          { season: 2026, sleeperLeagueId: 'L26', hasGames: false },
          { season: 2025, sleeperLeagueId: 'L25', hasGames: true },
        ],
        currentOwnership: { state: 'NOT_DRAFTED', rosterId: null, ownerName: null, avatarId: null, isMe: false, asOf: null },
      }),
      '/leagues/L26/players/1350',
    )
    expect(
      await screen.findByText('2026–27 has no games yet; showing 2025–26. Trends may show a different season.'),
    ).toBeInTheDocument()

    const picker = screen.getByRole('navigation', { name: 'Season' })
    const links = within(picker).getAllByRole('link')
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/leagues/L26/players/1350', '/leagues/L25/players/1350'])
    expect(within(links[0]).getByText('no games yet')).toBeInTheDocument()
    expect(links[0]).toHaveAttribute('aria-current', 'page')

    expect(screen.getByText('End of 2025–26 regular season (week 18)')).toBeInTheDocument()
    expect(screen.getByText('Dunk Tank')).toBeInTheDocument()
    expect(screen.getByText('Now (2026–27)')).toBeInTheDocument()
    expect(screen.getByText('This league hasn’t drafted yet, so nobody owns him.')).toBeInTheDocument()
  })

  it('marks the owner who is you', async () => {
    show(page({ ownership: owner({ isMe: true }) }))
    expect(await screen.findByText('Yours')).toBeInTheDocument()
  })

  it('says a player is not ranked rather than showing rank numbers', async () => {
    show(
      page({
        fantasy: { fpPerGame: { SEASON: 5, LAST_10: 5, LAST_5: 5 }, ranks: unranked('NOT_QUALIFIED'), breakdown: [], seasonTotal: 50 },
      }),
    )
    const ranks = await screen.findByRole('region', { name: 'Ranks' })
    expect(within(ranks).getByText('Hasn’t played enough games or minutes to be ranked.')).toBeInTheDocument()
    expect(within(ranks).queryByText(/#/)).not.toBeInTheDocument()
  })

  // NOT_QUALIFIED_STALE is not on the wire yet (US1); the copy is ready for when it is.
  it('still says something for a stale player', async () => {
    show(
      page({
        fantasy: { fpPerGame: { SEASON: 5, LAST_10: null, LAST_5: null }, ranks: unranked('NOT_QUALIFIED_STALE'), breakdown: [], seasonTotal: 50 },
      }),
    )
    const ranks = await screen.findByRole('region', { name: 'Ranks' })
    expect(within(ranks).getByText(/Hasn’t played recently/)).toBeInTheDocument()
  })

  it('answers a football league with a sentence and no stats', async () => {
    show(
      page({
        sport: 'nfl',
        available: false,
        reason: 'NOT_BASKETBALL',
        seasons: [],
        windows: {},
        fantasy: null,
        ownership: null,
        gameLog: [],
        player: { sleeperPlayerId: '1350', name: null, positions: [], team: null, known: false },
      }),
    )
    expect(await screen.findByText('Player pages are for basketball leagues.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Season' })).not.toBeInTheDocument()
  })

  it('says it could not load when the request fails', async () => {
    getPlayerStats.mockRejectedValue(new Error('boom'))
    mount()
    expect(await screen.findByText('Couldn’t load this player.')).toBeInTheDocument()
  })
})

const KEYS = ['ts', 'efg', 'ftr', 'tpar', 'usg', 'minutesShare', 'astPct', 'orbPct', 'drbPct', 'trbPct', 'stlPct', 'blkPct', 'tovPct'] as const
const pctPair = (nba: Partial<Pct>, rostered: Partial<Pct>): Pct[] => [
  { value: 50, group: 'NBA_POSITION', n: 59, reason: null, ...nba },
  { value: 50, group: 'LEAGUE_ROSTERED', n: 120, reason: null, ...rostered },
]
/** Percentiles for every window and key, with per-key overrides applied to all three windows. */
function percentiles(over: Partial<Record<(typeof KEYS)[number], Pct[]>> = {}): PlayerStatsPage['percentiles'] {
  const all = Object.fromEntries(KEYS.map((k) => [k, over[k] ?? pctPair({}, {})]))
  return { SEASON: all, LAST_10: all, LAST_5: all }
}

describe('PlayerPage advanced view', () => {
  it('shows season, last 10 and last 5 side by side with real counts and date spans', async () => {
    onTab('stats')
    show(
      page({
        windows: {
          SEASON: win(),
          LAST_10: win({ games: 10, firstGameDate: '2026-03-20', lastGameDate: '2026-04-07' }),
          LAST_5: win({ games: 3, firstGameDate: '2026-04-01', lastGameDate: '2026-04-07' }),
        },
        percentiles: percentiles(),
      }),
    )
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    const heads = within(adv).getAllByRole('columnheader')
    expect(heads).toHaveLength(4)
    expect(heads[1]).toHaveTextContent('Season76 games · Oct 22–Apr 12')
    expect(heads[2]).toHaveTextContent('Last 1010 games · Mar 20–Apr 7')
    expect(heads[3]).toHaveTextContent('Last 53 games · Apr 1–Apr 7')
    expect(heads[3]).toHaveTextContent('only 3 played, fewer than 5')
    expect(heads[2]).not.toHaveTextContent('fewer than')
    expect(within(adv).getAllByText('66.4%').length).toBeGreaterThan(0)
  })

  it('shows a zero-attempt rate as a short label with the sentence, never 0%', async () => {
    onTab('stats')
    const advanced = { ...win().advanced, ts: rate(null, 'NO_ATTEMPTS'), efg: rate(null, 'NO_ATTEMPTS'), tpar: rate(0) }
    show(page({ windows: { SEASON: win({ advanced }) }, percentiles: percentiles() }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    const row = within(adv).getByRole('row', { name: /True shooting %/ })
    const none = within(row).getByText('no att.')
    expect(none).toHaveAttribute('title', 'No attempts in these games, so there is no percentage.')
    expect(within(row).queryByText('0.0%')).not.toBeInTheDocument()
    expect(within(row).queryByText(/percentile/)).not.toBeInTheDocument()
    // A real zero is still a zero.
    expect(within(within(adv).getByRole('row', { name: /3-point attempt rate/ })).getByText('0.0%')).toBeInTheDocument()
  })

  it('labels a small sample', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles() }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    expect(within(adv).getAllByText('small sample')).toHaveLength(1)
  })

  it('says he has not played since his last game when the percentile is stale', async () => {
    onTab('stats')
    const stale: Pct[] = pctPair({ value: null, reason: 'NOT_QUALIFIED_STALE' }, { value: null, reason: 'NOT_QUALIFIED_STALE' })
    show(
      page({
        windows: { SEASON: win({ lastGameDate: '2026-03-04' }), LAST_10: win({ games: 10 }) },
        percentiles: { SEASON: Object.fromEntries(KEYS.map((k) => [k, stale])), LAST_10: percentiles().LAST_10 },
      }),
    )
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    // Written out under the table once, naming the window, not only on hover.
    expect(within(adv).getAllByText('Season: not ranked — Hasn’t played since Mar 4, 2026, so he isn’t ranked.')).toHaveLength(1)
    expect(within(adv).getAllByText('not ranked').length).toBeGreaterThan(0)
  })

  it('compares a free agent with rostered players and shows his value beside the percentile', async () => {
    onTab('stats')
    show(
      page({
        ownership: { state: 'FREE_AGENT', rosterId: null, ownerName: null, avatarId: null, isMe: false, asOf: null },
        percentiles: percentiles({ ts: pctPair({ value: 71.4, n: 59 }, { value: 40.2, n: 120 }) }),
      }),
    )
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    const row = () => within(adv).getByRole('row', { name: /True shooting %/ })
    expect(within(row()).getAllByText('66.4%')).toHaveLength(3)
    expect(within(row()).getAllByText('71st percentile')).toHaveLength(3)
    expect(within(row()).getAllByText(/^59 others/)).toHaveLength(3)

    fireEvent.click(within(adv).getByRole('button', { name: 'vs players rostered in this league' }))
    expect(within(row()).getAllByText('66.4%')).toHaveLength(3)
    expect(within(row()).getAllByText('40th percentile')).toHaveLength(3)
    expect(within(row()).getAllByText(/^120 others/)).toHaveLength(3)
    expect(within(row()).queryByText(/^59 others/)).not.toBeInTheDocument()
  })

  it('names the position group from his first position', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles() }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    expect(within(adv).getByRole('button', { name: 'vs Cs across the NBA' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(adv).getByRole('button', { name: 'vs players rostered in this league' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('states OWNERSHIP_UNAVAILABLE on the rostered group instead of a number', async () => {
    onTab('stats')
    show(
      page({
        percentiles: percentiles({
          ts: pctPair({}, { value: null, n: 0, reason: 'OWNERSHIP_UNAVAILABLE' }),
        }),
      }),
    )
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    fireEvent.click(within(adv).getByRole('button', { name: 'vs players rostered in this league' }))
    const row = within(adv).getByRole('row', { name: /True shooting %/ })
    const none = within(row).getAllByText('rosters unavailable')[0]
    expect(none).toHaveAttribute('title', expect.stringContaining('isn’t available'))
    expect(within(row).getAllByText('66.4%')).toHaveLength(3)
  })

  it('has a definition for all 13 stats, the pooling note, and says turnover % is lower-is-better', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles() }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    const labels = [
      'True shooting %', 'Effective FG %', 'Free throw rate', '3-point attempt rate', 'Usage rate', 'Share of game minutes played',
      'Assist %', 'Offensive rebound %', 'Defensive rebound %', 'Total rebound %', 'Steal %', 'Block %', 'Turnover %',
    ]
    for (const l of labels) {
      const summary = within(adv).getByText(l, { selector: 'summary' })
      expect(summary.parentElement!.querySelector('p')!.textContent!.length, l).toBeGreaterThan(20)
    }
    expect(within(adv).getAllByText(/Uses only the games he played/)).toHaveLength(8)
    const tov = within(adv).getByRole('row', { name: /Turnover %/ })
    expect(within(tov).getAllByText(/lower is better/i).length).toBeGreaterThan(0)
    expect(within(tov).queryByText(/fewer is better/)).not.toBeInTheDocument()
    expect(within(tov).getAllByText(/higher percentile = fewer turnovers/)).toHaveLength(3)
  })

  it('labels plus-minus as noisy', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles() }))
    expect(await screen.findByRole('row', { name: /Plus-minus per game \(noisy\)/ })).toBeInTheDocument()
    expect(screen.getByText(/Plus-minus is noisy/, { selector: 'p' })).toBeInTheDocument()
  })

  const distinct = () =>
    page({
      windows: {
        SEASON: win({ games: 70, gameScorePerGame: 14.8, plusMinusPerGame: 2.4, per36: { ...zero, pts: 14.2, reb: 12.3, ast: 1.6, stl: 1.1, blk: 2.2, tpm: 0.4, tov: 2.5 } }),
        LAST_10: win({ games: 10, gameScorePerGame: 11.1, plusMinusPerGame: -3.2, per36: { ...zero, pts: 20.5, reb: 8.8, ast: 3.3, stl: 0.7, blk: 1.4, tpm: 1.9, tov: 3.6 } }),
        LAST_5: win({ games: 5, gameScorePerGame: 9.9, plusMinusPerGame: 5.5, per36: { ...zero, pts: 25.5, reb: 6.6, ast: 4.4, stl: 0.2, blk: 0.8, tpm: 2.7, tov: 4.9 } }),
      },
      percentiles: percentiles(),
    })

  it('shows per-36, game score and plus-minus for all three windows side by side', async () => {
    onTab('stats')
    show(distinct())
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    const cells = (name: RegExp) =>
      within(within(adv).getByRole('row', { name })).getAllByRole('cell').map((c) => c.textContent)
    expect(cells(/^Points per 36/)).toEqual(['14.2', '20.5', '25.5'])
    expect(cells(/^Rebounds per 36/)).toEqual(['12.3', '8.8', '6.6'])
    expect(cells(/^Assists per 36/)).toEqual(['1.6', '3.3', '4.4'])
    expect(cells(/^Steals per 36/)).toEqual(['1.1', '0.7', '0.2'])
    expect(cells(/^Blocks per 36/)).toEqual(['2.2', '1.4', '0.8'])
    expect(cells(/^Three-pointers made per 36/)).toEqual(['0.4', '1.9', '2.7'])
    expect(cells(/^Turnovers per 36/)).toEqual(['2.5', '3.6', '4.9'])
    expect(cells(/^Game score per game/)).toEqual(['14.8', '11.1', '9.9'])
    expect(cells(/^Plus-minus per game \(noisy\)/)).toEqual(['+2.4', '−3.2', '+5.5'])
  })

  it('writes every reason under the table with the windows it applies to', async () => {
    onTab('stats')
    const advanced = { ...win().advanced, ts: rate(null, 'NO_ATTEMPTS') }
    const notQualified = Object.fromEntries(KEYS.map((k) => [k, pctPair({ value: null, reason: 'NOT_QUALIFIED' }, {})]))
    show(
      page({
        windows: { SEASON: win(), LAST_10: win({ games: 10, advanced }), LAST_5: win({ games: 5 }) },
        percentiles: { SEASON: percentiles().SEASON, LAST_10: notQualified, LAST_5: percentiles().LAST_5 },
      }),
    )
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    const notes = within(adv).getAllByRole('listitem').map((li) => li.textContent)
    expect(notes).toContain('Last 10: no att. — No attempts in these games, so there is no percentage.')
    expect(notes).toContain('Last 10: not ranked — Hasn’t played enough games or minutes to be ranked.')
  })

  it('states the qualification rule from the page payload', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles() }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    expect(within(adv).getByText(/at least 42 games \(half of the most any team has played, 83\) and 15\+ minutes per game/)).toBeInTheDocument()
    expect(within(adv).getByText(/game in the last 14 days/)).toBeInTheDocument()
  })

  it('dates the rostered group beside its toggle', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles() }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    expect(within(adv).queryByText(/Players rostered in this league ·/)).not.toBeInTheDocument()
    fireEvent.click(within(adv).getByRole('button', { name: 'vs players rostered in this league' }))
    expect(within(adv).getByText('Players rostered in this league · End of 2025–26 regular season (week 18).')).toBeInTheDocument()
  })

  it('says the rostered group is last season’s rosters when the page fell back', async () => {
    onTab('stats')
    show(page({ requestedSeason: 2026, percentiles: percentiles() }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    fireEvent.click(within(adv).getByRole('button', { name: 'vs players rostered in this league' }))
    expect(within(adv).getByText(/These are 2025–26 rosters, not today’s\./)).toBeInTheDocument()
  })

  it('uses a neutral bar for style stats and the colour bar for the rest; prints exact values', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles({ ftr: pctPair({ value: 94.2 }, {}), ts: pctPair({ value: 71.4 }, {}) }) }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    const fill = (name: RegExp) => within(adv).getByRole('row', { name }).querySelector('.pp-meter-fill')!
    expect(fill(/Free throw rate/)).toHaveClass('neutral')
    expect(fill(/Usage rate/)).toHaveClass('neutral')
    expect(fill(/3-point attempt rate/)).toHaveClass('neutral')
    expect(fill(/Share of game minutes played/)).toHaveClass('neutral')
    expect(fill(/True shooting %/)).not.toHaveClass('neutral')
    expect(fill(/Turnover %/)).not.toHaveClass('neutral')
    const ftr = within(adv).getByRole('row', { name: /Free throw rate/ })
    expect(within(ftr).getAllByText('94th percentile')).toHaveLength(3)
    expect(ftr.querySelector('[title="Exact percentile 94.2"]')).not.toBeNull()
  })

  it('never prints 100th or 0th percentile', async () => {
    onTab('stats')
    show(page({ percentiles: percentiles({ ts: pctPair({ value: 99.69 }, {}), efg: pctPair({ value: 0.3 }, {}) }) }))
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    expect(within(adv).queryByText(/100th|\b0th/)).not.toBeInTheDocument()
    expect(within(within(adv).getByRole('row', { name: /True shooting %/ })).getAllByText('99th percentile')).toHaveLength(3)
    expect(within(within(adv).getByRole('row', { name: /Effective FG %/ })).getAllByText('1st percentile')).toHaveLength(3)
  })

  it('says there is no position on file, not that too few players qualify, for a player with none', async () => {
    onTab('stats')
    const none = Object.fromEntries(KEYS.map((k) => [k, pctPair({ value: null, n: 0, reason: 'GROUP_TOO_SMALL' }, {})]))
    show(
      page({
        player: { sleeperPlayerId: '1350', name: 'Rudy Gobert', positions: [], team: 'MIN', known: true },
        percentiles: { SEASON: none, LAST_10: none, LAST_5: none },
      }),
    )
    const adv = await screen.findByRole('region', { name: 'Advanced' })
    expect(within(adv).getAllByText('group too small')[0]).toHaveAttribute('title', expect.stringContaining('no position on file'))
    const notes = within(adv).getAllByRole('listitem').map((li) => li.textContent ?? '')
    expect(notes.some((n) => n.includes('group too small — He has no position on file'))).toBe(true)
    expect(notes.some((n) => n.includes('Too few other players'))).toBe(false)
  })

  // ---- specs/042 US2: header and tabs

  const nextGame = (over: Partial<PlayerStatsPage['nextGame'] & object> = {}) => ({
    gameId: 'g9', date: '2026-10-21', opponent: 'OKC', isHome: true, week: 1, ...over,
  })

  it('opens on Overview with the ranks card, and leaves the stats and the log behind their tabs', async () => {
    show(page())
    expect(await screen.findByRole('region', { name: 'Ranks' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('region', { name: 'Season line' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Game log' })).not.toBeInTheDocument()
  })

  it('?tab=log opens the game log and ?tab=zzz falls back to Overview', async () => {
    const { unmount } = show(page(), '/leagues/L25/players/1350?tab=log')
    expect(await screen.findByRole('region', { name: 'Game log' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Game log' })).toHaveAttribute('aria-selected', 'true')
    unmount()
    show(page(), '/leagues/L25/players/1350?tab=zzz')
    expect(await screen.findByRole('region', { name: 'Ranks' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
  })

  it('is a real tablist: arrows move and select, only the selected tab is in the tab order', async () => {
    show(page())
    const overview = await screen.findByRole('tab', { name: 'Overview' })
    expect(screen.getByRole('tablist', { name: 'Player sections' })).toBeInTheDocument()
    expect(overview).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: 'Stats' })).toHaveAttribute('tabindex', '-1')
    overview.focus()
    fireEvent.keyDown(overview, { key: 'ArrowRight' })
    const stats = await screen.findByRole('tab', { name: 'Stats' })
    expect(stats).toHaveAttribute('aria-selected', 'true')
    expect(stats).toHaveFocus()
    expect(await screen.findByRole('region', { name: 'Season line' })).toBeInTheDocument()
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'pp-tab-stats')
    fireEvent.keyDown(stats, { key: 'End' })
    expect(await screen.findByRole('tab', { name: 'Schedule' })).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Schedule' }), { key: 'ArrowRight' })
    expect(await screen.findByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
  })

  it('prints the status with the time it was refreshed, never a fixed hour', async () => {
    show(page({
      status: { injuryStatus: 'Questionable', status: 'ACT', out: false, asOf: '2026-10-21T11:02:00Z' },
      nextGame: nextGame(), nextGameReason: null, upcoming: [nextGame()],
    }))
    expect(await screen.findByText('Listed: Questionable')).toBeInTheDocument()
    expect(screen.getByText('as of Oct 21, 7:02 AM EDT')).toBeInTheDocument()
    expect(screen.queryByText(/7 AM/)).not.toBeInTheDocument()
  })

  it('says the status time is unknown when the server has none, and words Out and a suspension as the tag', async () => {
    const { unmount } = show(page({ status: { injuryStatus: 'Out', status: 'ACT', out: true, asOf: null } }))
    expect(await screen.findByText('Out')).toBeInTheDocument()
    expect(screen.getByText('status time unknown')).toBeInTheDocument()
    unmount()
    show(page({ status: { injuryStatus: null, status: 'SUS', out: true, asOf: null } }))
    expect(await screen.findByText('SUS')).toBeInTheDocument()
  })

  it('shows the next game as a date, vs/@ and the opponent, with no time', async () => {
    const { unmount } = show(page({ nextGame: nextGame(), nextGameReason: null, upcoming: [nextGame()] }))
    expect(await screen.findByText('Wed Oct 21 · vs OKC')).toBeInTheDocument()
    unmount()
    show(page({ nextGame: nextGame({ isHome: false }), nextGameReason: null, upcoming: [nextGame({ isHome: false })] }))
    expect(await screen.findByText('Wed Oct 21 · @ OKC')).toBeInTheDocument()
    // The game itself has no time; the schedule's own fetched-at (a separate line) legitimately does.
    expect(screen.getByText('Wed Oct 21 · @ OKC').textContent).not.toMatch(/\d:\d\d (AM|PM)/)
  })

  it('gives a sentence, not a blank, when there is no next game', async () => {
    show(page({ nextGameReason: 'NO_TEAM' }))
    expect(await screen.findByText('He is not on a team right now, so there is no next game.')).toBeInTheDocument()
  })

  it('shows his position rank in the header and the upcoming games on the Schedule tab', async () => {
    show(page({
      nextGame: nextGame(), nextGameReason: null,
      upcoming: [nextGame(), nextGame({ gameId: 'g10', date: '2026-10-23', opponent: 'BOS', isHome: false, week: 1 })],
    }), '/leagues/L25/players/1350?tab=schedule')
    const sched = await screen.findByRole('region', { name: 'Schedule' })
    expect(within(sched).getAllByRole('listitem')).toHaveLength(2)
    expect(within(sched).getByText('@ BOS')).toBeInTheDocument()
    expect(within(sched).getByText(/dates only, no tip-off times/)).toBeInTheDocument()
    expect(screen.getByText('#12 C')).toBeInTheDocument()
    expect(within(sched).getByText(/schedule as of Oct 20/)).toBeInTheDocument()
  })

  it('labels the header position rank with its season when the page fell back to an earlier one (S1)', async () => {
    show(page({ requestedSeason: 2026, nextGame: nextGame(), nextGameReason: null, upcoming: [nextGame()] }))
    expect(await screen.findByText('#12 C (2025–26)')).toBeInTheDocument()
  })

  it('prints when the schedule was fetched beside the next game, or says it is unknown (S2)', async () => {
    const { unmount } = show(page({ nextGame: nextGame(), nextGameReason: null, upcoming: [nextGame()] }))
    expect(await screen.findByText(/^schedule as of Oct 20/)).toBeInTheDocument()
    unmount()
    show(page({ nextGame: nextGame(), nextGameReason: null, upcoming: [nextGame()], scheduleAsOf: null }))
    expect(await screen.findByText('schedule time unknown')).toBeInTheDocument()
  })
})

// --- specs/044 US2: the lock-ins tab ---------------------------------------------------------------

function lockInReport(over: Partial<LockInReport> = {}): LockInReport {
  const fig = (n: number, median: number | null = null, q1: number | null = null, q3: number | null = null) => ({ n, median, q1, q3 })
  return {
    sport: 'nba', season: 2025, status: 'MEASURED', reason: null, minLocks: 10, minCell: 15, bands: [0, 0.1, 0.25, 0.5],
    gamesLeftCap: 3, minPriorGames: 5, weeksCovered: 21,
    totals: {
      multiGame: 2168, lock: 1188, rodeOut: 894, rodeOutMissed: 14, tieNonLast: 10, tieWithLast: 62, unmatched: 0,
      singleGame: 92, noGames: 1, noBaselineStandIn: 127, noSnapshot: 1188, nonPositiveProjection: 0,
    },
    managers: [],
    players: [{ key: '1350', name: 'Rudy Gobert', avatarId: null, standIn: fig(12, 0.4, 0.2, 0.6), projection: fig(0), rodeOut: 9, tieNonLast: 0 }],
    payoff: { STAND_IN: [], PROJECTION: [] },
    locks: [
      {
        week: 3, playoff: false, managerId: 7, manager: 'popsharky', rosterId: 2, playerId: '1350', player: 'Rudy Gobert',
        gameDate: '2025-11-05', gameId: 'g1', points: 33, standIn: { baseline: 22, margin: 0.5 }, projection: null,
        laterGames: 2, lastGame: 25, bestLater: 30, beatenByLast: false, beatenLater: false,
      },
      {
        week: 21, playoff: true, managerId: 9, manager: 'xonicboom', rosterId: 5, playerId: '1350', player: 'Rudy Gobert',
        gameDate: '2026-03-20', gameId: 'g2', points: 20, standIn: { baseline: null, margin: null }, projection: null,
        laterGames: 1, lastGame: 18, bestLater: 18, beatenByLast: false, beatenLater: false,
      },
    ],
    excludedCategories: [], caveats: ['Locks are inferred: Sleeper does not publish which game a manager locked.'],
    thresholds: null, live: null,
    ...over,
  }
}

describe('PlayerPage lock-ins tab (spec 044)', () => {
  it('asks for only this player, without the live call, and adds the tab for a measured lock-in league', async () => {
    fetchLockIns.mockResolvedValue(lockInReport())
    show(page())
    expect(await screen.findByRole('tab', { name: 'Lock-ins' })).toBeInTheDocument()
    expect(fetchLockIns).toHaveBeenCalledWith('L25', { player: '1350', live: false })
  })

  it('has no such tab for a league that is not a lock-in league, and ?tab=lock-ins falls back to Overview', async () => {
    onTab('lock-ins')
    show(page())
    await screen.findByRole('tab', { name: 'Overview' })
    await waitFor(() => expect(fetchLockIns).toHaveBeenCalled())
    expect(screen.queryByRole('tab', { name: 'Lock-ins' })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
  })

  it('does not ask for lock-ins on a football page', async () => {
    show(page({ sport: 'nfl', available: false, reason: 'NOT_SUPPORTED' as PlayerStatsPage['reason'] }))
    await waitFor(() => expect(getPlayerStats).toHaveBeenCalled())
    await screen.findByRole('heading', { level: 1 })
    expect(fetchLockIns).not.toHaveBeenCalled()
  })

  it('shows the median with its count and the individual locks, each with its baseline', async () => {
    fetchLockIns.mockResolvedValue(lockInReport())
    onTab('lock-ins')
    show(page())
    const card = await screen.findByRole('region', { name: 'Lock-ins' })
    expect(within(card).getByRole('tab', { name: 'Season-average stand-in' })).toHaveAttribute('aria-selected', 'true')
    expect(card).toHaveTextContent('Season-average stand-in: +40% median over 12 locks')
    expect(card).toHaveTextContent('middle half +20% to +60%')
    const rows = within(card).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('popsharky')
    expect(rows[0]).toHaveTextContent('33.0')
    expect(rows[0]).toHaveTextContent('22.0')
    expect(rows[0]).toHaveTextContent('+50%')
    expect(rows[1]).toHaveTextContent('xonicboom')
    expect(rows[1]).toHaveTextContent('playoffs')
    expect(rows[1]).toHaveTextContent('no margin')
  })

  it('says "too few" below the minimum and switches to the projection baseline on request', async () => {
    const r = lockInReport()
    r.players[0].standIn = { n: 2, median: null, q1: null, q3: null }
    fetchLockIns.mockResolvedValue(r)
    onTab('lock-ins')
    show(page())
    const card = await screen.findByRole('region', { name: 'Lock-ins' })
    expect(card).toHaveTextContent('2 locks — too few for a median')
    fireEvent.click(within(card).getByRole('tab', { name: 'Projection' }))
    expect(card).toHaveTextContent('0 locks — too few for a median')
    expect(card).toHaveTextContent('none frozen')
  })

  it('says there are no provable locks instead of showing 0%', async () => {
    fetchLockIns.mockResolvedValue(lockInReport({ players: [], locks: [] }))
    onTab('lock-ins')
    show(page())
    expect(await screen.findByText('No provable locks for this player in this league this season.')).toBeInTheDocument()
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })
})
