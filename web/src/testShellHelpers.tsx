import type { ReactNode } from 'react'
import { render, type RenderResult } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { vi, type MockInstance } from 'vitest'
import AppShell from './components/AppShell'
import * as api from './api'
import type { DraftSummary, ManagerSummary, RefreshStatus } from './api'
import { invalidateRailLeagues } from './railLeague'
import { invalidateSearchIndex } from './searchIndex'
import { clearUser, setUser } from './user'

/*
 * Renders the real shell at an arbitrary pathname, so a test can ask the one
 * question this feature is about: standing on this URL, what does the chrome
 * show?
 *
 * Deliberately little mocking. The user is signed in through `setUser`, the
 * real code path, rather than a stubbed `useUser` -- the shell renders nothing
 * at all while signed out, and a stub that skipped that would hide it. Only
 * `getDrafts` is stubbed, because it is the one network call the shell makes.
 */

/** A draft row with every field the shell reads, overridable per test. */
export function draftSummary(over: Partial<DraftSummary> = {}): DraftSummary {
  return {
    platform: 'SLEEPER', draftType: 'snake',
    id: 1,
    sleeperDraftId: 'D1',
    leagueId: 1,
    leagueName: 'Test League',
    season: 2026,
    teams: 12,
    rounds: 15,
    status: 'complete',
    startTime: null,
    sleeperLeagueId: 'L1',
    previousLeagueId: null,
    sport: 'nfl',
    ...over,
  }
}

export type RenderAtPathOptions = {
  /** What `getDrafts()` resolves to. Defaults to a single complete NFL league. */
  drafts?: DraftSummary[]
  /** What `getManagers(sport)` resolves to, for the palette's index. */
  managers?: ManagerSummary[]
  /** Route state, for the destinations that carry their league that way. */
  state?: unknown
  /** What `refreshLeague` (the league header's on-visit POST) resolves to. Defaults to FRESH. */
  refresh?: RefreshStatus
  /** What each `getLeagueRefresh` poll resolves to, in order; the last one repeats. */
  polls?: RefreshStatus[]
  /** What renders inside the shell's page area. Defaults to a bare paragraph. */
  page?: ReactNode
}

/** A refresh status body with every field the header reads, overridable per test. */
export function refreshStatus(over: Partial<RefreshStatus> = {}): RefreshStatus {
  return {
    state: 'FRESH',
    leagueSleeperId: 'L1',
    season: 2026,
    lastSuccessAt: null,
    lastFailureAt: null,
    seasons: [],
    ...over,
  }
}

/** The spies the last `renderAtPath` installed -- so a test can count calls
 *  and assert what was and wasn't fetched. */
export let getDraftsSpy: MockInstance<typeof api.getDrafts>
export let getManagersSpy: MockInstance<typeof api.getManagers>
export let refreshLeagueSpy: MockInstance<typeof api.refreshLeague>
export let getLeagueRefreshSpy: MockInstance<typeof api.getLeagueRefresh>

export type RenderedShell = RenderResult & {
  /** The top bar, or null when the shell rendered none (signed out). */
  topBar: () => HTMLElement | null
  /** The league identity block -- the league header's own row, or the compact top bar's league
   *  slot -- or null when the shell dropped league context. The single assertion most of this
   *  feature's tests are making. */
  leagueSection: () => HTMLElement | null
  /** Labels of the league tabs, in order. */
  tabLabels: () => string[]
  /** Labels of the tabs marked current (aria-current="page"). */
  currentTabLabels: () => string[]
  /** Labels of the active tab's sub-tab links, in order (not the action button). */
  subTabLabels: () => string[]
  /** Labels of the sub-tab actions (Mock it). */
  subActionLabels: () => string[]
  /** Labels of the sub-tab links marked current. */
  currentSubTabLabels: () => string[]
}

/**
 * Signs a user in, stubs the draft list, and renders `AppShell` at `pathname`.
 *
 * The shell resolves its league asynchronously (railLeague.ts fetches and
 * caches), so callers must `await` something before asserting -- use
 * `await waitFor(() => expect(leagueSection()).not.toBeNull())` rather than
 * asserting synchronously.
 */
export function renderAtPath(pathname: string, options: RenderAtPathOptions = {}): RenderedShell {
  const drafts = options.drafts ?? [draftSummary()]

  // Module-scope caches in railLeague.ts and searchIndex.ts: without this, the
  // first test's league list would answer every later test's lookup.
  invalidateRailLeagues()
  invalidateSearchIndex()
  getDraftsSpy = vi.spyOn(api, 'getDrafts').mockResolvedValue(drafts)
  getManagersSpy = vi.spyOn(api, 'getManagers').mockResolvedValue(options.managers ?? [])

  // The league header POSTs a refresh on every league it lands in. Stubbed for every
  // test so none of them reaches for the network, and so a test can count calls.
  refreshLeagueSpy = vi.spyOn(api, 'refreshLeague').mockResolvedValue(options.refresh ?? refreshStatus())
  getLeagueRefreshSpy = vi.spyOn(api, 'getLeagueRefresh')
  const polls = options.polls ?? [options.refresh ?? refreshStatus()]
  let pollIndex = 0
  getLeagueRefreshSpy.mockImplementation(() =>
    Promise.resolve(polls[Math.min(pollIndex++, polls.length - 1)]),
  )

  setUser({
    sleeperUserId: 'U1',
    username: 'tester',
    displayName: 'Tester',
    avatar: null,
  })

  // A `?query` in `pathname` is split off: MemoryRouter would otherwise treat
  // it as part of the path and match no route.
  const [pathOnly, ...queryParts] = pathname.split('?')
  const search = queryParts.length ? `?${queryParts.join('?')}` : ''

  const result = render(
    <MemoryRouter initialEntries={[{ pathname: pathOnly, search, state: options.state ?? null }]}>
      <AppShell>
        {options.page ?? <p>page</p>}
      </AppShell>
    </MemoryRouter>,
  )

  const topBar = () => result.container.querySelector<HTMLElement>('header.topbar')
  const leagueSection = () => result.container.querySelector<HTMLElement>('.league-header:not(.compact) .league-header-row, .league-compact')

  const labelsIn = (selector: string) =>
    [...result.container.querySelectorAll(selector)].map((e) => (e.textContent ?? '').trim())

  return {
    ...result,
    topBar,
    leagueSection,
    tabLabels: () => labelsIn('nav[aria-label="League"] a.league-tab'),
    currentTabLabels: () => labelsIn('nav[aria-label="League"] a.league-tab[aria-current="page"]'),
    subTabLabels: () => labelsIn('.league-subtabs a.league-subtab'),
    subActionLabels: () => labelsIn('.league-subtabs-actions button'),
    currentSubTabLabels: () => labelsIn('.league-subtabs a.league-subtab[aria-current="page"]'),
  }
}

/** Signs out and drops the league cache. Call from `afterEach`. */
export function resetShell() {
  clearUser()
  invalidateRailLeagues()
  invalidateSearchIndex()
  vi.restoreAllMocks()
}
