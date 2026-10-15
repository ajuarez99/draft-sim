import { useState } from 'react'
import { Link } from 'react-router-dom'
import { addEspnLeague, EspnSetupError, type EspnAddResult, type EspnAddRequest } from '../api'
import { invalidateRailLeagues } from '../railLeague'
import UnmatchedPlayersNote from './UnmatchedPlayersNote'

/** Human text for every contract error code (contracts/espn-setup.md). Falls back to the server's own fixed text. */
export function espnErrorText(e: unknown): string {
  if (!(e instanceof EspnSetupError)) return 'Something went wrong adding that league.'
  switch (e.code) {
    case 'MALFORMED_REF':
      return "That doesn't look like an ESPN fantasy league link. Paste the address from your league's page."
    case 'ESPN_NOT_FOUND':
      return 'ESPN has no league with that id.'
    case 'ESPN_PRIVATE_NO_ACCESS':
      return 'This ESPN league is private. Add your ESPN login cookies below to read it.'
    case 'ESPN_CREDENTIAL_REJECTED':
      return 'ESPN rejected those cookies for this league. Check that you copied both values.'
    case 'UNSUPPORTED_SCORING':
      return e.message
    case 'CREDENTIALS_NOT_CONFIGURED':
      return 'Private ESPN leagues are not enabled on this server.'
    case 'ESPN_SHAPE_CHANGED':
      return `ESPN sent data Ball Knowers could not read. ${e.message}`
    case 'ALREADY_CLAIMED':
      return 'Someone else has already claimed that team.'
    case 'TEAM_NOT_FOUND':
      return 'That team is not in this league.'
    case 'MALFORMED_CREDENTIAL':
      return 'Those cookies were not accepted as typed. Paste each value on its own, with no spaces or semicolons.'
    case 'SIGN_IN_REQUIRED':
      return 'Sign in first.'
    default:
      return e.code.startsWith('ESPN_HTTP_') ? 'ESPN could not be reached. Try again in a minute.' : e.message
  }
}

const EMPTY_REQUEST: EspnAddRequest = {
  link: null, leagueId: null, sport: null, season: null, credential: null, claimRosterId: null,
}

/**
 * "Add an ESPN league" (spec 035 T033). Three steps: paste a link (nothing is written), pick "this is my
 * team" (the same request again with the claim, which writes), done. If ESPN says the league is private,
 * the two cookie fields appear.
 */
export default function EspnLeagueSetup({ onAdded }: { onAdded?: () => void }) {
  const [link, setLink] = useState('')
  const [needCookies, setNeedCookies] = useState(false)
  const [espnS2, setEspnS2] = useState('')
  const [swid, setSwid] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<EspnAddResult | null>(null)
  const [done, setDone] = useState<EspnAddResult | null>(null)

  function request(claim: number | null): EspnAddRequest {
    const credential = needCookies && espnS2.trim() && swid.trim()
      ? { espnS2: espnS2.trim(), swid: swid.trim() }
      : null
    return { ...EMPTY_REQUEST, link: link.trim(), credential, claimRosterId: claim }
  }

  async function run(claim: number | null) {
    setBusy(true)
    setError(null)
    try {
      const result = await addEspnLeague(request(claim))
      if (result.written) {
        setDone(result)
        setPreview(null)
        // The rail caches the league list; a new league is invisible to it until it is dropped.
        invalidateRailLeagues()
        onAdded?.()
      } else {
        setPreview(result)
      }
    } catch (e) {
      if (e instanceof EspnSetupError && (e.code === 'ESPN_PRIVATE_NO_ACCESS' || e.code === 'ESPN_CREDENTIAL_REJECTED')) {
        setNeedCookies(true)
      }
      setError(espnErrorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <details className="panel add-draft league-data espn-setup">
      <summary className="league-data-summary">
        <span>Add an ESPN league</span>
        <span className="muted small">By link: football or basketball, points scoring</span>
      </summary>

      {done ? (
        <>
          <p>
            Added <strong>{done.name}</strong> ({done.seasons.length} season{done.seasons.length === 1 ? '' : 's'}).{' '}
            <Link to={`/leagues/${done.leagueKey}`}>Open league</Link>
          </p>
          <UnmatchedPlayersNote count={done.unmatched.length} players={done.unmatched} />
        </>
      ) : (
        <>
          <p className="muted small">
            Paste the link from your ESPN league&apos;s page. Only points leagues work; category and roto leagues are refused.
          </p>
          {error && <div className="error">{error}</div>}
          <div className="controls">
            <label>
              ESPN league link
              <input
                value={link}
                onChange={(e) => { setLink(e.target.value); setPreview(null) }}
                onKeyDown={(e) => e.key === 'Enter' && !busy && link.trim() && run(null)}
                size={32}
                placeholder="https://fantasy.espn.com/basketball/league?leagueId=…"
              />
            </label>
            {!preview && (
              <button onClick={() => run(null)} disabled={busy || !link.trim()}>
                {busy ? 'Reading…' : 'Look up league'}
              </button>
            )}
          </div>

          {needCookies && (
            <div className="espn-cookies">
              <p className="espn-warning">
                <strong>These are your ESPN login cookies, not a league-only key.</strong> Ball Knowers stores them
                encrypted and only uses them to read this league.
              </p>
              <p className="espn-cookie-help">
                In your browser, open ESPN, then the developer tools, Application (or Storage), Cookies, and copy the
                values named <code>espn_s2</code> and <code>SWID</code>.
              </p>
              <div className="controls">
                <label>
                  espn_s2
                  <input type="password" autoComplete="off" value={espnS2} onChange={(e) => setEspnS2(e.target.value)} />
                </label>
                <label>
                  SWID
                  <input type="password" autoComplete="off" value={swid} onChange={(e) => setSwid(e.target.value)} />
                </label>
                <button onClick={() => run(null)} disabled={busy || !link.trim() || !espnS2.trim() || !swid.trim()}>
                  Try again with cookies
                </button>
              </div>
            </div>
          )}

          {preview && (
            <div className="espn-preview">
              <p>
                <strong>{preview.name}</strong> &middot; {preview.sport.toUpperCase()} &middot; {preview.seasons.length} season
                {preview.seasons.length === 1 ? '' : 's'} found
                {preview.missingSeasons.length > 0 && ` (${preview.missingSeasons.length} could not be read)`}
              </p>
              {Object.keys(preview.unavailable).includes('stats') && (
                <p className="muted small">
                  This league&apos;s ESPN scoring has rules Ball Knowers can&apos;t translate yet, so player-stat pages will say so.
                </p>
              )}
              <UnmatchedPlayersNote count={preview.unmatched.length} players={preview.unmatched} />
              <p>Which team is yours?</p>
              <ul className="espn-teams">
                {preview.teams.map((t) => (
                  <li key={t.rosterId} className="espn-team">
                    <span>
                      {t.name}
                      {t.managerName ? <span className="muted small"> &middot; {t.managerName}</span> : null}
                    </span>
                    {t.claimedByOther ? (
                      <span className="muted small">Claimed</span>
                    ) : (
                      <button disabled={busy} onClick={() => run(t.rosterId)}>
                        This is my team
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </details>
  )
}
