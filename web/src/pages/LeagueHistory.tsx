import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getLeagueHistory, type LeagueHistory as LeagueHistoryData, type StandingRow } from '../api'
import { hueFor } from '../hue'

/**
 * claude/league-suite.md Phase A: standings across every ingested season for
 * one league. Read-only, no auth -- run POST /api/ingest/league-history/{id}
 * first if a season is missing (the picker's "Follow"-style controls for this
 * live here rather than being duplicated on this page).
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

  useEffect(() => {
    if (!sleeperLeagueId) return
    setHistory(null)
    setError(null)
    getLeagueHistory(sleeperLeagueId)
      .then(setHistory)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [sleeperLeagueId])

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

        {error && (
          <div className="error">
            {error}
            {sleeperLeagueId && (
              <>
                {' '}
                — try{' '}
                <code>POST /api/ingest/league-history/{sleeperLeagueId}</code> first if this
                league has never had its history ingested.
              </>
            )}
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
              <StandingsTable rows={s.standings} />
            </div>
          ))}
      </section>
    </div>
  )
}
