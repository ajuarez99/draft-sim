import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalGameSummary, HistoricalSport, SeasonList } from '../api'
import { clearUser, setUser } from '../user'
import { __resetRedraftDataForTests } from '../redraftData'
import HistoricalDraft from './HistoricalDraft'

// Spec 074 T038 (PINNED TESTS REWRITTEN, T041): this page used to be the signed-in-only home, with a per-sport blurb read
// from the private seasons endpoint and a "Draft a random year" button that also served as the page's one action. It is
// now one home for a guest and a signed-in visitor: the season tiles come from the PUBLIC season list, the per-sport blurb
// and settings line moved to the season pages, and the random-year control is for a signed-in visitor only. These tests are
// signed in, because the games list, Abandon and the random-year control are what they pin.
const listHistoricalGames = vi.fn()
const getPublicSeasonList = vi.fn()
const startHistoricalGame = vi.fn()
const abandonHistoricalGame = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  listHistoricalGames: (...a: unknown[]) => listHistoricalGames(...a),
  getPublicSeasonList: (...a: unknown[]) => getPublicSeasonList(...a),
  startHistoricalGame: (...a: unknown[]) => startHistoricalGame(...a),
  abandonHistoricalGame: (...a: unknown[]) => abandonHistoricalGame(...a),
  getHistoricalDaily: () => Promise.reject(new Error('no daily in this test')),
  getHistoricalProfile: () => Promise.reject(new Error('no profile in this test')),
  claimGuestGames: () => Promise.resolve(null),
}))

const listOf = (sport: HistoricalSport, labels: string[]): SeasonList => ({
  schema: 1,
  sport,
  seasons: labels.map((label) => ({ sport, seasonStart: Number(label.slice(0, 4)), label, slug: label })),
})
const NBA_LIST = listOf('NBA', ['1995-96', '1996-97'])
const NFL_LIST = listOf('NFL', ['2000', '2007', '2025'])

const g = (id: number, status: HistoricalGameSummary['status'], finish: number | null): HistoricalGameSummary => ({
  id,
  sport: 'NBA',
  seasonLabel: `19${70 + id}-${71 + id}`,
  status,
  userSlot: 3,
  finish,
  createdAt: '2026-11-01T12:00:00Z',
  chosenSeason: false,
})

/** The start button stays disabled until both seasons requests have answered. */
async function startButton() {
  const b = await screen.findByRole('button', { name: 'Draft a random year' })
  await waitFor(() => expect(b).toBeEnabled())
  return b
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/history']}>
      <Routes>
        <Route path="/history" element={<HistoricalDraft />} />
        <Route path="/history/:gameId" element={<div>room page</div>} />
        <Route path="/game/:gameId" element={<div>room page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  // The home's sport tiles link to /nba and /nfl, routes of the Redraft site only (CR-04): this file tests that site.
  vi.stubEnv('VITE_SITE', 'timemachine')
  __resetRedraftDataForTests()
  setUser({ sleeperUserId: 'u1', username: 'u1', displayName: 'U One', avatar: null })
  listHistoricalGames.mockReset()
  getPublicSeasonList
    .mockReset()
    .mockImplementation((sp: HistoricalSport) => Promise.resolve(sp === 'NFL' ? { ...NFL_LIST, seasons: [] } : NBA_LIST))
  startHistoricalGame.mockReset()
  abandonHistoricalGame.mockReset()
})
afterEach(() => {
  clearUser()
  vi.unstubAllEnvs()
})

describe('HistoricalDraft', () => {
  it('shows the empty state, and the season tiles with their range and count from the public list', async () => {
    listHistoricalGames.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText(/No games yet/)).toBeInTheDocument()
    const tile = (await screen.findByRole('link', { name: /Basketball/ })).closest('a')!
    expect(tile).toHaveAttribute('href', '/nba')
    expect(tile).toHaveTextContent('1995-96 to 1996-97')
    expect(tile).toHaveTextContent('2 seasons to pick from')
    // No football seasons on offer: no football tile (a tile that leads nowhere is a trap).
    expect(screen.queryByRole('link', { name: /Football/ })).not.toBeInTheDocument()
  })

  it('V-08h: where no sport index page exists (the combined build) there is no "Pick a season" heading with nothing under it', async () => {
    vi.stubEnv('VITE_SITE', '')
    listHistoricalGames.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText(/No games yet/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Pick a season' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Pick a season' })).not.toBeInTheDocument()
  })

  it('lists Resume for drafting, View with the finish for complete, hides abandoned', async () => {
    listHistoricalGames.mockResolvedValue([g(1, 'DRAFTING', null), g(2, 'COMPLETE', 3), g(3, 'ABANDONED', null)])
    renderPage()
    expect(await screen.findByText('1971-72')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Resume' })).toHaveAttribute('href', '/game/1')
    expect(screen.getByRole('link', { name: 'View' })).toHaveAttribute('href', '/game/2')
    expect(screen.getByText('Finished 3rd (old format)')).toBeInTheDocument()
    expect(screen.queryByText('1973-74')).not.toBeInTheDocument()
  })

  it('words a finished game by its outcome, and never prints an old rank and a playoff finish as the same thing', async () => {
    const done = (id: number, finish: number, formatVersion: number | null, outcome: HistoricalGameSummary['outcome']) => ({
      ...g(id, 'COMPLETE', finish),
      formatVersion,
      outcome,
    })
    listHistoricalGames.mockResolvedValue([
      done(1, 1, 2, 'CHAMPION'),
      done(2, 2, 2, 'LOST_FINAL'),
      done(3, 8, 2, 'MISSED_PLAYOFFS'),
      done(4, 3, 2, 'NO_PLAYOFFS'),
      done(5, 3, null, null),
    ])
    renderPage()
    expect(await screen.findByText('Champion')).toBeInTheDocument()
    expect(screen.getByText('Lost the final')).toBeInTheDocument()
    expect(screen.getByText('Missed the playoffs (8th)')).toBeInTheDocument()
    expect(screen.getByText('Finished 3rd (no playoffs)')).toBeInTheDocument()
    expect(screen.getByText('Finished 3rd (old format)')).toBeInTheDocument()
  })

  it('abandons a drafting game and removes it from the list', async () => {
    listHistoricalGames.mockResolvedValue([g(1, 'DRAFTING', null)])
    abandonHistoricalGame.mockResolvedValue(undefined)
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Abandon' }))
    await waitFor(() => expect(screen.queryByText('1971-72')).not.toBeInTheDocument())
    expect(abandonHistoricalGame).toHaveBeenCalledWith(1)
  })

  it("removes the abandoned game's stored queue, and only that game's (CR-07)", async () => {
    localStorage.setItem('bk.tm.queue.1', JSON.stringify([5]))
    localStorage.setItem('bk.tm.queue.2', JSON.stringify([6]))
    listHistoricalGames.mockResolvedValue([g(1, 'DRAFTING', null)])
    abandonHistoricalGame.mockResolvedValue(undefined)
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Abandon' }))
    await waitFor(() => expect(localStorage.getItem('bk.tm.queue.1')).toBeNull())
    expect(localStorage.getItem('bk.tm.queue.2')).not.toBeNull()
    localStorage.clear()
  })

  it('starts a game and goes to its room', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 42 })
    renderPage()
    await userEvent.click(await startButton())
    expect(await screen.findByText('room page')).toBeInTheDocument()
  })

  it('sends the basketball sport explicitly when starting without touching the choice', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 42 })
    renderPage()
    await userEvent.click(await startButton())
    expect(startHistoricalGame).toHaveBeenCalledWith('NBA')
    expect(getPublicSeasonList).toHaveBeenCalledWith('NBA')
  })

  it('football: fetches football seasons, sends NFL, and shows football copy with no NBA wording or ADP', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 43 })
    getPublicSeasonList.mockImplementation((sp: HistoricalSport) => Promise.resolve(sp === 'NFL' ? NFL_LIST : NBA_LIST))
    const { container } = renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Football' }))
    expect(screen.getByRole('button', { name: 'Football' })).toHaveAttribute('aria-pressed', 'true')
    expect(getPublicSeasonList).toHaveBeenCalledWith('NFL')
    // The football tile's range comes from the list the server answered with, never from a literal.
    expect(await screen.findByText('2000 to 2025')).toBeInTheDocument()
    const text = container.textContent ?? ''
    expect(text).not.toMatch(/1970-71/)
    expect(text).not.toMatch(/ADP/i)
    await userEvent.click(await startButton())
    expect(startHistoricalGame).toHaveBeenCalledWith('NFL')
    expect(await screen.findByText('room page')).toBeInTheDocument()
  })

  it('tags each past game with its sport', async () => {
    listHistoricalGames.mockResolvedValue([
      g(1, 'DRAFTING', null),
      { ...g(2, 'COMPLETE', 3), sport: 'NFL', seasonLabel: '2007' },
    ])
    renderPage()
    const nfl = (await screen.findByText('2007')).closest('li')!
    const nba = screen.getByText('1971-72').closest('li')!
    expect(within(nfl).getByText('Football')).toBeInTheDocument()
    expect(within(nfl).queryByText('Basketball')).not.toBeInTheDocument()
    expect(within(nba).getByText('Basketball')).toBeInTheDocument()
  })

  it('offers only football when only football seasons exist (prod 2026-11-08: no NBA import yet)', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 44 })
    getPublicSeasonList.mockImplementation((sp: HistoricalSport) =>
      Promise.resolve(sp === 'NFL' ? listOf('NFL', ['2001']) : { ...NBA_LIST, seasons: [] }),
    )
    renderPage()
    const start = await startButton()
    expect(screen.queryByRole('group', { name: 'Sport' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Basketball/ })).not.toBeInTheDocument()
    await userEvent.click(start)
    expect(startHistoricalGame).toHaveBeenCalledWith('NFL')
  })

  it('offers no random year and no tile when no season of either sport is on offer', async () => {
    listHistoricalGames.mockResolvedValue([])
    getPublicSeasonList.mockImplementation((sp: HistoricalSport) => Promise.resolve({ schema: 1, sport: sp, seasons: [] }))
    renderPage()
    expect(await screen.findByText(/No games yet/)).toBeInTheDocument()
    await waitFor(() => expect(getPublicSeasonList).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: 'Draft a random year' })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Sport' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Basketball|Football/ })).not.toBeInTheDocument()
  })

  it('a failed season list shows no tile and no random-year control for that sport: nothing is offered that cannot be started', async () => {
    // (Was: "keeps basketball startable when a seasons request fails". The control now needs a season list to offer from.)
    listHistoricalGames.mockResolvedValue([])
    getPublicSeasonList.mockImplementation((sp: HistoricalSport) =>
      sp === 'NBA' ? Promise.reject(new Error('network')) : Promise.resolve(NFL_LIST),
    )
    renderPage()
    expect(await screen.findByRole('link', { name: /Football/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Basketball/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Sport' })).not.toBeInTheDocument()
    // CR-09: the failure is said, with a way to try again, instead of the tile just not being there.
    expect(await screen.findByText(/The season list didn't load\./)).toBeInTheDocument()
    const pick = screen.getByRole('region', { name: 'Pick a season' })
    expect(within(pick).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('Try again asks for the list again and the tile appears (CR-09)', async () => {
    listHistoricalGames.mockResolvedValue([])
    let nbaCalls = 0
    getPublicSeasonList.mockImplementation((sp: HistoricalSport) => {
      if (sp === 'NFL') return Promise.resolve({ ...NFL_LIST, seasons: [] })
      nbaCalls += 1
      return nbaCalls === 1 ? Promise.reject(new Error('network')) : Promise.resolve(NBA_LIST)
    })
    renderPage()
    const pick = screen.getByRole('region', { name: 'Pick a season' })
    await userEvent.click(await within(pick).findByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: /Basketball/ })).toBeInTheDocument()
    expect(screen.queryByText(/The season list didn't load\./)).toBeNull()
  })

  it('a signed-in player whose season list failed can still draft a random year (the server picks the season)', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 45 })
    getPublicSeasonList.mockRejectedValue(new Error('network'))
    renderPage()
    await screen.findByText(/The season list didn't load\./)
    await userEvent.click(screen.getByRole('button', { name: 'Draft a random year' }))
    expect(startHistoricalGame).toHaveBeenCalledWith('NBA')
  })

  // C4: the bar's "Sign in" link goes to /#sign-in; the panel's heading takes focus (not the input: a phone would open its keyboard).
  it('/#sign-in scrolls to the sign-in panel and focuses its heading, not its input', async () => {
    clearUser()
    render(
      <MemoryRouter initialEntries={['/history#sign-in']}>
        <Routes>
          <Route path="/history" element={<HistoricalDraft />} />
        </Routes>
      </MemoryRouter>,
    )
    const heading = await screen.findByRole('heading', { name: 'Keep your games' })
    await waitFor(() => expect(heading).toHaveFocus())
    expect(screen.getByLabelText(/Your Sleeper username/)).not.toHaveFocus()
  })

  it('does not offer "Draft a random year" to a guest (the server refuses it: a button that always fails is a trap)', async () => {
    clearUser()
    renderPage()
    expect(await screen.findByRole('link', { name: /Basketball/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Draft a random year' })).not.toBeInTheDocument()
    // A guest with no games in this browser is not asked about any (no id, no request).
    expect(listHistoricalGames).not.toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: 'Your games' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Keep your games' })).toBeInTheDocument()
  })
})
