import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import HistoricalDraft from './pages/HistoricalDraft'
import HistoricalRoom from './pages/HistoricalRoom'
import old062 from './__fixtures__/historical-result-062.json'
import type { HistoricalDaily, HistoricalGameState, HistoricalGameSummary, HistoricalPlayerCard, HistoricalProfile, HistoricalSeasonSummary } from './api'
import TimeMachineSignIn from './pages/TimeMachineSignIn'
import NotFound from './components/NotFound'
import ErrorBoundary from './components/ErrorBoundary'
import TimeMachineShell from './components/TimeMachineShell'
import { clearUser, setUser } from './user'
import { LEAGUE_WORDS } from './leagueWords'

// Spec 073 SC-001 / FR-003 / FR-004: on the Time Machine site no screen a visitor can reach says
// league, leagues, mock or manager. Case-insensitive.

const api = vi.hoisted(() => ({
  listHistoricalGames: vi.fn(),
  historicalSeasons: vi.fn(),
  getHistoricalDaily: vi.fn(),
  getHistoricalProfile: vi.fn(),
  getHistoricalGame: vi.fn(),
  getHistoricalGameAward: vi.fn(),
}))
vi.mock('./api', async (orig) => ({
  ...(await orig<typeof import('./api')>()),
  getSleeperUser: vi.fn(),
  ...api,
}))

// CR-05: real-shaped data, so the screens that print backend text actually render it. The reason and the
// settings strings below are the backend's own (EraScoring.java, NbaHistoricalSport.java, NflHistoricalSport.java).
const NBA_SETTINGS = 'App standard 12-team NBA roster · Legacy Ball Knowers scoring'
const NFL_SETTINGS = 'Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, FLEX, K, DEF, 6 bench · full PPR'
const NOT_TRACKED = 'not tracked by the league that season'

const seasonOf = (sport: 'NBA' | 'NFL', label: string, start: number, settingsSource: string): HistoricalSeasonSummary =>
  ({
    sport,
    seasonStart: start,
    label,
    source: sport === 'NBA' ? 'KAGGLE' : 'NFLVERSE',
    countedStats: ['pts', 'reb'],
    excludedStats: { stl: NOT_TRACKED, blk: NOT_TRACKED },
    statCoverage: { pts: 1, reb: 0.99 },
    rankingSource: 'PRIOR_SEASON_POINTS',
    settingsSource,
    dataNotes: [],
  }) as HistoricalSeasonSummary

const card = (id: number, name: string): HistoricalPlayerCard =>
  ({
    id, name, positions: ['SF'], team: 'CHI', age: 28, rank: id, positionalRank: id,
    prior: { seasonLabel: '1971-72', games: 79, perGame: { pts: 29.3, reb: 8.1 }, fantasyPoints: 3010.5, bestOfTwo: '1971-72', totals: {} },
    rookiePick: null,
  }) as HistoricalPlayerCard

const roomState = (over: Record<string, unknown> = {}): HistoricalGameState =>
  ({
    id: 5,
    sport: 'NBA',
    status: 'DRAFTING',
    season: {
      label: '1972-73', countedStats: ['pts', 'reb'], excludedStats: { stl: NOT_TRACKED, blk: NOT_TRACKED },
      statCoverage: { pts: 1, reb: 0.99 }, rankingSource: 'PRIOR_SEASON_POINTS',
      rankingLabel: "ranked by last season's points", settingsSource: NBA_SETTINGS, dataNotes: [], attribution: null,
    },
    teams: 2, rounds: 2, rosterPositions: ['PG', 'SG', 'BN'], userSlot: 1, myPicks: [1, 4], currentPickNo: 1,
    onTheClockSlot: 1, isUsersTurn: true, picks: [], available: [card(1, 'Top Guy'), card(2, 'Next Guy')], result: null,
    ...over,
  }) as unknown as HistoricalGameState

const finishedGame = (): HistoricalGameState =>
  roomState({
    status: 'COMPLETE', onTheClockSlot: null, isUsersTurn: false, available: [],
    userSlot: Number(Object.keys(old062.lineups)[0]), teams: 12, rounds: 14,
    picks: [{ pickNo: 1, round: 1, draftSlot: 1, seatType: 'USER', player: card(11, 'Mine One') }],
    result: old062,
  })

const homeGames = [
  { id: 1, sport: 'NBA', seasonLabel: '1971-72', status: 'DRAFTING', userSlot: 3, finish: null, createdAt: '2026-11-01T12:00:00Z' },
  { id: 2, sport: 'NBA', seasonLabel: '1972-73', status: 'COMPLETE', userSlot: 3, finish: 2, formatVersion: 2, outcome: 'LOST_FINAL', createdAt: '2026-11-02T12:00:00Z' },
] as HistoricalGameSummary[]

const dailyOf = (sport: 'NBA' | 'NFL'): HistoricalDaily => ({
  sport, date: '2999-01-01', seasonLabel: sport === 'NBA' ? '1988-89' : '1999', gameId: null, gameStatus: null,
  draftScore: null, streak: 2, bestStreak: 3, nextAt: '2999-01-02T05:00:00Z',
})

const profile: HistoricalProfile = {
  xp: 340, level: 3, levelName: 'Rookie', levelStartXp: 300, nextLevelXp: 600, seenLevel: 3,
  totals: { games: 4, gamesFromSavedResults: 0, weeklyWins: 30, playoffWins: 2, titles: 1 },
  badges: [{
    code: 'TITLE', name: 'Champion', rule: 'Win a title', xp: 80, source: 'STORED', unlocked: true,
    unlockedAt: '2026-11-13T12:00:00Z', gameId: 2, detail: null, seen: true,
  }],
  settleFailures: 0,
}

const SIGNED_IN = { sleeperUserId: '1', username: 'popsharky', displayName: 'Pop', avatar: null }

function Boom(): never {
  throw new Error('kaboom')
}

const visibleText = (container: HTMLElement) => (container.textContent ?? '').replace(/\s+/g, ' ')
const ariaText = (container: HTMLElement) =>
  [...container.querySelectorAll('[aria-label], [title], [placeholder], img[alt]')]
    .map((e) => [e.getAttribute('aria-label'), e.getAttribute('title'), e.getAttribute('placeholder'), e.getAttribute('alt')].join(' '))
    .join(' ')

function expectNoLeagueWords(container: HTMLElement) {
  expect(visibleText(container)).not.toMatch(LEAGUE_WORDS)
  expect(ariaText(container)).not.toMatch(LEAGUE_WORDS)
}

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})

function renderRoom() {
  setUser(SIGNED_IN)
  return render(
    <MemoryRouter initialEntries={['/game/5']}>
      <TimeMachineShell>
        <Routes>
          <Route path="/game/:gameId" element={<HistoricalRoom />} />
        </Routes>
      </TimeMachineShell>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  localStorage.clear()
  vi.stubEnv('VITE_SITE', 'timemachine')
  api.listHistoricalGames.mockReset().mockResolvedValue(homeGames)
  api.historicalSeasons.mockReset().mockImplementation(async (sport: string) =>
    sport === 'NFL' ? [seasonOf('NFL', '1999', 1999, NFL_SETTINGS)] : [seasonOf('NBA', '1972-73', 1972, NBA_SETTINGS)])
  api.getHistoricalDaily.mockReset().mockResolvedValue([dailyOf('NBA'), dailyOf('NFL')])
  api.getHistoricalProfile.mockReset().mockResolvedValue(profile)
  api.getHistoricalGame.mockReset()
  api.getHistoricalGameAward.mockReset().mockResolvedValue({
    gameId: 5, settled: true, fromSavedResult: false, xp: 120, lines: [{ label: '9 wins', xp: 90 }],
    badges: [profile.badges[0]], moments: [], momentsSeen: true, total: 200,
  })
})

afterEach(() => {
  clearUser()
  vi.unstubAllEnvs()
  vi.restoreAllMocks()
})

describe('the Time Machine site has no league wording', () => {
  it('the front door', () => {
    const { container } = render(<MemoryRouter><TimeMachineSignIn /></MemoryRouter>)
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    expectNoLeagueWords(container)
  })

  it('the home, signed in, inside the shell', async () => {
    setUser(SIGNED_IN)
    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <TimeMachineShell>
          <HistoricalDraft />
        </TimeMachineShell>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('Time Machine')).toBeInTheDocument())
    await screen.findByText('1971-72') // a past game row
    await screen.findByText(NBA_SETTINGS)
    await screen.findAllByText('Champion') // the profile's badge
    expectNoLeagueWords(container)
  })

  it('the room while drafting, with a not-counted reason and the settings line', async () => {
    api.getHistoricalGame.mockResolvedValue(roomState())
    const { container } = renderRoom()
    await screen.findByText("You're drafting 1972-73")
    expect(screen.getAllByText(/not tracked by the NBA that season/).length).toBeGreaterThan(0)
    expect(container.textContent ?? '').toContain(NBA_SETTINGS)
    expectNoLeagueWords(container)
  })

  it('the room for a football game, drafting', async () => {
    const base = roomState() as unknown as { season: Record<string, unknown> }
    api.getHistoricalGame.mockResolvedValue(
      roomState({ sport: 'NFL', season: { ...base.season, label: '1999', settingsSource: NFL_SETTINGS } }),
    )
    const { container } = renderRoom()
    await waitFor(() => expect(container.textContent ?? '').toContain(NFL_SETTINGS))
    expectNoLeagueWords(container)
  })

  it('a finished game: the results', async () => {
    api.getHistoricalGame.mockResolvedValue(finishedGame())
    const { container } = renderRoom()
    expect(await screen.findByRole('heading', { name: 'Final standings' })).toBeInTheDocument()
    await screen.findAllByText('Champion')
    expect(container.textContent ?? '').toMatch(/not tracked by the NBA that season/)
    expectNoLeagueWords(container)
  })

  it('the not-found page, for every kind', () => {
    for (const what of ['league', 'mock', 'draft', 'page'] as const) {
      const { container, unmount } = render(<MemoryRouter><NotFound what={what} /></MemoryRouter>)
      expectNoLeagueWords(container)
      expect(screen.getByRole('link', { name: 'Back to Time Machine' })).toHaveAttribute('href', '/')
      unmount()
    }
  })

  it('the error boundary', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const { container } = render(
      <MemoryRouter>
        <ErrorBoundary>
          <Boom />
        </ErrorBoundary>
      </MemoryRouter>,
    )
    expect(screen.getByText('This page hit an error')).toBeInTheDocument()
    // The message itself is the thrown error's text, not site wording.
    expect(container.querySelector('.muted')?.textContent ?? '').not.toMatch(LEAGUE_WORDS)
  })

  it('the same screens in the combined build still say leagues (nothing changed there)', () => {
    vi.stubEnv('VITE_SITE', '')
    render(<MemoryRouter><NotFound what="league" /></MemoryRouter>)
    expect(screen.getByText(/No league here/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to your leagues' })).toBeInTheDocument()
  })
})

// Spec 073 FR-019 was withdrawn 2026-11-16: the league site is private, so the Time Machine no longer
// tells anyone their leagues moved. Whatever the storage holds, the home shows no such notice.
describe('the Time Machine home shows no "leagues moved" notice, in any storage state', () => {
  const states: [string, () => void][] = [
    ['empty storage', () => {}],
    ['the old marker set', () => localStorage.setItem('bk.tm.signedInHere.v1', '1')],
    ['the old dismissed key set', () => localStorage.setItem('bk.tm.movedNotice.dismissed.v1', '1')],
    ['both old keys absent after a league-era sign-in', () => localStorage.setItem('bk.statCalc.saved.v1', '{"v":1,"formulas":[]}')],
  ]
  it.each(states)('%s', async (_name, prepare) => {
    prepare()
    setUser(SIGNED_IN)
    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <TimeMachineShell>
          <HistoricalDraft />
        </TimeMachineShell>
      </MemoryRouter>,
    )
    await screen.findByText('1971-72')
    expect(screen.queryByRole('complementary', { name: 'Your leagues moved' })).toBeNull()
    expect(container.querySelector('.tm-moved')).toBeNull()
    expect(container.textContent).not.toMatch(/moved to|Looking for your leagues/i)
  })
})
