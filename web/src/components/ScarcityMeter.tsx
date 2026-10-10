import type { CSSProperties } from 'react'
import { runLabel } from '../pickRun'
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
  // A position with no eligible starter-pool player has poolSize 0. "0 / 0" there reads
  // as a measurement of scarcity; it is really "no data". Hide the chip and say so
  // once (FR-018). Counting is by eligibility now (spec 025), so this is rare.
  // Eligibility wording is NBA-only: a football player has one position, so "eligible"
  // and "can count at more than one position" would be noise there, and SC-005 says no
  // football screen changes. Found in live verification (T024), 2026-10-10.
  const multi = scarcity.sport === 'nba'
  const definitionNote = multi
    ? `${scarcity.definition}; a player can count at more than one position`
    : scarcity.definition
  const shown = scarcity.rows.filter((r) => r.poolSize > 0)
  const hidden = scarcity.rows.filter((r) => r.poolSize === 0).map((r) => r.position)
  // An NBA run covers a whole family, so both PG and SG chips are "running" -- say the
  // sentence once, on the first running chip, instead of repeating it on each.
  const runChip = shown.find((r) => r.running)?.position
  // If every chip in the running family is hidden (poolSize 0), no chip can carry the
  // sentence -- print it once on its own so the run is never silently dropped (review N8).
  const runLoose = runChip == null && run != null && scarcity.rows.some((r) => r.running)
  return (
    <div className="scarcity-meter" aria-label="Position scarcity">
      <ul className="scarcity-chips">
        {shown.map((r) => (
          <li
            key={r.position}
            className={`scarcity-chip${r.running ? ' running' : ''}`}
            style={{ '--scar-hue': `var(--${r.position.toLowerCase()})` } as CSSProperties}
            title={
              multi
                ? `${r.position}: ${r.leftNow} of the ${r.poolSize} starter-pool players eligible at ${r.position} are still on the board (a player can count at more than one position)`
                : undefined
            }
          >
            <span className="scarcity-pos">{r.position}</span>
            <span className="scarcity-count mono">{`${r.leftNow} / ${r.poolSize}`}</span>
            {multi && <span className="scarcity-elig muted">{' eligible'}</span>}
            {r.expectedAtNext != null && (
              <span className="scarcity-next mono">
                {` · ${approx(r.expectedAtNext)}${myNextPickLabel ? ` at ${myNextPickLabel}` : ''}`}
              </span>
            )}
            {r.running && run && r.position === runChip && (
              <span className="scarcity-run cond">{`${run.count} of the last ${run.window} were ${runLabel(run, scarcity.sport)}`}</span>
            )}
          </li>
        ))}
      </ul>
      {runLoose && run && (
        <p className="scarcity-run cond">{`${run.count} of the last ${run.window} were ${runLabel(run, scarcity.sport)}`}</p>
      )}
      {hidden.length > 0 && (
        <p className="scarcity-note muted">
          {multi
            ? `${hidden.join(', ')}: no starter-pool players are eligible here`
            : `${hidden.join(', ')}: no starter-pool players list these first`}
        </p>
      )}
      <p className="scarcity-note muted" title={definitionNote}>{definitionNote}</p>
      {scarcity.gatedByDepth && (
        <p className="scarcity-note muted" title={`projected count from pick ~${scarcity.projectedFrom}`}>{`projected count from pick ~${scarcity.projectedFrom}`}</p>
      )}
    </div>
  )
}
