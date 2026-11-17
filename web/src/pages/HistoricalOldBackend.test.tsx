import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearUser, setUser } from '../user'
import HistoricalRoom from './HistoricalRoom'
import HistoricalDraft from './HistoricalDraft'
import old062 from '../__fixtures__/historical-result-062.json'

/*
 * Spec 056 review B1: the web deploys BEFORE the backend, so for a while the new pages talk to the 053 backend.
 * These fixtures are 053-shaped on purpose: no `sport` on a game, a list row or a season row, no `dataNotes` /
 * `attribution` on the season view, no `rookiePick` / `totals` on cards. A missing field must read as basketball,
 * and the old backend's habit of ignoring ?sport= (it answers NFL with NBA rows) must never light up Football.
 */

const getHistoricalGame = vi.fn()
const listHistoricalGames = vi.fn()
const getPublicSeasonList = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getHistoricalGame: (...a: unknown[]) => getHistoricalGame(...a),
  listHistoricalGames: (...a: unknown[]) => listHistoricalGames(...a),
  getPublicSeasonList: (...a: unknown[]) => getPublicSeasonList(...a),
  getHistoricalDaily: () => Promise.reject(new Error('404')),
  getHistoricalProfile: () => Promise.reject(new Error('404')),
  claimGuestGames: () => Promise.resolve(null),
}))
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})

const oldCard = (id: number, name: string) => ({
  id, name, positions: ['SF'], team: 'CHI', age: 28, rank: id, positionalRank: id,
  prior: { seasonLabel: '1994-95', games: 79, perGame: { pts: 29.3 }, fantasyPoints: 3010.5, bestOfTwo: '1994-95' },
})
const oldState = {
  id: 5, status: 'DRAFTING',
  season: { label: '1995-96', countedStats: ['pts'], excludedStats: {}, statCoverage: { pts: 1 },
    rankingSource: 'PRIOR_SEASON_POINTS', rankingLabel: "ranked by last season's points", settingsSource: 'x' },
  teams: 2, rounds: 2, rosterPositions: ['PG', 'SG', 'BN'], userSlot: 1, myPicks: [1, 4], currentPickNo: 1,
  onTheClockSlot: 1, isUsersTurn: true, picks: [], available: [oldCard(1, 'Top Guy')], result: null,
}

beforeEach(() => setUser({ sleeperUserId: 'u1', username: 'u1', displayName: 'U One', avatar: null }))
afterEach(() => clearUser())

describe('new web against the 053 backend (deploy order: web first)', () => {
  it('the room renders a basketball game from an old-shape state', async () => {
    getHistoricalGame.mockResolvedValue(oldState)
    render(
      <MemoryRouter initialEntries={['/history/5']}>
        <Routes>
          <Route path="/history/:gameId" element={<HistoricalRoom />} />
        </Routes>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getAllByText(/Top Guy/).length).toBeGreaterThan(0))
    expect(screen.getByRole('button', { name: 'PG' })).toBeInTheDocument() // the NBA position filters
    expect(screen.queryByRole('button', { name: 'QB' })).not.toBeInTheDocument()
  })

  it('a finished game from the 062 backend (no format, playoffs, phase, reversalRound) renders as the old format', async () => {
    const slot = Number(Object.keys(old062.lineups)[0])
    getHistoricalGame.mockResolvedValue({
      ...oldState, status: 'COMPLETE', teams: 12, rounds: 14, userSlot: slot, isUsersTurn: false, onTheClockSlot: null,
      available: [], result: old062,
    })
    render(
      <MemoryRouter initialEntries={['/history/5']}>
        <Routes>
          <Route path="/history/:gameId" element={<HistoricalRoom />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText('Played under the old format: no playoffs, finish is the standings rank.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Final standings' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Playoff bracket' })).not.toBeInTheDocument()
  })

  it('the start page lists old-shape past games and offers no Football', async () => {
    // Spec 074 (T041, rewritten): the home page's season list is the PUBLIC endpoint, which a 053 backend does not have
    // (404). The deploy order for this feature is backend first (DEPLOY.md), so this is the window where it is not:
    // the page must still list the old-shape games, name no Football tile or tab, and show no failure for the missing list.
    getPublicSeasonList.mockReset().mockRejectedValue(new Error('404'))
    listHistoricalGames.mockResolvedValue([
      { id: 1, seasonLabel: '1995-96', status: 'COMPLETE', userSlot: 3, finish: 2, createdAt: '2026-11-01T12:00:00Z' },
      { id: 2, seasonLabel: '1996-97', status: 'DRAFTING', userSlot: 3, finish: 0, createdAt: '2026-11-02T12:00:00Z' },
    ])
    render(
      <MemoryRouter initialEntries={['/history']}>
        <Routes>
          <Route path="/history" element={<HistoricalDraft />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText('1995-96')).toBeTruthy()
    expect(screen.getAllByText('Basketball').length).toBe(2) // both rows read as NBA
    // an old row carries no formatVersion: its number is a standings rank, and says so
    expect(screen.getByText('Finished 2nd (old format)')).toBeInTheDocument()
    // Both requests carry an explicit sport ...
    await waitFor(() => expect(getPublicSeasonList).toHaveBeenCalledTimes(2))
    expect(getPublicSeasonList).toHaveBeenCalledWith('NBA')
    expect(getPublicSeasonList).toHaveBeenCalledWith('NFL')
    // ... and nothing came back, so Football is not offered, and no tile is: nothing is invented to fill the gap.
    expect(screen.queryByRole('button', { name: 'Football' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Football/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Sport' })).not.toBeInTheDocument()
    // CR-09: the old page kept "Draft a random year" for a signed-in player when it could not tell which seasons exist
    // ("we can't tell, so the old behaviour stands"). It still does, and the failed list is said, with a way to retry.
    expect(await screen.findByRole('button', { name: 'Draft a random year' })).toBeInTheDocument()
    expect(screen.getByText(/The season list didn't load\./)).toBeInTheDocument()
    // (this default site has no sport index pages, so there is no "Pick a season" section to hold it, V-08h)
    expect(within(screen.getByText(/The season list didn't load\./).closest('div')!).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
