import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import fixtureJson from './__fixtures__/redraft-seasons.json'
import { ApiError } from './apiError'
import type { HistoricalGameSummary, RedraftSeasonData } from './api'
import DailyPage from './pages/DailyPage'
import HistoricalDraft from './pages/HistoricalDraft'
import SeasonPage from './pages/SeasonPage'
import SportIndex from './pages/SportIndex'
import { GUEST_KEY, __resetGuestForTests } from './guest'
import RedraftNotFound from './components/RedraftNotFound'
import { pageHeadTags, pageParts } from './landingHtml'
import * as copy from './redraftCopy'
import { __resetRedraftDataForTests } from './redraftData'
import { indexablePages } from './redraftPages'
import { makeConfig } from './site'
import { clearUser, setUser } from './user'

// Spec 074 T035-T037, T041: the season page, the sport index and the daily page, mounted. And the two cross-checks that keep
// the static file and the mounted page one page: the same words (parity) and the same title (PR-08).

const api = vi.hoisted(() => ({
  getPublicSeasonList: vi.fn(),
  getPublicSeasonPage: vi.fn(),
  getYesterdayDaily: vi.fn(),
  getHistoricalDaily: vi.fn(),
  listHistoricalGames: vi.fn(),
  startChosenSeasonGame: vi.fn(),
  startHistoricalDaily: vi.fn(),
  getHistoricalProfile: vi.fn(),
  claimGuestGames: vi.fn(),
}))
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), ...api }))

const data = fixtureJson as unknown as RedraftSeasonData
const cfg = makeConfig({ VITE_SITE: 'timemachine' })
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim()
const page = (sport: 'NBA' | 'NFL', slug: string) => data[sport].pages.find((p) => p.slug === slug)!
const nba = page('NBA', '2016-17')

const dailyRow = (sport: 'NBA' | 'NFL', label: string) => ({
  sport, date: '2999-01-01', seasonLabel: label, gameId: null, gameStatus: null, draftScore: null, streak: 0, bestStreak: 0,
  nextAt: '2999-01-02T05:00:00Z',
})

const SIGNED_IN = { sleeperUserId: '1', username: 'popsharky', displayName: 'Pop', avatar: null }

function at(path: string, el: React.ReactElement, routePath = path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={routePath} element={el} />
        <Route path="/game/:id" element={<div>room page</div>} />
        <Route path="/daily" element={<div>daily route</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function embed(json: unknown) {
  const el = document.createElement('script')
  el.type = 'application/json'
  el.id = 'redraft-data'
  el.textContent = JSON.stringify(json)
  document.body.appendChild(el)
}

beforeEach(() => {
  vi.stubEnv('VITE_SITE', 'timemachine')
  localStorage.clear()
  __resetGuestForTests()
  __resetRedraftDataForTests()
  clearUser()
  document.title = ''
  document.getElementById('redraft-data')?.remove()
  for (const f of Object.values(api)) f.mockReset()
  api.getPublicSeasonList.mockImplementation(async (s: 'NBA' | 'NFL') => data[s].list)
  api.getPublicSeasonPage.mockImplementation(async (s: 'NBA' | 'NFL', slug: string) => page(s, slug))
  api.getYesterdayDaily.mockResolvedValue([
    { sport: 'NBA', date: '2999-01-01', seasonLabel: '2016-17', slug: '2016-17' },
    { sport: 'NFL', date: '2999-01-01', seasonLabel: null, slug: null },
  ])
  api.getHistoricalDaily.mockResolvedValue([dailyRow('NBA', '1988-89'), dailyRow('NFL', '1999')])
  api.listHistoricalGames.mockResolvedValue([])
  api.getHistoricalProfile.mockRejectedValue(new Error('none'))
  api.claimGuestGames.mockResolvedValue(null)
})
afterEach(() => {
  clearUser()
  document.getElementById('redraft-data')?.remove()
  vi.unstubAllEnvs()
})

describe('SeasonPage', () => {
  it('first paint comes from the embedded #redraft-data: no request, every list and caveat present', () => {
    embed(nba)
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    expect(screen.getByRole('heading', { level: 1, name: '2016-17 NBA Redraft' })).toBeInTheDocument()
    expect(api.getPublicSeasonPage).not.toHaveBeenCalled()
    // every finisher, bust, steal and dream-roster name is in the DOM even with the fold closed
    for (const p of [...nba.topOverall, ...nba.busts, ...nba.steals, ...nba.dreamRoster]) {
      expect(screen.getAllByText(p.name).length, p.name).toBeGreaterThan(0)
    }
    expect(screen.getByText(nba.pointsNote)).toBeInTheDocument()
    expect(document.querySelector('details.tm-spoilers')).not.toHaveAttribute('open')
    expect(document.body.textContent).not.toMatch(/hindsight/i)
  })

  it('an embedded copy of ANOTHER season is not shown: a client-side move asks the endpoint', async () => {
    embed(page('NBA', '1976-77'))
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    // the loading state already has its h1 (D5); the loaded page is the one with the spoiler fold
    expect(await screen.findByText(copy.spoilerH2('2016-17'))).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '2016-17 NBA Redraft' })).toBeInTheDocument()
    expect(api.getPublicSeasonPage).toHaveBeenCalledWith('NBA', '2016-17')
  })

  it('an unoffered season (404) is the not-found body, not an error banner', async () => {
    api.getPublicSeasonPage.mockRejectedValue(new ApiError(404))
    at('/nba/1950-51', <SeasonPage sport="NBA" slug="1950-51" />)
    expect(await screen.findByRole('heading', { name: 'Nothing at this address' })).toBeInTheDocument()
    // The title is a passive effect: it can land a tick after the heading does (flaked 1 in 4 full runs, 2026-11-18).
    await waitFor(() => expect(document.title).toBe(copy.notFoundTitle))
  })

  it('a failed load says so and retries', async () => {
    api.getPublicSeasonPage.mockRejectedValueOnce(new ApiError(500)).mockResolvedValue(nba)
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    expect(await screen.findByText(/This season didn't load\./)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { level: 1, name: '2016-17 NBA Redraft' })).toBeInTheDocument()
  })

  it('a guest with the allowance sees the guest line and drafts the page\'s own season, by seasonStart', async () => {
    embed(nba)
    api.startChosenSeasonGame.mockResolvedValue({ id: 9 })
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    expect(screen.getByText(copy.stateLineGuest)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Draft this season' }))
    expect(api.startChosenSeasonGame).toHaveBeenCalledWith('NBA', 2016)
    expect(await screen.findByText('room page')).toBeInTheDocument()
  })

  it('a refused second season (403 GUEST_LIMIT) shows the limit panel inline, focuses its heading, keeps the button visible but disabled', async () => {
    embed(nba)
    api.startChosenSeasonGame.mockRejectedValue(new ApiError(403, 'limit', undefined, 'GUEST_LIMIT'))
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    await userEvent.click(screen.getByRole('button', { name: 'Draft this season' }))
    const panel = await screen.findByRole('region', { name: copy.limitHeading })
    // an h2 (it sits under the page's h1, before "The draft"), and it takes focus
    expect(within(panel).getByRole('heading', { level: 2, name: copy.limitHeading })).toHaveFocus()
    // aria-disabled, not disabled: a button that disables itself while focused drops that focus (C3)
    expect(screen.getByRole('button', { name: 'Draft this season' })).toHaveAttribute('aria-disabled', 'true')
    // the guest rule is said once, in the panel: the state line is hidden while the panel is up
    expect(screen.queryByText(copy.stateLineGuest)).toBeNull()
    expect(within(panel).getByText(copy.limitBody)).toBeInTheDocument()
    // sign-in is the panel's one filled control; "Play today's game" is the quiet one (D1)
    expect(within(panel).getByRole('link', { name: "Play today's game" })).toHaveClass('quiet')
    expect(within(panel).getByRole('link', { name: "Play today's game" })).toHaveAttribute('href', '/daily')
    // Sign-in is the Sleeper username and nothing else: no email, password or account wording anywhere on the panel.
    expect(within(panel).getByLabelText(/Your Sleeper username/)).not.toHaveFocus()
    expect(panel.textContent).not.toMatch(/e-?mail|\baccount\b|create an? /i)
    expect(panel.querySelector('input[type="password"]')).toBeNull()
  })

  it('shows the panel up front when this browser already holds a non-daily game, without a request that can fail', async () => {
    embed(nba)
    localStorage.setItem(GUEST_KEY, '0b0f6f56-4a38-4a4b-b7a5-0d4f0c3e8a11')
    __resetGuestForTests()
    api.listHistoricalGames.mockResolvedValue([
      { id: 3, sport: 'NBA', seasonLabel: '1990-91', status: 'ABANDONED', userSlot: 1, finish: null, createdAt: '2026-11-01T00:00:00Z', dailyDate: null, chosenSeason: true },
    ] as HistoricalGameSummary[])
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    expect(await screen.findByRole('region', { name: copy.limitHeading })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Draft this season' })).toHaveAttribute('aria-disabled', 'true')
  })

  it('a click on the disabled-looking button does nothing (aria-disabled still guards the handler)', async () => {
    embed(nba)
    localStorage.setItem(GUEST_KEY, '0b0f6f56-4a38-4a4b-b7a5-0d4f0c3e8a11')
    __resetGuestForTests()
    api.listHistoricalGames.mockResolvedValue([
      { id: 3, sport: 'NBA', seasonLabel: '1990-91', status: 'ABANDONED', userSlot: 1, finish: null, createdAt: '2026-11-01T00:00:00Z', dailyDate: null, chosenSeason: true },
    ] as HistoricalGameSummary[])
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    await screen.findByRole('region', { name: copy.limitHeading })
    await userEvent.click(screen.getByRole('button', { name: 'Draft this season' }))
    expect(api.startChosenSeasonGame).not.toHaveBeenCalled()
  })

  // A1 / CR-05: the limit is a GUEST's. A guest who reaches the panel through the 403 and signs in through the panel's own form
  // must not be left looking at "sign in to draft more" with a dead button.
  it('signing in from the limit panel lifts the limit at once: the panel goes, the button works, the claim line is where the state line was', async () => {
    embed(nba)
    api.startChosenSeasonGame.mockRejectedValueOnce(new ApiError(403, 'limit', undefined, 'GUEST_LIMIT'))
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    await userEvent.click(screen.getByRole('button', { name: 'Draft this season' }))
    const panel = await screen.findByRole('region', { name: copy.limitHeading })
    expect(screen.getByRole('button', { name: 'Draft this season' })).toHaveAttribute('aria-disabled', 'true')
    // sign in as the app does (SignInForm calls setUser): no reload
    act(() => setUser(SIGNED_IN))
    await waitFor(() => expect(screen.queryByRole('region', { name: copy.limitHeading })).toBeNull())
    expect(panel).not.toBeInTheDocument()
    expect(screen.getByText(copy.stateLineSignedIn)).toBeInTheDocument()
    // the form they typed in is gone: focus goes to the claim line where it was announced, not to the top of the page (C5)
    await waitFor(() => expect(document.querySelector('.tm-claim')).toHaveFocus())
    expect(document.querySelector('.tm-claim')).toHaveAttribute('role', 'status')
    const button = screen.getByRole('button', { name: 'Draft this season' })
    expect(button).not.toHaveAttribute('aria-disabled')
    api.startChosenSeasonGame.mockResolvedValue({ id: 21 })
    await userEvent.click(button)
    expect(api.startChosenSeasonGame).toHaveBeenLastCalledWith('NBA', 2016)
    expect(await screen.findByText('room page')).toBeInTheDocument()
  })

  it('a guest mid-draft on THIS season sees one Resume control and no limit panel (D1)', async () => {
    embed(nba)
    localStorage.setItem(GUEST_KEY, '0b0f6f56-4a38-4a4b-b7a5-0d4f0c3e8a11')
    __resetGuestForTests()
    api.listHistoricalGames.mockResolvedValue([
      { id: 12, sport: 'NBA', seasonLabel: '2016-17', status: 'DRAFTING', userSlot: 1, finish: null, createdAt: '2026-11-01T00:00:00Z', dailyDate: null, chosenSeason: true },
    ] as HistoricalGameSummary[])
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    const links = await screen.findAllByRole('link', { name: 'Resume your 2016-17 draft' })
    expect(links).toHaveLength(1)
    expect(screen.queryByRole('region', { name: copy.limitHeading })).toBeNull()
    expect(screen.queryByText(copy.limitHeading)).toBeNull()
  })

  it('the loading and error states still have the h1, the way to the sport index and the footer (D5)', async () => {
    api.getPublicSeasonPage.mockRejectedValue(new ApiError(500))
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    expect(await screen.findByRole('alert')).toHaveTextContent(copy.seasonError)
    expect(screen.getByRole('heading', { level: 1, name: '2016-17 NBA Redraft' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: copy.allSeasons('NBA') })).toHaveAttribute('href', '/nba')
    expect(screen.getByRole('navigation', { name: copy.footNavLabel })).toBeInTheDocument()
  })

  it('V-08i: the tab title is the season title from the first render, while the data is still loading', async () => {
    api.getPublicSeasonPage.mockReturnValue(new Promise(() => {}))
    at('/nba/2017-18', <SeasonPage sport="NBA" slug="2017-18" />)
    expect(await screen.findByRole('status')).toHaveTextContent(copy.seasonLoading('2017-18'))
    expect(document.title).toBe(copy.seasonTitle('2017-18', 'NBA'))
    expect(document.title).not.toBe(copy.brand)
  })

  it('a guest game of the day does not spend the allowance', async () => {
    embed(nba)
    localStorage.setItem(GUEST_KEY, '0b0f6f56-4a38-4a4b-b7a5-0d4f0c3e8a11')
    __resetGuestForTests()
    api.listHistoricalGames.mockResolvedValue([
      { id: 3, sport: 'NBA', seasonLabel: '1990-91', status: 'COMPLETE', userSlot: 1, finish: 1, createdAt: '2026-11-01T00:00:00Z', dailyDate: '2026-11-01', chosenSeason: false },
    ] as HistoricalGameSummary[])
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    await waitFor(() => expect(api.listHistoricalGames).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: copy.limitHeading })).toBeNull()
    expect(screen.getByRole('button', { name: 'Draft this season' })).toBeEnabled()
  })

  it('offers Resume for a chosen-season game of this season that is still drafting', async () => {
    embed(nba)
    setUser(SIGNED_IN)
    api.listHistoricalGames.mockResolvedValue([
      { id: 12, sport: 'NBA', seasonLabel: '2016-17', status: 'DRAFTING', userSlot: 1, finish: null, createdAt: '2026-11-01T00:00:00Z', dailyDate: null, chosenSeason: true },
    ] as HistoricalGameSummary[])
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    const links = await screen.findAllByRole('link', { name: 'Resume your 2016-17 draft' })
    expect(links[0]).toHaveAttribute('href', '/game/12')
  })

  it('signed in: the line says seasons you pick do not count, and there is no limit panel', () => {
    embed(nba)
    setUser(SIGNED_IN)
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    expect(screen.getByText(copy.stateLineSignedIn)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: copy.limitHeading })).toBeNull()
  })

  it('an early season names what it does not count, a modern one has no such list, and rows carry weeks played', () => {
    embed(page('NBA', '1976-77'))
    const { unmount } = at('/nba/1976-77', <SeasonPage sport="NBA" slug="1976-77" />)
    expect(document.querySelector('.hist-excluded')).not.toBeNull()
    expect(screen.getAllByText(/\d+ weeks? played/).length).toBeGreaterThan(0)
    unmount()
    document.getElementById('redraft-data')?.remove()
    __resetRedraftDataForTests()
    embed(nba)
    at('/nba/2016-17', <SeasonPage sport="NBA" slug="2016-17" />)
    expect(document.querySelector('.hist-excluded')).toBeNull()
  })
})

describe('SportIndex', () => {
  it('groups years by decade, newest decade first, years oldest to newest, and names the newest season', async () => {
    at('/nba', <SportIndex sport="NBA" />)
    expect(await screen.findByRole('heading', { level: 1, name: 'NBA Redraft: every past season' })).toBeInTheDocument()
    const heads = [...document.querySelectorAll('.tm-decade-head')].map((h) => norm(h.textContent))
    expect(heads).toEqual(['2010s 1 season', '1970s 1 season'])
    const newest = document.querySelector('a.tm-newest')!
    expect(newest).toHaveAttribute('href', '/nba/2016-17')
    expect(norm(newest.textContent)).toBe('Newest season 2016-17')
  })

  it('says the list did not load, with Try again', async () => {
    api.getPublicSeasonList.mockRejectedValueOnce(new Error('x')).mockImplementation(async (s: 'NBA' | 'NFL') => data[s].list)
    at('/nfl', <SportIndex sport="NFL" />)
    expect(await screen.findByText(/The season list didn't load\./)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: /^2007\s*NFL Redraft$/ })).toHaveAttribute('href', '/nfl/2007')
  })
})

describe('DailyPage', () => {
  it('shows the same cards as the home, and yesterday\'s seasons as links to their season page (none for a sport with no game)', async () => {
    at('/daily', <DailyPage />)
    expect(await screen.findByText('1988-89')).toBeInTheDocument()
    const link = await screen.findByRole('link', { name: 'How 2016-17 played out' })
    expect(link).toHaveAttribute('href', '/nba/2016-17')
    expect(await screen.findByText('No football game was dealt yesterday.')).toBeInTheDocument()
    expect(screen.getByText(/Sign in to keep a daily streak\./)).toBeInTheDocument()
  })

  // CR-10: at midnight Eastern the cards refetch, and "Yesterday" is a different day now.
  it('asks for yesterday\'s seasons again when the day rolls over under the open page', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const soon = new Date(Date.now() + 1000).toISOString()
      api.getHistoricalDaily
        .mockResolvedValueOnce([
          { ...dailyRow('NBA', '1988-89'), date: '2026-11-17', nextAt: soon },
          { ...dailyRow('NFL', '1999'), date: '2026-11-17', nextAt: soon },
        ])
        .mockResolvedValue([
          { ...dailyRow('NBA', '1990-91'), date: '2026-11-18' },
          { ...dailyRow('NFL', '2001'), date: '2026-11-18' },
        ])
      at('/daily', <DailyPage />)
      await screen.findByText('1988-89')
      await screen.findByRole('link', { name: 'How 2016-17 played out' })
      expect(api.getYesterdayDaily).toHaveBeenCalledTimes(1)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(31_000)
      })
      await screen.findByText('1990-91')
      await waitFor(() => expect(api.getYesterdayDaily).toHaveBeenCalledTimes(2))
    } finally {
      vi.useRealTimers()
    }
  })

  it('starts today\'s game for the sport and goes to it', async () => {
    api.startHistoricalDaily.mockResolvedValue({ id: 31 })
    at('/daily', <DailyPage />)
    await userEvent.click(await screen.findByRole('button', { name: "Play today's football game" }))
    expect(api.startHistoricalDaily).toHaveBeenCalledWith('NFL')
    expect(await screen.findByText('room page')).toBeInTheDocument()
  })
})

// PR-08: the title a crawler reads (the static file) and the one a tab shows once React has mounted are one string.
describe('document.title agrees with the page list', () => {
  const pages = indexablePages(cfg, data)
  const titleOf = (path: string) => pages.find((p) => p.path === path)!.title

  it.each([
    ['a season page', '/nba/2016-17', () => <SeasonPage sport="NBA" slug="2016-17" />],
    ['an early season page', '/nba/1976-77', () => <SeasonPage sport="NBA" slug="1976-77" />],
    ['a football season page', '/nfl/2007', () => <SeasonPage sport="NFL" slug="2007" />],
    ['a sport index', '/nba', () => <SportIndex sport="NBA" />],
    ['the other sport index', '/nfl', () => <SportIndex sport="NFL" />],
    ['/daily', '/daily', () => <DailyPage />],
    ['the home page', '/', () => <HistoricalDraft />],
  ])('%s', async (_name, path, el) => {
    at(path, el(), path === '/' ? '/' : path)
    await waitFor(() => expect(document.title).toBe(titleOf(path)))
  })
})

// The static-versus-React parity test: what index/season/sport/daily files deliver and what React mounts say the same
// thing. Regions that cannot match (today's seasons, the visitor's progress, which state line) carry data-dynamic in
// BOTH and are left out by that marker, not by loosening the comparison.
describe('static HTML vs the mounted page', () => {
  const shape = (root: ParentNode) =>
    [...root.querySelectorAll('h1,h2,h3,h4,p,li,dt,dd,summary,a,button,label,.tm-over')]
      .filter((e) => !e.closest('[data-dynamic]'))
      .map((e) => `${e.tagName.toLowerCase()}|${norm(e.textContent)}${e.tagName === 'A' ? `|${e.getAttribute('href')}` : ''}`)

  function staticContent(path: string): HTMLElement {
    const p = indexablePages(cfg, data).find((x) => x.path === path)!
    const doc = new DOMParser().parseFromString(`<body>${pageParts(p, cfg, data).body}</body>`, 'text/html')
    return doc.querySelector('.content') as HTMLElement
  }

  it.each([
    ['a modern season page', '/nba/2016-17', () => <SeasonPage sport="NBA" slug="2016-17" />],
    ['an early season page (excluded stats)', '/nba/1976-77', () => <SeasonPage sport="NBA" slug="1976-77" />],
    ['a football season page', '/nfl/2007', () => <SeasonPage sport="NFL" slug="2007" />],
    ['a sport index', '/nba', () => <SportIndex sport="NBA" />],
    ['/daily', '/daily', () => <DailyPage />],
    ['the home page', '/', () => <HistoricalDraft />],
  ])('%s says the same thing', async (_name, path, el) => {
    // The page as a real visit gets it: its own file's embedded data on first paint.
    const p = indexablePages(cfg, data).find((x) => x.path === path)!
    const after = pageParts(p, cfg, data).after
    if (after) document.body.insertAdjacentHTML('beforeend', after)
    const view = at(path, el(), path)
    // wait for the page to be fully mounted (home tiles / daily yesterday / sport tiles all settle)
    await waitFor(() => expect(document.title).toBe(p.title))
    await screen.findAllByRole('link', { name: /Home/ })
    if (path === '/' ) await screen.findByRole('link', { name: /Basketball/ })
    if (path === '/daily') await screen.findByText('1988-89')
    const mounted = view.container.querySelector('.content') as HTMLElement
    const fromStatic = shape(staticContent(path))
    const fromReact = shape(mounted)
    expect(fromStatic.length).toBeGreaterThan(8)
    expect(fromReact).toEqual(fromStatic)
    // The excluded regions are named, not accidental: they exist on both sides.
    // The one named exception: the "needs JavaScript" line is only ever in the static file (a mounted page has scripts on).
    const marks = (root: ParentNode) =>
      [...root.querySelectorAll('[data-dynamic]')].map((e) => e.getAttribute('data-dynamic')).filter((m) => m !== 'noscript')
    expect(marks(mounted)).toEqual(marks(staticContent(path)))
  })
})

// 2026-11-18 prod check: after a client-side move the canonical (and og:url, description, JSON-LD) still named the FIRST page
// loaded; only document.title followed the route. Each mounted page now writes the per-page head tags its static file has.
describe('the head follows a client-side move', () => {
  const pages = indexablePages(cfg, data)
  const entry = (path: string) => pages.find((p) => p.path === path)!

  /** The per-page head tags, read the same way from the live document and from a static file's head. */
  function perPageHead(head: ParentNode) {
    const meta = (sel: string) => head.querySelector(sel)?.getAttribute('content') ?? null
    return {
      canonical: head.querySelector('link[rel="canonical"]')?.getAttribute('href') ?? null,
      ogUrl: meta('meta[property="og:url"]'),
      description: meta('meta[name="description"]'),
      ogTitle: meta('meta[property="og:title"]'),
      ogDescription: meta('meta[property="og:description"]'),
      twitterTitle: meta('meta[name="twitter:title"]'),
      twitterDescription: meta('meta[name="twitter:description"]'),
      robots: meta('meta[name="robots"]'),
      jsonLd: [...head.querySelectorAll('script[type="application/ld+json"]')].map((s) => JSON.parse(s.textContent ?? '')),
    }
  }
  const staticHead = (path: string) =>
    perPageHead(new DOMParser().parseFromString(`<head>${pageHeadTags(entry(path), cfg)}</head>`, 'text/html').head)

  function Season({ sport }: { sport: 'NBA' | 'NFL' }) {
    const { season = '' } = useParams()
    return <SeasonPage key={`${sport}/${season}`} sport={sport} slug={season} />
  }
  let go: (to: string) => void = () => {}
  function Nav() {
    go = useNavigate()
    return null
  }

  beforeEach(() => {
    document.head.innerHTML = ''
  })
  afterEach(() => {
    document.head.innerHTML = ''
  })

  it('season -> sport index -> the other sport\'s season -> season: canonical, og:url, description and JSON-LD each match that page\'s static file', async () => {
    // A real first visit: the head and embedded data are /nba/2016-17's static file.
    document.head.innerHTML = pageHeadTags(entry('/nba/2016-17'), cfg)
    embed(nba)
    render(
      <MemoryRouter initialEntries={['/nba/2016-17']}>
        <Nav />
        <Routes>
          <Route path="/nba" element={<SportIndex sport="NBA" />} />
          <Route path="/nfl" element={<SportIndex sport="NFL" />} />
          <Route path="/nba/:season" element={<Season sport="NBA" />} />
          <Route path="/nfl/:season" element={<Season sport="NFL" />} />
          <Route path="*" element={<RedraftNotFound />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(perPageHead(document.head)).toEqual(staticHead('/nba/2016-17'))

    for (const path of ['/nfl', '/nfl/2007', '/nba/1976-77', '/nba/2016-17']) {
      act(() => go(path))
      await waitFor(() => expect(document.title).toBe(entry(path).title))
      await waitFor(() => expect(perPageHead(document.head)).toEqual(staticHead(path)))
      expect(document.head.querySelector('link[rel="canonical"]')).toHaveAttribute('href', `https://www.ballknowers.co${path}`)
    }

    // A page that is never indexed drops the canonical and og:url and says noindex, as 404.html does.
    act(() => go('/nowhere'))
    await waitFor(() => expect(document.title).toBe(copy.notFoundTitle))
    const head = perPageHead(document.head)
    expect(head.canonical).toBeNull()
    expect(head.ogUrl).toBeNull()
    expect(head.description).toBeNull()
    expect(head.robots).toBe('noindex')
    expect(head.jsonLd).toEqual([])

    // ...and moving back to an indexable page takes the noindex off again.
    act(() => go('/nfl/2007'))
    await waitFor(() => expect(perPageHead(document.head)).toEqual(staticHead('/nfl/2007')))
  })
})
