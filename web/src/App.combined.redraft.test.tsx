import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './apiError'
import { lock } from './leaguesLock'
import * as copy from './redraftCopy'
import { invalidateRailLeagues } from './railLeague'
import { clearUser, setUser } from './user'

// Spec 074 CR-04: the combined build (VITE_SITE unset: every `npm run dev` and the rollback build) mounts the Redraft
// home and room at /history, but it has no /daily, /nba or /nfl routes. The shared pages must take their links from
// site.ts, so the combined site shows none of them, must not turn a stranger's game into a shared result (it was a
// not-found there before), and must not leave the Redraft title on the tab.

const api = vi.hoisted(() => ({
  getHistoricalDaily: vi.fn(),
  getYesterdayDaily: vi.fn(),
  getPublicSeasonList: vi.fn(),
  listHistoricalGames: vi.fn(),
  getHistoricalProfile: vi.fn(),
  claimGuestGames: vi.fn(),
  getHistoricalGame: vi.fn(),
  getPublicGame: vi.fn(),
  getDrafts: vi.fn(),
}))
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), ...api }))
vi.mock('./goTo', () => ({ goTo: vi.fn() }))
vi.mock('./pages/DraftPicker', () => ({ default: () => <div>league picker</div> }))

const user = { sleeperUserId: '1', username: 'popsharky', displayName: 'popsharky', avatar: null }

function renderAt(entry: string) {
  setUser(user)
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <App />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  lock()
  clearUser()
  localStorage.clear()
  invalidateRailLeagues()
  document.title = ''
  for (const f of Object.values(api)) f.mockReset()
  api.getDrafts.mockResolvedValue([])
  api.getHistoricalDaily.mockResolvedValue([])
  api.getYesterdayDaily.mockResolvedValue([])
  api.getPublicSeasonList.mockImplementation(async (s: string) => ({
    schema: 1,
    sport: s,
    seasons: [{ seasonStart: 2016, label: '2016-17', slug: '2016-17', sport: s }],
  }))
  api.listHistoricalGames.mockResolvedValue([])
  api.getHistoricalProfile.mockRejectedValue(new Error('none'))
  api.claimGuestGames.mockResolvedValue(null)
  api.getHistoricalGame.mockRejectedValue(new ApiError(404))
  api.getPublicGame.mockResolvedValue({ state: { id: 7, result: null, season: { label: '2016-17' } }, reducedUntil: null })
})
afterEach(() => {
  clearUser()
  vi.unstubAllEnvs()
})

describe('the combined build keeps its own routes (VITE_SITE unset)', () => {
  it('the Redraft home at /history links to no page the combined site does not have', async () => {
    renderAt('/history')
    expect(await screen.findByRole('heading', { level: 1, name: copy.homeH1 })).toBeInTheDocument()
    await waitFor(() => expect(api.getPublicSeasonList).toHaveBeenCalled())
    const main = document.querySelector('main') as HTMLElement
    const hrefs = [...main.querySelectorAll('a[href]')].map((a) => a.getAttribute('href') ?? '')
    const missing = hrefs.filter((h) => h === '/daily' || /^\/(nba|nfl)(\/|$)/.test(h))
    expect(missing).toEqual([])
    // What is left still works: the footer's Home goes to the combined site's own home for this page.
    expect(hrefs).toContain('/history')
    // No sport tile and no "About the daily game": both link to routes that are not here.
    expect(screen.queryByRole('link', { name: copy.dailyAbout })).toBeNull()
    expect(screen.queryByRole('link', { name: /Basketball/ })).toBeNull()
  })

  it('does not leave the Redraft title on the tab (the combined App owns the title)', async () => {
    renderAt('/history')
    await screen.findByRole('heading', { level: 1, name: copy.homeH1 })
    expect(document.title).not.toBe(copy.homeTitle)
    expect(document.title).toBe('Ball Knowers')
  })

  it('a signed-in stranger at /history/:id gets not-found, as before: the public route is never asked', async () => {
    renderAt('/history/7')
    await waitFor(() => expect(api.getHistoricalGame).toHaveBeenCalledWith(7))
    // The old behaviour: the app's not-found page (a failed owner load is a 404), and no shared-result page.
    await waitFor(() => expect(document.body.textContent).toMatch(/not found|no such|nothing/i))
    expect(api.getPublicGame).not.toHaveBeenCalled()
    expect(screen.queryByText(/Shared result/)).toBeNull()
    expect(screen.queryByRole('heading', { name: copy.sharedNoneHeading })).toBeNull()
  })
})

describe('the Redraft site still asks the public route for a game that is not yours', () => {
  it('timemachine: a stranger gets the public answer', async () => {
    vi.stubEnv('VITE_SITE', 'timemachine')
    renderAt('/game/7')
    await waitFor(() => expect(api.getPublicGame).toHaveBeenCalledWith(7))
  })
})
