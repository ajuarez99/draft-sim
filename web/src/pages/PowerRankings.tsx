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
import { useUser } from '../user'

/**
 * claude/league-suite.md "Power rankings, three ways" -- one page, three
 * independent orderings of the same league, the deltas between them being
 * the actual point.
 *
 * REDESIGN NOTE (an earlier pass). The page used to open on a full-height bump
 * chart that, in week 1, is a single column of dots, plus a twelve-item legend
 * -- roughly 700px of vertical space before the reader reached the ordering
 * they came for. The ordering is now the hero; the chart is demoted into a
 * Trend panel that renders the tick strip until a mode actually HAS two
 * weeks, and the ballot board moves into a sticky sidebar.
 *
 * MARKET VALUE MERGE (this pass). COMPUTED_MARKET_VALUE used to be a fourth,
 * separate mode -- a forward-looking "board value right now" recomputed at
 * whatever week you happened to click Compute, sitting alongside a
 * backward-looking REALIZED at the very same week number, which read as two
 * different answers to "how good is this team". It is now week 0 of
 * REALIZED: a one-time preseason baseline (this app's board value on the
 * roster it saw at the FIRST compute, never overwritten by a later one -- see
 * the backend's `PowerRankingService#computeWeek0IfMissing`), and week 1
 * onward is purely "cumulative average starting-lineup points per week, from
 * games actually played" -- no market-value blending at any played week.
 */

const KIND_LABEL: Record<PowerRankingKind, string> = {
  COMPUTED_REALIZED: 'Realized',
  COMMISSIONER: 'Commissioner',
  MEMBER: 'The room',
}

// Fixed, not hueFor(kind) -- the mode names hash close enough together that two
// of them read as the same color. Clear of --crimson (~25, "you") and --teal
// (~175, generic interaction) per styles.css's house rule.
const KIND_HUE: Record<PowerRankingKind, number> = {
  COMPUTED_REALIZED: 210,
  COMMISSIONER: 290,
  MEMBER: 130,
}

const KIND_CAVEAT: Record<PowerRankingKind, string> = {
  COMPUTED_REALIZED:
    'Backward-looking: cumulative average starting-lineup points per week, from games already played. A hot start survives an injury it should not. Week 0 is the exception -- a one-time preseason baseline (this app\'s board value on each roster\'s best starting lineup, at first compute), not a played week.',
  COMMISSIONER: "Allan's own ordering. An opinion, signed -- not a measurement.",
  MEMBER:
    'The room: every manager who submitted a ballot this week, averaged. Managers rank their own team too, and the bias that produces is shown below rather than quietly removed.',
}

/** The mode each mode's movement column is measured against. Week 1 has no
 *  prior week, so a "movement" column can only honestly compare modes -- and
 *  the column header says which one. */
const REFERENCE_KIND: Record<PowerRankingKind, PowerRankingKind> = {
  MEMBER: 'COMPUTED_REALIZED',
  COMPUTED_REALIZED: 'MEMBER',
  COMMISSIONER: 'MEMBER',
}
const REFERENCE_LABEL: Record<PowerRankingKind, string> = {
  MEMBER: 'vs realized',
  COMPUTED_REALIZED: 'vs room',
  COMMISSIONER: 'vs room',
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
 * Unchanged by the redesign; PowerRankings.chart.test.ts covers it.
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
        {Array.from({ length: teamCount }, (_, i) => i + 1).map((rank) => (
          <g key={rank}>
            <line x1={marginLeft} y1={yOf(rank)} x2={width - marginRight} y2={yOf(rank)} className="bump-gridline" />
            <text x={marginLeft - 10} y={yOf(rank)} className="bump-rank-label mono" textAnchor="end" dominantBaseline="middle">
              {rank}
            </text>
          </g>
        ))}
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
 * Scaled against league size, so the bars are comparable down the column --
 * a full-width bar on every row is the bug this scaling exists to prevent.
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

/** A score bar for the modes that have a score but no spread: scaled between
 *  the week's own min and max, never 0-based, so twelve similar scores read as
 *  twelve similar bars rather than twelve full ones. */
function ScoreBar({ score, min, max, mine }: { score: number | null; min: number; max: number; mine: boolean }) {
  if (score == null || !(max > min)) return <span className="tiny muted">--</span>
  const width = 18 + ((score - min) / (max - min)) * 82
  return (
    <span className="pr-bar" title={`${score.toFixed(2)} on this mode's scale`}>
      <span className={`pr-bar-fill${mine ? ' mine' : ''}`} style={{ width: `${width}%` }} />
    </span>
  )
}

function avatarStyleFor(managerId: number | null, rosterId: number, isMe: boolean) {
  if (isMe) return { background: 'var(--crimson)', color: 'var(--bg)' }
  const hue = hueFor(String(managerId ?? rosterId))
  return { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
}

const ordinal = (n: number) => `${n}${n === 1 ? 'st' : n === 2 ? 'nd' : n === 3 ? 'rd' : 'th'}`

// Week 0 is an app-invented concept -- Sleeper's own week numbering starts at
// 1 -- so it never reads as a number in the UI, only as "Preseason".
const weekPhrase = (week: number) => (week === 0 ? 'Preseason' : `week ${week}`)
const weekShort = (week: number) => (week === 0 ? 'preseason' : `wk ${week}`)
const weekRangeLabel = (ws: number[]) => {
  const first = ws[0]
  const last = ws[ws.length - 1]
  if (first === last) return weekPhrase(first)
  return `weeks ${first === 0 ? 'preseason' : first}–${last}`
}

export default function PowerRankings() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const user = useUser()
  const [data, setData] = useState<PowerRankingsData | null>(null)
  const [ballot, setBallot] = useState<BallotState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<PowerRankingKind | null>(null)
  const [view, setView] = useState<'league' | 'team'>('league')
  const [highlighted, setHighlighted] = useState<number | null>(null)
  const [computing, setComputing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<string | null>(null)
  // 2026-09-11 feedback: "Realized" is meaningless on a cold open, so it's no
  // longer a top-level tab -- it's a popup off a manager's row instead.
  const [realizedFor, setRealizedFor] = useState<number | null>(null)

  function refetch() {
    if (!sleeperLeagueId) return
    getPowerRankings(sleeperLeagueId)
      .then((d) => {
        setData(d)
        setError(null)
        setMode((prev) => prev ?? 'MEMBER')
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
    getBallot(sleeperLeagueId)
      .then(setBallot)
      // Cleared, not left stale -- a failure here (signed out, not a member of
      // this league, a 403 for the newly-switched-to identity) must not leave
      // the PREVIOUS identity's ballot on screen. Editors only; the page works
      // without it.
      .catch(() => setBallot(null))
  }

  // Depends on the signed-in identity, not just the league -- switching
  // accounts (Sign out -> sign in as someone else) without leaving this page
  // used to leave `ballot` exactly as the old identity had fetched it, since
  // this effect never re-ran.
  useEffect(refetch, [sleeperLeagueId, user?.sleeperUserId])

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

  // Roster count stands in for member count, same reasoning as before: the two
  // differ only for a co-owned or orphaned roster, neither of which exists in
  // any league this app has data for.
  const teamCount = new Set(data.entries.filter((e) => e.season === season).map((e) => e.rosterId)).size

  const series = buildSeries(data.entries, mode, season, teamCount)
  const gridRows = teamCount || series.length

  const boardMembers: RankBoardMember[] = (ballot?.members ?? []).map((m) => ({
    rosterId: m.rosterId,
    managerId: m.managerId,
    manager: m.manager,
    teamName: m.teamName,
    isMe: m.isMe,
  }))

  const commissionerOrder = data.entries
    .filter((e) => e.kind === 'COMMISSIONER' && e.season === season && e.week === currentWeek)
    .sort((a, b) => a.rank - b.rank)
    .map((e) => e.rosterId)

  const weeks = [...new Set(data.entries.filter((e) => e.kind === mode && e.season === season).map((e) => e.week))].sort(
    (a, b) => a - b,
  )

  /** Entries for one mode, this season, one week -- the shape every panel below
   *  is built from. */
  const entriesFor = (kind: PowerRankingKind, week: number) =>
    data.entries.filter((e) => e.kind === kind && e.season === season && e.week === week).sort((a, b) => a.rank - b.rank)

  /** The roster-id order of the LATEST week a mode has anything for -- no new
   *  request, just re-slicing entries the page already fetched. Used only as a
   *  seed fallback below, never rendered as if it were this mode's own table. */
  const rankedBy = (kind: PowerRankingKind): number[] => {
    const modeWeeks = [...new Set(data.entries.filter((e) => e.kind === kind && e.season === season).map((e) => e.week))]
    const latest = modeWeeks.sort((a, b) => a - b).pop()
    return latest == null ? [] : entriesFor(kind, latest).map((e) => e.rosterId)
  }
  const memberIds = boardMembers.map((m) => m.rosterId)

  /**
   * What a board opens on. A stored ordering (a real, previously saved
   * commissioner ranking or submitted ballot) wins outright -- that's a fact,
   * not a seed. Failing that, the board is seeded from the best ordering the
   * page already has, so a FIRST commissioner ranking or ballot opens already
   * ranked instead of with all twelve chips in the tray: the user's job is to
   * disagree with a starting order, not assemble one from parts. `seededFrom`
   * is null exactly when `stored` was used, so the caller can tell "restored"
   * from "seeded" and copy the hint under the board accordingly.
   */
  function seedOrder(
    stored: number[] | undefined,
    fallbacks: { label: string; order: number[] }[],
  ): { order: number[]; seededFrom: string | null } {
    if (stored && stored.length > 0) return { order: stored, seededFrom: null }
    for (const f of fallbacks) if (f.order.length > 0) return { order: f.order, seededFrom: f.label }
    return { order: memberIds, seededFrom: 'the member list' }
  }

  // The member ballot's own seed deliberately excludes MEMBER as a fallback --
  // that mode is the average of everyone's ballots including the user's own,
  // so seeding a fresh ballot from it would feed the aggregate back into one
  // of its own inputs.
  const commissionerSeed = seedOrder(commissionerOrder, [
    { label: 'realized', order: rankedBy('COMPUTED_REALIZED') },
    { label: 'the room', order: rankedBy('MEMBER') },
  ])
  const ballotSeed = seedOrder(ballot?.mine?.rosterIds, [{ label: 'realized', order: rankedBy('COMPUTED_REALIZED') }])

  // The week the table shows: the latest week this mode actually has, not
  // necessarily the current NFL week.
  const tableWeek = weeks.length > 0 ? weeks[weeks.length - 1] : currentWeek
  const rows = entriesFor(mode, tableWeek)

  // Movement. rank(reference) - rank(current), both from the SAME season+week,
  // so a positive number means this mode is higher on the team than the
  // reference is. If the reference mode has no snapshot for this week there is
  // no honest delta to show -- the column goes to dashes and says so.
  const referenceKind = REFERENCE_KIND[mode]
  const referenceRows = entriesFor(referenceKind, tableWeek)
  const referenceRank = new Map(referenceRows.map((e) => [e.rosterId, e.rank]))
  const hasReference = referenceRows.length > 0
  const moveHeader = hasReference ? REFERENCE_LABEL[mode] : `${REFERENCE_LABEL[mode]} (n/a)`

  const scores = rows.map((e) => e.score).filter((s): s is number => s != null)
  const minScore = scores.length > 0 ? Math.min(...scores) : 0
  const maxScore = scores.length > 0 ? Math.max(...scores) : 0

  const memberEntries = entriesFor('MEMBER', currentWeek)

  const mostDivisive = memberEntries.reduce<PowerRankingEntry | null>(
    (best, e) => (e.stdev != null && (best == null || e.stdev > best.stdev!) ? e : best),
    null,
  )

  const homers = memberEntries.filter((e) => e.selfRankBias != null).sort((a, b) => a.selfRankBias! - b.selfRankBias!)

  // Team (transpose) view.
  const teamViewOptions = [...new Map(data.entries.map((e) => [e.rosterId, e.manager ?? `roster ${e.rosterId}`])).entries()]
  const teamViewRosterId = highlighted ?? teamViewOptions[0]?.[0] ?? null
  const teamSeries: Series[] =
    view === 'team' && teamViewRosterId != null
      ? ALL_POWER_RANKING_KINDS.map((k) => {
          const s = buildSeries(data.entries, k, season, teamCount).find((s) => s.rosterId === teamViewRosterId)
          return s && { ...s, hue: KIND_HUE[k] }
        }).filter((s): s is Series => !!s)
      : []
  const teamViewWeeks =
    view === 'team'
      ? [...new Set(data.entries.filter((e) => e.rosterId === teamViewRosterId && e.season === season).map((e) => e.week))].sort(
          (a, b) => a - b,
        )
      : []

  /** One row per mode for the pinned team: rank, a dot on the shared 1..N axis,
   *  and that mode's score. This is what the per-team view shows in a week the
   *  bump chart cannot draw (one week of data is not a line). */
  const focusRows =
    teamViewRosterId == null
      ? []
      : ALL_POWER_RANKING_KINDS.map((k) => {
          const week = [...new Set(data.entries.filter((e) => e.kind === k && e.season === season).map((e) => e.week))].sort(
            (a, b) => a - b,
          ).pop()
          const entry = week == null ? undefined : entriesFor(k, week).find((e) => e.rosterId === teamViewRosterId)
          return { kind: k, entry, week }
        })
  const focusRanks = focusRows.map((f) => f.entry?.rank).filter((r): r is number => r != null)
  const focusSpread = focusRanks.length > 1 ? Math.max(...focusRanks) - Math.min(...focusRanks) : 0
  const focusName = teamViewOptions.find(([id]) => id === teamViewRosterId)?.[1] ?? 'This team'
  const focusEntry = focusRows.find((f) => f.entry)?.entry

  // The chart is a line chart: below two weeks there is no line to draw, so the
  // Trend panel shows the coverage strip instead of 500px of empty gridlines.
  const chartReady = weeks.length >= 2
  const totalWeeks = Math.max(17, ...weeks)
  const hasSide = mode === 'MEMBER' || mode === 'COMMISSIONER'
  const scoreHeader = mode === 'MEMBER' ? 'Avg' : mode === 'COMPUTED_REALIZED' ? (tableWeek === 0 ? 'Value' : 'Pts/wk') : '--'

  // Keyed off the ROW's own week, not the mode -- REALIZED spans both week 0
  // (a market-value-shaped number) and weeks 1+ (points-per-week), so a
  // mode-level check would format every row the same regardless of which one
  // it actually is.
  function scoreLabel(e: PowerRankingEntry): string {
    if (e.score == null) return '--'
    if (e.week === 0) return String(Math.round(e.score))
    return e.score.toFixed(2)
  }

  function sourceLabel(e: PowerRankingEntry): string {
    if (mode === 'MEMBER') return `${e.ballotCount ?? 0}${e.stdev != null ? ` (o ${e.stdev.toFixed(1)})` : ''}`
    if (mode === 'COMMISSIONER') return 'signed'
    return weekShort(e.week)
  }

  return (
    <div className="content scrolls pr-page">
      <div className="pr-head">
        <div>
          <div className="pr-eyebrow">
            {season} · {weekPhrase(tableWeek)}
          </div>
          <h2 className="pr-title">Power rankings</h2>
        </div>
        <div className="pr-head-actions">
          {sleeperLeagueId && (
            <Link className="chip" to={`/leagues/${sleeperLeagueId}/history`}>
              ← League history
            </Link>
          )}
          {/* A write, not a view toggle: it runs a backend job and stores new
              snapshots, so it keeps the outline treatment and stays away from
              the selectors. */}
          <button
            className="action-button"
            onClick={compute}
            disabled={computing}
            title="Recompute realized rankings for the current week (and the preseason baseline, the first time) and save them"
          >
            {computing ? 'Computing…' : `Compute week ${currentWeek}`}
          </button>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {/* Modes are the page's primary axis, so they read as tabs rather than as
          one pill among several controls -- the six-controls-of-equal-weight
          problem this replaced. */}
      <div className="pr-tabs">
        <div className="pr-tablist" role="group" aria-label="Ranking mode">
          {/* Realized is deliberately excluded here -- it's a per-manager
              popup (click a row's avatar), not a top-level mode. It's still a
              real PowerRankingKind everywhere else (Trend legend, per-team
              view, movement reference). */}
          {ALL_POWER_RANKING_KINDS.filter((k) => k !== 'COMPUTED_REALIZED').map((k) => (
            <button
              key={k}
              type="button"
              className={`pr-tab${mode === k ? ' on' : ''}`}
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
            title={highlighted ? undefined : 'Pin a team in the table first'}
          >
            {highlighted ? `${focusName} only` : 'One team'}
          </button>
        </div>
      </div>

      <p className="pr-caveat">{KIND_CAVEAT[mode]}</p>

      <div className={`pr-grid${hasSide ? '' : ' solo'}`}>
        <div className="pr-main">
          {view === 'league' ? (
            <section className="panel">
              <div className="panel-head">
                <h2>{KIND_LABEL[mode]}</h2>
                <span className="small muted">
                  {weekPhrase(tableWeek)}
                  {mode === 'MEMBER' && ballot ? ` · ${ballot.ballotCount} ballots averaged` : ''}
                </span>
                {mode === 'MEMBER' && mostDivisive && (
                  <span className="chip">Most divisive: {mostDivisive.manager ?? `roster ${mostDivisive.rosterId}`}</span>
                )}
              </div>

              {rows.length === 0 ? (
                <p className="muted">
                  No {KIND_LABEL[mode].toLowerCase()} snapshots yet for this league.{' '}
                  {mode === 'COMPUTED_REALIZED'
                    ? `Use "Compute week ${currentWeek}" above to build the first one.`
                    : mode === 'MEMBER'
                      ? 'Ballots build this one -- submit yours below, and the room fills in as others do.'
                      : ''}
                </p>
              ) : (
                <div className="pr-list">
                  <div className="pr-row-head">
                    <span>#</span>
                    <span>Team</span>
                    <span className="pr-move-head">{moveHeader}</span>
                    <span className="pr-score-head">{scoreHeader}</span>
                    <span>{mode === 'MEMBER' ? 'Spread' : 'Score'}</span>
                    <span className="pr-source-head">{mode === 'MEMBER' ? 'Ballots' : 'Source'}</span>
                  </div>

                  {rows.map((e) => {
                    const isMe = ballot?.members.some((m) => m.isMe && m.rosterId === e.rosterId) ?? false
                    const pinned = highlighted === e.rosterId
                    const refRank = referenceRank.get(e.rosterId)
                    const delta = refRank == null ? null : refRank - e.rank
                    if (delta != null && Math.abs(delta) > Math.max(1, gridRows - 1)) {
                      // Impossible by construction: both ranks are 1..N for the
                      // same week. If this fires, the two sides came from
                      // different weeks or different orderings.
                      console.warn('[power] implausible movement delta', { rosterId: e.rosterId, rank: e.rank, refRank })
                    }
                    const member = ballot?.members.find((m) => m.rosterId === e.rosterId)
                    return (
                      <button
                        type="button"
                        key={e.rosterId}
                        className={`pr-row${pinned ? ' on' : ''}`}
                        aria-pressed={pinned}
                        onClick={() => setHighlighted(pinned ? null : e.rosterId)}
                      >
                        <span className={`pr-rank mono${isMe ? ' mine' : e.rank <= 3 ? ' top' : ''}`}>{e.rank}</span>
                        <span className="pr-team">
                          {/* Nested inside the row's own button on purpose --
                              stopPropagation keeps it from also toggling pin.
                              Opens the Realized popup for this manager. */}
                          <button
                            type="button"
                            className="avatar pr-realized-trigger"
                            style={avatarStyleFor(e.managerId, e.rosterId, isMe)}
                            title="See this team's Realized ranking"
                            onClick={(ev) => {
                              ev.stopPropagation()
                              setRealizedFor(e.rosterId)
                            }}
                          >
                            {(e.manager ?? `R${e.rosterId}`).charAt(0).toUpperCase()}
                          </button>
                          <span className="pr-team-text">
                            <span className="pr-team-name">{e.manager ?? `roster ${e.rosterId}`}</span>
                            {member?.teamName && member.teamName.toUpperCase() !== 'TBD' && (
                              <span className="pr-team-sub">{member.teamName}</span>
                            )}
                          </span>
                        </span>
                        <span className={`pr-move mono ${delta == null ? 'flat' : delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat'}`}>
                          {delta == null ? '–' : delta === 0 ? '–' : `${delta > 0 ? '▲' : '▼'}${Math.abs(delta)}`}
                        </span>
                        <span className="pr-score mono">{scoreLabel(e)}</span>
                        {mode === 'MEMBER' ? (
                          <SpreadBar entry={e} teamCount={gridRows} />
                        ) : (
                          <ScoreBar score={e.score} min={minScore} max={maxScore} mine={isMe} />
                        )}
                        <span className="pr-source mono">{sourceLabel(e)}</span>
                      </button>
                    )
                  })}

                  {mode === 'MEMBER' && (
                    <p className="pr-foot">
                      A team ranked by fewer than half this week's ballots is placed after every team that cleared that bar,
                      rather than winning the week on one enthusiastic vote.
                    </p>
                  )}
                  <p className="pr-foot">
                    {chartReady
                      ? 'Click a line in Trend to highlight it, or click a row to pin a team and unlock the per-team view.'
                      : 'Click a row to pin a team, then switch to the per-team view.'}
                  </p>
                </div>
              )}
            </section>
          ) : (
            <section className="panel">
              <div className="panel-head pr-focus-head">
                {focusEntry && (
                  <span
                    className="avatar"
                    style={avatarStyleFor(focusEntry.managerId, focusEntry.rosterId, false)}
                    aria-hidden="true"
                  >
                    {focusName.charAt(0).toUpperCase()}
                  </span>
                )}
                <h2>{focusName}</h2>
                <span className="small muted">all four modes</span>
              </div>

              {focusRanks.length === 0 ? (
                <p className="muted">No snapshots yet for this team in any mode.</p>
              ) : (
                <>
                  <p className="muted small">
                    {focusSpread === 0
                      ? `Every mode with data has ${focusName} ${ordinal(focusRanks[0])}.`
                      : `${focusSpread} places between the highest and lowest read on ${focusName}. ` +
                        focusRows
                          .filter((f) => f.entry)
                          .map((f) => `${KIND_LABEL[f.kind].toLowerCase()} ${ordinal(f.entry!.rank)}`)
                          .join(', ') +
                        '.'}
                  </p>

                  <div className="pr-focus-list">
                    <div className="pr-focus-row head">
                      <span>Mode</span>
                      <span className="pr-score-head">Rank</span>
                      <span>Placement, 1st → {gridRows}th</span>
                      <span className="pr-score-head">Score</span>
                    </div>
                    {focusRows.map((f) => (
                      <div className="pr-focus-row" key={f.kind}>
                        <span className="pr-focus-mode">
                          <span className="pr-dot" style={{ background: `oklch(70% 0.14 ${KIND_HUE[f.kind]})` }} />
                          {KIND_LABEL[f.kind]}
                        </span>
                        <span className="pr-score mono">{f.entry ? f.entry.rank : '--'}</span>
                        <span className="pr-axis">
                          <span className="pr-axis-line" />
                          {f.entry && (
                            <span
                              className="pr-axis-dot"
                              style={{
                                left: `${((f.entry.rank - 1) / Math.max(1, gridRows - 1)) * 100}%`,
                                background: `oklch(70% 0.14 ${KIND_HUE[f.kind]})`,
                              }}
                              title={`${KIND_LABEL[f.kind]} · ${f.week == null ? 'no week' : weekPhrase(f.week)} · rank ${f.entry.rank}`}
                            />
                          )}
                        </span>
                        <span className="pr-score mono">
                          {f.entry?.score == null ? 'no score' : f.week === 0 ? Math.round(f.entry.score) : f.entry.score.toFixed(2)}
                        </span>
                      </div>
                    ))}
                    <div className="pr-axis-ends mono">
                      <span>1st</span>
                      <span>{gridRows}th</span>
                    </div>
                  </div>

                  {teamViewWeeks.length >= 2 && (
                    <>
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
                </>
              )}
            </section>
          )}

          <section className="panel">
            <div className="panel-head">
              <h2>Trend</h2>
              <span className="small muted">
                {chartReady
                  ? `${KIND_LABEL[mode].toLowerCase()}, ${weekRangeLabel(weeks)}`
                  : weeks.length === 1
                    ? 'One week of data. The week-over-week bump chart appears once week 2 is computed.'
                    : 'No weeks yet for this mode.'}
              </span>
              <span className="mono tiny muted pr-trend-count">
                wk {weeks.length} / {totalWeeks + 1}
              </span>
            </div>

            {chartReady ? (
              <>
                <div className="bump-legend">
                  {series.map((s) => {
                    const isHighlighted = highlighted === s.rosterId
                    return (
                      <button
                        key={s.rosterId}
                        className={`bump-legend-item bump-legend-button${isHighlighted ? ' on' : ''}`}
                        onClick={() => setHighlighted(isHighlighted ? null : s.rosterId)}
                      >
                        <span className="bump-legend-swatch" style={{ background: `oklch(70% 0.14 ${s.hue})` }} />
                        {s.manager ?? `roster ${s.rosterId}`}
                      </button>
                    )
                  })}
                </div>
                <BumpChart series={series} weeks={weeks} teamCount={gridRows} highlighted={highlighted} onHighlight={setHighlighted} />
              </>
            ) : (
              <div className="pr-ticks" aria-hidden="true">
                {/* Starts at 0, not 1 -- the preseason baseline is a real tick
                    this strip must be able to light up, not just weeks 1..N. */}
                {Array.from({ length: totalWeeks + 1 }, (_, i) => i).map((w) => (
                  <span key={w} className={`pr-tick${weeks.includes(w) ? ' on' : ''}`} />
                ))}
              </div>
            )}
          </section>

          {mode === 'MEMBER' && homers.length > 0 && (
            <section className="panel">
              <div className="panel-head">
                <h2>Homers</h2>
                <span className="small muted">self vs room</span>
              </div>
              <p className="muted small">
                Where a manager put their own team, against where the room put it. Negative means they rank themselves
                higher than everyone else does. With this many ballots your own vote moves your own average by a
                fraction of a rank, so this is commentary, not a correction being applied.
              </p>
              <div className="pr-homers">
                {homers.map((e) => {
                  const bias = e.selfRankBias!
                  const pct = Math.min(50, Math.abs(bias) * 6)
                  return (
                    <div className="pr-homer" key={e.rosterId}>
                      <span className="pr-homer-name">{e.manager ?? `roster ${e.rosterId}`}</span>
                      <span className="pr-homer-track">
                        <span className="pr-homer-mid" />
                        <span
                          className={`pr-homer-fill${bias < 0 ? ' down' : ' up'}`}
                          style={bias < 0 ? { right: '50%', width: `${pct}%` } : { left: '50%', width: `${Math.max(1, pct)}%` }}
                        />
                      </span>
                      <span className="pr-homer-value mono">{bias > 0 ? `+${bias}` : bias}</span>
                    </div>
                  )
                })}
              </div>
            </section>
          )}
        </div>

        {hasSide && (
          <aside className="pr-side">
            {mode === 'COMMISSIONER' && ballot && (
              <section className="panel">
                <div className="panel-head">
                  <h2>Week {currentWeek}'s commissioner ranking</h2>
                </div>
                {ballot.canCommission ? (
                  <>
                    <p className="muted small">Your own ordering. An opinion, signed -- not a measurement.</p>
                    <RankBoard
                      key={`commissioner-${currentWeek}-${ballot.members.length}`}
                      members={boardMembers}
                      ariaLabel="Commissioner ranking"
                      initialOrder={commissionerSeed.order}
                      onSubmit={saveCommissioner}
                      submitLabel="Save ranking"
                      submitting={saving}
                    />
                    {commissionerSeed.seededFrom && (
                      <p className="tiny muted">
                        Starting order: {commissionerSeed.seededFrom}. Drag to disagree -- nothing is saved until you submit.
                      </p>
                    )}
                    {saveMessage && <p className="small muted">{saveMessage}</p>}
                  </>
                ) : (
                  <p className="muted small">
                    {ballot.commissionerKnown
                      ? "Only this league's commissioner can set this ranking."
                      : 'No commissioner detected for this league -- re-run league ingest to pick one up from Sleeper.'}
                  </p>
                )}
              </section>
            )}

            {mode === 'MEMBER' && ballot && (
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
                      // The signed-in user's own id is part of this key, not just
                      // week + submittedAt -- two different users who both haven't
                      // submitted yet otherwise produce the identical key, so
                      // switching accounts wouldn't remount the board even once
                      // `ballot` itself was correctly refetched for the new
                      // identity (RankBoard seeds its order once, on mount).
                      key={`ballot-${currentWeek}-${user?.sleeperUserId ?? 'anon'}-${ballot.mine?.submittedAt ?? 'new'}`}
                      members={boardMembers}
                      ariaLabel="Your ballot"
                      initialOrder={ballotSeed.order}
                      onSubmit={sendBallot}
                      submitLabel={ballot.mine ? 'Resubmit ballot' : 'Submit ballot'}
                      submitting={saving}
                    />
                    {ballotSeed.seededFrom && (
                      <p className="tiny muted">
                        Starting order: {ballotSeed.seededFrom}. Drag to disagree -- nothing is saved until you submit.
                      </p>
                    )}
                    <p className="tiny muted">
                      Drag a chip, or click one and use ↑ / ↓ to move it. Escape drops the selection.
                    </p>
                    {saveMessage && <p className="small muted">{saveMessage}</p>}
                  </>
                ) : (
                  <p className="muted small">
                    Sign in as a member of this league to submit a ballot for the current week.
                  </p>
                )}
              </section>
            )}
          </aside>
        )}
      </div>

      {realizedFor != null &&
        (() => {
          const rosterId = realizedFor
          const history = data.entries
            .filter((r) => r.kind === 'COMPUTED_REALIZED' && r.season === season && r.rosterId === rosterId)
            .sort((a, b) => b.week - a.week)
          const latest = history[0]
          const name = (rows.find((r) => r.rosterId === rosterId) ?? history[0])?.manager ?? `roster ${rosterId}`
          const managerId = (rows.find((r) => r.rosterId === rosterId) ?? history[0])?.managerId ?? null
          return (
            <div className="modal-backdrop" onClick={() => setRealizedFor(null)}>
              <div className="modal-card" onClick={(ev) => ev.stopPropagation()}>
                <button className="modal-close" onClick={() => setRealizedFor(null)} aria-label="Close">
                  ✕
                </button>
                <div className="panel-head">
                  <span className="avatar" style={avatarStyleFor(managerId, rosterId, false)} aria-hidden="true">
                    {name.charAt(0).toUpperCase()}
                  </span>
                  <h2>{name} -- Realized</h2>
                </div>
                {latest ? (
                  <>
                    <p className="small">
                      {ordinal(latest.rank)} of {gridRows}, {weekPhrase(latest.week)} ({scoreLabel(latest)}
                      {latest.week === 0 ? ' value' : ' pts/wk'}).
                    </p>
                    {history.length > 1 && (
                      <p className="tiny muted">
                        Earlier: {history.slice(1, 5).map((h) => `${weekShort(h.week)} ${ordinal(h.rank)}`).join(', ')}
                      </p>
                    )}
                  </>
                ) : (
                  <p className="muted small">No Realized snapshot yet for this team.</p>
                )}
                <p className="tiny muted">{KIND_CAVEAT.COMPUTED_REALIZED}</p>
              </div>
            </div>
          )
        })()}
    </div>
  )
}
