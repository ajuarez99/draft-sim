import PlayerLink from './PlayerLink'
import type { StatLeaderboard as Board } from '../api'

/**
 * specs/032-stat-calculator US1: the calculator's ranked bar chart (research R9).
 *
 * HTML rows, not SVG, so it reflows at 375 px with no viewBox maths. One encoding per mark: bar
 * length is |value| from a zero baseline and nothing else; every row also prints its exact value
 * beside the bar. One colour (the player page's meter colour), so nothing implies a second meaning.
 * Negative values grow left from the baseline; when every value is negative the baseline sits at
 * the right, so the leader (closest to zero) has the shortest bar and still shows its minus sign.
 * The page's table is the chart's table view, in the same order.
 */

export type Bar = { id: string; name: string | null; value: number }

export default function RankedBars({
  title, fullTitle, bars, sport, leagueId, format,
}: {
  title: string
  /** Shown on hover/focus when `title` was shortened. */
  fullTitle?: string
  bars: Bar[]
  sport: Board['sport']
  leagueId: string
  format: (v: number) => string
}) {
  const lo = Math.min(0, ...bars.map((b) => b.value))
  const hi = Math.max(0, ...bars.map((b) => b.value))
  const span = hi - lo
  const zero = span === 0 ? 0 : (-lo / span) * 100
  const mixed = lo < 0 && hi > 0
  return (
    <figure className="sc-chart">
      <figcaption className="sc-chart-title" title={fullTitle ?? title}>{title}</figcaption>
      <ol className="sc-bars" aria-label={title}>
        {bars.map((b, i) => {
          const w = span === 0 ? 0 : (Math.abs(b.value) / span) * 100
          const left = b.value < 0 ? zero - w : zero
          const text = format(b.value)
          const name = b.name ?? 'Unknown player'
          return (
            <li key={b.id} className="sc-bar-row" title={`${name}: ${text}`}>
              <span className="sc-bar-rank muted small" aria-hidden="true">{i + 1}</span>
              <span className="sc-bar-name">
                <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={b.id} sport={sport}>{name}</PlayerLink>
              </span>
              <span className="sc-bar-track" aria-hidden="true">
                {mixed && <span className="sc-bar-zero" style={{ left: `${zero}%` }} />}
                <span
                  className={`sc-bar${b.value < 0 ? ' neg' : ''}`}
                  style={{ left: `${left}%`, width: `${w}%` }}
                />
              </span>
              <span className="sc-bar-val pp-strong">{text}</span>
            </li>
          )
        })}
      </ol>
    </figure>
  )
}
