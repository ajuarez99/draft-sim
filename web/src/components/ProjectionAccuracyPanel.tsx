import { useEffect, useState } from 'react'
import {
  getProjectionAccuracy,
  type AccuracyCell,
  type AccuracySeries,
  type ProjectionAccuracy,
} from '../api'
import { categoryNames, leanWords } from '../projectionCopy'
import { formatIsoDate } from '../pages/LeagueHome'

/*
 * spec 036 US2: how far off the frozen pre-game projections were, under THIS league's scoring.
 *
 * Rules this panel keeps (the spec's FR-011 and the repo's "label the axis, spell out the number"):
 *  - the table holds ONLY errors and counts. A projected number and a realized number never share a
 *    column, so no cell can be read as "he was projected for X and scored Y";
 *  - the lean is words ("runs 2.1 high"), never a green/red colour: neither direction is "good";
 *  - while the server says TOO_EARLY it has sent no figures, and this prints none -- only counts;
 *  - a breakdown row with too few samples says "too few (n)" instead of a number it can't stand behind.
 */

function title(a: ProjectionAccuracy): string {
  return a.unit === 'GAME' ? 'games' : 'player-weeks'
}

/** The server's "Not graded" caveat carries raw keys; say them in words here, as the Analysis page does elsewhere. */
function caveatText(c: string, a: ProjectionAccuracy): string {
  if (!c.startsWith('Not graded')) return c
  return `Not graded (no projection carries them): ${categoryNames(a.excludedCategories).join(', ')}.`
}

function Figures({ cell, measured }: { cell: AccuracyCell; measured: boolean }) {
  if (!measured) {
    return (
      <>
        <td className="mono muted">–</td>
        <td className="muted">–</td>
      </>
    )
  }
  if (cell.mae == null || cell.lean == null) {
    return (
      <>
        <td className="mono muted">too few ({cell.n})</td>
        <td className="muted">–</td>
      </>
    )
  }
  return (
    <>
      <td className="mono">±{cell.mae.toFixed(1)} pts</td>
      <td>{leanWords(cell.lean)}</td>
    </>
  )
}

function Row({ cell, label, measured, strong }: { cell: AccuracyCell; label: string; measured: boolean; strong?: boolean }) {
  return (
    <tr className={strong ? 'projacc-overall' : undefined}>
      <th scope="row">{label}</th>
      <td className="mono">{cell.n}</td>
      <Figures cell={cell} measured={measured} />
    </tr>
  )
}

function SeriesTable({ a, s }: { a: ProjectionAccuracy; s: AccuracySeries }) {
  const measured = a.status === 'MEASURED'
  return (
    <div className="table-wrap">
      <table className="standings projacc-table">
        <caption className="muted small">
          {s.label}: error against what happened, in this league's points. Counts are {title(a)}.
        </caption>
        <thead>
          <tr>
            <th>Group</th>
            <th className="mono">{a.unit === 'GAME' ? 'Games' : 'Player-weeks'}</th>
            <th className="mono">Typical miss</th>
            <th>Lean</th>
          </tr>
        </thead>
        <tbody>
          <Row cell={s.overall} label="Overall" measured={measured} strong />
          {s.byPosition.length > 0 && (
            <tr className="projacc-group">
              <th colSpan={4} scope="colgroup">By position</th>
            </tr>
          )}
          {s.byPosition.map((c) => (
            <Row key={`p-${c.key}`} cell={c} label={c.key} measured={measured} />
          ))}
          <tr className="projacc-group">
            <th colSpan={4} scope="colgroup">By projected points</th>
          </tr>
          {s.byBand.map((c) => (
            <Row key={`b-${c.key}`} cell={c} label={c.key} measured={measured} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function ProjectionAccuracyView({ a }: { a: ProjectionAccuracy }) {
  // The NBA summary (and so the source line elsewhere on the page) reads the season-average series; open on it.
  const first = a.series.find((s) => s.kind === 'SEASON_AVERAGE') ?? a.series[0]
  const [kind, setKind] = useState<string | null>(first?.kind ?? null)
  const current = a.series.find((s) => s.kind === kind) ?? first

  if (a.status === 'NO_SNAPSHOTS' || !current) {
    return (
      <>
        <p className="muted small">
          No pre-game projections have been captured for the {a.season} season yet. Accuracy starts counting from
          the first game after one is.
        </p>
        <Caveats a={a} />
      </>
    )
  }

  const n = current.overall.n
  const tooEarly = a.status === 'TOO_EARLY'
  return (
    <>
      <div className="projacc-head">
        {tooEarly ? (
          <>
            <p>
              <strong>Too early to measure.</strong>{' '}
              <span className="mono">{n}</span> of <span className="mono">{a.minSamples}</span> {title(a)} graded so far.
            </p>
            <progress
              className="projacc-meter"
              max={a.minSamples}
              value={Math.min(n, a.minSamples)}
              aria-label={`${n} of ${a.minSamples} ${title(a)} graded`}
            />
            <p className="muted small">Figures appear once {a.minSamples} {title(a)} have finished.</p>
          </>
        ) : (
          <p>
            Typically off by{' '}
            <strong className="mono">±{current.overall.mae?.toFixed(1)} pts</strong>{' '}
            {a.unit === 'GAME' ? 'a game' : 'a week'}; on average the projections{' '}
            <strong>{current.overall.lean == null ? '' : leanWords(current.overall.lean)}</strong>, over{' '}
            <span className="mono">{n}</span> {title(a)}.
          </p>
        )}
      </div>

      {a.series.length > 1 && (
        <div className="segmented sm projacc-tabs" role="tablist" aria-label="Which projection is graded">
          {a.series.map((s) => (
            <button
              key={s.kind}
              type="button"
              role="tab"
              aria-selected={s.kind === current.kind}
              className={`segment${s.kind === current.kind ? ' on' : ''}`}
              onClick={() => setKind(s.kind)}
            >
              {s.label}
            </button>
          ))}
        </div>
      )}

      <SeriesTable a={a} s={current} />

      <p className="muted small projacc-foot">
        Did not play: <span className="mono">{current.didNotPlay}</span> · Not yet played:{' '}
        <span className="mono">{current.pending}</span>
        {a.voided > 0 && (
          <>
            {' '}· Postponed or canceled (not graded): <span className="mono">{a.voided}</span>
          </>
        )}
        {a.withoutSeasonAverage > 0 && (
          <>
            {' '}· Left out of both lines for having no season-average projection:{' '}
            <span className="mono">{a.withoutSeasonAverage}</span>
          </>
        )}
      </p>
      <p className="muted small">
        {a.snapshotsFrom ? `Covers games from ${formatIsoDate(a.snapshotsFrom)}` : ''}
        {a.scoredThrough ? ` · graded through ${formatIsoDate(a.scoredThrough)}` : ''}
      </p>
      <Caveats a={a} />
    </>
  )
}

function Caveats({ a }: { a: ProjectionAccuracy }) {
  if (a.caveats.length === 0) return null
  return (
    <ul className="muted small projacc-caveats">
      {a.caveats.map((c) => (
        <li key={c}>{caveatText(c, a)}</li>
      ))}
    </ul>
  )
}

/** Self-fetching: this is a separate read from `/analysis`, so a slow or failed one cannot hold the page. */
export default function ProjectionAccuracyPanel({
  sleeperLeagueId,
  dataVersion,
}: {
  sleeperLeagueId: string
  dataVersion?: number
}) {
  const [a, setA] = useState<ProjectionAccuracy | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let live = true
    setFailed(false)
    getProjectionAccuracy(sleeperLeagueId)
      .then((r) => live && setA(r))
      .catch(() => live && setFailed(true))
    return () => {
      live = false
    }
  }, [sleeperLeagueId, dataVersion])

  return (
    <section className="section analysis-section" id="projection-accuracy">
      <div className="panel-head">
        <h2 className="section-title">Projection accuracy</h2>
        <span className="muted small">pre-game projections vs what happened · this league's scoring</span>
      </div>
      {failed && <p className="muted small">Accuracy couldn't be loaded just now.</p>}
      {!failed && !a && <p className="muted small">Loading…</p>}
      {a && <ProjectionAccuracyView a={a} />}
    </section>
  )
}
