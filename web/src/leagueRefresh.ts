import { useEffect, useRef, useState } from 'react'
import { getLeagueRefresh, refreshLeague, type RefreshStatus } from './api'
import { useBumpLeagueDataVersion } from './leagueDataVersion'
import { relativeTime } from './relativeTime'

/** Hand-set, arbitrary (specs/009 refresh contract): how often the league header asks how a running refresh is going. */
export const REFRESH_POLL_MS = 3000

/**
 * Starts the league's background refresh when the league header mounts for it, follows it
 * while it runs, and tells the pages when it finished
 * (specs/009-auto-data-refresh T026, research R1).
 *
 * One POST per league change. While the answer is RUNNING it asks again every
 * {@link REFRESH_POLL_MS} until it is not, and on RUNNING -> FRESH/COMPLETE it
 * bumps the data version of every season in the lineage, which is what makes
 * the pages refetch. A network failure shows nothing and does not retry: a
 * refresh is an improvement to a page that already works, never a reason to
 * hammer the server.
 *
 * `seasonIds` rides in a ref so a lineage re-render cannot restart the POST.
 */
export function useLeagueRefresh(leagueId: string, seasonIds: string[]): RefreshStatus | null {
  const bump = useBumpLeagueDataVersion()
  const [status, setStatus] = useState<RefreshStatus | null>(null)
  const seasonIdsRef = useRef(seasonIds)
  seasonIdsRef.current = seasonIds

  useEffect(() => {
    let live = true
    let wasRunning = false
    let timer: ReturnType<typeof setTimeout> | undefined
    setStatus(null)

    function apply(next: RefreshStatus) {
      if (!live) return
      setStatus(next)
      if (next.state === 'RUNNING') {
        wasRunning = true
        timer = setTimeout(poll, REFRESH_POLL_MS)
      } else if (wasRunning) {
        wasRunning = false
        if (next.state === 'FRESH' || next.state === 'COMPLETE') {
          for (const id of seasonIdsRef.current) bump(id)
        }
      }
    }

    function poll() {
      getLeagueRefresh(leagueId)
        .then(apply)
        .catch(() => {
          // Stop asking, and stop claiming "Updating…" about a run we can no longer see.
          if (live) setStatus(null)
        })
    }

    // Inside a wrapper so a synchronous throw from the fetch layer (it does
    // under a partial module mock) is a swallowed failure, not a crashed shell.
    Promise.resolve()
      .then(() => refreshLeague(leagueId))
      .then(apply)
      .catch(() => {})

    return () => {
      live = false
      if (timer) clearTimeout(timer)
    }
  }, [leagueId, bump])

  return status
}

/** "3 h", "12 min", "2 d": short enough for a header line, and never rounds a recent thing up to "0". */
export function shortAge(iso: string, now: Date = new Date()): string {
  const min = Math.max(1, Math.round((now.getTime() - new Date(iso).getTime()) / 60000))
  if (min < 60) return `${min} min`
  const hours = Math.round(min / 60)
  if (hours < 24) return `${hours} h`
  return `${Math.round(hours / 24)} d`
}

/** The league header's one-line refresh state, per the contract's indicator table. Null shows nothing. */
export function refreshLine(status: RefreshStatus | null, now: Date = new Date()): string | null {
  if (!status) return null
  switch (status.state) {
    case 'RUNNING':
      return 'Updating…'
    case 'FAILED':
      return status.lastSuccessAt
        ? `Couldn't reach Sleeper — data from ${shortAge(status.lastSuccessAt, now)} ago`
        : "Couldn't reach Sleeper"
    default: {
      if (!status.lastSuccessAt) return null
      // "Updated Just now" reads wrong, and Today/Yesterday are not sentence-initial either.
      const age = relativeTime(status.lastSuccessAt, now).replace(/^(Just now|Today|Yesterday)$/, (w) =>
        w.toLowerCase(),
      )
      return `Updated ${age}`
    }
  }
}
