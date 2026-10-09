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

export const statsCacheKey = (sleeperLeagueId: string, window: PlayerWindowKind) => `${sleeperLeagueId}|${window}`

export function fetchDraftStats(sleeperLeagueId: string, window: PlayerWindowKind): Promise<StatLeaderboard> {
  const key = statsCacheKey(sleeperLeagueId, window)
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
}
