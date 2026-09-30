import type { CSSProperties } from 'react'
import type { ScarcityResult } from '../scarcity'

type Props = {
  /** Null while there is no pool to read (not fetched yet, or empty). */
  scarcity: ScarcityResult | null
  /** The /pool fetch failed (an old backend without it). Degrades this row only. */
  failed: boolean
  /** e.g. "3.04"; appended as "at 3.04" after the expected count. */
  myNextPickLabel?: string
}

/** One decimal at most, prefixed ~ ("~5", "~4.6"). */
const approx = (n: number) => `~${Number(n.toFixed(1))}`

/**
 * How thin each position is among the players a league is realistically
 * drafting as starters, and how many are expected to survive to your next pick.
 * The expected count is model output and only appears when the backend's
 * snapshot is deep enough to cover the whole starter pool (see scarcity.ts).
 */
export default function ScarcityMeter({ scarcity, failed, myNextPickLabel }: Props) {
  if (failed || scarcity == null || scarcity.S === 0 || scarcity.rows.every((r) => r.poolSize === 0)) {
    return (
      <div className="scarcity-meter" aria-label="Position scarcity">
        <p className="scarcity-note muted">no board built yet</p>
      </div>
    )
  }
  const { run } = scarcity
  return (
    <div className="scarcity-meter" aria-label="Position scarcity">
      <ul className="scarcity-chips">
        {scarcity.rows.map((r) => (
          <li
            key={r.position}
            className={`scarcity-chip${r.running ? ' running' : ''}`}
            style={{ '--scar-hue': `var(--${r.position.toLowerCase()})` } as CSSProperties}
          >
            <span className="scarcity-pos">{r.position}</span>
            <span className="scarcity-count mono">{`${r.leftNow} / ${r.poolSize}`}</span>
            {r.expectedAtNext != null && (
              <span className="scarcity-next mono">
                {` · ${approx(r.expectedAtNext)}${myNextPickLabel ? ` at ${myNextPickLabel}` : ''}`}
              </span>
            )}
            {r.running && run && (
              <span className="scarcity-run cond">{`${run.count} of the last ${run.window} were ${run.position}`}</span>
            )}
          </li>
        ))}
      </ul>
      <p className="scarcity-note muted">{scarcity.definition}</p>
      {scarcity.gatedByDepth && (
        <p className="scarcity-note muted">{`projected count from pick ~${scarcity.projectedFrom}`}</p>
      )}
    </div>
  )
}
