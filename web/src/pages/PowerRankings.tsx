import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  computePowerRankings,
  getLeagueHistory,
  getPowerRankings,
  saveCommissionerRanking,
  type PowerRankingEntry,
  type PowerRankingKind,
  type PowerRankings as PowerRankingsData,
  type StandingRow,
} from '../api'
import { hueFor } from '../hue'

/**
 * claude/league-suite.md "Power rankings, three ways" -- one page, three
 * independent orderings of the same league, the deltas between them being the
 * actual point. Only rank is drawn on the shared axis: modes 1 and 2 produce
 * orderings with no score behind them, so score rides in the tooltip on the
 * computed modes rather than on the axis (see the plan's "y-axis is forced").
 *
 * Hand-rolled SVG rather than a charting library -- this is the first chart in
 * an app with no `<svg>` in web/src today, and a bump chart is polylines, ticks
 * and labels, cheap enough to keep in this file's own visual vocabulary rather
 * than fighting a library's default look back to house style.
 */

const KIND_LABEL: Record<PowerRankingKind, string> = {
  COMPUTED_MARKET_VALUE: 'Market value',
  COMPUTED_REALIZED: 'Realized',
  COMMISSIONER: 'Commissioner',
}

// Fixed, not hueFor(kind) -- the three mode names hash close enough together
// (hueFor is built for a large, arbitrary manager-id space, not three fixed
// strings) that two of them read as the same color. Chosen to sit clear of
// --crimson (~25, reserved for "you") and --teal (~175, reserved for generic
// interaction) per styles.css's house rule.
const KIND_HUE: Record<PowerRankingKind, number> = {
  COMPUTED_MARKET_VALUE: 60,
  COMPUTED_REALIZED: 210,
  COMMISSIONER: 290,
}

const KIND_CAVEAT: Record<PowerRankingKind, string> = {
  COMPUTED_MARKET_VALUE:
    "Preseason-flavoured: this app's board value on each roster's best starting lineup, right now. Goes stale the moment real games are played.",
  COMPUTED_REALIZED:
    'Backward-looking: cumulative average starting-lineup points per week, from games already played. A hot start survives an injury it should not.',
  COMMISSIONER: "Allan's own ordering. An opinion, signed -- not a measurement.",
}

type SeriesPoint = { week: number; rank: number; score: number | null; note: string | null }
// hue is what actually draws the line -- the manager's own color in the
// all-teams view (hueFor over the whole manager-id space, spread wide enough
// to stay distinct), but a fixed per-mode color in the per-team view, where
// coloring by manager would draw all three lines in the same color since
// it's one manager's data three times (see KIND_HUE).
type Series = { rosterId: number; managerId: number | null; manager: string | null; hue: number; points: SeriesPoint[] }

function buildSeries(entries: PowerRankingEntry[], kind: PowerRankingKind, season: number): Series[] {
  const byRoster = new Map<number, Series>()
  for (const e of entries) {
    if (e.kind !== kind || e.season !== season) continue
    let s = byRoster.get(e.rosterId)
    if (!s) {
      s = { rosterId: e.rosterId, managerId: e.managerId, manager: e.manager, hue: hueFor(String(e.managerId ?? e.rosterId)), points: [] }
      byRoster.set(e.rosterId, s)
    }
    s.points.push({ week: e.week, rank: e.rank, score: e.score, note: e.note })
  }
  for (const s of byRoster.values()) s.points.sort((a, b) => a.week - b.week)
  return [...byRoster.values()].sort((a, b) => (a.manager ?? '').localeCompare(b.manager ?? ''))
}

/** Contiguous runs only -- claude/league-suite.md AC7: never draw a week a mode does not have. */
function segmentsOf(points: SeriesPoint[]): SeriesPoint[][] {
  const segments: SeriesPoint[][] = []
  let current: SeriesPoint[] = []
  for (const p of points) {
    if (current.length > 0 && p.week !== current[current.length - 1].week + 1) {
      segments.push(current)
      current = []
    }
    current.push(p)
  }
  if (current.length > 0) segments.push(current)
  return segments
}

type BumpChartProps = {
  series: Series[]
  weeks: number[]
  teamCount: number
  highlighted: number | null
  onHighlight: (rosterId: number | null) => void
}

function BumpChart({ series, weeks, teamCount, highlighted, onHighlight }: BumpChartProps) {
  const marginLeft = 36
  const marginRight = 20
  const marginTop = 16
  const marginBottom = 28
  const xStep = 56
  const yStep = 22
  const width = marginLeft + marginRight + Math.max(1, weeks.length - 1) * xStep
  const height = marginTop + marginBottom + Math.max(1, teamCount - 1) * yStep

  const xOf = (week: number) => marginLeft + weeks.indexOf(week) * xStep
  const yOf = (rank: number) => marginTop + (rank - 1) * yStep

  return (
    <div className="table-wrap">
      <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} className="bump-chart">
        {/* Rank gridlines + labels, 1 at top -- this is the one axis every mode shares. */}
        {Array.from({ length: teamCount }, (_, i) => i + 1).map((rank) => (
          <g key={rank}>
            <line x1={marginLeft} y1={yOf(rank)} x2={width - marginRight} y2={yOf(rank)} className="bump-gridline" />
            <text x={marginLeft - 10} y={yOf(rank)} className="bump-rank-label mono" textAnchor="end" dominantBaseline="middle">
              {rank}
            </text>
          </g>
        ))}
        {/* Week ticks. */}
        {weeks.map((w) => (
          <text key={w} x={xOf(w)} y={height - marginBottom + 18} className="bump-week-label mono" textAnchor="middle">
            {w}
          </text>
        ))}

        {series.map((s) => {
          const hue = s.hue
          const isHighlighted = highlighted === s.rosterId
          const dimmed = highlighted != null && !isHighlighted
          return (
            <g
              key={s.rosterId}
              className={`bump-series${dimmed ? ' dimmed' : ''}`}
              onClick={() => onHighlight(isHighlighted ? null : s.rosterId)}
            >
              {segmentsOf(s.points).map((seg, i) => (
                <polyline
                  key={i}
                  points={seg.map((p) => `${xOf(p.week)},${yOf(p.rank)}`).join(' ')}
                  fill="none"
                  stroke={`oklch(70% 0.14 ${hue})`}
                  strokeWidth={isHighlighted ? 3 : 1.75}
                />
              ))}
              {s.points.map((p) => (
                <circle key={p.week} cx={xOf(p.week)} cy={yOf(p.rank)} r={isHighlighted ? 4 : 3} fill={`oklch(70% 0.14 ${hue})`}>
                  <title>
                    {s.manager ?? `roster ${s.rosterId}`} · week {p.week} · rank {p.rank}
                    {p.score != null ? ` · ${p.score.toFixed(2)}` : ''}
                    {p.note ? ` · ${p.note}` : ''}
                  </title>
                </circle>
              ))}
            </g>
          )
        })}
      </svg>
    </div>
  )
}

export default function PowerRankings() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<PowerRankingsData | null>(null)
  const [standings, setStandings] = useState<StandingRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<PowerRankingKind | null>(null)
  const [view, setView] = useState<'league' | 'team'>('league')
  const [highlighted, setHighlighted] = useState<number | null>(null)
  const [computing, setComputing] = useState(false)
  const [rankInputs, setRankInputs] = useState<Record<number, string>>({})
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<string | null>(null)

  function refetch() {
    if (!sleeperLeagueId) return
    getPowerRankings(sleeperLeagueId)
      .then((d) => {
        setData(d)
        setError(null)
        // Default depends on the week, not a fixed mode -- before the season
        // starts REALIZED has nothing in it and MARKET_VALUE is the only thing
        // that can render, per claude/league-suite.md.
        setMode((prev) => prev ?? (d.nflState.started ? 'COMPUTED_REALIZED' : 'COMPUTED_MARKET_VALUE'))
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
    getLeagueHistory(sleeperLeagueId)
      .then((h) => setStandings(h.seasons[0]?.standings ?? []))
      .catch(() => {}) // commissioner-ranking input list only; the chart works without it
  }

  useEffect(refetch, [sleeperLeagueId])

  const season = useMemo(() => {
    if (!data || data.entries.length === 0) return Number(data?.nflState.season ?? new Date().getFullYear())
    return Math.max(...data.entries.map((e) => e.season))
  }, [data])

  const currentWeek = data?.nflState.week ?? 1

  async function compute() {
    if (!sleeperLeagueId) return
    setComputing(true)
    setError(null)
    try {
      await computePowerRankings(sleeperLeagueId, season, currentWeek)
      refetch()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setComputing(false)
    }
  }

  async function saveCommissioner() {
    if (!sleeperLeagueId || !standings) return
    const ranked = standings
      .map((s) => ({ rosterId: s.rosterId, rank: Number(rankInputs[s.rosterId]) }))
      .filter((r) => !Number.isNaN(r.rank))
      .sort((a, b) => a.rank - b.rank)
      .map((r) => r.rosterId)
    if (ranked.length !== standings.length) {
      setSaveMessage(`Rank every team first (${ranked.length} of ${standings.length} set)`)
      return
    }
    setSaving(true)
    setSaveMessage(null)
    try {
      await saveCommissionerRanking(sleeperLeagueId, season, currentWeek, ranked)
      setSaveMessage(`Saved week ${currentWeek}`)
      refetch()
    } catch (e) {
      setSaveMessage(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  if (!data || !mode) {
    return (
      <div className="content">
        <section className="panel">
          <div className="panel-head">
            <h2>Power rankings</h2>
          </div>
          {error ? <div className="error">{error}</div> : <p className="muted small">Loading power rankings…</p>}
        </section>
      </div>
    )
  }

  const series = buildSeries(data.entries, mode, season)
  const weeks = [...new Set(data.entries.filter((e) => e.kind === mode && e.season === season).map((e) => e.week))].sort(
    (a, b) => a - b,
  )
  const teamCount = new Set(data.entries.filter((e) => e.season === season).map((e) => e.rosterId)).size || series.length

  // Team (transpose) view: one manager, all three modes -- "the room had you
  // 3rd all October while your roster ranked 1st" is the delta this page
  // exists for, and it's the same data as the league view, differently sliced.
  const teamViewOptions = [...new Map(data.entries.map((e) => [e.rosterId, e.manager ?? `roster ${e.rosterId}`])).entries()]
  const teamViewRosterId = highlighted ?? teamViewOptions[0]?.[0] ?? null
  const teamSeries: Series[] =
    view === 'team' && teamViewRosterId != null
      ? (['COMPUTED_MARKET_VALUE', 'COMPUTED_REALIZED', 'COMMISSIONER'] as PowerRankingKind[])
          .map((k) => {
            const s = buildSeries(data.entries, k, season).find((s) => s.rosterId === teamViewRosterId)
            return s && { ...s, hue: KIND_HUE[k] } // color by mode here, not by manager -- see the Series comment
          })
          .filter((s): s is Series => !!s)
      : []
  const teamViewWeeks =
    view === 'team'
      ? [...new Set(data.entries.filter((e) => e.rosterId === teamViewRosterId && e.season === season).map((e) => e.week))].sort(
          (a, b) => a - b,
        )
      : []

  const seasonHasNoWeeksYet = mode === 'COMPUTED_REALIZED' && !data.nflState.started

  return (
    <div className="content">
      <section className="panel">
        <div className="panel-head">
          <h2>Power rankings</h2>
          {sleeperLeagueId && (
            <Link className="chip" to={`/leagues/${sleeperLeagueId}/history`}>
              ← League history
            </Link>
          )}
        </div>

        {error && <div className="error">{error}</div>}

        {/* Three jobs, three treatments -- see styles.css's control-hierarchy
            rule. These used to be six identical chips in one strip: two
            exclusive selectors and a write, with nothing saying which was
            which (and, until the `.controls button` fix, nothing even saying
            which mode was selected). */}
        <div className="power-controls">
          <div className="segmented" role="group" aria-label="Ranking mode">
            {(['COMPUTED_MARKET_VALUE', 'COMPUTED_REALIZED', 'COMMISSIONER'] as PowerRankingKind[]).map((k) => (
              <button
                key={k}
                type="button"
                className={`segment${mode === k ? ' on' : ''}`}
                aria-pressed={mode === k}
                onClick={() => setMode(k)}
              >
                {KIND_LABEL[k]}
              </button>
            ))}
          </div>

          <div className="segmented sm" role="group" aria-label="What to show">
            <button
              type="button"
              className={`segment${view === 'league' ? ' on' : ''}`}
              aria-pressed={view === 'league'}
              onClick={() => setView('league')}
            >
              All teams
            </button>
            <button
              type="button"
              className={`segment${view === 'team' ? ' on' : ''}`}
              aria-pressed={view === 'team'}
              onClick={() => setView('team')}
              disabled={!highlighted}
              title={highlighted ? undefined : 'Pick a team in the chart first'}
            >
              One team
            </button>
          </div>

          <span className="power-controls-spacer" />

          {/* A write, not a view toggle: it runs a backend job and stores new
              snapshots. Kept away from the selectors and given the outline
              treatment so it never reads as "the third mode". */}
          <button
            className="action-button"
            onClick={compute}
            disabled={computing}
            title="Recompute market value and realized rankings for the current week and save them"
          >
            {computing ? 'Computing…' : `Compute week ${currentWeek}`}
          </button>
        </div>

        <p className="muted small">{KIND_CAVEAT[mode]}</p>

        {seasonHasNoWeeksYet && weeks.length === 0 ? (
          <p className="muted">
            The {data.nflState.season} season hasn't started yet (kicks off {data.nflState.seasonStartDate}) -- realized
            power rankings need games actually played. Market value is the only mode with anything to show right now.
          </p>
        ) : view === 'league' && weeks.length === 0 ? (
          <p className="muted">
            {/* Names the button it is pointing at, rather than a shortened
                version of it -- "Click Compute" against a button reading
                "Compute week 1" makes the reader look for a third control. */}
            No {KIND_LABEL[mode].toLowerCase()} snapshots yet for this league.{' '}
            {mode !== 'COMMISSIONER' && `Use “Compute week ${currentWeek}” above to build the first one.`}
          </p>
        ) : view === 'league' ? (
          <>
            <div className="bump-legend">
              {series.map((s) => {
                const hue = s.hue
                const isHighlighted = highlighted === s.rosterId
                return (
                  <button
                    key={s.rosterId}
                    className={`bump-legend-item bump-legend-button${isHighlighted ? ' on' : ''}`}
                    onClick={() => setHighlighted(isHighlighted ? null : s.rosterId)}
                  >
                    <span className="bump-legend-swatch" style={{ background: `oklch(70% 0.14 ${hue})` }} />
                    {s.manager ?? `roster ${s.rosterId}`}
                  </button>
                )
              })}
            </div>
            <BumpChart series={series} weeks={weeks} teamCount={teamCount} highlighted={highlighted} onHighlight={setHighlighted} />
          </>
        ) : teamViewWeeks.length === 0 ? (
          <p className="muted">No snapshots yet for this team in any mode.</p>
        ) : (
          <>
            <p className="small">
              {teamSeries[0]?.manager ?? 'This team'} across all three modes. Each line is one mode, not one manager.
            </p>
            <div className="bump-legend">
              {(['COMPUTED_MARKET_VALUE', 'COMPUTED_REALIZED', 'COMMISSIONER'] as PowerRankingKind[]).map((k) => (
                <span key={k} className="bump-legend-item">
                  <span className="bump-legend-swatch" style={{ background: `oklch(70% 0.14 ${KIND_HUE[k]})` }} />
                  {KIND_LABEL[k]}
                </span>
              ))}
            </div>
            <BumpChart series={teamSeries} weeks={teamViewWeeks} teamCount={teamCount} highlighted={null} onHighlight={() => {}} />
          </>
        )}

        {view === 'league' && series.length > 0 && (
          <p className="tiny muted">Click a line to highlight it and unlock the per-team view.</p>
        )}
      </section>

      {mode === 'COMMISSIONER' && standings && standings.length > 0 && (
        <section className="panel">
          <div className="panel-head">
            <h2>Set week {currentWeek}'s commissioner ranking</h2>
          </div>
          <p className="muted small">Your own ordering. An opinion, signed -- not a measurement.</p>
          <div className="table-wrap">
            <table className="standings">
              <thead>
                <tr>
                  <th>Manager</th>
                  <th className="mono">Rank</th>
                </tr>
              </thead>
              <tbody>
                {standings.map((s) => (
                  <tr key={s.rosterId}>
                    <td>{s.manager ?? `roster ${s.rosterId}`}</td>
                    <td>
                      <input
                        type="number"
                        min={1}
                        max={standings.length}
                        className="mono rank-input"
                        value={rankInputs[s.rosterId] ?? ''}
                        onChange={(e) => setRankInputs((prev) => ({ ...prev, [s.rosterId]: e.target.value }))}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="controls">
            <button onClick={saveCommissioner} disabled={saving}>
              {saving ? 'Saving…' : 'Save ranking'}
            </button>
            {saveMessage && <span className="small muted">{saveMessage}</span>}
          </div>
        </section>
      )}
    </div>
  )
}
