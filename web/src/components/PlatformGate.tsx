import { useEffect, useState, type ReactNode } from 'react'
import type { Platform } from '../api'
import { cachedDrafts } from '../railLeague'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { isSettled, unavailableReason, useLeagueAvailability } from '../useLeagueAvailability'
import NotAvailableForPlatform from './NotAvailableForPlatform'

/**
 * Renders `children` only when this league can show `page` (spec 035 T042). While availability is being read
 * nothing renders, so the page's own requests are never made for a league that would refuse them; once read,
 * an unavailable page shows the platform message instead. A failed read renders the page (see
 * `useLeagueAvailability`).
 *
 * Give it a `leagueKey`, or a `draftId` for the draft pages, which are addressed by draft.
 *
 * <b>A Sleeper league costs no request here.</b> The platform is read first, off the same cached league
 * list the rest of the app already holds (`DraftSummary.platform`); only an ESPN league (or one that list
 * does not know) asks the availability endpoint. Sleeper pages, including the live draft room, render as
 * they did before this existed (spec 035 FR-013).
 */
export default function PlatformGate({
  page,
  leagueKey,
  draftId,
  children,
}: {
  page: string
  leagueKey?: string
  draftId?: string
  children: ReactNode
}) {
  const where = usePlatformOf(leagueKey, draftId)
  const key = where.status === 'found' ? where.leagueKey : leagueKey
  const version = useLeagueDataVersion(key)
  // Only ESPN (or an unknown league addressed by key) is asked. A Sleeper league, or a draft the list does
  // not know, is never asked.
  const ask = where.status === 'found' ? where.platform !== 'SLEEPER' : where.status === 'unknown' && !!leagueKey
  const availability = useLeagueAvailability(ask ? key : null, version)

  if (where.status === 'loading') return null
  if (where.status === 'found' && where.platform === 'SLEEPER') return <>{children}</>
  if (!ask) return <>{children}</>
  if (!isSettled(availability)) return null
  const reason = unavailableReason(availability, page)
  if (reason && availability.status === 'ok') {
    return <NotAvailableForPlatform platform={availability.data.platform} reason={reason} leagueKey={key} />
  }
  return <>{children}</>
}

export type PlatformOf =
  | { status: 'loading' }
  | { status: 'unknown' }
  | { status: 'found'; leagueKey: string; platform: Platform }

/**
 * The platform of a league (by key) or of the league a draft belongs to, from the cached league list.
 * `unknown` covers both "not in the list" and "the list failed": callers fail open on it.
 */
export function usePlatformOf(leagueKey: string | undefined, draftId?: string): PlatformOf {
  const [state, setState] = useState<PlatformOf>({ status: 'loading' })
  useEffect(() => {
    if (!leagueKey && !draftId) {
      setState({ status: 'unknown' })
      return
    }
    let live = true
    setState({ status: 'loading' })
    cachedDrafts()
      .then((drafts) => {
        const d = draftId
          ? drafts.find((x) => x.sleeperDraftId === draftId)
          : drafts.find((x) => x.sleeperLeagueId === leagueKey)
        if (live) {
          setState(d ? { status: 'found', leagueKey: d.sleeperLeagueId, platform: d.platform ?? 'SLEEPER' } : { status: 'unknown' })
        }
      })
      .catch(() => {
        if (live) setState({ status: 'unknown' })
      })
    return () => {
      live = false
    }
  }, [leagueKey, draftId])
  return state
}
