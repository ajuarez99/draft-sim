import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useLeagueDataVersion } from '../leagueDataVersion'
import {
  draftSummary,
  getLeagueRefreshSpy,
  refreshLeagueSpy,
  refreshStatus,
  renderAtPath,
  resetShell,
} from '../testShellHelpers'
import { REFRESH_POLL_MS } from '../leagueRefresh'

/*
 * The league header (claude/site-redesign.md Phase 2), as the shell actually wires it.
 *
 * The question this whole feature is about: standing on this URL, does the chrome still know which
 * league you are in, which tab you are on, and which page under it? Three of these routes answered
 * "no" before spec 003. The sharpest was Analysis -- the old rail linked you to it and then, because
 * the matcher did not recognise the route it had just sent you to, deleted the League section that
 * linked you. These tests replace the ones LeagueRailSection.test.tsx held for the rail.
 */

afterEach(resetShell)

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
    ['/leagues/L_NFL', 'Home', null],
    ['/leagues/L_NFL/history', 'History', 'Standings'],
    ['/leagues/L_NFL/power', 'Season', 'Power rankings'],
    ['/leagues/L_NFL/analysis', 'Season', 'Team strength'],
    ['/drafts/D_NFL/board', 'Draft', 'Draft board'],
  ])('shows the league on %s and marks the %s tab and %s page current', async (path, tab, sub) => {
    const { leagueSection, currentTabLabels, currentSubTabLabels } = renderAtPath(path, { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentTabLabels()).toEqual([tab])
    expect(currentSubTabLabels()).toEqual(sub ? [sub] : [])
  })

  it('shows the league on a manager history page reached from a league', async () => {
    const { leagueSection, tabLabels } = renderAtPath('/managers/12/history', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(tabLabels()).toContain('History')
  })

  it('marks no tab and shows no sub-tabs on a manager history page', async () => {
    const { leagueSection, currentTabLabels, container } = renderAtPath('/managers/12/history', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    // You are inside the league but on none of its pages. Marking one would claim you are
    // somewhere you are not.
    expect(currentTabLabels()).toEqual([])
    expect(container.querySelector('.league-subtabs')).toBeNull()
  })

  // The honest-absence half of the same rule. A direct visit genuinely has no league, and inventing
  // one would be worse than showing none.
  it('shows no league on a manager history page visited directly', async () => {
    const { leagueSection, topBar } = renderAtPath('/managers/12/history', { drafts: [NFL] })

    await waitFor(() => expect(topBar()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })

  it('shows no league on a mock with no seeding league', async () => {
    const { leagueSection, topBar } = renderAtPath('/mock/4', { drafts: [NFL] })

    await waitFor(() => expect(topBar()).not.toBeNull())
    expect(leagueSection()).toBeNull()
  })

  it('shows the seeding league on a mock that has one', async () => {
    const { leagueSection, tabLabels } = renderAtPath('/mock/4', {
      drafts: [NFL],
      state: { railLeagueId: 'L_NFL' },
    })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(tabLabels()).toContain('History')
  })

  it.each([['/'], ['/managers'], ['/mock/new']])('shows no league header on %s', async (path) => {
    const { leagueSection, topBar, container } = renderAtPath(path, { drafts: [NFL] })

    await waitFor(() => expect(topBar()).not.toBeNull())
    expect(leagueSection()).toBeNull()
    expect(container.querySelector('nav[aria-label="League"]')).toBeNull()
  })

  it('names the league, its sport and its size, and links the crest and name to the Home tab', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    const id = within(leagueSection()!).getByRole('link', { name: 'Football League' })
    expect(id.getAttribute('href')).toBe('/leagues/L_NFL')
    expect(id.textContent).toContain('NFL')
    expect(id.textContent).toContain('12 managers')
  })
})

describe('what a league is offered', () => {
  it('offers a football league five tabs, with History and its sub-tabs on the History tab', async () => {
    const { leagueSection, tabLabels, subTabLabels, subActionLabels } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    // No Players tab: football has no player pages.
    expect(tabLabels()).toEqual(['Home', 'Matchups', 'Season', 'Draft', 'History'])
    expect(subTabLabels()).toEqual(['Standings', 'Rivalries'])
    expect(subActionLabels()).toEqual([])
  })

  // Grouped by what a fan is doing (specs/013 US3), now as tabs: the sub-tab order is the table order.
  it('lists the Season tab pages in order for a football league', async () => {
    const { leagueSection, subTabLabels } = renderAtPath('/leagues/L_NFL/power', { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(subTabLabels()).toEqual(['Power rankings', 'Playoff odds', 'Team strength', 'Luck', 'Bench points', 'Awards'])
  })

  it('lists the Matchups pages for a football league, Weekly pot included', async () => {
    const { leagueSection, subTabLabels } = renderAtPath('/leagues/L_NFL/weekly-report', { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(subTabLabels()).toEqual(['Matchups & awards', 'Weekly pot'])
  })

  it('puts Mock it at the end of the Draft sub-tabs as a button, not a link', async () => {
    const { leagueSection, subTabLabels, subActionLabels, container } = renderAtPath('/drafts/D_NFL/board', { drafts: [NFL] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(subTabLabels()).toEqual(['Draft board'])
    expect(subActionLabels()).toEqual(['Mock it'])
    const mock = container.querySelector('.league-subtabs-actions button')!
    expect(mock.tagName).toBe('BUTTON')
    expect(container.querySelector('a.league-subtab.action')).toBeNull()
  })

  // specs/022 T027: the player page is reached from a name, never listed -- and being on it must
  // not make the league header vanish. It lights the Players tab.
  it('keeps the league header on a player page, lights Players, and lists no row for the player', async () => {
    const { leagueSection, tabLabels, currentTabLabels, subTabLabels, currentSubTabLabels } = renderAtPath(
      '/leagues/L_NBA/players/1350',
      { drafts: [NBA] },
    )

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(tabLabels()).toContain('Home')
    expect(currentTabLabels()).toEqual(['Players'])
    expect(subTabLabels()).toEqual(['Player stats', 'Trends'])
    expect(subTabLabels()).not.toContain('Player')
    expect(currentSubTabLabels()).toEqual([])
  })

  it('lights Players on the compare page too', async () => {
    const { leagueSection, currentTabLabels } = renderAtPath('/leagues/L_NBA/compare', { drafts: [NBA] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentTabLabels()).toEqual(['Players'])
  })

  // The sport gate is one-way, and it lives in one place -- so the tabs and the palette cannot
  // disagree about whether basketball has an Analysis page.
  it('offers a basketball league its Players tab, the schedule grid, and Analysis too (spec 030)', async () => {
    const { leagueSection, tabLabels, subTabLabels } = renderAtPath('/leagues/L_NBA/power', { drafts: [NBA] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(tabLabels()).toEqual(['Home', 'Matchups', 'Season', 'Players', 'Draft', 'History'])
    // Roster management is offered to basketball too: nothing on it is a projection.
    expect(subTabLabels()).toEqual(['Power rankings', 'Playoff odds', 'Team strength', 'Luck', 'Bench points', 'Awards'])

    const matchups = renderAtPath('/leagues/L_NBA/weekly-report', { drafts: [NBA] })
    await waitFor(() => expect(matchups.subTabLabels().length).toBeGreaterThan(0))
    expect(matchups.subTabLabels()).toEqual(['Matchups & awards', 'Schedule grid', 'Weekly pot'])
  })

  // Spec 046: the ADP compare page is basketball-only, and offered to a basketball league whatever
  // its draft is doing.
  it('offers ADP & prices to a basketball league and not a football one', async () => {
    const nba = renderAtPath('/leagues/L_NBA/adp', { drafts: [NBA] })
    await waitFor(() => expect(nba.leagueSection()).not.toBeNull())
    expect(nba.subTabLabels()).toContain('ADP & prices')
    expect(nba.currentSubTabLabels()).toEqual(['ADP & prices'])
    nba.unmount()
    resetShell()

    const nfl = renderAtPath('/drafts/D_NFL/board', { drafts: [NFL] })
    await waitFor(() => expect(nfl.leagueSection()).not.toBeNull())
    expect(nfl.subTabLabels()).not.toContain('ADP & prices')
  })

  it('offers Follow live only while a draft is running, and opens the room rather than a board', async () => {
    const drafting = draftSummary({ sleeperDraftId: 'D_LIVE', sleeperLeagueId: 'L_LIVE', status: 'drafting' })
    const { leagueSection, subTabLabels } = renderAtPath('/drafts/D_LIVE', { drafts: [drafting] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(subTabLabels()).toEqual(['Draft room', 'Follow live'])

    resetShell()
    const done = renderAtPath('/drafts/D_NFL/board', { drafts: [NFL] })
    await waitFor(() => expect(done.leagueSection()).not.toBeNull())
    expect(done.subTabLabels()).not.toContain('Follow live')
  })

  it('marks Follow live current on the live route, with its pulsing dot', async () => {
    const drafting = draftSummary({ sleeperDraftId: 'D_LIVE', sleeperLeagueId: 'L_LIVE', status: 'drafting' })
    const { leagueSection, currentSubTabLabels, container } = renderAtPath('/drafts/D_LIVE/live', { drafts: [drafting] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentSubTabLabels()).toEqual(['Follow live'])
    expect(container.querySelector('a.league-subtab.live .league-live-dot')).not.toBeNull()
  })

  it('lights Draft on an auction league’s ADP page, which has a board row but no sub-tab strip', async () => {
    const auction = draftSummary({ sleeperDraftId: 'D_A', sleeperLeagueId: 'L_A', sport: 'nba', draftType: 'auction', status: 'complete' })
    const { leagueSection, currentTabLabels, container } = renderAtPath('/leagues/L_A/adp', { drafts: [auction] })

    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(currentTabLabels()).toEqual(['Draft'])
    // One row and no action: a one-item strip would say nothing.
    expect(container.querySelector('.league-subtabs')).toBeNull()
  })

  it('renders no sub-tab strip on a tab with a single page', async () => {
    const { leagueSection, container } = renderAtPath('/leagues/L_NFL', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(container.querySelector('.league-subtabs')).toBeNull()
  })

  it('exposes the tab strip and the sub-tab strip as named navigation landmarks', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/power', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(screen.getByRole('navigation', { name: 'League' })).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Season pages' })).toBeTruthy()
  })
})

describe('Mock it', () => {
  function Probe() {
    const loc = useLocation()
    return <p data-testid="probe">{`${loc.pathname} ${JSON.stringify(loc.state)}`}</p>
  }

  it('opens the new-mock modal on Home with this league as the seed', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/drafts/D_NFL/board', { drafts: [NFL], page: <Probe /> })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    await user.click(screen.getByRole('button', { name: 'Mock it' }))

    const probe = screen.getByTestId('probe').textContent ?? ''
    expect(probe.startsWith('/ ')).toBe(true)
    expect(JSON.parse(probe.slice(2))).toEqual({ openMock: true, mockSeed: { sport: 'nfl', leagueId: 'L_NFL' } })
  })
})

/*
 * specs/009-auto-data-refresh T026/T028: the header starts a refresh when it lands in a league,
 * follows it while it runs, and tells the pages when it is done.
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
    await user.click(await screen.findByRole('button', { name: /switch league/i }))
    expect(refreshLeagueSpy).toHaveBeenCalledTimes(1)

    await user.click(await screen.findByRole('menuitem', { name: /Hoops League/ }))
    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(2))
    expect(refreshLeagueSpy).toHaveBeenLastCalledWith('L_NBA')
  })

  // Plan review PR-16: compact only hides the line the hook feeds, the hook still runs.
  it('still refreshes the league from a draft room, where the line is not shown', async () => {
    const { leagueSection, container } = renderAtPath('/drafts/D_NFL/board', {
      drafts: [NFL],
      refresh: refreshStatus({ state: 'RUNNING', leagueSleeperId: 'L_NFL' }),
    })

    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(1))
    expect(refreshLeagueSpy).toHaveBeenCalledWith('L_NFL')
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await flush()
    expect(container.querySelector('.league-header-refresh')).toBeNull()
    expect(refreshLeagueSpy).toHaveBeenCalledTimes(1)
  })

  it('polls while RUNNING, bumps the data version on FRESH, and stops polling', async () => {
    const running = refreshStatus({ state: 'RUNNING', leagueSleeperId: 'L_NFL' })
    const fresh = refreshStatus({
      state: 'FRESH',
      leagueSleeperId: 'L_NFL',
      lastSuccessAt: new Date().toISOString(),
    })

    // The poll is one setTimeout(REFRESH_POLL_MS) at a time. Catch just those and fire them by
    // hand: fake timers would also stall waitFor and testing-library's own scheduling, and a real
    // 3 s wait is a slow test.
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

  // specs/011 T005: a page for an older season is keyed by that season's own league id, so the
  // header must bump every season of the lineage. Pins the loop in leagueRefresh.ts
  // (`for (const id of seasonIdsRef.current) bump(id)`).
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
    const { leagueSection, container } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    // Before the league resolves, so the one POST hits the failing stub.
    refreshLeagueSpy.mockRejectedValue(new Error('network'))

    await waitFor(() => expect(refreshLeagueSpy).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    await flush()

    expect(container.querySelector('.league-header-refresh')).toBeNull()
    expect(getLeagueRefreshSpy).not.toHaveBeenCalled()
    expect(refreshLeagueSpy).toHaveBeenCalledTimes(1)
  })
})

/*
 * specs/011 US1: the season dropdown keeps the page you are on. Two-season NFL lineage, 2025
 * complete. (b), (c), (d) pin behaviour that already held; (a), (g), (e) and (f1) are the bug.
 */
describe('the season dropdown keeps the page', () => {
  async function openSeasons(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: /^Season: / }))
    return screen.getAllByRole('menuitem')
  }
  const hrefFor = (items: HTMLElement[], year: string) =>
    items.find((m) => m.textContent?.startsWith(year))!.getAttribute('href')

  it('(a) keeps a one-season page: superlatives 2026 -> 2025', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/L_NFL26/superlatives', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(hrefFor(await openSeasons(user), '2025')).toBe('/leagues/L_NFL25/superlatives')
  })

  it("(b) on the board, the other year is that year's board", async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/drafts/D_NFL26/board', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(hrefFor(await openSeasons(user), '2025')).toBe('/drafts/D_NFL25/board')
  })

  it('(c) on History every year is this same page, so the season is a label, not a menu', async () => {
    // History walks the whole season chain itself: each year's target is the URL you are on. A
    // dropdown whose every item does nothing read as broken (code review CR-02).
    const { leagueSection } = renderAtPath('/leagues/L_NFL26/history', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(screen.queryByRole('button', { name: /^Season:/ })).toBeNull()
    expect(leagueSection()!.querySelector('.league-season-static')?.textContent).toBe('2026')
  })

  it('(d) names the year being viewed on the trigger and marks it in the menu', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/L_NFL25/weekly-report', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(await screen.findByRole('button', { name: 'Season: 2025' })).toBeTruthy()
    const items = await openSeasons(user)
    expect(items.find((m) => m.textContent?.startsWith('2025'))!.classList.contains('on')).toBe(true)
    expect(items.find((m) => m.textContent?.startsWith('2026'))!.classList.contains('on')).toBe(false)
  })

  it('(g) does not carry the query string across a season switch', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/L_NFL26/weekly-report?week=5', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(hrefFor(await openSeasons(user), '2025')).toBe('/leagues/L_NFL25/weekly-report')
  })

  it('(f1) from Follow live on a pre_draft season, a complete year goes to its board, not History', async () => {
    const user = userEvent.setup()
    const pre26 = { ...NFL26, status: 'pre_draft' }
    const { leagueSection } = renderAtPath('/drafts/D_NFL26/live', { drafts: [pre26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(hrefFor(await openSeasons(user), '2025')).toBe('/drafts/D_NFL25/board')
  })

  // A hinted page (a mock room, a manager's history) has no league destination of its own, so no
  // page is current. Falling back to History there sent every year to the same URL; the board is
  // the per-year page.
  it('(h) on a hinted mock route, a year goes to its board, not History', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/mock/4', {
      drafts: [NFL26, NFL25],
      state: { railLeagueId: 'L_NFL26' },
    })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(hrefFor(await openSeasons(user), '2025')).toBe('/drafts/D_NFL25/board')
  })

  it('shows a lone season as plain text, not a dropdown', async () => {
    const { leagueSection } = renderAtPath('/leagues/L_NFL/history', { drafts: [NFL] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    expect(screen.queryByRole('button', { name: /^Season: / })).toBeNull()
    expect(leagueSection()!.querySelector('.league-season-static')?.textContent).toBe('2026')
  })

  it("on the stat calculator, the other year's link carries the query and strips own=r:", async () => {
    const user = userEvent.setup()
    const nba25 = draftSummary({ sleeperDraftId: 'D_NBA25', sleeperLeagueId: 'L_NBA25', sport: 'nba', season: 2025 })
    const nba26 = draftSummary({ sleeperDraftId: 'D_NBA26', sleeperLeagueId: 'L_NBA26', previousLeagueId: 'L_NBA25', sport: 'nba', season: 2026 })
    const { leagueSection } = renderAtPath('/leagues/L_NBA26/stats/calculator?f=pts%2Bast&own=r%3A3', { drafts: [nba26, nba25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())
    const href = hrefFor(await openSeasons(user), '2025')!
    expect(href.startsWith('/leagues/L_NBA25/stats/calculator?')).toBe(true)
    const q = new URLSearchParams(href.split('?')[1])
    expect(q.get('f')).toBe('pts+ast')
    expect(q.has('own')).toBe(false)
  })

  // Keyboard contract shared by every popup in the chrome.
  it('moves focus into the menu on open, closes on Escape, and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    const { leagueSection } = renderAtPath('/leagues/L_NFL26/superlatives', { drafts: [NFL26, NFL25] })
    await waitFor(() => expect(leagueSection()).not.toBeNull())

    const trigger = await screen.findByRole('button', { name: /^Season: / })
    await user.click(trigger)
    const menu = screen.getByRole('menu')
    expect(menu.contains(document.activeElement)).toBe(true)

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })
})
