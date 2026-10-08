// Power rankings' story and labels: the pure functions behind the page's headline, deck,
// ladder notes and ballot states (specs/021-codebase-cleanup T052). Moved verbatim out of
// PowerRankings.tsx, which re-exports its original public names from here, so
// LeagueHome and the PowerRankings.*.test files import exactly as before.
import { type BallotState, type PowerRankingEntry, type PowerRankingKind, type StandingRow } from '../api'
import { managerHues } from '../managerColor'
import { type Series } from '../components/BumpChart'
import { ordinal } from '../format'

/**
 * Ballots are on the honour system (claude/audit-2026-09-28/04), and so, since
 * 2026-10-05, are commissioner actions: the server maps a ballot to whatever Sleeper id the
 * request names, and league members' ids are public. Said where it is submitted.
 */
export const BALLOT_HONOUR_NOTE =
  "Ballots aren't verified: anyone who knows a member's Sleeper name could submit as them, so this runs on trust."

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
 *  - `PowerRankingEntry.makesPlayoffsPct` is populated as of
 *    claude/playoff-odds.md (a rest-of-season Monte Carlo, stored per week).
 *    It is still null for weeks with no odds snapshot -- weeks that predate
 *    the feature, and leagues whose seeding the backend refuses to model --
 *    so every render site below still degrades to "--" rather than
 *    fabricating a number; wiring the backend is a separate piece of work.
 *  - "Commissioner's take" does not reproduce the mockup's free-text quote
 *    ("Kittle Caesars at 4th is a gift...") -- there is no field anywhere
 *    that carries commissioner commentary about one team, only a signed
 *    ORDERING. The card instead states a fact the ordering actually contains
 *    (who the commissioner has at #1) and links to the full order.
 */

export const KIND_LABEL: Record<PowerRankingKind, string> = {
  COMPUTED_REALIZED: 'Box score',
  COMMISSIONER: 'Commissioner',
  MEMBER: 'League vote',
}

// Fixed, not hueFor(kind) -- the mode names hash close enough together that two
// of them read as the same color. Clear of --crimson (~25, "you") and --teal
// (~175, generic interaction) per styles.css's house rule.
export const KIND_HUE: Record<PowerRankingKind, number> = {
  COMPUTED_REALIZED: 210,
  COMMISSIONER: 290,
  MEMBER: 130,
}

export const KIND_CAVEAT: Record<PowerRankingKind, string> = {
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
export const REFERENCE_KIND: Record<PowerRankingKind, PowerRankingKind> = {
  MEMBER: 'COMPUTED_REALIZED',
  COMPUTED_REALIZED: 'MEMBER',
  COMMISSIONER: 'MEMBER',
}

// thin: this week's aggregate rests on fewer than half the league's ballots, so
// the segment through it is drawn dotted. Always false for the three stored
// modes -- a computed snapshot and a signed commissioner ordering are not
// "partially submitted", they either exist for a week or they don't.

export function buildSeries(
  entries: PowerRankingEntry[],
  kind: PowerRankingKind,
  season: number,
  memberCount: number,
): Series[] {
  const byRoster = new Map<number, Series>()
  // Spaced across the league rather than hashed per id -- the hash put ids 1-9
  // on hues 49-57, so a full league shared two colors (001-readable-bump-chart).
  const hues = managerHues(
    [...new Map(entries.map((e) => [e.rosterId, e])).values()].map((e) => ({
      rosterId: e.rosterId,
      managerId: e.managerId,
    })),
  )
  for (const e of entries) {
    if (e.kind !== kind || e.season !== season) continue
    let s = byRoster.get(e.rosterId)
    if (!s) {
      s = { rosterId: e.rosterId, managerId: e.managerId, manager: e.teamName?.trim() || e.manager, hue: hues.get(e.rosterId)?.hue ?? 0, points: [] }
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


// Week 0 never reads as a number in the UI, only as "Preseason".
//
// It used to be purely an app-invented slot for the preseason baseline, on the
// stated grounds that "Sleeper's own week numbering starts at 1". That is not
// true of every sport: measured 2026-09-15, `/state/nba` reports `week: 0` for
// the entire offseason, so for a basketball league week 0 is ALSO the current
// week -- the one its members vote in and its commissioner ranks in. Both
// meanings render the same way, which is the point.
export const weekPhrase = (week: number) => (week === 0 ? 'Preseason' : `week ${week}`)
export const weekShort = (week: number) => (week === 0 ? 'preseason' : `wk ${week}`)
/** Mid-sentence form, mirroring the backend's own weekLabel(), so a save
 *  confirmation here and a refusal from the server read the same way. */
export const weekIn = (week: number) => (week === 0 ? 'the preseason' : `week ${week}`)
export const weekRangeLabel = (ws: number[]) => {
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

/** Long odds, a coin flip, or all but locked in -- the pill's tint, nothing else. */
export function oddsTone(pct: number): 'in' | 'live' | 'out' {
  if (pct >= 75) return 'in'
  if (pct >= 25) return 'live'
  return 'out'
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

export function ladderNoteFor(mode: PowerRankingKind, e: PowerRankingEntry, teamCount: number): string {
  if (mode === 'MEMBER') return roomTakeSentence(e, teamCount)
  if (mode === 'COMMISSIONER') return `Signed, ${weekShort(e.week)}`
  return `As of ${weekShort(e.week)}`
}

export type SpaceStats = {
  barLeft: string
  barWidth: string
  medianPos: string
  minePos: string | null
  agreePct: number | null
  /** The same interval the bar draws, spelled out ("3rd-9th") so the number
   *  column teaches the reader how to read the bar instead of competing with
   *  it -- this replaced an "Agreement 45% - 6" cell nobody could decode. */
  rangeLabel: string
  consensus: 'tight' | 'mixed' | 'split' | null
  deltaLabel: string | null
  deltaClass: 'up' | 'down' | 'flat' | null
}

/** Ranking-space stats for the League-vote ladder row (ranking-space-optimization
 *  handoff): where this team's ballots ranged on the 1..N axis, an approximate
 *  median, and how the viewer's own ballot (when they have one) compares to it.
 *  There is no exact-median field from the backend -- only avg/best/worst/stdev
 *  -- so the median marker is the rounded average rank, which is close enough
 *  to place a dot and cheap to keep in sync if a real median ever ships. */
export function spaceStatsFor(e: PowerRankingEntry, teamCount: number, myRank: number | null): SpaceStats {
  const n = Math.max(1, teamCount)
  const lo = e.bestRank ?? e.rank
  const hi = e.worstRank ?? e.rank
  const pos = (rank: number) => `${(((rank - 0.5) / n) * 100).toFixed(2)}%`
  const med = e.score != null ? Math.round(e.score) : e.rank
  const range = hi - lo
  const agreePct = n > 1 ? Math.max(0, ((n - 1 - range) / (n - 1)) * 100) : null
  const delta = myRank == null ? null : med - myRank
  return {
    barLeft: `${(((lo - 1) / n) * 100).toFixed(2)}%`,
    barWidth: `${(((hi - lo + 1) / n) * 100).toFixed(2)}%`,
    medianPos: pos(med),
    minePos: myRank == null ? null : pos(myRank),
    agreePct,
    rangeLabel: lo === hi ? ordinal(lo) : `${ordinal(lo)}–${ordinal(hi)}`,
    // Only ever the pill's tint, never a word of its own: the range text is
    // the content, the tint is how loud it is.
    consensus: agreePct == null ? null : agreePct >= 70 ? 'tight' : agreePct >= 40 ? 'mixed' : 'split',
    deltaLabel: delta == null ? null : delta === 0 ? 'even' : delta > 0 ? `+${delta}` : String(delta),
    deltaClass: delta == null ? null : delta === 0 ? 'flat' : delta > 0 ? 'up' : 'down',
  }
}

/**
 * Ballots that actually produced one MEMBER week, read off that week's own
 * entries.
 *
 * Deliberately NOT `BallotState.ballotCount`: /ballot always answers for the
 * CURRENT week, so pairing it with a stale `heroWeek`/`tableWeek` label is
 * what printed "0 of 12 ballots in for week 1" above twelve real week-1
 * averages (ballknowers.co, 2026-09-17, once the NFL state advanced to week 2
 * with nobody voting yet). Every week shown on this page gets its tally from
 * here, and the live one from `ballot` -- labelled as the live one.
 *
 * Per-entry `ballotCount` is per-ROSTER, hence the max: a submitted ballot
 * must cover this league's roster-id set exactly (LeagueHistoryController
 * rejects a missing, extra or duplicate roster), so the best-covered roster's
 * count IS the week's ballot count. 0 is the honest answer for a week with no
 * MEMBER entries rather than "unknown" -- MemberRankingService.forWeek()
 * returns empty exactly when the week has no ballots at all, and MEMBER
 * entries are derived on read, never stored, so they cannot lag the ballots.
 */
export function ballotsCountedIn(entries: PowerRankingEntry[], season: number, week: number): number {
  let max = 0
  for (const e of entries) {
    if (e.kind !== 'MEMBER' || e.season !== season || e.week !== week) continue
    if (e.ballotCount != null && e.ballotCount > max) max = e.ballotCount
  }
  return max
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

// Team name first, on every page; the username is the second line where a row has room for one.
export const nameOf = (e: PowerRankingEntry) => e.teamName?.trim() || e.manager || `roster ${e.rosterId}`
export const userOf = (e: PowerRankingEntry) => (e.manager && e.manager.toLowerCase() !== nameOf(e).toLowerCase() ? e.manager : '')
export const weekTitle = (week: number) => (week === 0 ? 'Preseason' : `Week ${week}`)

/**
 * Spec 013: the hero's "which weeks" rule, stated once and shared with League home.
 * The latest League-vote (MEMBER) week of the newest season any entry has,
 * compared with the MEMBER week before it. Null when that season has no
 * League-vote week yet. The season is the max over ALL entries, the same as
 * this page's own `season`, so the two pages can't pick different years.
 */
export function leagueVoteHero(
  entries: PowerRankingEntry[],
): { season: number; week: number; rows: PowerRankingEntry[]; prevWeek: number | null; prevRows: PowerRankingEntry[] | null } | null {
  if (entries.length === 0) return null
  const season = Math.max(...entries.map((e) => e.season))
  const member = entries.filter((e) => e.kind === 'MEMBER' && e.season === season)
  if (member.length === 0) return null
  const weeks = [...new Set(member.map((e) => e.week))].sort((a, b) => a - b)
  const week = weeks[weeks.length - 1]
  const rowsOf = (w: number) => member.filter((e) => e.week === w).sort((a, b) => a.rank - b.rank)
  const prevWeek = [...weeks].reverse().find((w) => w < week) ?? null
  return { season, week, rows: rowsOf(week), prevWeek, prevRows: prevWeek == null ? null : rowsOf(prevWeek) }
}

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
  // One sentence: the takeaway first (when there is one), then the ballot tally
  // that qualifies it. The tally is a caveat, so it stays in the sentence.
  const tally = `${ballotCount} of ${memberCount} ballot${memberCount === 1 ? '' : 's'} in for ${weekPhrase(week)}.`
  let lead: string | null = null
  const namedId = story.newTop
    ? story.top?.rosterId
    : story.riser && story.riser.delta >= 3
      ? story.riser.entry.rosterId
      : story.faller && story.faller.delta <= -3
        ? story.faller.entry.rosterId
        : story.divisive?.rosterId
  if (story.riser && story.riser.entry.rosterId !== namedId) {
    lead = `${nameOf(story.riser.entry)} climbed ${story.riser.delta} spot${story.riser.delta === 1 ? '' : 's'}`
  } else if (story.faller && story.faller.entry.rosterId !== namedId) {
    const n = Math.abs(story.faller.delta)
    lead = `${nameOf(story.faller.entry)} dropped ${n} spot${n === 1 ? '' : 's'}`
  } else if (story.divisive && story.divisive.rosterId !== namedId) {
    lead = `${nameOf(story.divisive)} splits the room, ${ordinal(story.divisive.bestRank ?? story.divisive.rank)} to ${ordinal(story.divisive.worstRank ?? story.divisive.rank)}`
  }
  return lead ? `${lead}; ${tally}` : tally
}

// --- "can this viewer rank" (§7 blocked states, 2d) -------------------------

export type BallotBlockState = 'loading' | 'signed-out' | 'not-member' | 'voting-closed' | 'load-error' | 'ok'

/** Every state the ballot sidebar/modal can be in. `ballotFailed` is the
 *  fetch's own catch (the pre-reskin page discarded this into a bare `null`,
 *  same as "haven't loaded yet" -- 2d's "Couldn't load" state needs the two
 *  told apart). `signedIn` is `!!useUser()`; the global route table already
 *  gates every page behind sign-in (App.tsx), so 'signed-out' cannot actually
 *  fire from this page in production today -- it's kept for if that gate
 *  ever moves, and PowerRankings.builders.test.ts pins it. */
export function ballotBlockState(signedIn: boolean, ballot: BallotState | null, ballotFailed: boolean): BallotBlockState {
  if (!signedIn) return 'signed-out'
  if (ballotFailed) return 'load-error'
  if (!ballot) return 'loading'
  const amMember = ballot.members.some((m) => m.isMe)
  if (!amMember) return 'not-member'
  if (!ballot.canSubmit) return 'voting-closed'
  return 'ok'
}

/** Value and unit from one switch: what `score` means depends on the kind.
 *  Empty string when there is no honest number to print. */
export function scoreLabel(e: { score: number | null; week: number }, kind: PowerRankingKind | null): string {
  if (e.score == null || kind == null) return ''
  if (kind === 'MEMBER') return `avg rank ${e.score.toFixed(2)}`
  if (kind === 'COMPUTED_REALIZED') return e.week === 0 ? `lineup value ${Math.round(e.score)}` : `${e.score.toFixed(2)} pts`
  return ''
}
