import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getDrafts, ingestLeague, type DraftSummary } from '../api'

export default function DraftPicker() {
  const [drafts, setDrafts] = useState<DraftSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [leagueId, setLeagueId] = useState('')
  const [adding, setAdding] = useState(false)

  function refetch() {
    getDrafts().then(setDrafts).catch((e) => setError(e.message))
  }

  useEffect(refetch, [])

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
            {drafts.map((d) => (
              <Link key={d.id} to={`/drafts/${d.sleeperDraftId}`} className="draft-row">
                <span className="draft-row-league">{d.leagueName}</span>
                <span className="muted small">{d.season}</span>
                <span className="muted small">
                  {d.teams} teams &middot; {d.rounds} rounds
                </span>
                <span className={`chip status-${d.status}`}>{d.status.replace('_', ' ')}</span>
              </Link>
            ))}
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
