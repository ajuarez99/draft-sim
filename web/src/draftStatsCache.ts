import { getStatLeaderboard } from './api'
import type { PlayerWindowKind, StatLeaderboard } from './api'

/**
 * Leaderboard responses for the draft room's Stats view, shared across panel mounts (spec 023,
 * code-review S1 + S5). Module-level on purpose: a mock room unmounts the sheet between the
 * member's turns, and a panel-local record would refetch the whole leaderboard (about 327 KB
 * gzipped) every turn. The cache holds the *promise*, so two mounts asking for one key share one
 * request. A rejected promise is evicted so a later open can retry.
 */
const cache = new Map<string, Promise<StatLeaderboard>>()
/** The league data version each cached promise was fetched under (only for callers that pass one). */
const versions = new Map<string, number>()

export const statsCacheKey = (sleeperLeagueId: string, window: PlayerWindowKind) => `${sleeperLeagueId}|${window}`

/**
 * `version` is the league's data version (`useLeagueDataVersion`): a caller that passes a newer one than the
 * cached entry was fetched under gets a fresh request, so a nightly refresh isn't hidden for the life of the tab
 * (spec 049 review N2). Callers that omit it keep the old behaviour.
 */
export function fetchDraftStats(sleeperLeagueId: string, window: PlayerWindowKind, version?: number): Promise<StatLeaderboard> {
  const key = statsCacheKey(sleeperLeagueId, window)
  if (version != null && version > (versions.get(key) ?? 0)) {
    cache.delete(key)
    versions.set(key, version)
  }
  const hit = cache.get(key)
  if (hit) return hit
  const p = getStatLeaderboard(sleeperLeagueId, window)
  cache.set(key, p)
  p.catch(() => {
    if (cache.get(key) === p) cache.delete(key)
  })
  return p
}

/** For tests: forget everything cached. */
export function clearDraftStatsCache(): void {
  cache.clear()
  versions.clear()
}
