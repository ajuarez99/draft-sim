import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalGameSummary, HistoricalSeasonSummary } from '../api'
import HistoricalDraft from './HistoricalDraft'

const listHistoricalGames = vi.fn()
const historicalSeasons = vi.fn()
const startHistoricalGame = vi.fn()
const abandonHistoricalGame = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  listHistoricalGames: (...a: unknown[]) => listHistoricalGames(...a),
  historicalSeasons: (...a: unknown[]) => historicalSeasons(...a),
  startHistoricalGame: (...a: unknown[]) => startHistoricalGame(...a),
  abandonHistoricalGame: (...a: unknown[]) => abandonHistoricalGame(...a),
}))

const season: HistoricalSeasonSummary = {
  sport: 'NBA',
  seasonStart: 1995,
  label: '1995-96',
  source: 'KAGGLE',
  countedStats: ['pts'],
  excludedStats: {},
  statCoverage: { pts: 1 },
  rankingSource: 'PRIOR_SEASON_POINTS',
  settingsSource: 'Settings: the Legacy Ball Knowers league.',
  dataNotes: [],
}

const g = (id: number, status: HistoricalGameSummary['status'], finish: number | null): HistoricalGameSummary => ({
  id,
  sport: 'NBA',
  seasonLabel: `19${70 + id}-${71 + id}`,
  status,
  userSlot: 3,
  finish,
  createdAt: '2026-11-01T12:00:00Z',
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
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  listHistoricalGames.mockReset()
  historicalSeasons.mockReset().mockResolvedValue([season])
  startHistoricalGame.mockReset()
  abandonHistoricalGame.mockReset()
})

describe('HistoricalDraft', () => {
  it('shows the empty state and the settings source line', async () => {
    listHistoricalGames.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText(/No games yet/)).toBeInTheDocument()
    expect(await screen.findByText('Settings: the Legacy Ball Knowers league.')).toBeInTheDocument()
  })

  it('lists Resume for drafting, View with the finish for complete, hides abandoned', async () => {
    listHistoricalGames.mockResolvedValue([g(1, 'DRAFTING', null), g(2, 'COMPLETE', 3), g(3, 'ABANDONED', null)])
    renderPage()
    expect(await screen.findByText('1971-72')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Resume' })).toHaveAttribute('href', '/history/1')
    expect(screen.getByRole('link', { name: 'View' })).toHaveAttribute('href', '/history/2')
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
    expect(historicalSeasons).toHaveBeenCalledWith('NBA')
  })

  it('football: fetches football seasons, sends NFL, and shows football copy with no NBA wording or ADP', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 43 })
    historicalSeasons.mockImplementation((sp: string) =>
      Promise.resolve(
        sp === 'NFL'
          ? [2000, 2007, 2025].map((y) => ({
              ...season, sport: 'NFL', seasonStart: y, label: String(y), source: 'NFLVERSE',
              settingsSource: 'Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, 2 FLEX + 7 bench · full PPR',
            }))
          : [season],
      ),
    )
    const { container } = renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Football' }))
    expect(screen.getByRole('button', { name: 'Football' })).toHaveAttribute('aria-pressed', 'true')
    expect(await screen.findByText(/Hand-set 12-team, 15-round roster/)).toBeInTheDocument()
    expect(historicalSeasons).toHaveBeenLastCalledWith('NFL')
    const text = container.textContent ?? ''
    expect(text).toMatch(/real NFL season, 2000 to 2025/)   // the range comes from the seasons response
    expect(text).toMatch(/no kickers or defenses/)
    expect(text).not.toMatch(/real NBA season/)
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
    historicalSeasons.mockImplementation((sp: string) =>
      Promise.resolve(sp === 'NFL' ? [{ ...season, sport: 'NFL', seasonStart: 2001, label: '2001', source: 'NFLVERSE' }] : []),
    )
    const { container } = renderPage()
    const start = await startButton()
    expect(screen.queryByRole('group', { name: 'Sport' })).not.toBeInTheDocument()
    expect(container.textContent ?? '').toMatch(/real NFL season/)
    await userEvent.click(start)
    expect(startHistoricalGame).toHaveBeenCalledWith('NFL')
  })

  it('disables start and says so when no season of either sport is loaded', async () => {
    listHistoricalGames.mockResolvedValue([])
    historicalSeasons.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText(/No seasons are loaded yet/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Draft a random year' })).toBeDisabled()
    expect(screen.queryByRole('group', { name: 'Sport' })).not.toBeInTheDocument()
  })

  it('keeps basketball startable when a seasons request fails (cannot tell what exists)', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 45 })
    historicalSeasons.mockRejectedValue(new Error('network'))
    renderPage()
    await userEvent.click(await startButton())
    expect(startHistoricalGame).toHaveBeenCalledWith('NBA')
    expect(screen.queryByText(/No seasons are loaded yet/)).not.toBeInTheDocument()
  })
})
