import { apiFetch, json } from './http'

// --- specs/009-auto-data-refresh T024: refresh on visit ---

/**
 * Mirrors refresh/LeagueRefreshService.State. FAILED means the last attempt
 * failed and no newer success exists; COMPLETE means the season is fully loaded
 * and is never fetched again.
 */
export type RefreshState = 'FRESH' | 'RUNNING' | 'FAILED' | 'COMPLETE'

/**
 * Mirrors RefreshController's body (contracts/refresh-api.md). `leagueSleeperId`
 * and `season` name the league-season the pages show after the resolver walks
 * back, not necessarily the URL's. Both timestamps are null until the first
 * success or failure, and the backend builds this body from a mutable map for
 * that reason.
 */
export type RefreshStatus = {
  state: RefreshState
  leagueSleeperId: string
  season: number
  lastSuccessAt: string | null
  lastFailureAt: string | null
  seasons: { leagueSleeperId: string; season: number; state: RefreshState }[]
}

/** Starts a background refresh if the league is stale and returns at once. Called once per league change by the rail. */
export const refreshLeague = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/refresh`, { method: 'POST' }).then(json<RefreshStatus>)

/** Same body, starts nothing. The rail polls this while a refresh runs. */
export const getLeagueRefresh = (sleeperLeagueId: string) =>
  apiFetch(`/api/leagues/${sleeperLeagueId}/refresh`).then(json<RefreshStatus>)
