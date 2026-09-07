import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  getLeagueHistory,
  ingestLeagueHistory,
  type LeagueHistory as LeagueHistoryData,
  type StandingRow,
} from '../api'
import { hueFor } from '../hue'

/**
 * claude/league-suite.md Phase A: standings across every ingested season for
 * one league. Read-only apart from the error state's own "Load past seasons"
 * button, which fires the ingest rather than telling the reader to curl it.
 */

function StandingsTable({ rows }: { rows: StandingRow[] }) {
  return (
    <div className="table-wrap">
      <table className="standings">
        <thead>
          <tr>
            <th></th>
            <th>Manager</th>
            <th className="mono">W</th>
            <th className="mono">L</th>
            <th className="mono">T</th>
            <th className="mono">PF</th>
            <th className="mono">PA</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const hue = r.managerId != null ? hueFor(String(r.managerId)) : 220
            return (
              <tr key={r.rosterId}>
                <td>{r.champion && <span title="Champion">🏆</span>}</td>
                <td>
                  {r.managerId != null ? (
                    <Link to={`/managers/${r.managerId}/history`} className="standings-manager">
                      <span className="avatar" style={{ background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }}>
                        {(r.manager ?? '?').trim().charAt(0).toUpperCase()}
                      </span>
                      {r.manager ?? `roster ${r.rosterId}`}
                    </Link>
                  ) : (
                    <span className="muted">roster {r.rosterId} (unowned)</span>
                  )}
                </td>
                <td className="mono">{r.wins ?? '—'}</td>
                <td className="mono">{r.losses ?? '—'}</td>
                <td className="mono">{r.ties ?? '—'}</td>
                <td className="mono">{r.pointsFor?.toFixed(2) ?? '—'}</td>
                <td className="mono">{r.pointsAgainst?.toFixed(2) ?? '—'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export default function LeagueHistory() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [history, setHistory] = useState<LeagueHistoryData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!sleeperLeagueId) return
    setHistory(null)
    setError(null)
    getLeagueHistory(sleeperLeagueId)
      .then(setHistory)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [sleeperLeagueId])

  // Backs the error state's own button. Same call the page used to print as a
  // curl line for the reader to run in a terminal.
  async function loadHistory() {
    if (!sleeperLeagueId) return
    setLoading(true)
    setError(null)
    try {
      await ingestLeagueHistory(sleeperLeagueId)
      setHistory(await getLeagueHistory(sleeperLeagueId))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="content">
      <section className="panel">
        <div className="panel-head">
          <h2>{history?.seasons[0]?.name ?? 'League history'}</h2>
          {sleeperLeagueId && (
            <Link className="chip" to={`/leagues/${sleeperLeagueId}/power`}>
              Power rankings →
            </Link>
          )}
        </div>
        <p className="muted small">
          Standings as Sleeper reports them -- wins, losses, points, the champion. What this app
          thinks about a draft (reach, value) lives on a manager's own history page, kept visually
          separate from what actually happened.
        </p>

        {/* This used to print `POST /api/ingest/league-history/{id}` for the
            reader to run themselves. Step 3 of the design review removed
            exactly that pattern from the draft room's pre-start overlay and it
            came straight back in new code, so it is a convention problem: a
            button that fires the call, never the call itself. */}
        {error && (
          <div className="error history-error">
            <span>
              {error.toLowerCase().includes('not found') || error.includes('404')
                ? "This league's past seasons haven't been loaded yet."
                : error}
            </span>
            <button className="action-button" onClick={loadHistory} disabled={loading}>
              {loading ? 'Loading seasons…' : 'Load past seasons'}
            </button>
          </div>
        )}

        {/* A standings table isn't `.draft-row`-shaped, so it gets a plain wait
            rather than SkeletonRows -- see Skeleton.tsx's own convention. */}
        {!history && !error && (
          <p className="muted small" role="status" aria-busy="true">
            Loading league history…
          </p>
        )}

        {history && history.seasons.length === 0 && (
          <p className="muted">No seasons ingested for this league yet.</p>
        )}

        {history &&
          history.seasons.map((s) => (
            <div key={s.leagueId} className="history-season">
              <h3 className="cond">{s.season}</h3>
              {/* A season with no standings rendered its headers over nothing
                  -- verified on the 2026 season, which is ingested but hasn't
                  been played. `seasons.length === 0` was guarded; this wasn't. */}
              {s.standings.length === 0 ? (
                <p className="muted small">
                  No standings for {s.season} yet — Sleeper reports them once the season is under way.
                </p>
              ) : (
                <StandingsTable rows={s.standings} />
              )}
            </div>
          ))}
      </section>
    </div>
  )
}
