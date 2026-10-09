import { useEffect, useState } from 'react'
import { getDraftPool } from './api'
import type { PlayerRef } from './api'
import { INSIGHT } from './insightConstants'

/**
 * The NBA Stats view's candidate universe (spec 023): the head of the board from /pool, up to
 * the pool cap. Separate from any starter pool a page keeps, because `availability` only holds
 * players the simulation's snapshots surfaced at one of the member's picks, so it is not the
 * undrafted pool. Fetched only for basketball and only once `enabled` (e.g. seats loaded).
 */
export function useStatsPool(draftId: string, enabled: boolean, sport: string): {
  pool: PlayerRef[] | null
  loading: boolean
  error: boolean
} {
  const [pool, setPool] = useState<PlayerRef[] | null>(null)
  const [error, setError] = useState(false)
  const active = enabled && sport === 'nba'
  useEffect(() => {
    if (!active) return
    let cancelled = false
    Promise.resolve()
      .then(() => getDraftPool(draftId, INSIGHT.POOL_LIMIT_MAX))
      .then((p) => {
        if (cancelled) return
        setPool(p)
        setError(false)
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
    return () => {
      cancelled = true
    }
  }, [draftId, active])
  return { pool, loading: active && pool == null && !error, error }
}
