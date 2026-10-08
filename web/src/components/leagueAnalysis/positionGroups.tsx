import { type AnalysisProjections } from '../../api'
import { NotYet, pts, useTeamName } from './shared'

export function PositionGroupsBlock({ block }: { block: AnalysisProjections }) {
  const teamName = useTeamName()
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
              <td>{teamName(r.manager, r.rosterId)}</td>
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
                    title={`${teamName(r.manager, r.rosterId)} — ${g}: ${pts(points)} projected points, ${rank} of ${teams}`}
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
