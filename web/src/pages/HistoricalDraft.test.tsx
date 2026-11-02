import { render, screen, waitFor } from '@testing-library/react'
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
  seasonStart: 1995,
  label: '1995-96',
  source: 'KAGGLE',
  countedStats: ['pts'],
  excludedStats: {},
  statCoverage: { pts: 1 },
  rankingSource: 'PRIOR_SEASON_POINTS',
  settingsSource: 'Settings: the Legacy Ball Knowers league.',
}

const g = (id: number, status: HistoricalGameSummary['status'], finish: number | null): HistoricalGameSummary => ({
  id,
  seasonLabel: `19${70 + id}-${71 + id}`,
  status,
  userSlot: 3,
  finish,
  createdAt: '2026-11-01T12:00:00Z',
})

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
    expect(screen.getByText('Finished 3rd')).toBeInTheDocument()
    expect(screen.queryByText('1973-74')).not.toBeInTheDocument()
  })

  it('abandons a drafting game and removes it from the list', async () => {
    listHistoricalGames.mockResolvedValue([g(1, 'DRAFTING', null)])
    abandonHistoricalGame.mockResolvedValue(undefined)
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Abandon' }))
    await waitFor(() => expect(screen.queryByText('1971-72')).not.toBeInTheDocument())
    expect(abandonHistoricalGame).toHaveBeenCalledWith(1)
  })

  it('starts a game and goes to its room', async () => {
    listHistoricalGames.mockResolvedValue([])
    startHistoricalGame.mockResolvedValue({ id: 42 })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Draft a random year' }))
    expect(await screen.findByText('room page')).toBeInTheDocument()
  })
})
