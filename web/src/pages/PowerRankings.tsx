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
import CommissionerHonourNote from '../components/CommissionerHonourNote'
import Avatar from '../components/Avatar'
import HowThisWorks from '../components/HowThisWorks'
import BumpChart, { segmentsOf, type Series, type SeriesPoint } from '../components/BumpChart'

// Re-exported so PowerRankings.chart.test.ts keeps importing the rule from the
// page that owns the chart's meaning, while the drawing itself now lives in a
// component two pages share (claude/league-analysis-week-by-week.md).
export { segmentsOf }
export type { SeriesPoint }
import { useUser } from '../user'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { ordinal } from '../format'

import {
  BALLOT_HONOUR_NOTE,
  KIND_CAVEAT,
  KIND_HUE,
  KIND_LABEL,
  REFERENCE_KIND,
  ballotBlockState,
  ballotsCountedIn,
  buildDeck,
  buildHeadline,
  buildSeries,
  computeWeeklyStory,
  ladderNoteFor,
  leagueVoteHero,
  nameOf,
  oddsTone,
  pctLabel,
  recordLabel,
  roomTakeSentence,
  scoreLabel,
  spaceStatsFor,
  userOf,
  weekIn,
  weekPhrase,
  weekRangeLabel,
  weekShort,
  weekTitle,
} from './powerRankingsStory'
// Re-exported: these were public names of this page before spec 021 moved them.
export {
  ballotBlockState,
  ballotsCountedIn,
  buildDeck,
  buildHeadline,
  computeWeeklyStory,
  leagueVoteHero,
  oddsTone,
  pctLabel,
  recordLabel,
  roomTakeSentence,
  scoreLabel,
  spaceStatsFor,
} from './powerRankingsStory'
export type { BallotBlockState, DeltaEntry, SpaceStats, WeeklyStory } from './powerRankingsStory'

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
  const [ballotModalOpen, setBallotModalOpen] = useState(false)
  // Bumped by the rail when this league's background refresh finishes (specs/009-auto-data-refresh).
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)

  // Take the rail off the page entirely while the ballot is open.
  //
  // In principle this is redundant: `.modal-backdrop` is `position: fixed;
  // inset: 0` and on phones the ballot is now a full-screen sheet, so there
  // should be nothing of the rail left to see. On a real phone there was --
  // Allan's screenshot shows the nav sitting above the sheet, undimmed, while
  // the page content below it is dimmed, which is not a thing a fixed
  // full-viewport backdrop can do. I could not reproduce it here and would
  // rather not ship a fix aimed at a cause I never proved.
  //
  // Removing the rail from the layout does not depend on knowing why. Ranking
  // twelve teams is the whole job while this is open and none of that
  // navigation is usable during it, so it is also the better design.
  useEffect(() => {
    if (!ballotModalOpen) return
    document.body.classList.add('bk-modal-fullscreen')
    return () => document.body.classList.remove('bk-modal-fullscreen')
  }, [ballotModalOpen])
  const [computing, setComputing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<string | null>(null)

  function refetch() {
    if (!sleeperLeagueId) return
    getPowerRankings(sleeperLeagueId)
      .then((d) => {
        // A 200 without `sportState` is a backend older than this build: it
        // answered `nflState` until 90274e7's multi-sport rename, and a
        // frontend deploy that outran the backend deploy hit exactly that
        // (measured against api.ballknowers.co, 2026-09-15). Rejecting the
        // payload here keeps `data` null so the panel below says so. Reading
        // it optimistically is what white-screened the app -- and defaulting
        // the missing week to 1 instead would be worse than a crash, since
        // basketball is legitimately at week 0 all offseason and every ballot
        // would silently land on the wrong week.
        if (!d || !d.sportState) {
          setData(null)
          setError('This league’s power rankings came back in a format this page cannot read — the server is running an older build than the site. Redeploy the backend.')
          return
        }
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
  useEffect(refetch, [sleeperLeagueId, user?.sleeperUserId, dataVersion])

  const season = useMemo(() => {
    if (!data || data.entries.length === 0) return Number(data?.sportState.season ?? new Date().getFullYear())
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
  }, [sleeperLeagueId, season, dataVersion])

  // `?? 1` only covers "no data yet". Once data is here, week 0 is a real
  // value and must survive -- `data.sportState.week || 1` would quietly send
  // every basketball ballot to week 1 all offseason.
  const currentWeek = data?.sportState.week ?? 1

  // The week the hero and the League-vote ladder show: the latest week with
  // ballots. Distinct from `currentWeek` (the open week `ballot` answers for),
  // so "Your ballot" needs its own fetch keyed to it. `===` elsewhere: week 0 is real.
  const viewedWeek = useMemo(() => {
    if (!data) return null
    const weeks = data.entries.filter((e) => e.kind === 'MEMBER' && e.season === season).map((e) => e.week)
    return weeks.length > 0 ? Math.max(...weeks) : null
  }, [data, season])
  const [viewedBallotState, setViewedBallotState] = useState<{ week: number; ballot: BallotState } | null>(null)
  useEffect(() => {
    if (!sleeperLeagueId || viewedWeek == null || viewedWeek === currentWeek) {
      setViewedBallotState(null)
      return
    }
    let cancelled = false
    getBallot(sleeperLeagueId, viewedWeek)
      .then((b) => {
        if (!cancelled) setViewedBallotState({ week: viewedWeek, ballot: b })
      })
      // Cleared on failure, like `ballot`: never leave another identity's ballot up.
      .catch(() => {
        if (!cancelled) setViewedBallotState(null)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId, viewedWeek, currentWeek, user?.sleeperUserId, dataVersion])

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
      setSaveMessage(`Saved ${weekIn(currentWeek)}`)
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
      setSaveMessage(`Ballot submitted for ${weekIn(currentWeek)}`)
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
    avatarId: m.avatarId,
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

  // League vote gets the ranking-space row (range bar + your ballot +
  // agreement) instead of the movement/playoff/note columns -- the spread
  // data behind it (bestRank/worstRank/stdev) is MEMBER-only, and Box
  // score/Commissioner keep today's row untouched.
  const memberSpace = ladderMode === 'MEMBER'
  const viewedBallot =
    viewedWeek === currentWeek ? ballot : viewedBallotState?.week === viewedWeek ? viewedBallotState.ballot : null
  const myRankByRoster = new Map<number, number>(viewedBallot?.mine?.rosterIds.map((rosterId, i) => [rosterId, i + 1]) ?? [])

  // ---- the hero (headline / #1 / your-team strip / story cards) is always
  // League vote, independent of which ladder tab is selected -- it is "state
  // of the league", not "state of the current tab" (2a/2c both keep the
  // headline fixed while only the ladder switches). ----
  // The weeks rule lives in leagueVoteHero() (shared with League home). With no
  // League-vote week yet the hero falls back to the current week, empty.
  const hero = leagueVoteHero(data.entries)
  const heroWeek = hero?.week ?? currentWeek
  const heroRows = hero?.rows ?? entriesFor('MEMBER', heroWeek)
  const heroPrevRows = hero?.prevRows ?? null
  const story = computeWeeklyStory(heroRows, heroPrevRows)
  const headline = buildHeadline(story, heroWeek)
  const memberCount = ballot?.memberCount ?? teamCount
  // heroWeek's OWN tally, not the live one. See ballotsCountedIn().
  const heroBallots = ballotsCountedIn(data.entries, season, heroWeek)
  const deck = buildDeck(story, heroBallots, memberCount, heroWeek)
  // The two weeks this page has to hold at once: the last one the room
  // actually produced (heroWeek/tableWeek) and the one it is voting in now
  // (currentWeek). They differ for the whole stretch between a week advancing
  // and the first ballot landing, which for this league is most of a week.
  // Null when they agree, so every label below can say which is which
  // instead of printing one week's number under the other's heading.
  // `===` on purpose: week 0 is a real week all NBA offseason.
  const collectingWeek = currentWeek === heroWeek ? null : currentWeek

  const heroReferenceRows = entriesFor(REFERENCE_KIND.MEMBER, heroWeek)
  const heroReferenceRank = new Map(heroReferenceRows.map((e) => [e.rosterId, e.rank]))
  const deltaVs = (e: PowerRankingEntry, refRank: Map<number, number>) => {
    const r = refRank.get(e.rosterId)
    return r == null ? null : r - e.rank
  }

  const myEntry = heroRows.find((e) => ballot?.members.some((m) => m.isMe && m.rosterId === e.rosterId))
  const myBallotRank = viewedBallot?.mine ? viewedBallot.mine.rosterIds.indexOf(myEntry?.rosterId ?? -1) : -1

  // The footnote's second sentence, built from the snapshot that actually
  // produced the numbers. When there is no snapshot the sentence is absent --
  // it used to claim a simulation that did not exist anywhere in the codebase.
  const oddsSummary = data?.playoffOdds ?? null
  const oddsNote = oddsSummary
    ? `Playoff odds are ${oddsSummary.iterations.toLocaleString()} simulated seasons against the real remaining schedule, from ${oddsSummary.weeksOfScoring} week${oddsSummary.weeksOfScoring === 1 ? '' : 's'} of scoring.`
    : null

  // The homer card is a fact about the ballots that produced the averages
  // above it, so it reads heroWeek. Against `currentWeek` it silently emptied
  // itself the moment the season advanced, which is how a week-1 page ended
  // up with no homer while showing week 1's ballots.
  const memberEntries = entriesFor('MEMBER', heroWeek)
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
  const teamViewOptions = [...new Map(data.entries.map((e) => [e.rosterId, nameOf(e)])).entries()]
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

  function openBallotModal() {
    setSaveMessage(null)
    setBallotModalOpen(true)
  }

  return (
    <div className="content scrolls pr-page">
      {error && <div className="error">{error}</div>}

      {/* ---- eyebrow ---- */}
      {/* The `← League history` chip that used to sit here is gone: the rail
          now carries this league's whole menu (History, Power rankings, the
          draft board, Mock it) on every league-scoped route --
          claude/site-wide-shell-propagation.md Phase 3. It was the only
          hand-rolled back link in the app, and keeping it would mean the same
          navigation in two places, which is how the two drift apart. */}
      <div className="pr-eyebrow-row">
        <span className="page-eyebrow accent">
          {weekTitle(heroWeek)} · {leagueName ?? 'League'} · {teamCount} teams
        </span>
      </div>

      {/* ---- hero: headline + deck + #1 card ---- */}
      <div className="pr-hero">
        <div className="pr-hero-copy">
          <h1 className="page-title hero cond">{headline}</h1>
          <p className="page-sub wide">{deck}</p>
          <div className="pr-hero-pills">
            <span className="chip on">
              {heroBallots} of {memberCount} ballots in · {weekPhrase(heroWeek)}
            </span>
            {collectingWeek != null && ballot && (
              <span className="chip">
                {ballot.ballotCount} of {ballot.memberCount} in · {weekPhrase(collectingWeek)} open
              </span>
            )}
          </div>
        </div>

        {story.top && (
          <div className="pr-number-one">
            <div className="pr-number-one-label cond">Number one</div>
            <div className="pr-number-one-body">
              <div className="pr-number-one-rank cond mono">1</div>
              <div className="pr-number-one-id">
                <div className="pr-number-one-name">{nameOf(story.top)}</div>
                <div className="pr-number-one-meta">
                  {userOf(story.top) ? `${userOf(story.top)} · ` : ''}
                  <span className="mono">{recordLabel(standings?.get(story.top.rosterId))}</span>
                  {scoreLabel(story.top, 'MEMBER') && (
                    <>
                      {' '}· <span className="mono">{scoreLabel(story.top, 'MEMBER')}</span>
                    </>
                  )}
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
          <span className="pr-your-team-name">{nameOf(myEntry)}</span>
          <span className="pr-your-team-detail">
            {ordinal(myEntry.rank)} of {gridRows}
            {(() => {
              const d = deltaVs(myEntry, heroReferenceRank)
              return d != null && d !== 0 ? ` · the room moved you ${d > 0 ? 'up' : 'down'} ${Math.abs(d)}` : ''
            })()}
            {myEntry.makesPlayoffsPct != null && (
              <>
                {' '}· <span className="mono">{pctLabel(myEntry.makesPlayoffsPct)}</span> to make it
              </>
            )}
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
            <div className="pr-story">
              <h2 className="section-title">Riser of the week</h2>
              <div className="pr-story-name">
                {nameOf(story.riser.entry)} <span className="mono pr-move up">▲ +{story.riser.delta}</span>
              </div>
              <div className="pr-story-note">Now {ordinal(story.riser.entry.rank)}, from {ordinal(story.riser.entry.rank + story.riser.delta)}.</div>
            </div>
          )}
          {story.faller && (
            <div className="pr-story">
              <h2 className="section-title">Free fall</h2>
              <div className="pr-story-name">
                {nameOf(story.faller.entry)} <span className="mono pr-move down">▼ −{Math.abs(story.faller.delta)}</span>
              </div>
              <div className="pr-story-note">
                Now {ordinal(story.faller.entry.rank)}, from {ordinal(story.faller.entry.rank + story.faller.delta)}.
              </div>
            </div>
          )}
          {story.divisive && (
            <div className="pr-story">
              <h2 className="section-title">Nobody agrees</h2>
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
          <section className="section pr-section">
            <div className="pr-ladder-head panel-head">
              <h2 className="section-title">The ladder</h2>
              <span className="small muted">
                {KIND_LABEL[ladderMode]} · {weekPhrase(tableWeek)}
                {ladderMode === 'MEMBER' ? ` · ${ballotsCountedIn(data.entries, season, tableWeek)} ballots` : ''}
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
                  {computing ? 'Computing…' : `Recompute ${weekIn(currentWeek)} (commissioner)`}
                </button>
              )}
              {ballot?.canCommission && <CommissionerHonourNote />}
            </div>

            {/* The ladder deliberately shows the latest week this mode HAS,
                not the current week (regression checklist #2) -- so when those
                differ it has to say so. Without this line twelve week-1 rows
                sat under a week-1 heading while the ballot panel below
                collected for week 2, and nothing on the page connected the
                two. */}
            {rows.length > 0 && tableWeek !== currentWeek && (
              <p className="small muted pr-ladder-lag">
                Showing {weekIn(tableWeek)} — the latest {KIND_LABEL[ladderMode].toLowerCase()} week there is. Nothing for{' '}
                {weekIn(currentWeek)} yet.
              </p>
            )}

            {rows.length === 0 ? (
              <p className="muted">
                No {KIND_LABEL[ladderMode].toLowerCase()} snapshots yet for this league.{' '}
                {ladderMode === 'COMPUTED_REALIZED'
                  ? ballot?.canCommission
                    ? `Use "Recompute ${weekIn(currentWeek)}" above to build the first one.`
                    : 'Ask the commissioner to run the first box-score snapshot.'
                  : ladderMode === 'MEMBER'
                    ? 'Ballots build this one -- submit yours above, and the room fills in as others do.'
                    : ''}
              </p>
            ) : (
              <div className={`pr-list${memberSpace ? ' member-space' : hasReference ? '' : ' no-reference'}`}>
                <div className="pr-row-head">
                  <span>#</span>
                  <span>Team</span>
                  <span>Record</span>
                  {memberSpace ? (
                    <>
                      <span className="pr-score-head">Avg rank</span>
                      <span className="pr-score-head">Your ballot</span>
                      {/* The axis lives under the column title and inside the
                          bar's own grid track, so "1st" and "12th" sit over the
                          ends of the bar they scale. (They used to float at the
                          right of the header, where they read as a label for
                          the next column over.) */}
                      <span className="pr-space-head">
                        <span>Where the room had them</span>
                        <span className="pr-axis" aria-hidden="true">
                          <span className="pr-axis-tick start">1st</span>
                          <span className="pr-axis-tick mid">halfway</span>
                          <span className="pr-axis-tick end">{ordinal(gridRows)}</span>
                        </span>
                      </span>
                      <span className="pr-score-head">Ballot range</span>
                    </>
                  ) : (
                    <>
                      {hasReference && <span className="pr-move-head">{sinceLabel}</span>}
                      <span>Where the room had them</span>
                    </>
                  )}
                </div>

                <div className="row-list">
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
                  const pct = e.makesPlayoffsPct
                  const myRank = myRankByRoster.get(e.rosterId) ?? null
                  const space = memberSpace ? spaceStatsFor(e, gridRows, myRank) : null
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
                        <Avatar
                          avatarId={e.avatarId}
                          seed={String(e.managerId ?? e.rosterId)}
                          label={e.teamName?.trim() || e.manager || `R${e.rosterId}`}
                          isMe={isMe}
                        />
                        <span className="pr-team-text">
                          <span className="pr-team-name">
                            <span className="pr-team-name-text">{nameOf(e)}</span>
                            {isMe && <span className="cond pr-you-tag">You</span>}
                          </span>
                          <span className="pr-team-sub">
                            {userOf(e)}
                          </span>
                        </span>
                      </span>
                      {/* Record and odds are one cell on purpose: both are
                          "how this team's season is going", neither is about
                          the ranking mode, and it costs the row no width. The
                          pill is simply absent when there is no answer -- a
                          dash under every record is noise. */}
                      <span className="pr-record-cell">
                        <span className="mono pr-record">{recordLabel(standings?.get(e.rosterId))}</span>
                        {pct != null && (
                          <span className={`mono pr-odds ${oddsTone(pct)}`} title={`${pctLabel(pct)} to make the playoffs`}>
                            {pctLabel(pct)}
                          </span>
                        )}
                      </span>
                      {space ? (
                        <>
                          <span className="mono pr-avg">{e.score == null ? '' : e.score.toFixed(2)}</span>
                          <span className="pr-your-ballot">
                            {myRank == null ? (
                              <span className="tiny muted">{viewedBallot?.mine ? '—' : 'No ballot'}</span>
                            ) : (
                              <>
                                <span className="mono">{ordinal(myRank)}</span>
                                {space.deltaLabel && (
                                  <span className={`pr-move mono ${space.deltaClass}`}>{space.deltaLabel}</span>
                                )}
                              </>
                            )}
                          </span>
                          <span className="pr-space-cell" title={roomTakeSentence(e, gridRows)}>
                            <span className="spread-bar">
                              <span className="spread-bar-mid" />
                              <span className="spread-bar-range" style={{ left: space.barLeft, width: space.barWidth }} />
                              <span className="spread-bar-mean" style={{ left: space.medianPos }} />
                              {space.minePos != null && <span className="spread-bar-mine" style={{ left: space.minePos }} />}
                            </span>
                          </span>
                          <span className="pr-range-cell">
                            <span
                              className={`mono pr-range-pill${space.consensus ? ` ${space.consensus}` : ''}`}
                              title={roomTakeSentence(e, gridRows)}
                            >
                              {space.rangeLabel}
                            </span>
                          </span>
                        </>
                      ) : (
                        <>
                          {hasReference && (
                            <span className={`pr-move mono ${delta == null ? 'flat' : delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat'}`}>
                              {delta == null ? '–' : delta === 0 ? '–' : `${delta > 0 ? '▲' : '▼'}${Math.abs(delta)}`}
                            </span>
                          )}
                          <span className="pr-note">{ladderNoteFor(ladderMode, e, gridRows)}</span>
                        </>
                      )}
                    </button>
                  )
                })}
                </div>

                {ladderMode === 'MEMBER' && (
                  <p className="pr-foot">
                    A team ranked by fewer than half this week's ballots is placed after every team that cleared that bar,
                    rather than winning the week on one enthusiastic vote.
                  </p>
                )}
                {memberSpace && (
                  <div className="pr-space-legend">
                    <span className="pr-space-legend-item">
                      <span className="pr-space-legend-swatch range" /> ballot range, best to worst
                    </span>
                    <span className="pr-space-legend-item">
                      <span className="pr-space-legend-swatch median" /> room median
                    </span>
                    <span className="pr-space-legend-item">
                      <span className="pr-space-legend-swatch mine" /> your ballot
                    </span>
                    <span className="pr-space-legend-item">
                      <span className="pr-space-legend-swatch halfway" /> halfway line
                    </span>
                  </div>
                )}
              </div>
            )}

            <HowThisWorks>
              <p>Ranks are manager ballots, averaged. {oddsNote}</p>
              <p>{KIND_CAVEAT[ladderMode]}</p>
              <p>
                Movement compares this mode against {KIND_LABEL[referenceKind]} for the same week, when that mode has a
                snapshot for it -- otherwise the column is hidden rather than showing a fake zero. Playoff odds are a
                team-level simulation: each roster's weekly scoring, shrunk toward the league average by how few games
                it stands on, played out over the real remaining schedule. It knows nothing about injuries, byes or
                trades, and a league whose playoff seeding this app does not model (divisions, a custom seed type)
                shows "--" rather than a number that would be quietly wrong.
              </p>
            </HowThisWorks>
          </section>

          {/* ---- compare across modes (regression #9, no mockup slot) ---- */}
          {highlighted != null && (
            <section className="panel">
              <div className="panel-head pr-focus-head">
                <Avatar
                  avatarId={focusRows.find((f) => f.entry)?.entry?.avatarId ?? null}
                  seed={String(focusRows.find((f) => f.entry)?.entry?.managerId ?? highlighted)}
                  label={focusName}
                />
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
                          <span className="pr-score mono">{(f.entry && scoreLabel(f.entry, f.kind)) || 'no score'}</span>
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
                        <BumpChart series={teamSeries} weeks={teamViewWeeks} teamCount={gridRows} colorBy="series" />
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
                    colorBy="focus"
                    /* This page's `highlighted` is a page-wide single selection --
                       it also drives the team view and the row pinning -- so it is
                       adapted to the chart's pin list here rather than replaced. */
                    selection={[highlighted, null, null]}
                    meRosterId={myEntry?.rosterId ?? null}
                    onToggle={(rosterId) =>
                      setHighlighted((cur) => (cur === rosterId ? null : rosterId))
                    }
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
                          <span className="pr-homer-name">{nameOf(e)}</span>
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
                <span className="small muted">{weekPhrase(heroWeek)}</span>
              </div>
              <div className="pr-homer-of-week-name">{nameOf(topHomer)}</div>
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
                      <span className="pr-homer-name">{nameOf(e)}</span>
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
                No commissioner is recorded for this league yet. It is read from Sleeper whenever the league refreshes.
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
                {ballot?.canCommission
                  ? `You haven't set a ranking for ${weekIn(currentWeek)} yet.`
                  : `The commissioner hasn't ranked ${weekIn(currentWeek)} yet.`}
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
            {blockState === 'ok' && <p className="tiny muted">{BALLOT_HONOUR_NOTE}</p>}
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
                Signed in as <strong>{user?.displayName ?? user?.username}</strong>, who isn't on a roster here. Rosters are read from
                Sleeper whenever the league refreshes, so if you just joined, check back shortly.
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

        </aside>
      </div>

      {/* ---- ballot / commissioner ranking modal (2d) ---- */}
      {ballotModalOpen && (
        <div className="modal-backdrop pr-ballot-backdrop" onClick={() => setBallotModalOpen(false)}>
          <div className="modal-card wide" onClick={(ev) => ev.stopPropagation()}>
            <button className="modal-close" onClick={() => setBallotModalOpen(false)} aria-label="Close">
              ✕
            </button>
            <div className="pr-ballot-modal-head">
              <span className="cond pr-ballot-modal-title">
                {editingCommissioner
                  ? `${weekTitle(currentWeek)} commissioner ranking`
                  : `Your ${weekPhrase(currentWeek).toLowerCase()} ballot`}
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
                      Starting order: {commissionerSeed.seededFrom}. Drag by the grip, or tap a team and use the arrows -- nothing is saved until you submit.
                    </p>
                  )}
                  {saveMessage && <p className="small muted">{saveMessage}</p>}
                </>
              ) : (
                <p className="muted small">
                  {ballot?.commissionerKnown
                    ? "Only this league's commissioner can set this ranking."
                    : 'No commissioner is recorded for this league yet. It is read from Sleeper whenever the league refreshes.'}
                </p>
              )
            ) : blockState === 'ok' ? (
              <>
                <p className="muted small">
                  Drag a team by the grip on its right, or tap it and use the arrows that appear. Your own team is in the
                  list; we show the bias rather
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
                    Starting order: {ballotSeed.seededFrom}. Drag by the grip, or tap a team and use the arrows -- nothing is saved until you submit.
                  </p>
                )}
                <p className="tiny muted">
                  Drag by the grip, or tap a team and use ↑ / ↓ to move it. Escape drops the selection.
                </p>
                <p className="tiny muted">{BALLOT_HONOUR_NOTE}</p>
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
                Signed in as <strong>{user?.displayName ?? user?.username}</strong>, who isn't on a roster here. Rosters are read from
                Sleeper whenever the league refreshes, so if you just joined, check back shortly.
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
