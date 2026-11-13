import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalProfile } from '../api'
import HistoricalDraft from './HistoricalDraft'

const listHistoricalGames = vi.fn()
const historicalSeasons = vi.fn()
const getHistoricalProfile = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  listHistoricalGames: (...a: unknown[]) => listHistoricalGames(...a),
  historicalSeasons: (...a: unknown[]) => historicalSeasons(...a),
  getHistoricalProfile: (...a: unknown[]) => getHistoricalProfile(...a),
}))

beforeEach(() => {
  listHistoricalGames.mockReset()
  historicalSeasons.mockReset()
  getHistoricalProfile.mockReset()
  listHistoricalGames.mockResolvedValue([])
  historicalSeasons.mockResolvedValue([])
})

const profile: HistoricalProfile = {
  xp: 340, level: 3, levelName: 'Rookie', levelStartXp: 300, nextLevelXp: 600, seenLevel: 3,
  totals: { games: 5, gamesFromSavedResults: 2, weeklyWins: 41, playoffWins: 3, titles: 1 },
  settleFailures: 1,
  badges: [
    { code: 'TITLE', name: 'Champion', rule: 'Win a title.', xp: 100, source: 'STORED', unlocked: true,
      unlockedAt: '2026-11-13T12:00:00Z', gameId: 9, detail: null, seen: true },
    { code: 'LATE_GEM', name: 'Late gem', rule: 'Find a top player late.', xp: 40, source: 'NEW_GAMES', unlocked: false,
      unlockedAt: null, gameId: null, detail: null, seen: false },
  ],
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/history']}>
      <Routes>
        <Route path="/history" element={<HistoricalDraft />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('HistoricalDraft: level card and trophy case (spec 064)', () => {
  it('renders the level, the sentence with the same numbers as the bar, totals and the trophy case', async () => {
    getHistoricalProfile.mockResolvedValue(profile)
    renderPage()
    const card = await screen.findByRole('region', { name: 'Your level' })
    expect(within(card).getByText('Rookie')).toBeInTheDocument()
    expect(within(card).getByText('40 of 300 XP to level 4')).toBeInTheDocument()
    const bar = within(card).getByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '40')
    expect(bar).toHaveAttribute('aria-valuemax', '300')
    expect(within(card).getByText('41')).toBeInTheDocument()
    expect(within(card).getByText('2 games counted from saved results')).toBeInTheDocument()
    expect(within(card).getByText('1 game could not be counted')).toBeInTheDocument()

    const trophies = screen.getByRole('region', { name: 'Trophy case' })
    const earned = within(trophies).getByText('Champion').closest('li') as HTMLElement
    expect(within(earned).getByText('Earned')).toBeInTheDocument()
    expect(within(earned).getByRole('link', { name: 'See the game' })).toHaveAttribute('href', '/history/9')
    const locked = within(trophies).getByText('Late gem').closest('li') as HTMLElement
    expect(within(locked).getByText('Locked')).toBeInTheDocument()
    expect(within(locked).getByText('Find a top player late.')).toBeInTheDocument()
    expect(within(locked).getByText('Only from games finished after achievements were added.')).toBeInTheDocument()
  })

  it('renders neither the card nor the trophy case when the profile request fails', async () => {
    getHistoricalProfile.mockRejectedValue(new Error('boom'))
    renderPage()
    await screen.findByRole('region', { name: 'Your games' })
    await waitFor(() => expect(getHistoricalProfile).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: 'Your level' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Trophy case' })).not.toBeInTheDocument()
  })
})

describe('achievement styles', () => {
  it('has a non-trivial stylesheet that styles toasts and reduces motion', async () => {
    // @ts-expect-error node:fs has no types here
    const { readFileSync } = (await import('node:fs')) as { readFileSync: (p: string, enc: string) => string }
    const css = readFileSync('src/styles.css', 'utf8')
    expect(css.length).toBeGreaterThan(10000)
    expect(css).toMatch(/\.ach-toasts\s*\{[^}]*z-index:\s*52/)
    expect(css).toMatch(/prefers-reduced-motion[\s\S]*\.ach-toast\s*\{\s*animation:\s*none/)
  })
})
