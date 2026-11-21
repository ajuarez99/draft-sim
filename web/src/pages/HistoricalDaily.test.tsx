import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalDaily, HistoricalGameSummary } from '../api'
import { __resetRedraftDataForTests } from '../redraftData'
import { clearUser, setUser } from '../user'
import HistoricalDraft from './HistoricalDraft'

// Spec 071: the game of the day and the landing stats on the Redraft home page. Spec 074: signed in here (the games list, the
// streak and Abandon are a signed-in visitor's; HistoricalDraft.test.tsx covers the guest's home).

const listHistoricalGames = vi.fn()
const getPublicSeasonList = vi.fn()
const startHistoricalGame = vi.fn()
const abandonHistoricalGame = vi.fn()
const getHistoricalDaily = vi.fn()
const startHistoricalDaily = vi.fn()
const getHistoricalProfile = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  listHistoricalGames: (...a: unknown[]) => listHistoricalGames(...a),
  getPublicSeasonList: (...a: unknown[]) => getPublicSeasonList(...a),
  claimGuestGames: () => Promise.resolve(null),
  startHistoricalGame: (...a: unknown[]) => startHistoricalGame(...a),
  abandonHistoricalGame: (...a: unknown[]) => abandonHistoricalGame(...a),
  getHistoricalDaily: (...a: unknown[]) => getHistoricalDaily(...a),
  startHistoricalDaily: (...a: unknown[]) => startHistoricalDaily(...a),
  getHistoricalProfile: (...a: unknown[]) => getHistoricalProfile(...a),
}))

const SEASONS = { schema: 1, sport: 'NBA', seasons: [{ sport: 'NBA', seasonStart: 1995, label: '1995-96', slug: '1995-96' }] }

// Far in the future, so the countdown is always there whatever day the test runs.
const NEXT = '2999-01-02T05:00:00Z'
const daily = (over: Partial<HistoricalDaily> = {}): HistoricalDaily => ({
  sport: 'NBA',
  date: '2999-01-01',
  seasonLabel: '1988-89',
  gameId: null,
  gameStatus: null,
  draftScore: null,
  streak: 0,
  bestStreak: 0,
  nextAt: NEXT,
  ...over,
})

const game = (id: number, over: Partial<HistoricalGameSummary> = {}): HistoricalGameSummary => ({
  id,
  sport: 'NBA',
  seasonLabel: `19${70 + id}-${71 + id}`,
  status: 'COMPLETE',
  userSlot: 3,
  finish: 2,
  formatVersion: 2,
  outcome: 'LOST_FINAL',
  createdAt: '2026-11-01T12:00:00Z',
  chosenSeason: false,
  ...over,
})

beforeEach(() => {
  __resetRedraftDataForTests()
  setUser({ sleeperUserId: 'u1', username: 'u1', displayName: 'U One', avatar: null })
  listHistoricalGames.mockReset().mockResolvedValue([])
  getPublicSeasonList.mockReset().mockResolvedValue(SEASONS)
  startHistoricalGame.mockReset()
  abandonHistoricalGame.mockReset()
  getHistoricalDaily.mockReset().mockResolvedValue([])
  startHistoricalDaily.mockReset()
  getHistoricalProfile.mockReset().mockRejectedValue(new Error('no profile'))
})
afterEach(() => clearUser())

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/history']}>
      <Routes>
        <Route path="/history" element={<HistoricalDraft />} />
        <Route path="/history/:gameId" element={<div>room page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

const dailySection = () => screen.findByRole('region', { name: 'Game of the day' })

describe('Time Machine landing: the game of the day', () => {
  it('shows one card per sport with its season, and says what is and is not shared', async () => {
    getHistoricalDaily.mockResolvedValue([daily(), daily({ sport: 'NFL', seasonLabel: '2013' })])
    renderPage()
    const section = await dailySection()
    expect(within(section).getByText('1988-89')).toBeInTheDocument()
    expect(within(section).getByText('2013')).toBeInTheDocument()
    expect(within(section).getAllByText('Not played yet')).toHaveLength(2)
    // Honest about comparability: the same season, not the same draft.
    expect(within(section).getByText(/Jan 1: the same season for everyone, from a random draft slot\./)).toBeInTheDocument()
    expect(within(section).getByText(/Next one in \d+h \d+m\./)).toBeInTheDocument()
    expect(within(section).queryByText(/streak/)).toBeNull()
  })

  it("starts that sport's daily game, not a random year, and goes to its room", async () => {
    getHistoricalDaily.mockResolvedValue([daily(), daily({ sport: 'NFL', seasonLabel: '2013' })])
    startHistoricalDaily.mockResolvedValue({ id: 77 })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: "Play today's football game" }))
    expect(startHistoricalDaily).toHaveBeenCalledWith('NFL')
    expect(startHistoricalGame).not.toHaveBeenCalled()
    expect(await screen.findByText('room page')).toBeInTheDocument()
  })

  it('offers Resume for a daily game in progress and View with the score for a finished one', async () => {
    getHistoricalDaily.mockResolvedValue([
      daily({ gameId: 40, gameStatus: 'DRAFTING', streak: 3, bestStreak: 5 }),
      daily({ sport: 'NFL', seasonLabel: '2013', gameId: 41, gameStatus: 'COMPLETE', draftScore: 82, streak: 1, bestStreak: 1 }),
    ])
    renderPage()
    const section = await dailySection()
    expect(within(section).getByRole('link', { name: "Resume today's basketball game" })).toHaveAttribute('href', '/history/40')
    expect(within(section).getByRole('link', { name: "View today's football game" })).toHaveAttribute('href', '/history/41')
    expect(within(section).getByText('Done. Draft score 82')).toBeInTheDocument()
    expect(within(section).getByText('3-day streak')).toBeInTheDocument()
    expect(within(section).getByText('1-day streak')).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: /Play today/ })).toBeNull()
  })

  it('says the games did not load, with Try again, when the request fails (spec 074: it used to show nothing)', async () => {
    // PINNED RULE REWRITTEN (T041): spec 071 hid the whole section on a failed request (an old backend has no such route).
    // The Redraft home is a public page with real content under it, so a missing game of the day is said, and can be retried.
    getHistoricalDaily.mockRejectedValueOnce(new Error('404')).mockResolvedValue([daily()])
    renderPage()
    expect(await screen.findByText("Today's games didn't load.")).toBeInTheDocument()
    expect(screen.queryByText(/404/)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('1988-89')).toBeInTheDocument()
    expect(getHistoricalDaily).toHaveBeenCalledTimes(2)
  })

  it('shows a guest the sign-in line for the streak and no streak pill', async () => {
    clearUser()
    getHistoricalDaily.mockResolvedValue([daily({ streak: 4 })])
    renderPage()
    const section = await dailySection()
    await within(section).findByText('1988-89')
    expect(within(section).getByText(/Sign in to keep a daily streak\./)).toBeInTheDocument()
    expect(within(section).queryByText(/streak$/)).toBeNull()
  })

  it('shows the failure when starting the daily game fails, and keeps the page usable', async () => {
    getHistoricalDaily.mockResolvedValue([daily()])
    startHistoricalDaily.mockRejectedValue(new Error('the server said no'))
    renderPage()
    const play = await screen.findByRole('button', { name: "Play today's basketball game" })
    await userEvent.click(play)
    expect(await screen.findByText(/the server said no/)).toBeInTheDocument()
    await waitFor(() => expect(play).toBeEnabled())
  })
})

describe('Time Machine landing: your numbers and the games list', () => {
  it('prints finished, best and average from the list scores', async () => {
    listHistoricalGames.mockResolvedValue([game(1, { draftScore: 79 }), game(2, { draftScore: 64 }), game(3, { draftScore: 91 })])
    renderPage()
    const stats = await screen.findByLabelText('Your numbers')
    const value = (label: string) => within(stats).getByText(label).nextElementSibling?.textContent
    expect(value('Finished')).toBe('3')
    expect(value('Best draft score')).toBe('91')
    expect(value('Average draft score')).toBe('78')
    expect(screen.queryByText(/were saved without one/)).toBeNull()
  })

  it('says how many games the scores cover when some finished games have none', async () => {
    listHistoricalGames.mockResolvedValue([game(1, { draftScore: 80 }), game(2, { draftScore: null }), game(3, { status: 'DRAFTING', finish: null })])
    renderPage()
    const stats = await screen.findByLabelText('Your numbers')
    expect(within(stats).getByText('Finished').nextElementSibling?.textContent).toBe('2')
    expect(screen.getByText('The scores cover 1 of your 2 finished games; older ones were saved without one.')).toBeInTheDocument()
  })

  it('shows no numbers before a game is finished', async () => {
    listHistoricalGames.mockResolvedValue([game(1, { status: 'DRAFTING', finish: null })])
    renderPage()
    await screen.findByRole('link', { name: 'Resume' })
    expect(screen.queryByLabelText('Your numbers')).toBeNull()
  })

  it('marks a daily game in the list with its day and score, and offers no Abandon for one in progress', async () => {
    listHistoricalGames.mockResolvedValue([
      game(1, { draftScore: 88, dailyDate: '2026-11-14' }),
      game(2, { status: 'DRAFTING', finish: null, dailyDate: '2026-11-15' }),
      game(3, { status: 'DRAFTING', finish: null }),
      game(4, { draftScore: 70, dailyDate: '2026-11-21', dailyNumber: 8 }),
    ])
    renderPage()
    const list = (await screen.findByRole('region', { name: 'Your games' })).querySelector('.tm-games') as HTMLElement
    const rows = within(list).getAllByRole('listitem')
    expect(within(rows[0]).getByText('Daily · Nov 14')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Score 88')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Daily · Nov 15')).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button', { name: 'Abandon' })).toBeNull()
    expect(within(rows[1]).getByRole('link', { name: 'Resume' })).toBeInTheDocument()
    expect(within(rows[2]).getByRole('button', { name: 'Abandon' })).toBeInTheDocument()
    // spec 078: a game the server numbered says so
    expect(within(rows[3]).getByText('Daily #8 · Nov 21')).toBeInTheDocument()
  })
})
