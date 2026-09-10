import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  ALL_POWER_RANKING_KINDS,
  computePowerRankings,
  getBallot,
  getPowerRankings,
  saveCommissionerRanking,
  submitBallot,
  type BallotState,
  type PowerRankingEntry,
  type PowerRankingKind,
  type PowerRankings as PowerRankingsData,
} from '../api'
import RankBoard, { type RankBoardMember } from '../components/RankBoard'
import { hueFor } from '../hue'

/**
 * claude/league-suite.md "Power rankings, three ways", now four -- one page,
 * four independent orderings of the same league, the deltas between them being
 * the actual point. Only rank is drawn on the shared axis: COMMISSIONER
 * produces an ordering with no score behind it, so score rides in the tooltip
 * rather than on the axis (see the plan's "y-axis is forced"). MEMBER, added by
 * claude/power-rankings-ballots.md, turns out to have a score after all -- the
 * average ballot rank -- which is why it carries spread fields the other three
 * leave null.
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
  MEMBER: 'The room',
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
  // 130 rather than anything nearer the existing three: spacings of 70/80/80/130
  // are the most even four-way split available under the constraint above, and
  // it clears --teal (~175) by 45, which is the tightest gap but the least
  // costly one -- teal is generic interaction, never an identity color, so the
  // two never appear as peers competing to be read as the same thing.
  MEMBER: 130,
}

const KIND_CAVEAT: Record<PowerRankingKind, string> = {
  COMPUTED_MARKET_VALUE:
    "Preseason-flavoured: this app's board value on each roster's best starting lineup, right now. Goes stale the moment real games are played.",
  COMPUTED_REALIZED:
    'Backward-looking: cumulative average starting-lineup points per week, from games already played. A hot start survives an injury it should not.',
  COMMISSIONER: "Allan's own ordering. An opinion, signed -- not a measurement.",
  MEMBER:
    'The room: every manager who submitted a ballot this week, averaged. Managers rank their own team too, and the bias that produces is shown below rather than quietly removed.',
}

// thin: this week's aggregate rests on fewer than half the league's ballots, so
// the segment through it is drawn dotted. Always false for the three stored
// modes -- a computed snapshot and a signed commissioner ordering are not
// "partially submitted", they either exist for a week or they don't.
export type SeriesPoint = {
  week: number
  rank: number
  score: number | null
  note: string | null
  ballotCount: number | null
  thin: boolean
}
// hue is what actually draws the line -- the manager's own color in the
// all-teams view (hueFor over the whole manager-id space, spread wide enough
// to stay distinct), but a fixed per-mode color in the per-team view, where
// coloring by manager would draw all three lines in the same color since
// it's one manager's data three times (see KIND_HUE).
type Series = { rosterId: number; managerId: number | null; manager: string | null; hue: number; points: SeriesPoint[] }

function buildSeries(
  entries: PowerRankingEntry[],
  kind: PowerRankingKind,
  season: number,
  memberCount: number,
): Series[] {
  const byRoster = new Map<number, Series>()
  for (const e of entries) {
    if (e.kind !== kind || e.season !== season) continue
    let s = byRoster.get(e.rosterId)
    if (!s) {
      s = { rosterId: e.rosterId, managerId: e.managerId, manager: e.manager, hue: hueFor(String(e.managerId ?? e.rosterId)), points: [] }
      byRoster.set(e.rosterId, s)
    }
    const ballotCount = e.ballotCount ?? null
    s.points.push({
      week: e.week,
      rank: e.rank,
      score: e.score,
      note: e.note,
      ballotCount,
      thin: ballotCount != null && memberCount > 0 && ballotCount * 2 < memberCount,
    })
  }
  for (const s of byRoster.values()) s.points.sort((a, b) => a.week - b.week)
  return [...byRoster.values()].sort((a, b) => (a.manager ?? '').localeCompare(b.manager ?? ''))
}

type Segment = { points: SeriesPoint[]; thin: boolean }

/**
 * Contiguous runs of equal coverage -- claude/league-suite.md AC7 (never draw a
 * week a mode does not have) plus the coverage split this feature adds.
 *
 * Two rules, and the second one has a trap in it. A break in weeks starts a new
 * segment and the two do NOT share a point: that gap is real, and an undrawn
 * gap is what "no data" looks like everywhere else on this chart. A change in
 * coverage also starts a new segment, but there the boundary point IS
 * duplicated into both -- weeks 3 and 4 both exist, so leaving 3->4 undrawn
 * would say "no data" about a week that has data, just thinner
 * (claude/plan-review-power-rankings-ballots.md finding 9c).
 *
 * The duplicated point goes to the THIN side, so a boundary link is drawn
 * dotted. Drawing it solid would claim the step into thin coverage was as
 * well-supported as the weeks before it, which is the direction this page is
 * not allowed to round in.
 */
export function segmentsOf(points: SeriesPoint[]): Segment[] {
  const segments: Segment[] = []
  let current: SeriesPoint[] = []
  for (const p of points) {
    const prev = current[current.length - 1]
    if (prev) {
      if (p.week !== prev.week + 1) {
        segments.push({ points: current, thin: prev.thin })
        current = []
      } else if (p.thin !== prev.thin) {
        segments.push({ points: current, thin: prev.thin })
        current = [prev] // carried into the next segment, so the boundary is drawn
      }
    }
    current.push(p)
  }
  if (current.length > 0) segments.push({ points: current, thin: current[current.length - 1].thin })
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

  /**
   * Linear in week number, not ordinal in the weeks that happen to be present.
   *
   * This used to be `weeks.indexOf(week) * xStep`, which is the same thing for
   * a mode that has every week -- so the three computed/commissioner modes
   * never showed the difference. A ballot mode does: ballots in weeks 2 and 5
   * would sit one column apart, labelled "2" and "5", compressing four missing
   * weeks into one column's width. Nothing is interpolated, so AC7 is still
   * satisfied -- it is the subtler failure of a chart that is correct and still
   * says the wrong thing (claude/lessons.md #5).
   */
  const minWeek = weeks.length > 0 ? weeks[0] : 1
  const maxWeek = weeks.length > 0 ? weeks[weeks.length - 1] : 1
  const weekSpan = Math.max(1, maxWeek - minWeek)
  const width = marginLeft + marginRight + weekSpan * xStep
  const height = marginTop + marginBottom + Math.max(1, teamCount - 1) * yStep

  const xOf = (week: number) => marginLeft + (week - minWeek) * xStep
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
              {segmentsOf(s.points).map((seg, i) =>
                // A one-point segment draws no stroke at all -- <polyline> with
                // a single vertex renders nothing, and only the <circle> below
                // survives. Harmless for the modes that have every week; for a
                // ballot mode with one submitted week it is the whole chart. So
                // a lone point gets an explicit ring instead of silently
                // becoming a dot indistinguishable from a data marker.
                seg.points.length === 1 ? (
                  <circle
                    key={i}
                    cx={xOf(seg.points[0].week)}
                    cy={yOf(seg.points[0].rank)}
                    r={isHighlighted ? 7 : 6}
                    fill="none"
                    stroke={`oklch(70% 0.14 ${hue})`}
                    strokeWidth={1.25}
                    strokeDasharray={seg.thin ? '2 2' : undefined}
                  />
                ) : (
                  <polyline
                    key={i}
                    points={seg.points.map((p) => `${xOf(p.week)},${yOf(p.rank)}`).join(' ')}
                    fill="none"
                    stroke={`oklch(70% 0.14 ${hue})`}
                    strokeWidth={isHighlighted ? 3 : 1.75}
                    // Thin coverage is drawn, but never drawn as confidently as
                    // a full week. The chart may not look more certain than its
                    // inputs -- claude/league-suite.md's rule for this page.
                    strokeDasharray={seg.thin ? '4 3' : undefined}
                  />
                ),
              )}
              {s.points.map((p) => (
                <circle key={p.week} cx={xOf(p.week)} cy={yOf(p.rank)} r={isHighlighted ? 4 : 3} fill={`oklch(70% 0.14 ${hue})`}>
                  <title>
                    {s.manager ?? `roster ${s.rosterId}`} · week {p.week} · rank {p.rank}
                    {p.score != null ? ` · ${p.score.toFixed(2)}` : ''}
                    {p.note ? ` · ${p.note}` : ''}
                    {p.thin ? ' · thin coverage' : ''}
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

/**
 * min..max of the ranks a team actually received, with a dot at the mean.
 *
 * A column of numbers cannot show "half the league has him 1st and half has him
 * 8th" -- that is the single most interesting fact this mode produces, and it
 * is a shape, not a statistic (claude/power-rankings-ballots.md, "The room").
 * Scaled against the league size so bars are comparable down the column.
 */
function SpreadBar({ entry, teamCount }: { entry: PowerRankingEntry; teamCount: number }) {
  const best = entry.bestRank
  const worst = entry.worstRank
  if (best == null || worst == null || teamCount < 2) return <span className="tiny muted">--</span>

  const pct = (rank: number) => ((rank - 1) / (teamCount - 1)) * 100
  const left = pct(best)
  const width = Math.max(2, pct(worst) - left)
  const meanLeft = entry.score == null ? null : pct(entry.score)

  return (
    <span className="spread-bar" title={`ranked between ${best} and ${worst}`}>
      <span className="spread-bar-range" style={{ left: `${left}%`, width: `${width}%` }} />
      {meanLeft != null && <span className="spread-bar-mean" style={{ left: `${meanLeft}%` }} />}
    </span>
  )
}

export default function PowerRankings() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<PowerRankingsData | null>(null)
  // Replaces the old getLeagueHistory() standings fetch, which came from
  // roster_season and so was empty on a league whose history had never been
  // ingested -- the editor simply never rendered there (design doc AC14).
  // /ballot sources its members from league_member + Sleeper's rosters, which
  // exist from the day a league does.
  const [ballot, setBallot] = useState<BallotState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<PowerRankingKind | null>(null)
  const [view, setView] = useState<'league' | 'team'>('league')
  const [highlighted, setHighlighted] = useState<number | null>(null)
  const [computing, setComputing] = useState(false)
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
    getBallot(sleeperLeagueId)
      .then(setBallot)
      .catch(() => {}) // editors only; the chart works without it
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

  async function saveCommissioner(orderedRosterIds: number[]) {
    if (!sleeperLeagueId) return
    setSaving(true)
    setSaveMessage(null)
    try {
      await saveCommissionerRanking(sleeperLeagueId, season, currentWeek, orderedRosterIds)
      setSaveMessage(`Saved week ${currentWeek}`)
      refetch()
    } catch (e) {
      setSaveMessage(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  async function sendBallot(orderedRosterIds: number[]) {
    if (!sleeperLeagueId) return
    setSaving(true)
    setSaveMessage(null)
    try {
      await submitBallot(sleeperLeagueId, currentWeek, orderedRosterIds)
      setSaveMessage(`Ballot submitted for week ${currentWeek}`)
      refetch()
    } catch (e) {
      setSaveMessage(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  if (!data || !mode) {
    return (
      <div className="content scrolls">
        <section className="panel">
          <div className="panel-head">
            <h2>Power rankings</h2>
          </div>
          {error ? <div className="error">{error}</div> : <p className="muted small">Loading power rankings…</p>}
        </section>
      </div>
    )
  }

  // Computed before the series, because it is also the denominator for "is this
  // week's aggregate thin?". Roster count stands in for member count on purpose:
  // it needs no extra field on this payload, and the two differ only where a
  // roster is co-owned (two voters, one roster) or orphaned (no voter) -- neither
  // of which exists in any league this app has data for. If that stops being
  // true, this is the line to replace with a real memberCount from the wire.
  const teamCount = new Set(data.entries.filter((e) => e.season === season).map((e) => e.rosterId)).size

  const series = buildSeries(data.entries, mode, season, teamCount)
  // Gridline count only: falls back to the drawn series when the season filter
  // above matched nothing, which is the pre-existing `|| series.length` guard.
  const gridRows = teamCount || series.length

  // The board's chips. `members` comes from /ballot, so it exists on a league
  // with no ingested history -- which is the whole point of AC14.
  const boardMembers: RankBoardMember[] = (ballot?.members ?? []).map((m) => ({
    rosterId: m.rosterId,
    managerId: m.managerId,
    manager: m.manager,
    teamName: m.teamName,
    isMe: m.isMe,
  }))

  // Seed the commissioner board from the ordering already stored for this week,
  // so re-opening it edits rather than starts over.
  const commissionerOrder = data.entries
    .filter((e) => e.kind === 'COMMISSIONER' && e.season === season && e.week === currentWeek)
    .sort((a, b) => a.rank - b.rank)
    .map((e) => e.rosterId)

  const memberEntries = data.entries
    .filter((e) => e.kind === 'MEMBER' && e.season === season && e.week === currentWeek)
    .sort((a, b) => a.rank - b.rank)

  // Suppressed below three ballots upstream, so a null stdev is "not enough to
  // say", not "no disagreement" -- and it must not be able to win this.
  const mostDivisive = memberEntries.reduce<PowerRankingEntry | null>(
    (best, e) => (e.stdev != null && (best == null || e.stdev > best.stdev!) ? e : best),
    null,
  )

  const homers = memberEntries
    .filter((e) => e.selfRankBias != null)
    .sort((a, b) => a.selfRankBias! - b.selfRankBias!)
  const weeks = [...new Set(data.entries.filter((e) => e.kind === mode && e.season === season).map((e) => e.week))].sort(
    (a, b) => a - b,
  )

  // Team (transpose) view: one manager, all four modes -- "the room had you
  // 3rd all October while your roster ranked 1st" is the delta this page
  // exists for, and it's the same data as the league view, differently sliced.
  const teamViewOptions = [...new Map(data.entries.map((e) => [e.rosterId, e.manager ?? `roster ${e.rosterId}`])).entries()]
  const teamViewRosterId = highlighted ?? teamViewOptions[0]?.[0] ?? null
  const teamSeries: Series[] =
    view === 'team' && teamViewRosterId != null
      ? ALL_POWER_RANKING_KINDS
          .map((k) => {
            const s = buildSeries(data.entries, k, season, teamCount).find((s) => s.rosterId === teamViewRosterId)
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
    <div className="content scrolls">
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
            {ALL_POWER_RANKING_KINDS.map((k) => (
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
            {/* Names the control it is pointing at, rather than a shortened
                version of it -- "Click Compute" against a button reading
                "Compute week 1" makes the reader look for a third control.
                And each mode is filled by a DIFFERENT control: the two computed
                modes by the Compute button, MEMBER by twelve people submitting
                ballots, COMMISSIONER by one person's board. Pointing all three
                at Compute would be pointing two of them at a button that cannot
                produce them. */}
            No {KIND_LABEL[mode].toLowerCase()} snapshots yet for this league.{' '}
            {mode === 'COMPUTED_MARKET_VALUE' || mode === 'COMPUTED_REALIZED'
              ? `Use “Compute week ${currentWeek}” above to build the first one.`
              : mode === 'MEMBER'
                ? 'Ballots build this one -- submit yours below, and the room fills in as others do.'
                : ''}
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
            <BumpChart series={series} weeks={weeks} teamCount={gridRows} highlighted={highlighted} onHighlight={setHighlighted} />
          </>
        ) : teamViewWeeks.length === 0 ? (
          <p className="muted">No snapshots yet for this team in any mode.</p>
        ) : (
          <>
            <p className="small">
              {teamSeries[0]?.manager ?? 'This team'} across all four modes. Each line is one mode, not one manager.
            </p>
            <div className="bump-legend">
              {ALL_POWER_RANKING_KINDS.map((k) => (
                <span key={k} className="bump-legend-item">
                  <span className="bump-legend-swatch" style={{ background: `oklch(70% 0.14 ${KIND_HUE[k]})` }} />
                  {KIND_LABEL[k]}
                </span>
              ))}
            </div>
            <BumpChart series={teamSeries} weeks={teamViewWeeks} teamCount={gridRows} highlighted={null} onHighlight={() => {}} />
          </>
        )}

        {view === 'league' && series.length > 0 && (
          <p className="tiny muted">Click a line to highlight it and unlock the per-team view.</p>
        )}
      </section>

      {/* The drag board, used twice: one signed ordering, or one ballot among
          twelve. Same component, different copy and a different onSubmit --
          which is all "commissioner is a bit simpler" amounts to in code. */}
      {mode === 'COMMISSIONER' && ballot && (
        <section className="panel">
          <div className="panel-head">
            <h2>Set week {currentWeek}'s commissioner ranking</h2>
          </div>
          {ballot.canCommission ? (
            <>
              <p className="muted small">Your own ordering. An opinion, signed -- not a measurement.</p>
              <RankBoard
                key={`commissioner-${currentWeek}-${ballot.members.length}`}
                members={boardMembers}
                ariaLabel="Commissioner ranking"
                initialOrder={commissionerOrder}
                onSubmit={saveCommissioner}
                submitLabel="Save ranking"
                submitting={saving}
              />
              {saveMessage && <p className="small muted">{saveMessage}</p>}
            </>
          ) : (
            <p className="muted small">
              {/* Fails closed, and names the fix rather than just refusing. An
                  empty commissioner set means this league was ingested before
                  league_member existed, not that nobody commissions it. */}
              {ballot.commissionerKnown
                ? "Only this league's commissioner can set this ranking."
                : 'No commissioner detected for this league -- re-run league ingest to pick one up from Sleeper.'}
            </p>
          )}
        </section>
      )}

      {mode === 'MEMBER' && ballot && (
        <>
          <section className="panel">
            <div className="panel-head">
              <h2>Your ballot -- week {currentWeek}</h2>
              <span className="chip">
                {ballot.ballotCount} of {ballot.memberCount} in
              </span>
            </div>
            {ballot.canSubmit ? (
              <>
                <p className="muted small">
                  Rank all {ballot.members.length}, your own team included -- the bias that produces is shown below
                  rather than quietly removed. You can resubmit this week's ballot; you cannot backdate one.
                </p>
                <RankBoard
                  key={`ballot-${currentWeek}-${ballot.mine?.submittedAt ?? 'new'}`}
                  members={boardMembers}
                  ariaLabel="Your ballot"
                  initialOrder={ballot.mine?.rosterIds}
                  onSubmit={sendBallot}
                  submitLabel={ballot.mine ? 'Resubmit ballot' : 'Submit ballot'}
                  submitting={saving}
                />
                {saveMessage && <p className="small muted">{saveMessage}</p>}
              </>
            ) : (
              <p className="muted small">
                {/* canSubmit folds together signed-out, not-a-member, non-NFL
                    and not-the-current-week. Naming which one applies needs the
                    same rule twice; naming the common one and being honest
                    about the rest does not. */}
                Sign in as a member of this league to submit a ballot for the current week.
              </p>
            )}
          </section>

          {memberEntries.length > 0 && (
            <section className="panel">
              <div className="panel-head">
                <h2>The room</h2>
                {mostDivisive && (
                  <span className="chip">Most divisive: {mostDivisive.manager ?? `roster ${mostDivisive.rosterId}`}</span>
                )}
              </div>
              <div className="table-wrap">
                <table className="standings">
                  <thead>
                    <tr>
                      <th className="mono">#</th>
                      <th>Team</th>
                      <th className="mono">Avg</th>
                      <th>Spread</th>
                      <th className="mono">Ballots</th>
                    </tr>
                  </thead>
                  <tbody>
                    {memberEntries.map((e) => (
                      <tr key={e.rosterId}>
                        <td className="mono">{e.rank}</td>
                        <td>{e.manager ?? `roster ${e.rosterId}`}</td>
                        <td className="mono">{e.score?.toFixed(2) ?? '--'}</td>
                        <td>
                          <SpreadBar entry={e} teamCount={gridRows} />
                        </td>
                        <td className="mono">
                          {e.ballotCount ?? 0}
                          {/* Below three ballots sigma is suppressed upstream -- a
                              population sigma of 0.0 from one vote renders as
                              perfect consensus, the most misleading output here. */}
                          {e.stdev != null ? ` (o ${e.stdev.toFixed(1)})` : ''}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="tiny muted">
                A team ranked by fewer than half this week's ballots is placed after every team that cleared that bar,
                rather than winning the week on one enthusiastic vote.
              </p>
            </section>
          )}

          {homers.length > 0 && (
            <section className="panel">
              <div className="panel-head">
                <h2>Homers</h2>
              </div>
              <p className="muted small">
                Where a manager put their own team, against where the room put it. Negative means they rank themselves
                higher than everyone else does. With this many ballots your own vote moves your own average by a
                fraction of a rank, so this is commentary, not a correction being applied.
              </p>
              <div className="table-wrap">
                <table className="standings">
                  <thead>
                    <tr>
                      <th>Manager</th>
                      <th className="mono">Self vs room</th>
                    </tr>
                  </thead>
                  <tbody>
                    {homers.map((e) => (
                      <tr key={e.rosterId}>
                        <td>{e.manager ?? `roster ${e.rosterId}`}</td>
                        <td className="mono">
                          {e.selfRankBias! > 0 ? `+${e.selfRankBias}` : e.selfRankBias}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </>
      )}
    </div>
  )
}
