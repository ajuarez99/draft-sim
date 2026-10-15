import { useState } from 'react'
import { putEspnCredential, refreshLeague, type LeagueLastRefresh } from '../api'
import { espnErrorText } from './EspnLeagueSetup'
import { invalidateLeagueAvailability } from '../useLeagueAvailability'
import { useBumpLeagueDataVersion } from '../leagueDataVersion'
import { relativeTime } from '../relativeTime'

/**
 * Plain words for a refresh failure CODE (spec 035 T047/T048). The server stores a code and nothing else, so
 * this is the only place a code becomes a sentence; an unknown code shows as the code itself.
 */
export function refreshFailureText(code: string): string {
  switch (code) {
    case 'ESPN_ACCESS_DENIED':
      return 'ESPN refused the stored login cookies. They may have expired, or the league went private.'
    case 'ESPN_NOT_FOUND':
      return 'ESPN could not find this league.'
    case 'ESPN_SHAPE_CHANGED':
      return 'ESPN sent data in a shape Ball Knowers could not read.'
    case 'UNSUPPORTED_SCORING':
      return "This league's scoring type is not one Ball Knowers supports."
    case 'ESPN_REFRESH_ERROR':
      return 'The refresh failed on our side.'
    default:
      return code.startsWith('ESPN_HTTP_') ? 'ESPN could not be reached.' : 'The refresh failed.'
  }
}

/**
 * "Last refreshed ..." for an ESPN league, and, when the last refresh failed, the failure as text with the
 * code beside it. For the person who stored the cookies a failure also offers "Replace cookies", which
 * reuses the same two fields as the add flow. The data shown is the last good read: a failed refresh never
 * touches it.
 */
export default function EspnRefreshNote({
  leagueKey,
  lastRefresh,
  canReplaceCookies,
}: {
  leagueKey: string
  lastRefresh: LeagueLastRefresh | null
  canReplaceCookies: boolean
}) {
  const bump = useBumpLeagueDataVersion()
  const [open, setOpen] = useState(false)
  const [espnS2, setEspnS2] = useState('')
  const [swid, setSwid] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const failure = lastRefresh?.failure ?? null
  if (!lastRefresh?.succeededAt && !failure) return null

  async function replace() {
    setBusy(true)
    setError(null)
    try {
      await putEspnCredential(leagueKey, espnS2.trim(), swid.trim())
      setOpen(false)
      setEspnS2('')
      setSwid('')
      invalidateLeagueAvailability(leagueKey)
      // New cookies are a reason to look again now; a failure to start one is not worth an error here.
      await refreshLeague(leagueKey).catch(() => undefined)
      bump(leagueKey)
    } catch (e) {
      setError(espnErrorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="espn-refresh-note small" role="status">
      {lastRefresh?.succeededAt && <span className="muted">Last refreshed {relativeTime(lastRefresh.succeededAt).toLowerCase()}. </span>}
      {failure && (
        <span className="espn-refresh-failure">
          The latest refresh failed: {refreshFailureText(failure)} <code>{failure}</code> The numbers below are from the last good read.{' '}
        </span>
      )}
      {failure && canReplaceCookies && !open && (
        <button type="button" onClick={() => setOpen(true)}>
          Replace cookies
        </button>
      )}
      {open && (
        <div className="espn-cookies">
          <p className="espn-warning">
            <strong>These are your ESPN login cookies, not a league-only key.</strong> Ball Knowers stores them encrypted and only
            uses them to read this league.
          </p>
          {error && <div className="error">{error}</div>}
          <div className="controls">
            <label>
              espn_s2
              <input type="password" autoComplete="off" value={espnS2} onChange={(e) => setEspnS2(e.target.value)} />
            </label>
            <label>
              SWID
              <input type="password" autoComplete="off" value={swid} onChange={(e) => setSwid(e.target.value)} />
            </label>
            <button type="button" onClick={replace} disabled={busy || !espnS2.trim() || !swid.trim()}>
              {busy ? 'Checking…' : 'Save cookies'}
            </button>
            <button type="button" onClick={() => setOpen(false)} disabled={busy}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
