import { render, type RenderResult } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { vi, type MockInstance } from 'vitest'
import AppShell from './components/AppShell'
import * as api from './api'
import type { DraftSummary, ManagerSummary } from './api'
import { invalidateRailLeagues } from './railLeague'
import { invalidateSearchIndex } from './searchIndex'
import { clearUser, setUser } from './user'

/*
 * Renders the real shell at an arbitrary pathname, so a test can ask the one
 * question this feature is about: standing on this URL, what does the rail
 * show?
 *
 * Deliberately little mocking. The user is signed in through `setUser`, the
 * real code path, rather than a stubbed `useUser` -- the rail renders nothing
 * at all while signed out, and a stub that skipped that would hide it. Only
 * `getDrafts` is stubbed, because it is the one network call the rail makes.
 */

/** A draft row with every field the rail reads, overridable per test. */
export function draftSummary(over: Partial<DraftSummary> = {}): DraftSummary {
  return {
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
}

/** The spies the last `renderAtPath` installed -- so a test can count calls
 *  and assert what was and wasn't fetched. */
export let getDraftsSpy: MockInstance<typeof api.getDrafts>
export let getManagersSpy: MockInstance<typeof api.getManagers>

export type RenderedRail = RenderResult & {
  /** The rail element, or null when the shell rendered none. */
  rail: () => HTMLElement | null
  /** The league-context block, or null when the rail dropped it -- the single
   *  assertion most of this feature's tests are making. */
  leagueSection: () => HTMLElement | null
  /** Labels of the LEAGUE section's rows, in render order. Scoped to the
   *  league block on purpose -- the global Menu rows (Home, Managers, Mock
   *  drafts) are there on every route and would drown the assertion. */
  rowLabels: () => string[]
  /** Labels of the league rows currently marked `.on`. */
  currentRowLabels: () => string[]
  /** Labels of every row in the rail, league and global alike. */
  allRowLabels: () => string[]
}

/**
 * Signs a user in, stubs the draft list, and renders `AppShell` at `pathname`.
 *
 * The rail resolves its league asynchronously (railLeague.ts fetches and
 * caches), so callers must `await` something before asserting -- use
 * `await waitFor(() => expect(leagueSection()).not.toBeNull())` rather than
 * asserting synchronously.
 */
export function renderAtPath(pathname: string, options: RenderAtPathOptions = {}): RenderedRail {
  const drafts = options.drafts ?? [draftSummary()]

  // Module-scope caches in railLeague.ts and searchIndex.ts: without this, the
  // first test's league list would answer every later test's lookup.
  invalidateRailLeagues()
  invalidateSearchIndex()
  getDraftsSpy = vi.spyOn(api, 'getDrafts').mockResolvedValue(drafts)
  getManagersSpy = vi.spyOn(api, 'getManagers').mockResolvedValue(options.managers ?? [])

  setUser({
    sleeperUserId: 'U1',
    username: 'tester',
    displayName: 'Tester',
    avatar: null,
  })

  const result = render(
    <MemoryRouter initialEntries={[{ pathname, state: options.state ?? null }]}>
      <AppShell>
        <p>page</p>
      </AppShell>
    </MemoryRouter>,
  )

  const rail = () => result.container.querySelector<HTMLElement>('nav.app-rail')
  const leagueSection = () => result.container.querySelector<HTMLElement>('.app-rail-league')

  const labelsIn = (root: Element | null, selector: string) =>
    root ? [...root.querySelectorAll(selector)].map((e) => (e.textContent ?? '').trim()) : []

  return {
    ...result,
    rail,
    leagueSection,
    rowLabels: () => labelsIn(leagueSection(), '.app-rail-row .app-rail-row-label'),
    currentRowLabels: () => labelsIn(leagueSection(), '.app-rail-row.on .app-rail-row-label'),
    allRowLabels: () => labelsIn(rail(), '.app-rail-row .app-rail-row-label'),
  }
}

/** Signs out and drops the league cache. Call from `afterEach`. */
export function resetRail() {
  clearUser()
  invalidateRailLeagues()
  invalidateSearchIndex()
  vi.restoreAllMocks()
}
