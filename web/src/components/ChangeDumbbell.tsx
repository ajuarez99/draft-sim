import PlayerLink from './PlayerLink'
import type { StatLeaderboard as Board } from '../api'

/**
 * specs/032-stat-calculator US5 (FR-019): the calculator's Change chart, a dumbbell per player.
 *
 * HTML rows (they reflow at 375 px with no viewBox maths). Two dots on one shared scale: a hollow ring
 * for the comparison view ("then") and a filled dot for the current one ("now"), joined by a line, so
 * which end is which does not depend on colour. The line is --teal for a rise and --wr for a fall, and the
 * signed change is printed with its sign ("+1.20", "−0.80") beside the exact values, so direction is never
 * colour alone. Those two are deliberately not --up/--down, the app's better/worse colours: the page cannot
 * know whether a higher turnover count or a lower one is good, so the legend says "higher"/"lower"
 * (amended after US5 review, C3).
 */

export type ChangeRow = { id: string; name: string | null; now: number; then: number }

export default function ChangeDumbbell({
  title, rows, nowLabel, thenLabel, sport, leagueId, format,
}: {
  title: string
  rows: ChangeRow[]
  /** The current view, e.g. "Season". */
  nowLabel: string
  /** The comparison view, e.g. "Last 10". */
  thenLabel: string
  sport: Board['sport']
  leagueId: string
  format: (v: number) => string
}) {
  const vals = rows.flatMap((r) => [r.now, r.then])
  let lo = vals.length ? Math.min(...vals) : 0
  let hi = vals.length ? Math.max(...vals) : 1
  if (lo === hi) {
    lo -= 1
    hi += 1
  }
  const pos = (v: number) => 2 + ((v - lo) / (hi - lo)) * 96
  const signed = (d: number) => (d > 0 ? `+${format(d)}` : format(d))
  return (
    <figure className="sc-chart">
      <figcaption className="sc-chart-title">{title}</figcaption>
      <ul className="sc-legend small" aria-label="Legend">
        <li><span className="sc-swatch sc-swatch-then" aria-hidden="true" />{`${thenLabel} (hollow dot)`}</li>
        <li><span className="sc-swatch sc-swatch-now" aria-hidden="true" />{`${nowLabel} (filled dot)`}</li>
        <li><span className="sc-swatch sc-swatch-rise" aria-hidden="true" />Higher (+)</li>
        <li><span className="sc-swatch sc-swatch-fall" aria-hidden="true" />Lower (−)</li>
      </ul>
      <ol className="sc-bars sc-dumbbells" aria-label={title}>
        {rows.map((r, i) => {
          const d = r.now - r.then
          const a = pos(Math.min(r.now, r.then))
          const b = pos(Math.max(r.now, r.then))
          const name = r.name ?? 'Unknown player'
          const dir = d > 0 ? 'rise' : d < 0 ? 'fall' : 'flat'
          return (
            <li key={r.id} className="sc-bar-row sc-db-row" title={`${name}: ${thenLabel} ${format(r.then)}, ${nowLabel} ${format(r.now)}, change ${signed(d)}`}>
              <span className="sc-bar-rank muted small" aria-hidden="true">{i + 1}</span>
              <span className="sc-bar-name">
                <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={r.id} sport={sport}>{name}</PlayerLink>
              </span>
              <span className="sc-bar-track sc-db-track" aria-hidden="true">
                <span className={`sc-db-line ${dir}`} style={{ left: `${a}%`, width: `${b - a}%` }} />
                <span className="sc-db-dot then" style={{ left: `${pos(r.then)}%` }} />
                <span className="sc-db-dot now" style={{ left: `${pos(r.now)}%` }} />
              </span>
              <span className="sc-bar-val sc-db-val">
                <span className="pp-strong">{signed(d)}</span>
                <span className="muted small">{`${format(r.then)} → ${format(r.now)}`}</span>
              </span>
            </li>
          )
        })}
      </ol>
      <p className="muted small sc-scatter-note">{`Each row reads ${thenLabel} → ${nowLabel}; the change is ${nowLabel} minus ${thenLabel}. All rows share one scale.`}</p>
    </figure>
  )
}
