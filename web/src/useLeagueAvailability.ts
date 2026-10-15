import { useEffect, useState } from 'react'
import { getLeagueAvailability, type LeagueAvailability, type LeagueUnavailable } from './api'

/**
 * Spec 035 FR-008: "which pages can this league honestly show?", read once per league route.
 *
 * This hook only READS the server's `LeagueAvailability`. It has no list of its own: which page keys a
 * league lacks is decided on the server, by the same object that makes the server refuse the matching
 * routes with a 422. Adding an unavailable page therefore means changing `LeagueAvailability.java`, not this
 * file.
 *
 * A null key asks nothing and stays `loading`; a caller that does not want an answer must not read one.
 *
 * `error` is deliberately not "unavailable": a failed availability read leaves the page to render and the
 * server to refuse if it has to. A gate that failed closed would hide every Sleeper page behind one flaky
 * request.
 */
export type AvailabilityState =
  | { status: 'loading' }
  | { status: 'ok'; data: LeagueAvailability }
  | { status: 'error' }

/** One in-flight/settled read per league, tagged with the data version it was made for. */
const cache = new Map<string, { version: number; read: Promise<LeagueAvailability> }>()

function read(leagueKey: string, version: number): Promise<LeagueAvailability> {
  const hit = cache.get(leagueKey)
  if (hit && hit.version === version) return hit.read
  const fresh = getLeagueAvailability(leagueKey)
  cache.set(leagueKey, { version, read: fresh })
  // A failed read must not be cached: the next visit asks again.
  fresh.catch(() => {
    if (cache.get(leagueKey)?.read === fresh) cache.delete(leagueKey)
  })
  return fresh
}

/** Drops what is cached (all leagues, or one), e.g. after cookies are replaced. */
export function invalidateLeagueAvailability(leagueKey?: string): void {
  if (leagueKey) cache.delete(leagueKey)
  else cache.clear()
}

/**
 * @param version the league's data version (`useLeagueDataVersion`): a finished background refresh can change
 *                what a league can show (player points arrive, a failure clears), so it asks again.
 */
export function useLeagueAvailability(leagueKey: string | null | undefined, version = 0): AvailabilityState {
  const [state, setState] = useState<AvailabilityState>({ status: 'loading' })
  useEffect(() => {
    if (!leagueKey) {
      // Nothing to ask (yet): stay "loading", never "error", so a caller that passes a key a moment later
      // cannot see one render of a settled state it never read.
      setState({ status: 'loading' })
      return
    }
    let cancelled = false
    setState((prev) => (prev.status === 'ok' && prev.data.leagueKey === leagueKey ? prev : { status: 'loading' }))
    read(leagueKey, version)
      .then((data) => {
        if (!cancelled) setState({ status: 'ok', data })
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error' })
      })
    return () => {
      cancelled = true
    }
  }, [leagueKey, version])
  return state
}

/** Why this page is unavailable, or null when it is available, unknown, or not yet read. */
export function unavailableReason(state: AvailabilityState, page: string): LeagueUnavailable | null {
  return state.status === 'ok' ? (state.data.unavailable[page] ?? null) : null
}

/** True once the read has finished either way (loading is the only unsettled state). */
export function isSettled(state: AvailabilityState): boolean {
  return state.status !== 'loading'
}
