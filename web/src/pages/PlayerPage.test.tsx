import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PlayerPage from './PlayerPage'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import type {
  PlayerCounting,
  PlayerGameLogRow,
  PlayerOwnership,
  PlayerStatsPage,
  PlayerWindow,
  Rate,
} from '../api'

const getPlayerStats = vi.fn()
vi.mock('../api', () => ({
  getPlayerStats: (...args: unknown[]) => getPlayerStats(...args),
}))

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
    ...over,
  }
}

function mount(url = '/leagues/L25/players/1350') {
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
})

describe('PlayerPage', () => {
  it('asks for the player in the league named by the URL', async () => {
    show(page())
    expect(await screen.findByRole('region', { name: 'Season line' })).toBeInTheDocument()
    expect(getPlayerStats).toHaveBeenCalledWith('L25', '1350')
  })

  it('labels real and fantasy figures apart, and shows rank, move and group sizes', async () => {
    show(page())
    const line = await screen.findByRole('region', { name: 'Season line' })
    expect(within(line).getByText('Real stat')).toBeInTheDocument()
    expect(within(line).getAllByText('This league’s fantasy').length).toBeGreaterThan(0)
    expect(within(line).getByText('22.56')).toBeInTheDocument()

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
    show(page())
    const line = await screen.findByRole('region', { name: 'Season line' })
    expect(within(line).getAllByText(/No attempts in these games, so there is no percentage\./)).toHaveLength(1)
  })

  it('shows makes and attempts beneath each shooting percentage', async () => {
    const totals: PlayerCounting = { ...zero, fgm: 348, fga: 510, ftm: 120.0, fta: 206 }
    show(page({ windows: { SEASON: win({ totals }) } }))
    const line = await screen.findByRole('region', { name: 'Season line' })
    const row = within(line).getByRole('row', { name: /^Season/ })
    expect(within(row).getByText('66.4%')).toBeInTheDocument()
    expect(within(row).getByText('348-510')).toBeInTheDocument()
    expect(within(row).getByText('120-206')).toBeInTheDocument()
  })

  it('states NO_ATTEMPTS as words with the sentence as title, never 0 or a bare dash', async () => {
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
