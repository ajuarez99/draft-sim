import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  ALL_POWER_RANKING_KINDS,
  computePowerRankings,
  getBallot,
  getLeagueHistory,
  getPowerRankings,
  saveCommissionerRanking,
  submitBallot,
  type BallotState,
  type PowerRankingEntry,
  type PowerRankingKind,
  type PowerRankings as PowerRankingsData,
  type StandingRow,
} from '../api'
import RankBoard, { type RankBoardMember } from '../components/RankBoard'
import { hueFor } from '../hue'
import { useUser } from '../user'

/**
 * power-rankings-reskin.md. This used to be a page that explained its own
 * three modes to whoever landed on it (mode tabs, a caveat paragraph, "vs
 * room" column headers). It now reads as a fantasy page for league members --
 * a headline, a #1 card, a ladder -- with the three modes demoted to a
 * segmented control inside the ladder panel, and the model's own vocabulary
 * (Realized, stdev, selfRankBias, the four-sentence caveats) pushed behind a
 * "How this works" disclosure.
 *
 * Two deliberate gaps vs. the approved mockups, both because the brief they
 * shipped with is explicit that this is a presentation-only pass:
 *  - `PowerRankingEntry.makesPlayoffsPct` is real API surface but always
 *    undefined today -- there is no playoff-odds simulation anywhere in this
 *    codebase yet. Every render site below degrades to "--" rather than
 *    fabricating a number; wiring the backend is a separate piece of work.
 *  - "Commissioner's take" does not reproduce the mockup's free-text quote
 *    ("Kittle Caesars at 4th is a gift...") -- there is no field anywhere
 *    that carries commissioner commentary about one team, only a signed
 *    ORDERING. The card instead states a fact the ordering actually contains
 *    (who the commissioner has at #1) and links to the full order.
 */

const KIND_LABEL: Record<PowerRankingKind, string> = {
  COMPUTED_REALIZED: 'Box score',
  COMMISSIONER: 'Commissioner',
  MEMBER: 'League vote',
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
    'Box score: cumulative average starting-lineup points per week, from games already played. A hot start survives an injury it should not. Preseason is the exception -- a one-time baseline (this app\'s board value on each roster\'s best starting lineup, at first compute), not a played week.',
  COMMISSIONER: "The commissioner's own ordering. An opinion, signed -- not a measurement.",
  MEMBER:
    'League vote: every manager who submitted a ballot this week, averaged. Managers rank their own team too, and the bias that produces is shown below rather than quietly removed.',
}

/** The mode each mode's movement column is measured against. Week 1 has no
 *  prior week, so a "movement" column can only honestly compare modes -- and
 *  the disclosure below says which. Unchanged from the pre-reskin page --
 *  only the on-screen header text ("Since wk N") changed, not this mapping. */
const REFERENCE_KIND: Record<PowerRankingKind, PowerRankingKind> = {
  MEMBER: 'COMPUTED_REALIZED',
  COMPUTED_REALIZED: 'MEMBER',
  COMMISSIONER: 'MEMBER',
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
          <g key={`rank-${rank}`}>
            <line x1={marginLeft} y1={yOf(rank)} x2={width - marginRight} y2={yOf(rank)} className="bump-gridline" />
            <text x={marginLeft - 10} y={yOf(rank)} className="bump-rank-label mono" textAnchor="end" dominantBaseline="middle">
              {rank}
            </text>
          </g>
        ))}
        {weeks.map((w) => (
          <text key={`week-${w}`} x={xOf(w)} y={height - marginBottom + 18} className="bump-week-label mono" textAnchor="middle">
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
                    key={`seg-${i}`}
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
                    key={`seg-${i}`}
                    points={seg.points.map((p) => `${xOf(p.week)},${yOf(p.rank)}`).join(' ')}
                    fill="none"
                    stroke={`oklch(70% 0.14 ${hue})`}
                    strokeWidth={isHighlighted ? 3 : 1.75}
                    strokeDasharray={seg.thin ? '4 3' : undefined}
                  />
                ),
              )}
              {s.points.map((p) => (
                <circle key={`pt-${p.week}`} cx={xOf(p.week)} cy={yOf(p.rank)} r={isHighlighted ? 4 : 3} fill={`oklch(70% 0.14 ${hue})`}>
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

function avatarStyleFor(managerId: number | null, rosterId: number, isMe: boolean) {
  if (isMe) return { background: 'var(--crimson)', color: 'var(--bg)' }
  const hue = hueFor(String(managerId ?? rosterId))
  return { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
}

export const ordinal = (n: number) => `${n}${n === 1 ? 'st' : n === 2 ? 'nd' : n === 3 ? 'rd' : 'th'}`

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

/** `${wins}-${losses}` (or `-ties` when there are any). `--` when the roster
 *  has no standings row at all (an unowned/co-owned roster). */
export function recordLabel(r: StandingRow | undefined | null): string {
  if (!r || r.wins == null || r.losses == null) return '--'
  return r.ties ? `${r.wins}-${r.losses}-${r.ties}` : `${r.wins}-${r.losses}`
}

/** `makesPlayoffsPct` is real API surface with no backend behind it yet --
 *  see the file header. Every call site goes through this so "no data" and
 *  "0%" can never be confused. */
export function pctLabel(pct: number | null | undefined): string {
  return pct == null ? '--' : `${Math.round(pct)}%`
}

/** The ladder's rightmost "note" column, one sentence, mode-dependent. MEMBER
 *  gets a real sentence built from bestRank/worstRank (regression: these
 *  fields must keep surfacing somewhere); the mockup's literal copy
 *  ("1st on 9 of 11 ballots") needs a per-rank ballot tally this API doesn't
 *  expose, so this is the honest version of the same idea. */
export function roomTakeSentence(e: PowerRankingEntry, teamCount: number): string {
  const best = e.bestRank
  const worst = e.worstRank
  if (best == null || worst == null) {
    return e.ballotCount ? `${e.ballotCount} ballot${e.ballotCount === 1 ? '' : 's'} counted` : 'Not yet ranked'
  }
  if (best === worst) return `Ranked ${ordinal(best)} on every ballot`
  if (worst - best <= 1) return `${ordinal(best)} or ${ordinal(worst)} on every ballot`
  if (worst - best >= Math.ceil(teamCount / 2)) return `${ordinal(best)} to ${ordinal(worst)} — no consensus`
  return `Ranked ${ordinal(best)} to ${ordinal(worst)}, ${e.ballotCount ?? 0} ballots`
}

function ladderNoteFor(mode: PowerRankingKind, e: PowerRankingEntry, teamCount: number): string {
  if (mode === 'MEMBER') return roomTakeSentence(e, teamCount)
  if (mode === 'COMMISSIONER') return `Signed, ${weekShort(e.week)}`
  return `As of ${weekShort(e.week)}`
}

// --- headline / deck / story cards (§5) -------------------------------------
//
// Deterministic, not an LLM call: exactly the rule order in
// power-rankings-reskin.md §5, and every sentence is built only from numbers
// already on screen (rank deltas, stdev) -- never invented.

export type DeltaEntry = { entry: PowerRankingEntry; delta: number }
export type WeeklyStory = {
  top: PowerRankingEntry | null
  newTop: boolean
  riser: DeltaEntry | null
  faller: DeltaEntry | null
  divisive: PowerRankingEntry | null
}

/** `currentRows`/`previousRows` are one mode's entries for one week, sorted by
 *  rank ascending (previousRows is the same mode's most recent earlier week,
 *  or null if none exists yet). */
export function computeWeeklyStory(currentRows: PowerRankingEntry[], previousRows: PowerRankingEntry[] | null): WeeklyStory {
  const prevRank = new Map((previousRows ?? []).map((e) => [e.rosterId, e.rank]))
  let riser: DeltaEntry | null = null
  let faller: DeltaEntry | null = null
  for (const e of currentRows) {
    const pr = prevRank.get(e.rosterId)
    if (pr == null) continue
    const delta = pr - e.rank
    if (delta > 0 && (!riser || delta > riser.delta)) riser = { entry: e, delta }
    if (delta < 0 && (!faller || delta < faller.delta)) faller = { entry: e, delta }
  }
  let divisive: PowerRankingEntry | null = null
  for (const e of currentRows) {
    if (e.stdev != null && e.stdev > 0 && (!divisive || e.stdev > (divisive.stdev ?? 0))) divisive = e
  }
  const top = currentRows[0] ?? null
  const prevTop = previousRows?.[0] ?? null
  return { top, newTop: !!(top && prevTop && top.rosterId !== prevTop.rosterId), riser, faller, divisive }
}

const nameOf = (e: PowerRankingEntry) => e.manager ?? `roster ${e.rosterId}`
const weekTitle = (week: number) => (week === 0 ? 'Preseason' : `Week ${week}`)

export function buildHeadline(story: WeeklyStory, week: number): string {
  if (!story.top) return `${weekTitle(week)} power rankings`
  if (story.newTop) return `${nameOf(story.top)} is your new No. 1`
  if (story.riser && story.riser.delta >= 3) {
    return `${nameOf(story.riser.entry)} jumps ${story.riser.delta} spots to ${ordinal(story.riser.entry.rank)}`
  }
  if (story.faller && story.faller.delta <= -3) {
    return `${nameOf(story.faller.entry)} falls ${Math.abs(story.faller.delta)} spots to ${ordinal(story.faller.entry.rank)}`
  }
  if (story.divisive) return `Nobody agrees on ${nameOf(story.divisive)}`
  return `${weekTitle(week)} power rankings`
}

export function buildDeck(story: WeeklyStory, ballotCount: number, memberCount: number, week: number): string {
  const parts: string[] = []
  parts.push(`${ballotCount} of ${memberCount} ballot${memberCount === 1 ? '' : 's'} in for ${weekPhrase(week)}.`)
  const namedId = story.newTop
    ? story.top?.rosterId
    : story.riser && story.riser.delta >= 3
      ? story.riser.entry.rosterId
      : story.faller && story.faller.delta <= -3
        ? story.faller.entry.rosterId
        : story.divisive?.rosterId
  if (story.riser && story.riser.entry.rosterId !== namedId) {
    parts.push(`${nameOf(story.riser.entry)} climbed ${story.riser.delta} spot${story.riser.delta === 1 ? '' : 's'}.`)
  } else if (story.faller && story.faller.entry.rosterId !== namedId) {
    const n = Math.abs(story.faller.delta)
    parts.push(`${nameOf(story.faller.entry)} dropped ${n} spot${n === 1 ? '' : 's'}.`)
  } else if (story.divisive && story.divisive.rosterId !== namedId) {
    parts.push(
      `${nameOf(story.divisive)} splits the room, ${ordinal(story.divisive.bestRank ?? story.divisive.rank)} to ${ordinal(story.divisive.worstRank ?? story.divisive.rank)}.`,
    )
  }
  return parts.join(' ')
}

// --- "can this viewer rank" (§7 blocked states, 2d) -------------------------

export type BallotBlockState = 'loading' | 'signed-out' | 'not-member' | 'voting-closed' | 'load-error' | 'ok'

/** Every state the ballot sidebar/modal can be in. `ballotFailed` is the
 *  fetch's own catch (the pre-reskin page discarded this into a bare `null`,
 *  same as "haven't loaded yet" -- 2d's "Couldn't load" state needs the two
 *  told apart). `signedIn` is `!!useUser()`; the global route table already
 *  gates every page behind sign-in (App.tsx), so 'signed-out' cannot actually
 *  fire from this page in production today -- it's kept for the verify
 *  harness and for if that gate ever moves. */
export function ballotBlockState(signedIn: boolean, ballot: BallotState | null, ballotFailed: boolean): BallotBlockState {
  if (!signedIn) return 'signed-out'
  if (ballotFailed) return 'load-error'
  if (!ballot) return 'loading'
  const amMember = ballot.members.some((m) => m.isMe)
  if (!amMember) return 'not-member'
  if (!ballot.canSubmit) return 'voting-closed'
  return 'ok'
}

export default function PowerRankings() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const user = useUser()
  const [data, setData] = useState<PowerRankingsData | null>(null)
  const [ballot, setBallot] = useState<BallotState | null>(null)
  const [ballotFailed, setBallotFailed] = useState(false)
  const [standings, setStandings] = useState<Map<number, StandingRow> | null>(null)
  const [leagueName, setLeagueName] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [ladderMode, setLadderMode] = useState<PowerRankingKind>('MEMBER')
  const [highlighted, setHighlighted] = useState<number | null>(null)
  const [compareOpen, setCompareOpen] = useState(false)
  const [trendOpen, setTrendOpen] = useState(false)
  const [homersOpen, setHomersOpen] = useState(false)
  const [howOpen, setHowOpen] = useState(false)
  const [ballotModalOpen, setBallotModalOpen] = useState(false)
  const [computing, setComputing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<string | null>(null)

  function refetch() {
    if (!sleeperLeagueId) return
    getPowerRankings(sleeperLeagueId)
      .then((d) => {
        setData(d)
        setError(null)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
    getBallot(sleeperLeagueId)
      .then((b) => {
        setBallot(b)
        setBallotFailed(false)
      })
      // Cleared, not left stale -- a failure here (signed out, not a member of
      // this league, a 403 for the newly-switched-to identity) must not leave
      // the PREVIOUS identity's ballot on screen. Editors only; the page works
      // without it.
      .catch(() => {
        setBallot(null)
        setBallotFailed(true)
      })
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

  // Record + league name: read-only, from the standings endpoint every other
  // league-scoped page already calls (LeagueHistory.tsx) -- not a new API
  // surface, just a second fetch this page didn't make before.
  useEffect(() => {
    if (!sleeperLeagueId) return
    getLeagueHistory(sleeperLeagueId)
      .then((h) => {
        const s = h.seasons.find((s) => s.season === season) ?? h.seasons[0] ?? null
        setLeagueName(s?.name ?? null)
        setStandings(s ? new Map(s.standings.map((r) => [r.rosterId, r])) : null)
      })
      .catch(() => {
        setLeagueName(null)
        setStandings(null)
      })
  }, [sleeperLeagueId, season])

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
      setBallotModalOpen(false)
    } catch (e) {
      setSaveMessage(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  if (!data) {
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
  const gridRows = teamCount || 1

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

  /** Entries for one mode, this season, one week -- the shape every panel
   *  below is built from. */
  const entriesFor = (kind: PowerRankingKind, week: number) =>
    data.entries.filter((e) => e.kind === kind && e.season === season && e.week === week).sort((a, b) => a.rank - b.rank)

  const weeksOf = (kind: PowerRankingKind) =>
    [...new Set(data.entries.filter((e) => e.kind === kind && e.season === season).map((e) => e.week))].sort((a, b) => a - b)

  /** The roster-id order of the LATEST week a mode has anything for -- no new
   *  request, just re-slicing entries the page already fetched. Used only as a
   *  seed fallback below, never rendered as if it were this mode's own table. */
  const rankedBy = (kind: PowerRankingKind): number[] => {
    const latest = weeksOf(kind).pop()
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
    { label: 'the box score', order: rankedBy('COMPUTED_REALIZED') },
    { label: 'the league vote', order: rankedBy('MEMBER') },
  ])
  const ballotSeed = seedOrder(ballot?.mine?.rosterIds, [{ label: 'the box score', order: rankedBy('COMPUTED_REALIZED') }])

  // ---- the ladder (whichever mode is selected) ----
  const ladderWeeks = weeksOf(ladderMode)
  const tableWeek = ladderWeeks.length > 0 ? ladderWeeks[ladderWeeks.length - 1] : currentWeek
  const rows = entriesFor(ladderMode, tableWeek)

  const referenceKind = REFERENCE_KIND[ladderMode]
  const referenceRows = entriesFor(referenceKind, tableWeek)
  const referenceRank = new Map(referenceRows.map((e) => [e.rosterId, e.rank]))
  const hasReference = referenceRows.length > 0
  const sinceLabel = tableWeek > 0 ? `Since ${weekShort(tableWeek - 1)}` : 'Since —'

  // ---- the hero (headline / #1 / your-team strip / story cards) is always
  // League vote, independent of which ladder tab is selected -- it is "state
  // of the league", not "state of the current tab" (2a/2c both keep the
  // headline fixed while only the ladder switches). ----
  const memberWeeks = weeksOf('MEMBER')
  const heroWeek = memberWeeks.length > 0 ? memberWeeks[memberWeeks.length - 1] : currentWeek
  const heroRows = entriesFor('MEMBER', heroWeek)
  const heroPrevWeek = [...memberWeeks].reverse().find((w) => w < heroWeek) ?? null
  const heroPrevRows = heroPrevWeek == null ? null : entriesFor('MEMBER', heroPrevWeek)
  const story = computeWeeklyStory(heroRows, heroPrevRows)
  const headline = buildHeadline(story, heroWeek)
  const deck = buildDeck(story, ballot?.ballotCount ?? 0, ballot?.memberCount ?? teamCount, heroWeek)

  const heroReferenceRows = entriesFor(REFERENCE_KIND.MEMBER, heroWeek)
  const heroReferenceRank = new Map(heroReferenceRows.map((e) => [e.rosterId, e.rank]))
  const deltaVs = (e: PowerRankingEntry, refRank: Map<number, number>) => {
    const r = refRank.get(e.rosterId)
    return r == null ? null : r - e.rank
  }

  const myEntry = heroRows.find((e) => ballot?.members.some((m) => m.isMe && m.rosterId === e.rosterId))
  const myBallotRank = ballot?.mine ? ballot.mine.rosterIds.indexOf(myEntry?.rosterId ?? -1) : -1

  const memberEntries = entriesFor('MEMBER', currentWeek)
  const homers = memberEntries.filter((e) => e.selfRankBias != null).sort((a, b) => a.selfRankBias! - b.selfRankBias!)
  const topHomer = homers[0] ?? null

  const commissionerNowRows = entriesFor('COMMISSIONER', currentWeek)
  const commissionerTop = commissionerNowRows[0] ?? null

  // ---- ballot / commissioner editing (2d), unified behind one modal ----
  const signedIn = !!user
  const blockState = ballotBlockState(signedIn, ballot, ballotFailed)
  // "Rank the league" edits whichever board the currently-selected tab
  // implies: the commissioner's own ordering when Commissioner is selected
  // and this viewer can commission, the member ballot otherwise. Not shown in
  // the mockups (2d only covers the member ballot) -- see the handoff note.
  const editingCommissioner = ladderMode === 'COMMISSIONER' && !!ballot?.canCommission

  // ---- per-team compare (regression #9; no slot in the mockups, so it's a
  // disclosure a pinned row reveals rather than a page-level view toggle) ----
  const teamViewOptions = [...new Map(data.entries.map((e) => [e.rosterId, e.manager ?? `roster ${e.rosterId}`])).entries()]
  const teamViewRosterId = highlighted
  const teamSeries: Series[] =
    compareOpen && teamViewRosterId != null
      ? ALL_POWER_RANKING_KINDS.map((k) => {
          const s = buildSeries(data.entries, k, season, teamCount).find((s) => s.rosterId === teamViewRosterId)
          return s && { ...s, hue: KIND_HUE[k] }
        }).filter((s): s is Series => !!s)
      : []
  const teamViewWeeks =
    teamViewRosterId == null
      ? []
      : [...new Set(data.entries.filter((e) => e.rosterId === teamViewRosterId && e.season === season).map((e) => e.week))].sort(
          (a, b) => a - b,
        )
  const focusRows =
    teamViewRosterId == null
      ? []
      : ALL_POWER_RANKING_KINDS.map((k) => {
          const week = weeksOf(k).pop()
          const entry = week == null ? undefined : entriesFor(k, week).find((e) => e.rosterId === teamViewRosterId)
          return { kind: k, entry, week }
        })
  const focusRanks = focusRows.map((f) => f.entry?.rank).filter((r): r is number => r != null)
  const focusSpread = focusRanks.length > 1 ? Math.max(...focusRanks) - Math.min(...focusRanks) : 0
  const focusName = teamViewOptions.find(([id]) => id === teamViewRosterId)?.[1] ?? 'This team'

  // Trend chart is a line chart: below two weeks there is no line to draw.
  const chartReady = ladderWeeks.length >= 2

  function scoreLabel(e: PowerRankingEntry): string {
    if (e.score == null) return '--'
    if (e.week === 0) return String(Math.round(e.score))
    return e.score.toFixed(2)
  }

  function openBallotModal() {
    setSaveMessage(null)
    setBallotModalOpen(true)
  }

  return (
    <div className="content scrolls pr-page">
      {error && <div className="error">{error}</div>}

      {/* ---- eyebrow ---- */}
      <div className="pr-eyebrow-row">
        <span className="pr-eyebrow">
          {weekTitle(heroWeek)} · {leagueName ?? 'League'} · {teamCount} teams
        </span>
        {sleeperLeagueId && (
          <Link className="chip" to={`/leagues/${sleeperLeagueId}/history`}>
            ← League history
          </Link>
        )}
      </div>

      {/* ---- hero: headline + deck + #1 card ---- */}
      <div className="pr-hero">
        <div className="pr-hero-copy">
          <h1 className="pr-headline cond">{headline}</h1>
          <p className="pr-deck">{deck}</p>
          {ballot && (
            <div className="pr-hero-pills">
              <span className="chip on">
                {ballot.ballotCount} of {ballot.memberCount} ballots in
              </span>
            </div>
          )}
        </div>

        {story.top && (
          <div className="pr-number-one panel">
            <div className="pr-number-one-label cond">Number one</div>
            <div className="pr-number-one-body">
              <div className="pr-number-one-rank cond mono">1</div>
              <div className="pr-number-one-id">
                <div className="pr-number-one-name">{nameOf(story.top)}</div>
                <div className="pr-number-one-meta">
                  {story.top.manager ? `${story.top.manager} · ` : ''}
                  <span className="mono">{recordLabel(standings?.get(story.top.rosterId))}</span> ·{' '}
                  <span className="mono">{scoreLabel(story.top)}</span> pts
                </div>
              </div>
            </div>
            <div className="pr-number-one-rule" />
            <div className="pr-number-one-stats">
              <div>
                <div className="pr-stat-label cond">This week</div>
                {(() => {
                  const d = deltaVs(story.top, heroReferenceRank)
                  return (
                    <div className={`mono pr-stat-value pr-move ${d == null ? 'flat' : d > 0 ? 'up' : d < 0 ? 'down' : 'flat'}`}>
                      {d == null || d === 0 ? '–' : `${d > 0 ? '▲' : '▼'} ${Math.abs(d)}`}
                    </div>
                  )
                })()}
              </div>
              <div>
                <div className="pr-stat-label cond">Makes playoffs</div>
                <div className="mono pr-stat-value">{pctLabel(story.top.makesPlayoffsPct)}</div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* ---- your team strip ---- */}
      {myEntry && (
        <div className="pr-your-team">
          <span className="pr-your-team-label cond">Your team</span>
          <span className="pr-your-team-name">{myEntry.manager ?? `roster ${myEntry.rosterId}`}</span>
          <span className="pr-your-team-detail">
            {ordinal(myEntry.rank)} of {gridRows}
            {(() => {
              const d = deltaVs(myEntry, heroReferenceRank)
              return d != null && d !== 0 ? ` · the room moved you ${d > 0 ? 'up' : 'down'} ${Math.abs(d)}` : ''
            })()}{' '}
            · <span className="mono">{pctLabel(myEntry.makesPlayoffsPct)}</span> to make it
          </span>
          {myBallotRank != null && myBallotRank >= 0 && (
            <span className="pr-your-team-vote">You voted yourself {ordinal(myBallotRank + 1)}.</span>
          )}
        </div>
      )}

      {/* ---- story cards ---- */}
      {(story.riser || story.faller || story.divisive) && (
        <div className="pr-stories">
          {story.riser && (
            <div className="pr-story panel">
              <h2 className="cond">Riser of the week</h2>
              <div className="pr-story-name">
                {nameOf(story.riser.entry)} <span className="mono pr-move up">▲{story.riser.delta}</span>
              </div>
              <div className="pr-story-note">Now {ordinal(story.riser.entry.rank)}, from {ordinal(story.riser.entry.rank + story.riser.delta)}.</div>
            </div>
          )}
          {story.faller && (
            <div className="pr-story panel">
              <h2 className="cond">Free fall</h2>
              <div className="pr-story-name">
                {nameOf(story.faller.entry)} <span className="mono pr-move down">▼{Math.abs(story.faller.delta)}</span>
              </div>
              <div className="pr-story-note">
                Now {ordinal(story.faller.entry.rank)}, from {ordinal(story.faller.entry.rank + story.faller.delta)}.
              </div>
            </div>
          )}
          {story.divisive && (
            <div className="pr-story panel">
              <h2 className="cond">Nobody agrees</h2>
              <div className="pr-story-name">{nameOf(story.divisive)}</div>
              <div className="pr-story-note">
                Ranked as high as {ordinal(story.divisive.bestRank ?? story.divisive.rank)} and as low as{' '}
                {ordinal(story.divisive.worstRank ?? story.divisive.rank)}.
              </div>
            </div>
          )}
        </div>
      )}

      <div className="pr-grid">
        <div className="pr-main">
          {/* ---- the ladder ---- */}
          <section className="panel">
            <div className="pr-ladder-head panel-head">
              <h2>The ladder</h2>
              <span className="small muted">
                {KIND_LABEL[ladderMode]} · {weekPhrase(tableWeek)}
                {ladderMode === 'MEMBER' && ballot ? ` · ${ballot.ballotCount} ballots` : ''}
              </span>
              <div className="segmented sm pr-ladder-modes" role="group" aria-label="Ranking mode">
                {ALL_POWER_RANKING_KINDS.map((k) => (
                  <button
                    key={k}
                    type="button"
                    className={`segment${ladderMode === k ? ' on' : ''}`}
                    aria-pressed={ladderMode === k}
                    onClick={() => setLadderMode(k)}
                  >
                    {KIND_LABEL[k]}
                  </button>
                ))}
              </div>
              <button type="button" className="action-button" onClick={openBallotModal}>
                Rank the league
              </button>
              {ballot?.canCommission && (
                <button
                  type="button"
                  className="link-button pr-compute-trigger"
                  onClick={compute}
                  disabled={computing}
                  title="Recompute box-score rankings for the current week (and the preseason baseline, the first time) and save them"
                >
                  {computing ? 'Computing…' : `Recompute week ${currentWeek} (commissioner)`}
                </button>
              )}
            </div>

            {rows.length === 0 ? (
              <p className="muted">
                No {KIND_LABEL[ladderMode].toLowerCase()} snapshots yet for this league.{' '}
                {ladderMode === 'COMPUTED_REALIZED'
                  ? ballot?.canCommission
                    ? `Use "Recompute week ${currentWeek}" above to build the first one.`
                    : 'Ask the commissioner to run the first box-score snapshot.'
                  : ladderMode === 'MEMBER'
                    ? 'Ballots build this one -- submit yours above, and the room fills in as others do.'
                    : ''}
              </p>
            ) : (
              <div className={`pr-list${hasReference ? '' : ' no-reference'}`}>
                <div className="pr-row-head">
                  <span>#</span>
                  <span>Team</span>
                  <span>Record</span>
                  {hasReference && <span className="pr-move-head">{sinceLabel}</span>}
                  <span className="pr-score-head">Makes playoffs</span>
                  <span>Where the room had them</span>
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
                  const pct = e.makesPlayoffsPct
                  return (
                    <button
                      type="button"
                      key={e.rosterId}
                      className={`pr-row${pinned ? ' on' : ''}${isMe ? ' mine' : ''}`}
                      aria-pressed={pinned}
                      onClick={() => {
                        setHighlighted(pinned ? null : e.rosterId)
                        if (pinned) setCompareOpen(false)
                      }}
                    >
                      <span className={`pr-rank mono${isMe ? ' mine' : e.rank <= 3 ? ' top' : ''}`}>{e.rank}</span>
                      <span className="pr-team">
                        <span className="avatar" style={avatarStyleFor(e.managerId, e.rosterId, isMe)} aria-hidden="true">
                          {(e.manager ?? `R${e.rosterId}`).charAt(0).toUpperCase()}
                        </span>
                        <span className="pr-team-text">
                          <span className="pr-team-name">
                            {e.manager ?? `roster ${e.rosterId}`}
                            {isMe && <span className="cond pr-you-tag">You</span>}
                          </span>
                          <span className="pr-team-sub">
                            {member?.teamName && member.teamName.toUpperCase() !== 'TBD' ? member.teamName : e.manager ?? ''}
                          </span>
                        </span>
                      </span>
                      <span className="mono pr-record">{recordLabel(standings?.get(e.rosterId))}</span>
                      {hasReference && (
                        <span className={`pr-move mono ${delta == null ? 'flat' : delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat'}`}>
                          {delta == null ? '–' : delta === 0 ? '–' : `${delta > 0 ? '▲' : '▼'}${Math.abs(delta)}`}
                        </span>
                      )}
                      <span className="pr-playoff-cell">
                        <span className="pr-bar">
                          <span className={`pr-bar-fill${isMe ? ' mine' : ''}`} style={{ width: pct == null ? '0%' : `${pct}%` }} />
                        </span>
                        <span className="mono pr-playoff-pct">{pctLabel(pct)}</span>
                      </span>
                      <span className={`pr-note${ladderMode === 'MEMBER' && e.stdev != null && e.stdev > 1.5 ? ' notable' : ''}`}>
                        {ladderNoteFor(ladderMode, e, gridRows)}
                      </span>
                    </button>
                  )
                })}

                {ladderMode === 'MEMBER' && (
                  <p className="pr-foot">
                    A team ranked by fewer than half this week's ballots is placed after every team that cleared that bar,
                    rather than winning the week on one enthusiastic vote.
                  </p>
                )}
                <p className="pr-foot">
                  Ranks are manager ballots, averaged. Playoff odds come from simulating the rest of the season.{' '}
                  <button type="button" className="link-button" onClick={() => setHowOpen((v) => !v)}>
                    How this works →
                  </button>
                </p>
                {howOpen && (
                  <div className="pr-how">
                    <p className="tiny muted">{KIND_CAVEAT[ladderMode]}</p>
                    <p className="tiny muted">
                      Movement compares this mode against {KIND_LABEL[referenceKind]} for the same week, when that mode has a
                      snapshot for it -- otherwise the column is hidden rather than showing a fake zero. "Makes playoffs" is not
                      wired to a simulation yet; every team shows "--" until it is.
                    </p>
                  </div>
                )}
              </div>
            )}
          </section>

          {/* ---- compare across modes (regression #9, no mockup slot) ---- */}
          {highlighted != null && (
            <section className="panel">
              <div className="panel-head pr-focus-head">
                <span
                  className="avatar"
                  style={avatarStyleFor(focusRows.find((f) => f.entry)?.entry?.managerId ?? null, highlighted, false)}
                  aria-hidden="true"
                >
                  {focusName.charAt(0).toUpperCase()}
                </span>
                <h2>{focusName}</h2>
                <button type="button" className="link-button" onClick={() => setCompareOpen((v) => !v)}>
                  {compareOpen ? 'Hide' : 'Compare across modes'} ↓
                </button>
              </div>

              {compareOpen &&
                (focusRanks.length === 0 ? (
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
                          <span className="pr-score mono">{f.entry ? scoreLabel(f.entry) : 'no score'}</span>
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
                ))}
            </section>
          )}

          {/* ---- trend (regression #8, kept behind a disclosure) ---- */}
          <section className="panel">
            <div className="panel-head">
              <h2>Trend</h2>
              <span className="small muted">
                {chartReady
                  ? `${KIND_LABEL[ladderMode].toLowerCase()}, ${weekRangeLabel(ladderWeeks)}`
                  : ladderWeeks.length === 1
                    ? 'One week of data so far.'
                    : 'No weeks yet for this mode.'}
              </span>
              <button type="button" className="link-button" onClick={() => setTrendOpen((v) => !v)}>
                {trendOpen ? 'Hide' : 'Show'} chart
              </button>
            </div>

            {trendOpen &&
              (chartReady ? (
                <>
                  <div className="bump-legend">
                    {buildSeries(data.entries, ladderMode, season, teamCount).map((s) => {
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
                  <BumpChart
                    series={buildSeries(data.entries, ladderMode, season, teamCount)}
                    weeks={ladderWeeks}
                    teamCount={gridRows}
                    highlighted={highlighted}
                    onHighlight={setHighlighted}
                  />
                </>
              ) : (
                <div className="pr-ticks" aria-hidden="true">
                  {Array.from({ length: Math.max(17, ...ladderWeeks) + 1 }, (_, i) => i).map((w) => (
                    <span key={w} className={`pr-tick${ladderWeeks.includes(w) ? ' on' : ''}`} />
                  ))}
                </div>
              ))}
          </section>

          {/* ---- homers (regression #7, kept behind a disclosure) ---- */}
          {homers.length > 0 && (
            <section className="panel">
              <div className="panel-head">
                <h2>Homers</h2>
                <span className="small muted">self vs. room, every manager</span>
                <button type="button" className="link-button" onClick={() => setHomersOpen((v) => !v)}>
                  {homersOpen ? 'Hide' : 'Show'} all
                </button>
              </div>
              {homersOpen && (
                <>
                  <p className="muted small">
                    Where a manager put their own team, against where the room put it. Negative means they rank themselves
                    higher than everyone else does.
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
                </>
              )}
            </section>
          )}
        </div>

        {/* ---- sidebar (from 2c) ---- */}
        <aside className="pr-side">
          {topHomer && (
            <section className="panel">
              <div className="panel-head">
                <h2>Homer of the week</h2>
              </div>
              <div className="pr-homer-of-week-name">{topHomer.manager ?? `roster ${topHomer.rosterId}`}</div>
              <p className="muted small">
                Ranked their own team {Math.abs(topHomer.selfRankBias!)} spot{Math.abs(topHomer.selfRankBias!) === 1 ? '' : 's'}{' '}
                {topHomer.selfRankBias! < 0 ? 'higher' : 'lower'} than the room did.
              </p>
              <div className="pr-homers pr-homers-compact">
                {homers.slice(0, 4).map((e) => {
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

          <section className="panel">
            <div className="panel-head">
              <h2>Commissioner's take</h2>
            </div>
            {!ballot?.commissionerKnown ? (
              <p className="muted small">
                No commissioner detected for this league -- re-run league ingest to pick one up from Sleeper.
              </p>
            ) : commissionerTop ? (
              <>
                <p className="pr-commish-quote">
                  {commissionerTop.note ?? `${nameOf(commissionerTop)} tops the commissioner's board this week.`}
                </p>
                <div className="tiny muted">
                  Signed, {weekPhrase(currentWeek)} ·{' '}
                  <button type="button" className="link-button" onClick={() => setLadderMode('COMMISSIONER')}>
                    full order
                  </button>
                </div>
              </>
            ) : (
              <p className="muted small">
                {ballot?.canCommission ? "You haven't set a ranking for this week yet." : "The commissioner hasn't ranked this week yet."}
              </p>
            )}
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2>Your ballot</h2>
            </div>
            {blockState === 'ok' && ballot?.mine && (
              <p className="muted small">
                In for {weekPhrase(currentWeek)}. You can resubmit until kickoff.
              </p>
            )}
            {blockState === 'ok' && !ballot?.mine && (
              <p className="muted small">Nothing submitted yet for {weekPhrase(currentWeek)}.</p>
            )}
            {blockState === 'loading' && <p className="muted small">Loading your ballot…</p>}
            {blockState === 'signed-out' && (
              <>
                <p className="muted small">
                  You're reading this league as a guest. Sign in as a member to put in a ballot -- the rankings stay visible
                  either way.
                </p>
                <Link className="action-button pr-ballot-cta" to="/">
                  Sign in
                </Link>
              </>
            )}
            {blockState === 'not-member' && (
              <p className="muted small">
                Signed in as <strong>{user?.displayName ?? user?.username}</strong>, who isn't on a roster here. Ask the
                commissioner to re-run league ingest if that's wrong.
              </p>
            )}
            {blockState === 'voting-closed' && (
              <p className="muted small">Voting is closed for {weekPhrase(currentWeek)}.</p>
            )}
            {blockState === 'load-error' && (
              <>
                <p className="muted small">We couldn't reach your ballot just now.</p>
                <button type="button" className="action-button pr-ballot-cta" onClick={refetch}>
                  Try again
                </button>
              </>
            )}
            {blockState === 'ok' && (
              <button type="button" className="action-button pr-ballot-cta" onClick={openBallotModal}>
                {ballot?.mine ? 'Edit your ballot' : 'Submit your ballot'}
              </button>
            )}
          </section>

          <p className="pr-foot">
            Ranks are manager ballots, averaged. Playoff odds come from simulating the rest of the season.{' '}
            <button type="button" className="link-button" onClick={() => setHowOpen((v) => !v)}>
              How this works →
            </button>
          </p>
        </aside>
      </div>

      {/* ---- ballot / commissioner ranking modal (2d) ---- */}
      {ballotModalOpen && (
        <div className="modal-backdrop" onClick={() => setBallotModalOpen(false)}>
          <div className="modal-card wide" onClick={(ev) => ev.stopPropagation()}>
            <button className="modal-close" onClick={() => setBallotModalOpen(false)} aria-label="Close">
              ✕
            </button>
            <div className="pr-ballot-modal-head">
              <span className="cond pr-ballot-modal-title">
                {editingCommissioner ? `Week ${currentWeek}'s commissioner ranking` : `Your week ${currentWeek} ballot`}
              </span>
              {!editingCommissioner && ballot && (
                <span className="small muted">
                  {ballot.ballotCount} of {ballot.memberCount} in
                </span>
              )}
            </div>

            {editingCommissioner ? (
              ballot?.canCommission ? (
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
                  {ballot?.commissionerKnown
                    ? "Only this league's commissioner can set this ranking."
                    : 'No commissioner detected for this league -- re-run league ingest to pick one up from Sleeper.'}
                </p>
              )
            ) : blockState === 'ok' ? (
              <>
                <p className="muted small">
                  Drag to reorder -- or tap a team and use the arrows. Your own team is in the list; we show the bias rather
                  than removing it. Nothing saves until you submit.
                </p>
                <RankBoard
                  // The signed-in user's own id is part of this key, not just
                  // week + submittedAt -- two different users who both haven't
                  // submitted yet otherwise produce the identical key, so
                  // switching accounts wouldn't remount the board even once
                  // `ballot` itself was correctly refetched for the new
                  // identity (RankBoard seeds its order once, on mount).
                  key={`ballot-${currentWeek}-${user?.sleeperUserId ?? 'anon'}-${ballot?.mine?.submittedAt ?? 'new'}`}
                  members={boardMembers}
                  ariaLabel="Your ballot"
                  initialOrder={ballotSeed.order}
                  onSubmit={sendBallot}
                  submitLabel={ballot?.mine ? 'Resubmit ballot' : 'Submit ballot'}
                  submitting={saving}
                />
                {ballotSeed.seededFrom && (
                  <p className="tiny muted">
                    Starting order: {ballotSeed.seededFrom}. Drag to disagree -- nothing is saved until you submit.
                  </p>
                )}
                <p className="tiny muted">Drag a chip, or click one and use ↑ / ↓ to move it. Escape drops the selection.</p>
                {saveMessage && <p className="small muted">{saveMessage}</p>}
              </>
            ) : blockState === 'signed-out' ? (
              <>
                <p className="muted small">
                  You're reading this league as a guest. Sign in as a member to put in a ballot -- the rankings stay visible
                  either way.
                </p>
                <Link className="action-button" to="/" onClick={() => setBallotModalOpen(false)}>
                  Sign in
                </Link>
              </>
            ) : blockState === 'not-member' ? (
              <p className="muted small">
                Signed in as <strong>{user?.displayName ?? user?.username}</strong>, who isn't on a roster here. Ask the
                commissioner to re-run league ingest if that's wrong.
              </p>
            ) : blockState === 'voting-closed' ? (
              <p className="muted small">Voting is closed for {weekPhrase(currentWeek)}.</p>
            ) : blockState === 'load-error' ? (
              <>
                <p className="muted small">We couldn't reach your ballot just now.</p>
                <button type="button" className="action-button" onClick={refetch}>
                  Try again
                </button>
              </>
            ) : (
              <p className="muted small">Loading your ballot…</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
