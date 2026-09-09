import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getManagerHistory, type ManagerHistory as ManagerHistoryData } from '../api'
import { hueFor } from '../hue'
import { reachGapText } from '../managerBehaviour'

/**
 * claude/league-suite.md Phase A: one manager's record across every ingested
 * season, plus their career-wide draft-side numbers -- kept visually separate
 * (two panels) rather than one flat table, since one is "what happened"
 * (record, points) and the other is "what this app thinks about it" (reach,
 * tilt) -- the same distinction the board's own provenance labels enforce.
 */
export default function ManagerHistory() {
  const { managerId } = useParams<{ managerId: string }>()
  const [data, setData] = useState<ManagerHistoryData | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!managerId) return
    setData(null)
    setError(null)
    getManagerHistory(Number(managerId))
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [managerId])

  if (error) {
    return (
      <div className="content">
        <section className="panel">
          <div className="error">{error}</div>
        </section>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="content">
        <section className="panel">
          <p className="muted small">Loading manager history…</p>
        </section>
      </div>
    )
  }

  const hue = hueFor(String(data.managerId))
  const totalWins = data.seasons.reduce((sum, s) => sum + (s.wins ?? 0), 0)
  const totalLosses = data.seasons.reduce((sum, s) => sum + (s.losses ?? 0), 0)
  const championships = data.seasons.filter((s) => s.champion).length

  return (
    <div className="content">
      <section className="panel">
        <div className="panel-head">
          <h2>
            <span className="avatar" style={{ background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }}>
              {(data.manager ?? '?').trim().charAt(0).toUpperCase()}
            </span>{' '}
            {data.manager ?? `manager ${data.managerId}`}
          </h2>
        </div>
        <p className="small">
          {totalWins}-{totalLosses} across {data.seasons.length} season{data.seasons.length === 1 ? '' : 's'}
          {championships > 0 && ` · ${championships} title${championships === 1 ? '' : 's'}`}
        </p>

        <div className="table-wrap">
          <table className="standings">
            <thead>
              <tr>
                <th></th>
                <th>Season</th>
                <th className="mono">W</th>
                <th className="mono">L</th>
                <th className="mono">PF</th>
                <th className="mono">PA</th>
              </tr>
            </thead>
            <tbody>
              {data.seasons.map((s) => (
                <tr key={`${s.sleeperLeagueId}-${s.rosterId}`}>
                  <td>{s.champion && <span title="Champion">🏆</span>}</td>
                  <td>
                    {s.sleeperLeagueId ? (
                      <Link to={`/leagues/${s.sleeperLeagueId}/history`}>{s.season}</Link>
                    ) : (
                      s.season
                    )}
                  </td>
                  <td className="mono">{s.wins ?? '—'}</td>
                  <td className="mono">{s.losses ?? '—'}</td>
                  <td className="mono">{s.pointsFor?.toFixed(2) ?? '—'}</td>
                  <td className="mono">{s.pointsAgainst?.toFixed(2) ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2>What this app thinks about their drafting</h2>
        </div>
        <p className="muted small">
          Fitted from their own draft history, the same numbers the simulator uses -- not the
          record above, which is what actually happened on the scoreboard.
        </p>
        {data.draftHistory.length === 0 ? (
          <p className="muted">No drafts observed yet -- drafts like the room, no history to fit from.</p>
        ) : (
          // One block per sport. The same person can appear twice here, which
          // is the honest answer rather than a chosen one -- these are two
          // separate fits off two separate sets of picks.
          data.draftHistory.map((h) => (
            <div key={h.sport} className="manager-sport-block">
              <p className="small">
                <span className={`sport-pill ${h.sport}`}>{h.sport.toUpperCase()}</span>
                {h.picksScored > 0 ? (
                  <>
                    Reach bias: <span className="mono">{h.reachBias?.toFixed(2) ?? '—'}</span> over{' '}
                    {h.draftsObserved} draft{h.draftsObserved === 1 ? '' : 's'} ({h.provenance.toLowerCase()})
                  </>
                ) : (
                  // No reach number rather than a reach number of zero -- with
                  // no scoreable picks, `reachBias` is the league mean wearing
                  // this manager's name. Printing "0.00" there would read as a
                  // finding. Same rule as SeatPopover and /managers.
                  <>
                    {h.draftsObserved} draft{h.draftsObserved === 1 ? '' : 's'} observed, no reach number
                  </>
                )}
              </p>
              {h.picksScored === 0 && (
                <p className="muted tiny">{reachGapText(h)}</p>
              )}
              {h.positionalTilt && Object.keys(h.positionalTilt).length > 0 && (
                <p className="small mono">
                  {Object.entries(h.positionalTilt)
                    .map(([pos, tilt]) => `${pos} ${tilt.toFixed(2)}×`)
                    .join(' · ')}
                </p>
              )}
            </div>
          ))
        )}
      </section>
    </div>
  )
}
