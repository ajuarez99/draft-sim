import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getDrafts, ingestLeague, trackDraft, type DraftSummary, type TrackResponse } from '../api'

export default function DraftPicker() {
  const [drafts, setDrafts] = useState<DraftSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [leagueId, setLeagueId] = useState('')
  const [adding, setAdding] = useState(false)
  const [tracking, setTracking] = useState<string | null>(null)
  const [tracked, setTracked] = useState<Record<string, TrackResponse | { failed: string }>>({})

  function refetch() {
    getDrafts().then(setDrafts).catch((e) => setError(e.message))
  }

  useEffect(refetch, [])

  // /track now runs one poll tick synchronously before it answers, so this is
  // also the status refresh -- one button doing both jobs. The number that
  // matters on draft night is seatsMapped: 0 means every seat in the room is a
  // league-average bot, and it is better to find that out here than at 8:15.
  async function track(sleeperDraftId: string) {
    setTracking(sleeperDraftId)
    setError(null)
    try {
      const r = await trackDraft(sleeperDraftId)
      setTracked((prev) => ({ ...prev, [sleeperDraftId]: r }))
      refetch()
    } catch (e) {
      setTracked((prev) => ({
        ...prev,
        [sleeperDraftId]: { failed: e instanceof Error ? e.message : String(e) },
      }))
    } finally {
      setTracking(null)
    }
  }

  async function addDraft() {
    if (!leagueId.trim()) return
    setAdding(true)
    setError(null)
    try {
      await ingestLeague(leagueId.trim())
      setLeagueId('')
      refetch()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setAdding(false)
    }
  }

  return (
    <div className="content">
      <section className="panel">
        <div className="panel-head">
          <h2>Your drafts</h2>
        </div>

        {error && <div className="error">{error}</div>}

        {drafts && drafts.length === 0 && (
          <p className="muted">No drafts ingested yet — add one below.</p>
        )}

        {drafts && drafts.length > 0 && (
          <div className="draft-list">
            {drafts.map((d) => {
              // draft.status is nullable in the DB. Reading .replace() off it
              // threw a TypeError during render, and with no error boundary
              // above this component that took out the entire picker screen --
              // one unstarted draft row was enough to make the app unusable.
              const status = d.status ?? 'unknown'
              const t = tracked[d.sleeperDraftId]
              // A <button> cannot nest inside an <a>, and `.draft-row .chip`
              // is pointer-events: none besides -- so the actions are a
              // sibling of the link, not a child of it. The link keeps
              // flex: 1 and its own hover.
              return (
                <div key={d.id} className="draft-row-wrap">
                  <Link to={`/drafts/${d.sleeperDraftId}`} className="draft-row">
                    <span className="draft-row-league">{d.leagueName}</span>
                    <span className="muted small">{d.season}</span>
                    <span className="muted small">
                      {d.teams} teams &middot; {d.rounds} rounds
                    </span>
                    <span className={`chip status-${status}`}>{status.replace('_', ' ')}</span>
                  </Link>
                  <div className="draft-row-actions">
                    {t &&
                      ('failed' in t ? (
                        <span className="tiny track-note failed">{t.failed}</span>
                      ) : typeof t.seatsMapped !== 'number' ? (
                        // A backend older than the seatsMapped field answers
                        // 200 with it simply absent. Say what came back rather
                        // than rendering "undefined/undefined seats mapped" --
                        // verified live 2026-09-02 against a pre-restart 8080.
                        <span className="tiny track-note">
                          tracking · {t.status ?? 'unknown'} · no seat count from this backend
                        </span>
                      ) : (
                        <span className={`tiny track-note${t.seatsMapped === 0 ? ' failed' : ''}`}>
                          {t.seatsMapped}/{t.teams} seats mapped
                          {/* observed: false means Sleeper was unreachable and
                              `status` is the stale DB value -- label it rather
                              than showing it as fact. */}
                          {t.observed === false ? ' · stale' : ''}
                        </span>
                      ))}
                    <button
                      className="chip"
                      onClick={() => track(d.sleeperDraftId)}
                      disabled={tracking === d.sleeperDraftId}
                      title="Start live polling and refresh this draft's status"
                    >
                      {tracking === d.sleeperDraftId ? 'tracking…' : 'track'}
                    </button>
                    {(status === 'pre_draft' || status === 'drafting') && (
                      <Link className="chip live-link" to={`/drafts/${d.sleeperDraftId}/live`}>
                        live →
                      </Link>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </section>

      <section className="panel add-draft">
        <h2>Add a draft</h2>
        <p className="muted small">
          Paste a Sleeper league ID to add its draft history. Run a full ingest separately
          first if the player pool and board haven't been loaded yet.
        </p>
        <div className="controls">
          <label>
            league id
            <input
              value={leagueId}
              onChange={(e) => setLeagueId(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && addDraft()}
              size={22}
            />
          </label>
          <button onClick={addDraft} disabled={adding || !leagueId.trim()}>
            {adding ? 'adding…' : 'add'}
          </button>
        </div>
      </section>
    </div>
  )
}
