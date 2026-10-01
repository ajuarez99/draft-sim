import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useLeagueDataVersion } from '../leagueDataVersion'
import {
  draftSummary,
  getLeagueRefreshSpy,
  refreshLeagueSpy,
  refreshStatus,
  renderAtPath,
  resetRail,
} from '../testRailHelpers'
import { REFRESH_POLL_MS, switchTarget } from './LeagueRailSection'
import type { LeagueContext } from '../destinations'
import type { LeagueLineage } from '../leagueLineage'

/*
 * The question this whole feature is about: standing on this URL, does the rail
 * still know which league you are in?
 *
 * Three of these routes answered "no" before 003. The sharpest was Analysis --
 * the rail linked you to it and then, because the matcher did not recognise the
 * route the rail had just sent you to, deleted the League section that linked
 * you.
 */

afterEach(resetRail)

const NFL = draftSummary({
  sleeperDraftId: 'D_NFL',
  sleeperLeagueId: 'L_NFL',
  leagueName: 'Football League',
  sport: 'nfl',
  status: 'complete',
})

const NBA = draftSummary({
  id: 2,
  leagueId: 2,
  sleeperDraftId: 'D_NBA',
  sleeperLeagueId: 'L_NBA',
  leagueName: 'Hoops League',
  sport: 'nba',
  status: 'complete',
})

const NFL25 = draftSummary({
  id: 11,
  leagueId: 11,
  sleeperDraftId: 'D_NFL25',
  sleeperLeagueId: 'L_NFL25',
  leagueName: 'Football League',
  season: 2025,
  sport: 'nfl',
  status: 'complete',
})

const NFL26 = draftSummary({
  id: 12,
  leagueId: 12,
  sleeperDraftId: 'D_NFL26',
  sleeperLeagueId: 'L_NFL26',
  previousLeagueId: 'L_NFL25',
  leagueName: 'Football League',
  season: 2026,
  sport: 'nfl',
  status: 'complete',
})

describe('league context per route', () => {
  it.each([
    ['/leagues/L_NFL', 'League home'],
    ['/leagues/L_NFL/history', 'Standings'],
    ['/leagues/L_NFL/power', 'Power rankings'],
    ['/leagues/L_NFL/analysis', 'Team strength'],
    ['/drafts/D_NFL/board', 'Draft board'],
  ])('shows the league on %s and marks %s current', async (path, current) => {
    const { leagueSection, currentRowLabels } = renderAtPath(path, { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentRowLabels()).toEqual([current])
  })

  it('shows the league on a manager history page reached from a league', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/managers/12/history', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toContain('Standings')
  })

  it('marks nothing current on a manager history page', async () => {
    const { leagueSection, currentRowLabels } = renderAtPath('/managers/12/history', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    // You are inside the league but on none of its pages. Marking one would
    // claim you are somewhere you are not.
    expect(currentRowLabels()).toEqual([])
  })

  // The honest-absence half of the same rule. A direct visit genuinely has no
  // league, and inventing one would be worse than showing none.
  it('shows no league on a manager history page visited directly', async () => {
    const { leagueSection, rail } = renderAtPath('/managers/12/history', { drafts: [NFL] })

    await waitFor(() => expect(rail()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })

  it('shows no league on a mock with no seeding league', async () => {
    const { leagueSection, rail } = renderAtPath('/mock/4', { drafts: [NFL] })

    await waitFor(() => expect(rail()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })

  it('shows the seeding league on a mock that has one', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/mock/4', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toContain('Standings')
  })
})

describe('what a league is offered', () => {
  it('offers Analysis to a football league', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    // Grouped by what a fan is doing (specs/013 US3): home, this week, the
    // season, draft, history.
    expect(rowLabels()).toEqual([
      'League home',
      'Matchups & awards',
      'Power rankings',
      'Team strength',
      'Luck',
      'Bench points',
      'Playoff odds',
      'Awards',
      'Draft board',
      'Mock it',
      'Standings',
    ])
  })

  // The sport gate is one-way, and it now lives in one place rather than in
  // this component's JSX -- so the rail and the palette cannot disagree about
  // whether basketball has an Analysis page.
  it('does not offer Analysis to a basketball league', async () => {
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NBA/history', { drafts: [NBA] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).not.toContain('Team strength')
    expect(rowLabels()).toContain('Power rankings')
    // Roster management IS offered to basketball, and the contrast with
    // Analysis one line up is the whole point: Analysis is gated because two
    // of its blocks are projections and there is no basketball projection
    // source, while nothing on Roster management is a projection -- it reads
    // points already scored, which Sleeper reports for both sports.
    expect(rowLabels()).toContain('Bench points')
    expect(rowLabels()).toContain('Luck')
    expect(rowLabels()).toContain('Playoff odds')
    expect(rowLabels()).toContain('Matchups & awards')
  })

  it('offers Follow live only while a draft is running', async () => {
    const drafting = draftSummary({ sleeperDraftId: 'D_LIVE', sleeperLeagueId: 'L_LIVE', status: 'drafting' })
    const { leagueSection, rowLabels } = renderAtPath('/drafts/D_LIVE', { drafts: [drafting] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toContain('Follow live')
    // A running draft opens the room, not a board of picks that don't exist.
    expect(rowLabels()).toContain('Draft room')
  })

  it('marks Follow live current on the live route', async () => {
    const drafting = draftSummary({ sleeperDraftId: 'D_LIVE', sleeperLeagueId: 'L_LIVE', status: 'drafting' })
    const { leagueSection, currentRowLabels } = renderAtPath('/drafts/D_LIVE/live', { drafts: [drafting] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentRowLabels()).toEqual(['Follow live'])
  })
})

describe('routes with no league at all', () => {
  it.each([['/'], ['/managers'], ['/mock/new']])('shows no league section on %s', async (path) => {
    const { leagueSection, rail } = renderAtPath(path, { drafts: [NFL] })

    await waitFor(() => expect(rail()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })
})


/*
 * specs/009-auto-data-refresh T026/T028: the rail starts a refresh when it lands
 * in a league, follows it while it runs, and tells the pages when it is done.
 */
describe('refresh on visit', () => {
  function VersionProbe() {
    return <p data-testid="version">{useLeagueDataVersion('L_NFL')}</p>
  }

  const flush = () =>
    act(async () => {
      await Promise.resolve()
    })

  it('calls refreshLeague once per league, and again when the league changes', async () => {
    renderAtPath('/leagues/L_NFL/history', { drafts: [NFL, NBA] })

    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(1))
    expect(refreshLeagueSpy).toHaveBeenCalledWith('L_NFL')

    // Same league, further renders (the switcher opening): still one.
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /switch/i }))
    expect(refreshLeagueSpy).toHaveBeenCalledTimes(1)

    await user.click(await screen.findByRole('menuitem', { name: /Hoops League/ }))
    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(2))
    expect(refreshLeagueSpy).toHaveBeenLastCalledWith('L_NBA')
  })

  it('polls while RUNNING, bumps the data version on FRESH, and stops polling', async () => {
    const running = refreshStatus({ state: 'RUNNING', leagueSleeperId: 'L_NFL' })
    const fresh = refreshStatus({
      state: 'FRESH',
      leagueSleeperId: 'L_NFL',
      lastSuccessAt: new Date().toISOString(),
    })

    // The rail's poll is one setTimeout(REFRESH_POLL_MS) at a time. Catch just
    // those and fire them by hand: fake timers would also stall waitFor and
    // testing-library's own scheduling, and a real 3 s wait is a slow test.
    const pending: Array<() => void> = []
    const realSetTimeout = globalThis.setTimeout
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((fn: () => void, ms?: number, ...rest: unknown[]) => {
      if (ms === REFRESH_POLL_MS) {
        pending.push(fn)
        return 0 as unknown as ReturnType<typeof setTimeout>
      }
      return realSetTimeout(fn, ms, ...rest)
    }) as unknown as typeof setTimeout)
    const firePoll = () =>
      act(async () => {
        pending.shift()!()
        await Promise.resolve()
      })

    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', {
      drafts: [NFL],
      refresh: running,
      polls: [running, fresh],
      page: <VersionProbe />,
    })
    await waitFor(() => expect(leagueSection()?.textContent).toContain('Updating…'))
    expect(pending).toHaveLength(1)
    expect(getLeagueRefreshSpy).not.toHaveBeenCalled()
    expect(screen.getByTestId('version')).toHaveTextContent('0')

    await firePoll()
    await waitFor(() => expect(getLeagueRefreshSpy).toHaveBeenCalledTimes(1))
    expect(screen.getByTestId('version')).toHaveTextContent('0')
    expect(leagueSection()?.textContent).toContain('Updating…')
    expect(pending).toHaveLength(1)

    await firePoll()
    await waitFor(() => expect(screen.getByTestId('version')).toHaveTextContent('1'))
    expect(getLeagueRefreshSpy).toHaveBeenCalledTimes(2)
    expect(leagueSection()?.textContent).toContain('Updated just now')
    expect(leagueSection()?.textContent).not.toContain('Updating…')
    expect(pending).toHaveLength(0)
  })

  // specs/011 T005: a page for an older season is keyed by that season's own
  // league id, so the rail must bump every season of the lineage. Pins the loop
  // in LeagueRailSection.tsx (`for (const id of seasonIdsRef.current) bump(id)`).
  it('bumps the data version of every season in the lineage when a refresh finishes', async () => {
    const running = refreshStatus({ state: 'RUNNING', leagueSleeperId: 'L_NFL26' })
    const fresh = refreshStatus({
      state: 'FRESH',
      leagueSleeperId: 'L_NFL26',
      lastSuccessAt: new Date().toISOString(),
    })
    const pending: Array<() => void> = []
    const realSetTimeout = globalThis.setTimeout
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((fn: () => void, ms?: number, ...rest: unknown[]) => {
      if (ms === REFRESH_POLL_MS) {
        pending.push(fn)
        return 0 as unknown as ReturnType<typeof setTimeout>
      }
      return realSetTimeout(fn, ms, ...rest)
    }) as unknown as typeof setTimeout)

    function TwoVersions() {
      return (
        <>
          <p data-testid="v-new">{useLeagueDataVersion('L_NFL26')}</p>
          <p data-testid="v-old">{useLeagueDataVersion('L_NFL25')}</p>
        </>
      )
    }

    const { leagueSection } = renderAtPath('/leagues/L_NFL26/history', {
      drafts: [NFL26, NFL25],
      refresh: running,
      polls: [fresh],
      page: <TwoVersions />,
    })
    await waitFor(() => expect(leagueSection()?.textContent).toContain('Updating…'))
    expect(screen.getByTestId('v-old')).toHaveTextContent('0')

    await act(async () => {
      pending.shift()!()
      await Promise.resolve()
    })
    await waitFor(() => expect(screen.getByTestId('v-new')).toHaveTextContent('1'))
    expect(screen.getByTestId('v-old')).toHaveTextContent('1')
  })

  it('does not bump the data version when a refresh was never running', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', {
      drafts: [NFL],
      refresh: refreshStatus({ state: 'FRESH', leagueSleeperId: 'L_NFL', lastSuccessAt: new Date().toISOString() }),
      page: <VersionProbe />,
    })
    await waitFor(() => expect(leagueSection()?.textContent).toContain('Updated just now'))

    expect(screen.getByTestId('version')).toHaveTextContent('0')
    expect(getLeagueRefreshSpy).not.toHaveBeenCalled()
  })

  it('renders the could-not-reach-Sleeper line with the age of the data on FAILED', async () => {
    const threeHoursAgo = new Date(Date.now() - 3 * 3600_000).toISOString()
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', {
      drafts: [NFL],
      refresh: refreshStatus({
        state: 'FAILED',
        leagueSleeperId: 'L_NFL',
        lastSuccessAt: threeHoursAgo,
        lastFailureAt: new Date().toISOString(),
      }),
    })

    await waitFor(() =>
      expect(leagueSection()?.textContent).toContain("Couldn't reach Sleeper — data from 3 h ago"),
    )
  })

  it('shows nothing, and does not retry, when the POST fails', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    // Before the league resolves, so the rail's one POST hits the failing stub.
    refreshLeagueSpy.mockRejectedValue(new Error('network'))

    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await flush()

    expect(leagueSection()?.querySelector('.rail-league-refresh')).toBeNull()
    expect(getLeagueRefreshSpy).not.toHaveBeenCalled()
    expect(refreshLeagueSpy).toHaveBeenCalledTimes(1)
  })
})

/*
 * specs/011 US1: a year link keeps the page you are on. Two-season NFL lineage,
 * 2025 complete. (b), (c), (d) pin behaviour that already held; (a), (g), (e)
 * and (f1) are the bug.
 */
describe('year links keep the page', () => {
  // Draft routes open with the rail collapsed, which hides the year row; the
  // saved override ('bk-rail') expands it so the board and live cases can see it.
  const expandRail = () => localStorage.setItem('bk-rail', 'expanded')
  afterEach(() => localStorage.removeItem('bk-rail'))

  const yearLink = (leagueSection: () => HTMLElement | null, year: string) =>
    [...leagueSection()!.querySelectorAll<HTMLAnchorElement>('a.league-season-link')].find(
      (a) => a.textContent?.trim() === year,
    )!

  it('(a) keeps a one-season page: superlatives 2026 -> 2025', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL26/superlatives', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(yearLink(leagueSection, '2025').getAttribute('href')).toBe('/leagues/L_NFL25/superlatives')
  })

  it("(b) on the board, the other year is that year's board", async () => {
    expandRail()
    const { leagueSection } = renderAtPath('/drafts/D_NFL26/board', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(yearLink(leagueSection, '2025').getAttribute('href')).toBe('/drafts/D_NFL25/board')
  })

  it('(c) on History, both year links are the same History link', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL26/history', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(yearLink(leagueSection, '2025').getAttribute('href')).toBe('/leagues/L_NFL26/history')
    expect(yearLink(leagueSection, '2026').getAttribute('href')).toBe('/leagues/L_NFL26/history')
  })

  it('(d) marks the year being viewed on a one-season page', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL25/weekly-report', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(yearLink(leagueSection, '2025').classList.contains('on')).toBe(true)
    expect(yearLink(leagueSection, '2026').classList.contains('on')).toBe(false)
  })

  it('(g) does not carry the query string across a year switch', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL26/weekly-report?week=5', {
      drafts: [NFL26, NFL25],
    })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(yearLink(leagueSection, '2025').getAttribute('href')).toBe('/leagues/L_NFL25/weekly-report')
  })

  it('(e) the Switch flyout Seasons items follow the same rule', async () => {
    const user = userEvent.setup()
    const a = renderAtPath('/leagues/L_NFL26/superlatives', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(a.leagueSection()).not.toBeNull())
    await user.click(await screen.findByRole('button', { name: /switch/i }))
    const item = (await screen.findAllByRole('menuitem')).find((m) => m.textContent?.startsWith('2025'))!
    expect(item.getAttribute('href')).toBe('/leagues/L_NFL25/superlatives')
    a.unmount()
    resetRail()

    const b = renderAtPath('/drafts/D_NFL26/board', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(b.leagueSection()).not.toBeNull())
    await user.click(await screen.findByRole('button', { name: /switch/i }))
    const item2 = (await screen.findAllByRole('menuitem')).find((m) => m.textContent?.startsWith('2025'))!
    expect(item2.getAttribute('href')).toBe('/drafts/D_NFL25/board')
  })

  it('(f1) from Follow live on a pre_draft season, a complete year goes to its board, not History', async () => {
    const pre26 = { ...NFL26, status: 'pre_draft' }
    expandRail()
    const { leagueSection } = renderAtPath('/drafts/D_NFL26/live', { drafts: [pre26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(yearLink(leagueSection, '2025').getAttribute('href')).toBe('/drafts/D_NFL25/board')
  })

  // A hinted page (a mock room, a manager's history) has no league destination
  // of its own, so currentKey is null. Falling back to History there sent every
  // year link in the flyout to the same URL; the board is the per-year page.
  it('(h) with no current page, a year goes to its own board', () => {
    const ctx: LeagueContext = { lineage: { current: NFL26, seasons: [NFL26, NFL25] }, season: NFL25 }
    expect(switchTarget(null, ctx)).toBe('/drafts/D_NFL25/board')
  })

  it('(h) on a hinted mock route, the Switch flyout year goes to its board, not History', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/mock/4', {
      drafts: [NFL26, NFL25],
      state: { railLeagueId: 'L_NFL26' },
    })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await user.click(await screen.findByRole('button', { name: /switch/i }))
    const item = (await screen.findAllByRole('menuitem')).find((m) => m.textContent?.startsWith('2025'))!
    expect(item.getAttribute('href')).toBe('/drafts/D_NFL25/board')
  })

  // (f2) Leagues-flyout coverage: year links cannot cross sports (a lineage is
  // one sport), so the "current page not offered by the target" fallback is only
  // reachable from the Leagues group of the flyout. Analysis is football-only.
  it('(f2) falls back to League home when the target sport lacks the page', () => {
    const nba25 = draftSummary({ sleeperDraftId: 'D_NBA25', sleeperLeagueId: 'L_NBA25', sport: 'nba', season: 2025 })
    const nba26 = draftSummary({
      sleeperDraftId: 'D_NBA26',
      sleeperLeagueId: 'L_NBA26',
      previousLeagueId: 'L_NBA25',
      sport: 'nba',
      season: 2026,
    })
    const nbaLineage: LeagueLineage = { current: nba26, seasons: [nba26, nba25] }
    const nbaCtx: LeagueContext = { lineage: nbaLineage, season: nba26 }
    expect(switchTarget('analysis', nbaCtx)).toBe('/leagues/L_NBA26')
  })
})

describe('rail groups (specs/013 US3)', () => {
  const headings = (root: HTMLElement | null) =>
    root ? [...root.querySelectorAll('.app-rail-group-head')].map((e) => (e.textContent ?? '').trim()) : []

  afterEach(() => {
    vi.unstubAllGlobals()
    try {
      window.localStorage.clear()
    } catch {
      /* none */
    }
  })

  it('renders the four headed groups in order', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(headings(leagueSection()).map((h) => h.replace(/[▾▸]/g, '').trim())).toEqual([
      'This week',
      'The season',
      'Draft',
      'History',
    ])
  })

  it('collapses a group on click, remembers it, and keeps the current group open', async () => {
    const user = userEvent.setup()
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    const season = [...leagueSection()!.querySelectorAll<HTMLButtonElement>('button.app-rail-group-head')].find(
      (b) => (b.textContent ?? '').includes('The season'),
    )!
    expect(season.getAttribute('aria-expanded')).toBe('true')
    await user.click(season)
    expect(rowLabels()).not.toContain('Luck')
    expect(season.getAttribute('aria-expanded')).toBe('false')
    expect(JSON.parse(window.localStorage.getItem('bk.rail.groups.v1') ?? '{}')).toEqual({ season: false })
  })

  it('reopens a remembered-closed group when you land on one of its pages', async () => {
    window.localStorage.setItem('bk.rail.groups.v1', JSON.stringify({ season: false }))
    const { leagueSection, rowLabels, currentRowLabels } = renderAtPath('/leagues/L_NFL/expected-wins', {
      drafts: [NFL],
    })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await waitFor(() => expect(currentRowLabels()).toEqual(['Luck']))
    expect(rowLabels()).toContain('Team strength')
  })

  it('starts with a remembered-closed group closed elsewhere', async () => {
    window.localStorage.setItem('bk.rail.groups.v1', JSON.stringify({ season: false }))
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).not.toContain('Luck')
    expect(rowLabels()).toContain('Standings')
  })

  it('shows every group expanded in the phone lane, ignoring remembered state', async () => {
    window.localStorage.setItem('bk.rail.groups.v1', JSON.stringify({ season: false, draft: false }))
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('860px'),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
    const { leagueSection, rowLabels } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(rowLabels()).toContain('Luck')
    expect(rowLabels()).toContain('Draft board')
    // headings are separators on a phone, not buttons: no extra tap to find a page
    expect(leagueSection()!.querySelectorAll('button.app-rail-group-head')).toHaveLength(0)
    expect(headings(leagueSection())).toHaveLength(4)
  })

  it('titles every row with its fan label, so a collapsed rail is never an unlabeled mark', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    const luck = [...leagueSection()!.querySelectorAll<HTMLElement>('.app-rail-row')].find(
      (e) => e.getAttribute('aria-label') === 'Luck',
    )
    expect(luck?.getAttribute('title')).toBe('Luck')
  })
})
