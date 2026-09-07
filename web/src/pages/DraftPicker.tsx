import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { roundPickLabel } from '../roundPickLabel'
import {
  getDrafts,
  getMockSessions,
  ingestLeague,
  trackDraft,
  type DraftSummary,
  type MockSessionSummary,
  type TrackResponse,
} from '../api'

export default function DraftPicker() {
  const [drafts, setDrafts] = useState<DraftSummary[] | null>(null)
  const [mocks, setMocks] = useState<MockSessionSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [leagueId, setLeagueId] = useState('')
  const [adding, setAdding] = useState(false)
  const [tracking, setTracking] = useState<string | null>(null)
  const [tracked, setTracked] = useState<Record<string, TrackResponse | { failed: string }>>({})

  function refetch() {
    getDrafts().then(setDrafts).catch((e) => setError(e.message))
    getMockSessions().then(setMocks).catch(() => {}) // non-critical -- the picker still works without it
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

  // The field says "link or ID" now, so a pasted Sleeper URL has to work --
  // https://sleeper.com/leagues/1391509063170293760/team is what is actually
  // in someone's clipboard, and asking them to cut the id out of it by hand is
  // the kind of chore this screen was full of. Sleeper ids are long digit
  // runs; nothing else in one of these URLs is, so the first one is the id.
  function leagueIdFrom(input: string): string {
    const trimmed = input.trim()
    if (/^\d+$/.test(trimmed)) return trimmed
    return trimmed.match(/\d{6,}/)?.[0] ?? trimmed
  }

  async function addDraft() {
    if (!leagueId.trim()) return
    setAdding(true)
    setError(null)
    try {
      await ingestLeague(leagueIdFrom(leagueId))
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
          <h2>Your leagues</h2>
        </div>

        {error && <div className="error">{error}</div>}

        {drafts && drafts.length === 0 && (
          <p className="muted">No leagues yet — add one below.</p>
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
                          Following · {t.status ?? 'unknown'} · no seat count from this backend
                        </span>
                      ) : (
                        // "12/14 seats mapped" described a data structure. What
                        // the reader needs on draft night is whether any seat is
                        // still an unmodelled league-average bot -- so the full
                        // house says so quietly and anything short of it is the
                        // thing that stands out.
                        <span className={`tiny track-note${t.seatsMapped < t.teams ? ' failed' : ''}`}>
                          {t.seatsMapped === t.teams
                            ? `All ${t.teams} managers identified`
                            : `Only ${t.seatsMapped} of ${t.teams} managers identified`}
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
                      title="Follow this draft live and refresh its status"
                    >
                      {tracking === d.sleeperDraftId ? 'Following…' : 'Follow'}
                    </button>
                    {(status === 'pre_draft' || status === 'drafting') && (
                      <Link className="chip live-link" to={`/drafts/${d.sleeperDraftId}/live`}>
                        Follow live →
                      </Link>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2>Mock drafts</h2>
          <Link className="chip on" to="/mock/new">
            New mock draft
          </Link>
        </div>

        {mocks && mocks.length === 0 && <p className="muted">No mock drafts yet — start one above.</p>}

        {mocks && mocks.length > 0 && (
          <div className="draft-list">
            {mocks.map((m) => (
              <Link key={m.id} to={`/mock/${m.id}`} className="draft-row">
                {/* "Mock draft #7" is only distinguishable from "#8" by a
                    number the reader never chose. Size, seat and how far it
                    got are what actually tell two saved mocks apart. */}
                <span className="draft-row-league">
                  {m.teams}-team mock <span className="muted small">from {roundPickLabel(m.userSlot, m.teams)}</span>
                </span>
                <span className={`chip status-${m.status === 'COMPLETE' ? 'complete' : 'drafting'}`}>
                  {m.status === 'COMPLETE'
                    ? 'Complete'
                    : `through ${roundPickLabel(Math.max(1, m.currentPickNo - 1), m.teams)}`}
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section className="panel add-draft">
        <h2>Add a league</h2>
        <p className="muted small">
          Paste a Sleeper league link or ID to add its draft history. Load the player pool
          and board separately first if this is a brand new install.
        </p>
        <div className="controls">
          <label>
            Sleeper league link or ID
            <input
              value={leagueId}
              onChange={(e) => setLeagueId(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && addDraft()}
              size={22}
            />
          </label>
          <button onClick={addDraft} disabled={adding || !leagueId.trim()}>
            {adding ? 'Adding…' : 'Add league'}
          </button>
        </div>
      </section>
    </div>
  )
}
