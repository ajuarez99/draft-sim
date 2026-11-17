import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import old062 from '../__fixtures__/historical-result-062.json'
import type { HistoricalGameState, HistoricalPlayerCard, HistoricalResult, PublicGame } from '../api'
import { ApiError } from '../apiError'
import { GUEST_KEY, __resetGuestForTests } from '../guest'
import { clearUser, setUser } from '../user'
import HistoricalRoom from './HistoricalRoom'

// Spec 074 T039/T043: the room's non-owner branch (owner endpoint first, the public one on 404/403, loading held until it
// settles), the shared result page, and what an owner's results page does and does not mount.

const api = vi.hoisted(() => ({
  getHistoricalGame: vi.fn(),
  getPublicGame: vi.fn(),
  getHistoricalGameAward: vi.fn(),
  getHistoricalProfile: vi.fn(),
  startHistoricalDaily: vi.fn(),
}))
vi.mock('../api', async (orig) => ({ ...(await orig<typeof import('../api')>()), ...api }))

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})

const card = (id: number, name: string): HistoricalPlayerCard =>
  ({ id, name, positions: ['SF'], team: 'CHI', age: 28, rank: id, positionalRank: id, prior: null, rookiePick: null }) as HistoricalPlayerCard

const finished = (over: Partial<HistoricalGameState> = {}): HistoricalGameState =>
  ({
    id: 5,
    sport: 'NBA',
    status: 'COMPLETE',
    season: {
      label: '2016-17', countedStats: ['pts'], excludedStats: {}, statCoverage: { pts: 1 }, rankingSource: 'PRIOR_SEASON_POINTS',
      rankingLabel: "ranked by last season's points", settingsSource: 'x', dataNotes: [], attribution: null,
    },
    teams: 12, rounds: 14, rosterPositions: ['PG', 'BN'], userSlot: Number(Object.keys(old062.lineups)[0]), myPicks: [],
    currentPickNo: 169, onTheClockSlot: null, isUsersTurn: false, available: [], reversalRound: 0, dailyDate: null, chosenSeason: false,
    picks: [{ pickNo: 1, round: 1, draftSlot: 1, seatType: 'USER', player: card(11, 'Mine One') }],
    result: old062 as unknown as HistoricalResult,
    ...over,
  }) as HistoricalGameState

const pub = (over: Partial<PublicGame> = {}, state: Partial<HistoricalGameState> = {}): PublicGame => ({
  state: finished(state),
  reducedUntil: null,
  ...over,
})

const noindex = () => document.head.querySelectorAll('meta[name="robots"][content="noindex"]')

function renderRoom() {
  return render(
    <MemoryRouter initialEntries={['/game/5']}>
      <Routes>
        <Route path="/game/:gameId" element={<HistoricalRoom />} />
        <Route path="/nba/:season" element={<div>season page</div>} />
        <Route path="/" element={<div>home</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.stubEnv('VITE_SITE', 'timemachine')
  localStorage.clear()
  __resetGuestForTests()
  clearUser()
  // Most tests are a browser that has played before (a stored guest id), so the owner route is asked first; the
  // identity-less viewer has its own tests below.
  localStorage.setItem(GUEST_KEY, '11111111-2222-4333-8444-555555555555')
  for (const f of Object.values(api)) f.mockReset()
  api.getHistoricalGameAward.mockResolvedValue({
    gameId: 5, settled: true, fromSavedResult: false, xp: 10, lines: [], badges: [], moments: [], momentsSeen: true, total: 10, notCounted: false,
  })
  api.getHistoricalProfile.mockRejectedValue(new Error('none'))
})
afterEach(() => {
  clearUser()
  vi.unstubAllEnvs()
})

describe("the room for somebody else's game", () => {
  it('asks the owner endpoint first: the owner never touches the public one', async () => {
    setUser({ sleeperUserId: '1', username: 'u', displayName: 'U', avatar: null })
    api.getHistoricalGame.mockResolvedValue(finished())
    renderRoom()
    expect(await screen.findByRole('region', { name: 'Your finish' })).toBeInTheDocument()
    expect(api.getPublicGame).not.toHaveBeenCalled()
    // V-08g: any game page, the owner's room included, is kept out of the index while it is mounted
    expect(noindex()).toHaveLength(1)
  })

  it('V-08j: a viewer with no identity at all goes straight to the public route, so no private request can 404', async () => {
    localStorage.clear()
    __resetGuestForTests()
    api.getPublicGame.mockResolvedValue(pub())
    renderRoom()
    expect(await screen.findByText(/^Shared result/)).toBeInTheDocument()
    expect(api.getHistoricalGame).not.toHaveBeenCalled()
    expect(api.getPublicGame).toHaveBeenCalledWith(5)
    // and an unfinished or missing game reads the same for them
    cleanup()
    api.getPublicGame.mockReset()
    api.getPublicGame.mockRejectedValue(new ApiError(404))
    renderRoom()
    expect(await screen.findByRole('heading', { name: 'No finished game here' })).toBeInTheDocument()
    expect(api.getHistoricalGame).not.toHaveBeenCalled()
  })

  it('V-08j: with a stored guest id or a signed-in user, the owner route still goes first', async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    api.getPublicGame.mockResolvedValue(pub())
    renderRoom()
    await screen.findByText(/^Shared result/)
    expect(api.getHistoricalGame).toHaveBeenCalledWith(5)
    cleanup()
    localStorage.clear()
    __resetGuestForTests()
    setUser({ sleeperUserId: '1', username: 'u', displayName: 'U', avatar: null })
    api.getHistoricalGame.mockClear()
    renderRoom()
    await screen.findByText(/^Shared result/)
    expect(api.getHistoricalGame).toHaveBeenCalledWith(5)
  })

  it('V-08g: exactly one noindex meta while a shared result is mounted, even when the delivered shell already had one', async () => {
    const shell = document.createElement('meta')
    shell.name = 'robots'
    shell.content = 'noindex'
    document.head.appendChild(shell)
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    api.getPublicGame.mockResolvedValue(pub())
    const { unmount } = renderRoom()
    await screen.findByText(/^Shared result/)
    expect(document.head.querySelectorAll('meta[name="robots"]')).toHaveLength(1)
    unmount()
    expect(document.head.querySelectorAll('meta[name="robots"]')).toHaveLength(0)
  })

  it.each([404, 403])('a %s from the owner endpoint falls back to the public result, written about "this team"', async (status) => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(status))
    api.getPublicGame.mockResolvedValue(pub())
    renderRoom()
    expect(await screen.findByText(/^Shared result · 2016-17 NBA Redraft/)).toBeInTheDocument()
    expect(api.getPublicGame).toHaveBeenCalledWith(5)
    expect(screen.getByRole('region', { name: "This team's finish" })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /share/i })).toBeNull()
    expect(document.body.textContent).not.toMatch(/\byou(r|rs)?\b/i)
  })

  it('holds the loading state until the public answer settles: no not-found flash, no owner page flash', async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    let release!: (p: PublicGame) => void
    api.getPublicGame.mockReturnValue(new Promise<PublicGame>((r) => (release = r)))
    renderRoom()
    await waitFor(() => expect(api.getPublicGame).toHaveBeenCalled())
    expect(screen.getByText('Loading this draft…')).toBeInTheDocument()
    expect(screen.queryByText('No finished game here')).toBeNull()
    expect(screen.queryByText('Nothing here')).toBeNull()
    release(pub())
    expect(await screen.findByText(/^Shared result/)).toBeInTheDocument()
  })

  it('adds a noindex meta while it shows, and removes it when the page goes away', async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    api.getPublicGame.mockResolvedValue(pub())
    const { unmount } = renderRoom()
    await screen.findByText(/^Shared result/)
    expect(noindex()).toHaveLength(1)
    unmount()
    expect(noindex()).toHaveLength(0)
  })

  it('anything not finished or not there is "No finished game here", with the way back, and also not indexed', async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    api.getPublicGame.mockRejectedValue(new ApiError(404))
    const { unmount } = renderRoom()
    expect(await screen.findByRole('heading', { name: 'No finished game here' })).toBeInTheDocument()
    expect(screen.getByText("There is no finished game at this address.")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'NBA seasons' })).toHaveAttribute('href', '/nba')
    expect(noindex()).toHaveLength(1)
    unmount()
    expect(noindex()).toHaveLength(0)
  })

  it('a server error on the owner endpoint is an error, not a shared page', async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(500, 'boom'))
    renderRoom()
    expect(await screen.findByText('boom')).toBeInTheDocument()
    expect(api.getPublicGame).not.toHaveBeenCalled()
  })

  it("the primary action starts today's game of that sport, and the season link is there for an ordinary shared game", async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    api.getPublicGame.mockResolvedValue(pub())
    api.startHistoricalDaily.mockResolvedValue({ id: 88 })
    renderRoom()
    expect(await screen.findByRole('link', { name: 'How 2016-17 played out' })).toHaveAttribute('href', '/nba/2016-17')
    await userEvent.click(screen.getByRole('button', { name: "Play today's basketball game" }))
    expect(api.startHistoricalDaily).toHaveBeenCalledWith('NBA')
  })

  it("today's daily is reduced: it says when the rest shows, links no season page and sends no hindsight total", async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    const reducedResult = {
      userFinish: 5, userOutcome: 'LOST_ROUND_ONE', weeks: [], lineups: {}, finalPlaces: [], biggestBust: null, bestStealPassedOn: null,
      standings: [{ slot: 3, wins: 12, losses: 7, ties: 0, pointsFor: 4800, place: 5 }],
      format: { version: 2, seasonWeeks: 25, configuredRegularSeasonWeeks: 19, regularSeasonWeeks: 19, playoffWeeks: [20, 21, 22], unusedWeeks: 3, shortened: false },
      hindsight: { label: 'greedy hindsight, bots unchanged', userPointsFor: 700, userBeatIt: false, draftScore: 77, picks: [] },
      // CR-03: a reduced row is a pick number and a mark; the position and the exact ranks are never sent.
      pickFinishes: [{ pickNo: 3, mark: 'ahead' }],
    } as unknown as HistoricalResult
    api.getPublicGame.mockResolvedValue(
      pub({ reducedUntil: '2999-01-02T05:00:00Z' }, { userSlot: 3, picks: [], result: reducedResult, dailyDate: '2999-01-01' }),
    )
    renderRoom()
    expect(await screen.findByText("The picks and lineups show tomorrow, once today's game is over.")).toBeInTheDocument()
    expect(screen.getByText(/Game of the day, Jan 1/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /played out/ })).toBeNull()
    expect(screen.getByText('77')).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/\b900\b/)
    // V-08a: nothing is below a reduced result, so nothing says so; the unplayed-weeks fact stays
    expect(document.body.textContent).not.toMatch(/comparisons below/)
    expect(document.body.textContent).toMatch(/not played/)
    // the square is drawn from the mark alone
    expect(document.querySelector('.tm-pickgrid .tm-pick.ahead')).not.toBeNull()
    // a "reduced until" far in the future is not a timer (setTimeout would run it at once past 2^31 ms): one request only
    await new Promise((r) => setTimeout(r, 80))
    expect(api.getPublicGame).toHaveBeenCalledTimes(1)
  })

  // CR-10: a result that is reduced until midnight Eastern must not stay reduced under an open page.
  it('asks again once its "reduced until" instant has passed, and then shows the full result with the season link', async () => {
    api.getHistoricalGame.mockRejectedValue(new ApiError(404))
    const reducedResult = {
      userFinish: 5, userOutcome: 'LOST_ROUND_ONE', weeks: [], lineups: {}, finalPlaces: [], biggestBust: null, bestStealPassedOn: null,
      standings: [{ slot: 3, wins: 12, losses: 7, ties: 0, pointsFor: 4800, place: 5 }],
      format: { version: 2, seasonWeeks: 25, configuredRegularSeasonWeeks: 19, regularSeasonWeeks: 19, playoffWeeks: [20, 21, 22], unusedWeeks: 3, shortened: false },
      hindsight: { label: 'greedy hindsight, bots unchanged', userPointsFor: 700, userBeatIt: false, draftScore: 77, picks: [] },
      pickFinishes: [{ pickNo: 3, mark: 'ahead' }],
    } as unknown as HistoricalResult
    const soon = new Date(Date.now() + 300).toISOString()
    api.getPublicGame
      .mockResolvedValueOnce(pub({ reducedUntil: soon }, { userSlot: 3, picks: [], result: reducedResult, dailyDate: '2026-11-17' }))
      .mockResolvedValue(pub({ reducedUntil: null }, { dailyDate: '2026-11-17' }))
    renderRoom()
    expect(await screen.findByText("The picks and lineups show tomorrow, once today's game is over.")).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /played out/ })).toBeNull()
    expect(api.getPublicGame).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(api.getPublicGame).toHaveBeenCalledTimes(2), { timeout: 4000 })
    expect(await screen.findByRole('link', { name: 'How 2016-17 played out' })).toBeInTheDocument()
    expect(screen.queryByText("The picks and lineups show tomorrow, once today's game is over.")).toBeNull()
  })
})

describe("the owner's results page (spec 074 T039)", () => {
  it('a guest gets "Keep this result" and no award panel, and no award request (the server would answer 403)', async () => {
    api.getHistoricalGame.mockResolvedValue(finished())
    renderRoom()
    const panel = await screen.findByRole('region', { name: 'Keep this result' })
    expect(panel).toHaveTextContent('Sign in with your Sleeper username to save this result beyond this browser')
    expect(screen.queryByRole('region', { name: 'What this game earned' })).toBeNull()
    expect(api.getHistoricalGameAward).not.toHaveBeenCalled()
    expect(panel.querySelector('input[type="password"], input[type="email"]')).toBeNull()
  })

  it('a chosen-season game says it does not count, and asks for no award', async () => {
    setUser({ sleeperUserId: '1', username: 'u', displayName: 'U', avatar: null })
    api.getHistoricalGame.mockResolvedValue(finished({ chosenSeason: true }))
    renderRoom()
    expect(await screen.findByText("Seasons you pick don't count toward achievements, your daily streak, or your best and average draft score.")).toBeInTheDocument()
    expect(api.getHistoricalGameAward).not.toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: 'Keep this result' })).toBeNull()
  })

  it('a signed-in free-play game still gets its award panel', async () => {
    setUser({ sleeperUserId: '1', username: 'u', displayName: 'U', avatar: null })
    api.getHistoricalGame.mockResolvedValue(finished())
    renderRoom()
    expect(await screen.findByRole('region', { name: 'What this game earned' })).toBeInTheDocument()
    expect(api.getHistoricalGameAward).toHaveBeenCalledWith(5)
  })
})
