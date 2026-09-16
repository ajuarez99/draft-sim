import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import {
  getLeagueAnalysis,
  ingestLeagueHistory,
  type LeagueAnalysis as LeagueAnalysisData,
  type AnalysisProjections,
  type AnalysisRankingScores,
} from '../api'

/**
 * claude/league-analysis.md: what each roster is MADE OF, as opposed to Power
 * rankings' question of who is best.
 *
 * The bump chart ffwrapped shows alongside these blocks is deliberately absent:
 * Power rankings already draws one, and this page links to it instead of
 * drawing a second.
 */

const SCORING_LABEL: Record<string, string> = {
  PPR: 'full PPR',
  HALF_PPR: 'half PPR',
  STANDARD: 'standard',
}

function managerName(manager: string | null, rosterId: number) {
  return manager ?? `roster ${rosterId}`
}

/**
 * A block that has nothing to show yet says why, in the same shape whichever
 * block it is. Never an empty table with headers over nothing -- that reads as
 * a broken page rather than an early one.
 */
function NotYet({ reason }: { reason: string | null }) {
  return (
    <p className="muted small analysis-notyet" role="status">
      {reason ?? 'Nothing to show yet.'}
    </p>
  )
}

function RankingScoresBlock({ block }: { block: AnalysisRankingScores }) {
  if (!block.available) {
    return (
      <>
        <NotYet reason={block.reason} />
        <p className="muted small analysis-formula mono">{block.formula}</p>
      </>
    )
  }
  return (
    <>
      <div className="table-wrap">
        <table className="standings analysis-scores">
          <thead>
            <tr>
              <th className="mono">#</th>
              <th>Manager</th>
              <th className="mono">Score</th>
              <th className="mono">Record</th>
              <th className="mono">Avg</th>
              <th className="mono">High</th>
              <th className="mono">Low</th>
            </tr>
          </thead>
          <tbody>
            {block.entries.map((e) => (
              <tr key={e.rosterId}>
                <td className="mono analysis-rank">{e.rank}</td>
                <td>
                  {e.managerId != null ? (
                    <Link to={`/managers/${e.managerId}/history`} className="standings-manager">
                      <Avatar avatarId={e.avatarId} seed={String(e.managerId)} label={e.manager} />
                      {managerName(e.manager, e.rosterId)}
                    </Link>
                  ) : (
                    <span className="muted">{managerName(e.manager, e.rosterId)}</span>
                  )}
                </td>
                {/* The 1-100 number and the formula's own raw output, side by
                    side. The scaling is ours, not ffwrapped's, so the page
                    shows what it scaled FROM rather than asking to be trusted. */}
                <td className="mono">
                  <span className="analysis-score-pill">{e.score.toFixed(1)}</span>
                  <span className="muted small analysis-raw">{e.raw.toFixed(1)} raw</span>
                </td>
                <td className="mono">
                  {e.wins}-{e.losses}
                  {e.ties > 0 ? `-${e.ties}` : ''}
                </td>
                <td className="mono">{e.avgWeekly.toFixed(1)}</td>
                <td className="mono">{e.high.toFixed(1)}</td>
                <td className="mono">{e.low.toFixed(1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small analysis-formula mono">{block.formula}</p>
    </>
  )
}

function ProjectionsBlock({ block }: { block: AnalysisProjections }) {
  if (!block.available) return <NotYet reason={block.reason} />

  const max = Math.max(...block.rosters.map((r) => r.total), 1)

  return (
    <div className="analysis-bars">
      {block.rosters.map((r) => (
        <div key={r.rosterId} className="analysis-bar-row">
          <div className="analysis-bar-who">
            <span className="mono analysis-rank">{r.rank}</span>
            {r.managerId != null ? (
              <Link to={`/managers/${r.managerId}/history`} className="standings-manager">
                <Avatar avatarId={r.avatarId} seed={String(r.managerId)} label={r.manager} />
                {managerName(r.manager, r.rosterId)}
              </Link>
            ) : (
              <span className="muted">{managerName(r.manager, r.rosterId)}</span>
            )}
          </div>

          {/* One bar, segments in the app's own position colors. The bar
              carries proportion; the numbers under it carry the values --
              nothing here is readable by hue alone. */}
          <div className="analysis-track" style={{ width: `${(r.total / max) * 100}%` }}>
            {block.positionGroups.map((g) => {
              const v = r.byPosition[g] ?? 0
              if (v <= 0) return null
              return (
                <span
                  key={g}
                  className={`analysis-seg pos-${g}`}
                  style={{ flexGrow: v }}
                  title={`${g}: ${v.toFixed(1)} projected points`}
                />
              )
            })}
          </div>

          <div className="analysis-bar-total mono">
            {r.total.toFixed(1)}
            <span className="muted small"> pts</span>
          </div>

          <div className="analysis-legend">
            {block.positionGroups.map((g) => {
              const v = r.byPosition[g] ?? 0
              if (v <= 0) return null
              return (
                <span key={g} className="analysis-legend-item">
                  <span className={`pos ${g}`}>{g}</span>
                  <span className="mono">{v.toFixed(0)}</span>
                </span>
              )
            })}
            {r.missing > 0 && (
              <span
                className="muted small"
                title="Rostered players Sleeper publishes no projection for — IR, Out, PUP. They count as zero."
              >
                {r.missing} unprojected
              </span>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}

function PositionGroupsBlock({ block }: { block: AnalysisProjections }) {
  if (!block.available) return <NotYet reason={block.reason} />
  const teams = block.rosters.length

  return (
    <div className="table-wrap">
      <table className="standings analysis-matrix">
        <thead>
          <tr>
            <th>Manager</th>
            {block.positionGroups.map((g) => (
              <th key={g} className="analysis-matrix-head">
                <span className={`pos ${g}`}>{g}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {block.rosters.map((r) => (
            <tr key={r.rosterId}>
              <td>{managerName(r.manager, r.rosterId)}</td>
              {block.positionGroups.map((g) => {
                const rank = r.rankByPosition[g] ?? 0
                const points = r.byPosition[g] ?? 0
                // Strength of the tint is the rank; the rank is also printed,
                // and so is the number it came from. Color is never the only
                // thing saying how good this cell is.
                const strength = teams > 1 ? (teams - rank) / (teams - 1) : 1
                return (
                  <td
                    key={g}
                    className={`analysis-cell pos-${g}`}
                    style={{ '--tint': `${Math.round(strength * 26)}%` } as React.CSSProperties}
                    title={`${managerName(r.manager, r.rosterId)} — ${g}: ${points.toFixed(1)} projected points, ${rank} of ${teams}`}
                  >
                    <span className="mono analysis-cell-rank">{rank}</span>
                    <span className="mono analysis-cell-points">{points.toFixed(0)}</span>
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function LeagueAnalysis() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<LeagueAnalysisData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!sleeperLeagueId) return
    setData(null)
    setError(null)
    getLeagueAnalysis(sleeperLeagueId)
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [sleeperLeagueId])

  // Same convention as LeagueHistory: a button that fires the ingest, never a
  // curl line printed for the reader to run.
  async function loadHistory() {
    if (!sleeperLeagueId) return
    setLoading(true)
    setError(null)
    try {
      await ingestLeagueHistory(sleeperLeagueId)
      setData(await getLeagueAnalysis(sleeperLeagueId))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  const window = data?.window

  return (
    <div className="content">
      <PageHeader
        eyebrow="League"
        title="League analysis"
        sub="What each roster is made of: a composite ranking score from games already played, and the rest of the regular season projected onto the lineup each manager would actually start. Who is best — the ladders, the vote, the bump chart — is Power rankings."
      />

      {error && (
        <div className="error history-error">
          <span>{error.includes('404') ? "This league hasn't been loaded yet." : error}</span>
          <button className="action-button" onClick={loadHistory} disabled={loading}>
            {loading ? 'Loading…' : 'Load this league'}
          </button>
        </div>
      )}

      {!data && !error && (
        <p className="muted small" role="status" aria-busy="true">
          Loading league analysis…
        </p>
      )}

      {data && (
        <>
          <section className="panel">
            <div className="panel-head">
              <h2>Ranking score</h2>
              {sleeperLeagueId && (
                <Link className="chip" to={`/leagues/${sleeperLeagueId}/power`}>
                  Power rankings →
                </Link>
              )}
            </div>
            <p className="muted small">
              A composite of scoring and record over the {data.rankingScores.weeksScored} week
              {data.rankingScores.weeksScored === 1 ? '' : 's'} played, scaled 1–100 with the league
              average at 50.
            </p>
            <RankingScoresBlock block={data.rankingScores} />
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Roster projections</h2>
              <span className="muted small">
                {window && window.weeks > 0
                  ? `weeks ${window.fromWeek}–${window.toWeek} · ${SCORING_LABEL[data.scoringKey] ?? data.scoringKey}`
                  : SCORING_LABEL[data.scoringKey] ?? data.scoringKey}
              </span>
            </div>
            <p className="muted small">
              Rest-of-season points for the starting lineup each roster would field, split by the
              position the starter actually plays — a running back filling a flex slot counts under
              RB.
            </p>
            <ProjectionsBlock block={data.projections} />
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Position group rankings</h2>
            </div>
            <p className="muted small">
              The same projections read down the columns: where each roster stands at each position.
              Big number is the rank, small number is the projected points behind it.
            </p>
            <PositionGroupsBlock block={data.projections} />
          </section>
        </>
      )}
    </div>
  )
}
