import { matchupWord } from '../lib/matchupResult'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import NotFound from '../components/NotFound'
import PlayerSpotlight from '../components/PlayerSpotlight'
import LineupCheckSection from '../components/LineupCheck'
import PersonName from '../components/PersonName'
import PreseasonStrengthBlock from '../components/PreseasonStrengthBlock'
import EspnRefreshNote from '../components/EspnRefreshNote'
import UnmatchedPlayersNote from '../components/UnmatchedPlayersNote'
import { BlockNote } from '../components/NotAvailableForPlatform'
import GradeChip, { GradesEarlyBadge } from '../components/GradeChip'
import { SkeletonRows } from '../components/Skeleton'
import {
  fetchSuperlatives,
  getLeagueAnalysis,
  getLeagueHistory,
  getLineupCheck,
  getNextMatchup,
  getPlayerSpotlight,
  getPowerRankings,
  getSeasonStart,
  getWeeklyReport,
  type AnalysisMatchups,
  type DraftGradeSummary,
  type DraftSummary,
  type FirstWeek,
  type LeagueAnalysis,
  type LeagueHistory,
  type NextMatchup,
  type PlayerSpotlight as PlayerSpotlightData,
  type PowerRankings,
  type SeasonHistory,
  type SeasonStart,
  type Sport,
  type StandingRow,
  type SuperlativesResponse,
  type WeeklyReport,
} from '../api'
import { auctionAdpRoute, destinationsFor, labelOf } from '../destinations'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useBlock, type Block } from '../useBlock'
import { isSettled, unavailableReason, useLeagueAvailability } from '../useLeagueAvailability'
import { cachedDrafts } from '../railLeague'
import { ordinal } from '../rankOrder'
import { buildHeadline, computeWeeklyStory, leagueVoteHero } from './PowerRankings'
import { TITLES } from '../awardTitles'

export type { Block } from '../useBlock'

/**
 * specs/013 US4: League home, the page a league's name leads to.
 *
 * Composed client-side from endpoints that already exist, one block per
 * endpoint, so a slow or failing one costs its own block and nothing else:
 *
 *   standings + your record + position  <- getLeagueHistory  (both sports)
 *   latest matchup                      <- getWeeklyReport(id, 0) via WeeklySide.isMe  (both sports)
 *   next opponent                       <- football: getLeagueAnalysis matchups isMe (shows the
 *                                          projected score); basketball: getNextMatchup (specs/017,
 *                                          pairing only, no projection). Football moves to
 *                                          getNextMatchup after spec 017 T043's measurement.
 *   which blocks, and the preseason    <- getSeasonStart (specs/030): its `state` decides the layout.
 *   strength + draft links                 DRAFTED_UNSCORED drops last season's matchup / power /
 *                                          awards / empty standings and shows projected strength.
 *   power headline                      <- getPowerRankings, via PowerRankings' own
 *                                          computeWeeklyStory + buildHeadline
 *   top award                           <- fetchSuperlatives
 *   player spotlight                    <- getPlayerSpotlight (specs/014); football's top-of-week
 *                                          list is read from the `weekly` block, but nothing
 *                                          else depends on the spotlight block
 *
 * <b>Per-block availability (spec 035 F13).</b> An ESPN league cannot produce some of these blocks. Each one
 * the server's LeagueAvailability lists as unavailable is not fetched and is replaced by a one-line note
 * (key: weeklyReport -> latest matchup, superlatives -> top award, stats -> player spotlight, schedule ->
 * next matchup, draftGrades -> grades, draftRoom -> the draft links, analysis -> next opponent). Standings,
 * power and the rest render. A Sleeper league never asks: the platform comes from the league list this page
 * already reads, so its blocks load exactly as before.
 *
 * "You" is only ever a server-side fact (`isMe` on a standings row, a weekly
 * side, an analysis side), never a client-side name match. A reader who is not
 * in the league gets no "you" blocks and no error.
 */

/** Availability page key -> the league-home block it hides. The keys are the server's. */
const BLOCK_LABELS: [string, string][] = [
  ['weeklyReport', 'Latest matchup'],
  ['schedule', 'Next matchup'],
  ['lineupCheck', 'Lineup check'],
  ['superlatives', 'Season awards'],
  ['stats', 'Player spotlight'],
  ['draftGrades', 'Draft grades'],
  ['draftRoom', 'Draft room'],
]

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/**
 * "2026-10-20" to "Oct 20". Split, never `new Date(str)`: a date-only string
 * parses as UTC midnight and shows as the 19th in US time zones.
 */
export function formatIsoDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  return m ? `${MONTHS[Number(m[2]) - 1]} ${Number(m[3])}` : iso
}

function record(r: StandingRow): string {
  const w = r.wins ?? 0
  const l = r.losses ?? 0
  return r.ties ? `${w}-${l}-${r.ties}` : `${w}-${l}`
}

const teamOf = (r: StandingRow) => r.teamName?.trim() || r.manager || `roster ${r.rosterId}`

/**
 * The season whose standings the home shows: the one this URL's league id
 * names. The history endpoint walks the chain starting from the requested
 * league, so that is `seasons[0]` as well -- looked up by id first so the
 * answer does not depend on that ordering.
 */
function seasonFor(history: LeagueHistory, sleeperLeagueId: string): SeasonHistory | null {
  return history.seasons.find((s) => s.sleeperLeagueId === sleeperLeagueId) ?? history.seasons[0] ?? null
}

function unitLabel(unit: string | null): string {
  switch (unit) {
    case 'POINTS':
      return 'pts'
    case 'WINS':
      return 'wins'
    case 'GAMES':
      return 'games'
    case 'ADDS':
      return 'adds'
    case 'PICKUPS':
      return 'trending pickups'
    default:
      return ''
  }
}

function powerHeadline(data: PowerRankings): string | null {
  // The same rule and the same words as the Power page's hero (leagueVoteHero,
  // computeWeeklyStory, buildHeadline are all PowerRankings.tsx's own).
  const hero = leagueVoteHero(data.entries)
  return hero ? buildHeadline(computeWeeklyStory(hero.rows, hero.prevRows), hero.week) : null
}

export default function LeagueHome() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const id = sleeperLeagueId ?? ''
  // Bumped by the rail when this league's background refresh finishes.
  const version = useLeagueDataVersion(sleeperLeagueId)

  const drafts = useBlock(() => cachedDrafts(), [id])
  // Spec 035: the platform comes off the league list this page reads anyway. Only an ESPN league asks which
  // blocks it can show, and the gated blocks wait for the answer so they are never fetched just to be refused.
  const isEspn = drafts.status === 'ok' && drafts.data.find((d) => d.sleeperLeagueId === id)?.platform === 'ESPN'
  const availability = useLeagueAvailability(isEspn ? id : null, version)
  const gateOpen = drafts.status !== 'loading' && (!isEspn || isSettled(availability))
  const reasonFor = (key: string) => (isEspn ? unavailableReason(availability, key) : null)
  const allow = (key: string) => gateOpen && reasonFor(key) == null
  const okWeekly = allow('weeklyReport')
  const okAwards = allow('superlatives')
  const okSpotlight = allow('stats')
  const okSchedule = allow('schedule')
  const okAnalysis = allow('analysis')
  const okLineup = allow('lineupCheck')

  const history = useBlock(id ? () => getLeagueHistory(id) : null, [id, version])
  const weekly = useBlock(id && okWeekly ? () => getWeeklyReport(id, 0) : null, [id, version, okWeekly])
  const power = useBlock(id ? () => getPowerRankings(id) : null, [id, version])
  const awards = useBlock(id && okAwards ? () => fetchSuperlatives(id) : null, [id, version, okAwards])
  const spotlight = useBlock(id && okSpotlight ? () => getPlayerSpotlight(id) : null, [id, version, okSpotlight])
  const seasonStart = useBlock(id ? () => getSeasonStart(id) : null, [id, version])

  const draft: DraftSummary | null =
    drafts.status === 'ok' ? (drafts.data.find((d) => d.sleeperLeagueId === id) ?? null) : null
  const sport: Sport | null = draft?.sport ?? (weekly.status === 'ok' ? weekly.data.sport : null)

  // Football only, and only once the sport is known: guessing it would fetch a
  // projection for a league that has none. Gated on the sport itself, not on
  // "does the sport have an Analysis page": basketball has one now (spec 030),
  // but its home shows the pairing block below, and fetching the analysis too
  // would run a second full lineup pass and render a second "Next opponent".
  const analysis = useBlock(sport === 'nfl' && okAnalysis ? () => getLeagueAnalysis(id) : null, [id, sport, version, okAnalysis])
  // Basketball only (specs/017 F7: football stays on the analysis block until its Tue/Wed
  // boundary is measured). Keyed on the data version so the visit's refresh refetches it (F8).
  const nextMatchup = useBlock(id && sport === 'nba' && okSchedule ? () => getNextMatchup(id) : null, [id, sport, version, okSchedule])
  // specs/042 US3: your live starters' tags and byes. A member's block only (the server answers 404 to anyone
  // else, so a non-member never asks), keyed on the data version like its neighbours (review F12).
  const viewerIsMember = history.status === 'ok' && (seasonFor(history.data, id)?.standings ?? []).some((r) => r.isMe)
  const lineupCheck = useBlock(id && okLineup && viewerIsMember ? () => getLineupCheck(id) : null, [id, version, okLineup, viewerIsMember])
  // When the check read live starters it REPLACES the projected-starters note (review F5b): two lists of "your
  // starters" from two definitions of who starts would sit side by side. The note stays as the fallback.
  const lineupCheckLive = lineupCheck.status === 'ok' && lineupCheck.data.available

  // An id the server does not know (or one that is not yours) is the shared
  // not-found state; any other failure stays inside its own block.
  if (!id || (history.status === 'error' && history.notFound)) return <NotFound what="league" />

  const season = history.status === 'ok' ? seasonFor(history.data, id) : null
  const standings = season?.standings ?? []
  const meIndex = standings.findIndex((r) => r.isMe)
  const me = meIndex >= 0 ? standings[meIndex] : null
  // A position needs games behind it. Before anyone has played, every roster is
  // 0-0 and "1st of 12" would be a claim made from nothing (spec 013 review).
  const played = (r: StandingRow) => (r.wins ?? 0) + (r.losses ?? 0) + (r.ties ?? 0)
  const anyGames = standings.some((r) => played(r) > 0)
  // The layout is `seasonStart.state`'s, never re-derived from the draft or the standings.
  // While it loads, the blocks that differ by state are held back so last season's matchup,
  // power headline and awards never flash before disappearing (spec 030 N3).
  const settling = seasonStart.status === 'loading' || !gateOpen
  const start: SeasonStart | null = seasonStart.status === 'ok' ? seasonStart.data : null
  const unscored = start?.state === 'DRAFTED_UNSCORED'
  // Only the error fallback: if season-start can't be read, the page falls back to the rule
  // it used before spec 030 (a draft that isn't complete means pre-season) rather than going blank.
  const legacyPreSeason = draft != null && (draft.status ?? 'unknown') !== 'complete'
  const preSeason = start ? start.state === 'PRE_DRAFT' : legacyPreSeason
  const leagueName = draft?.leagueName ?? season?.name ?? 'League home'
  const seasonYear = draft?.season ?? season?.season ?? null
  const draftBoardId = start?.draft.sleeperDraftId ?? draft?.sleeperDraftId ?? null
  // Spec 046 FR-015: an auction has no pick order to open a simulator or board on; its draft links go to the ADP page.
  const auctionRoute = draft ? auctionAdpRoute(draft.draftType, draft.sport, draft.sleeperLeagueId) : null
  // DRAFTED_UNSCORED shows the latest-matchup block only if it holds a week of THIS season.
  const scoredThisSeason =
    weekly.status === 'ok' &&
    weekly.data.available &&
    weekly.data.week > 0 &&
    weekly.data.season === seasonYear &&
    weekly.data.requestedSeason == null

  // What each state keeps. Matchup, standings, power and awards read last season's numbers
  // in DRAFTED_UNSCORED: standings, power and awards are dropped there outright, the matchup
  // only survives if it is this season's.
  const showMatchup = okWeekly && !settling && !preSeason && (!unscored || scoredThisSeason)
  const showSeasonBlocks = !settling && !unscored

  const sub = unscored
    ? `${start.season} · draft complete · ${
        start.firstWeek?.startDate ? `first games ${formatIsoDate(start.firstWeek.startDate)}` : 'first games: date not known yet'
      }`
    : me && anyGames
      ? `You're ${ordinal(meIndex + 1)} of ${standings.length} at ${record(me)}.`
      : me
        ? "Your season hasn't started yet."
        : undefined

  return (
    <div className="content lh-page">
      <PageHeader
        eyebrow={seasonYear ? `League home · ${seasonYear}` : 'League home'}
        title={leagueName}
        sub={sub}
      />

      {availability.status === 'ok' && isEspn && (
        <EspnRefreshNote
          leagueKey={id}
          lastRefresh={availability.data.lastRefresh}
          canReplaceCookies={availability.data.canReplaceCookies}
        />
      )}
      {availability.status === 'ok' && isEspn && (
        <UnmatchedPlayersNote count={availability.data.unmatchedPlayers} players={availability.data.unmatched} />
      )}

      {preSeason && draft && reasonFor('draftRoom') == null && <DraftBlock draft={draft} auctionRoute={auctionRoute} />}

      <div className="lh-grid">
        {/* "You" first: record, then your latest matchup, then what's next, so all
            three fit the first screen on a phone (SC-005); the league after. */}
        {me && anyGames && <YourSeason me={me} position={meIndex + 1} of={standings.length} />}

        {unscored && start.strength && <PreseasonStrengthBlock strength={start.strength} leagueId={id} />}

        {showMatchup && <MatchupBlock block={weekly} isMember={me != null} leagueId={id} seasonYear={seasonYear} />}

        {analysis.status !== 'idle' && <NextOpponentBlock block={analysis} leagueId={id} />}
        {nextMatchup.status !== 'idle' && (
          <NextMatchupBlock block={nextMatchup} draft={draft} note={unscored && start.sport === 'nba' && !lineupCheckLive ? idleStartersNote(start.firstWeek, start.strength?.available) : null} />
        )}

        {lineupCheck.status === 'ok' && sport && <LineupCheckSection check={lineupCheck.data} leagueId={id} sport={sport} />}

        {unscored && reasonFor('draftRoom') == null && <DraftDoneBlock draftId={draftBoardId} auctionRoute={auctionRoute} />}

        {showSeasonBlocks && (
          <>
            <StandingsBlock block={history} season={anyGames ? season : null} meIndex={meIndex} leagueId={id} />

            <PowerBlock block={power} leagueId={id} seasonYear={seasonYear} />

            {okAwards && <AwardBlock block={awards} leagueId={id} seasonShown={seasonYear} />}
          </>
        )}

        {start?.state === 'IN_SEASON' && start.draft.grades && draftBoardId && reasonFor('draftGrades') == null && (
          <GradesSummaryBlock grades={start.draft.grades} draftId={draftBoardId} auctionRoute={auctionRoute} />
        )}
      </div>

      <SpotlightArea id={id} block={spotlight} weekly={weekly} />

      {/* Spec 035 FR-008: a hidden block says so, in one line, instead of just not being there. */}
      {isEspn && availability.status === 'ok' && (
        <ul className="lh-unavailable">
          {BLOCK_LABELS.map(([key, label]) => {
            const reason = reasonFor(key)
            return reason ? <BlockNote key={key} label={label} reason={reason} /> : null
          })}
        </ul>
      )}
    </div>
  )
}

// --- blocks ------------------------------------------------------------------

/** The spotlight's own error/loading handling: a failure or a 404 here never touches another block. */
function SpotlightArea({ id, block, weekly }: { id: string; block: Block<PlayerSpotlightData>; weekly: Block<WeeklyReport> }) {
  if (block.status === 'idle') return null
  if (block.status === 'loading') {
    return (
      <section className="section lh-spotlight" aria-label="Player spotlight">
        <SkeletonRows count={3} label="Loading player spotlight" />
      </section>
    )
  }
  if (block.status === 'error') {
    if (block.notFound) return null
    return (
      <section className="section lh-spotlight">
        <p className="muted small">Couldn't load the player spotlight.</p>
      </section>
    )
  }
  if (!block.data.applies) return null
  return <PlayerSpotlight sleeperLeagueId={id} spotlight={block.data} weekly={weekly} />
}

function DraftBlock({ draft, auctionRoute }: { draft: DraftSummary; auctionRoute: string | null }) {
  const status = draft.status ?? 'unknown'
  const text =
    status === 'drafting'
      ? 'The draft is under way.'
      : status === 'pre_draft'
        ? "The draft hasn't started."
        : `Draft status: ${status.replace(/_/g, ' ')}.`
  return (
    <section className="section lh-draft" aria-labelledby="lh-draft-h">
      <h2 className="section-title" id="lh-draft-h">
        Draft
      </h2>
      <p>{text}</p>
      <div className="lh-actions">
        {/* The page's one primary action: before the draft there is nothing else to do here. */}
        <Link className="home-hero-cta" to={auctionRoute ?? `/drafts/${draft.sleeperDraftId}`}>
          {auctionRoute ? 'Open the auction board' : 'Open the draft room'}
        </Link>
        {!auctionRoute && (status === 'pre_draft' || status === 'drafting') && (
          <Link className="lh-link" to={`/drafts/${draft.sleeperDraftId}/live`}>
            Follow live
          </Link>
        )}
      </div>
    </section>
  )
}

/** After the draft, before week 1 (spec 030): the draft is done, so say so and link the board. */
function DraftDoneBlock({ draftId, auctionRoute }: { draftId: string | null; auctionRoute: string | null }) {
  return (
    <section className="section lh-draft-done" aria-labelledby="lh-draft-done-h">
      <h2 className="section-title" id="lh-draft-done-h">
        Draft
      </h2>
      <p>The draft is complete.</p>
      {draftId && (
        <Link className="lh-link" to={auctionRoute ?? `/drafts/${draftId}/board`}>
          {auctionRoute ? 'See what everyone paid' : 'View the draft board'}
        </Link>
      )}
    </section>
  )
}

/** Once weeks are scored (spec 030 FR-010): the draft's best and worst grade, early-flagged like everywhere else. */
function GradesSummaryBlock({ grades, draftId, auctionRoute }: { grades: DraftGradeSummary; draftId: string; auctionRoute: string | null }) {
  const line = (tag: string, t: DraftGradeSummary['best']) => (
    <li className="lh-grades-row">
      <span className="lh-grades-tag">{tag}</span>
      <Avatar avatarId={t.avatarId} seed={t.manager ?? tag} label={t.manager} />
      <span className="lh-name" title={t.manager ?? undefined}>
        {t.manager ?? 'Unknown'}
      </span>
      <GradeChip grade={t.grade}>{t.rank != null ? <span className="lh-rec">#{t.rank}</span> : null}</GradeChip>
    </li>
  )
  return (
    <section className="section lh-grades" aria-labelledby="lh-grades-h">
      <h2 className="section-title" id="lh-grades-h">
        Draft grades
      </h2>
      <ol className="row-list lh-rows">
        {line('Best', grades.best)}
        {line('Worst', grades.worst)}
      </ol>
      <p className="small">
        <GradesEarlyBadge early={grades.gradesEarly} />
      </p>
      <Link className="lh-link" to={auctionRoute ?? `/drafts/${draftId}/board`}>
        Draft board and grades
      </Link>
    </section>
  )
}

function YourSeason({ me, position, of }: { me: StandingRow; position: number; of: number }) {
  return (
    <section className="section lh-you" aria-labelledby="lh-you-h">
      <h2 className="section-title" id="lh-you-h">
        Your season
      </h2>
      <div className="lh-hero-nums">
        <div>
          <span className="lh-hero-num cond">{record(me)}</span>
          <span className="lh-hero-label">Record</span>
        </div>
        <div>
          {/* RosterSeasonRepository.forLeague's ORDER BY: final_placement first (nulls last), then wins desc, then points_for desc. */}
          <span
            className="lh-hero-num cond"
            title="Standings order: final placement when the season is over, otherwise wins, then points for"
          >
            {ordinal(position)}
          </span>
          <span className="lh-hero-label">of {of} in the standings</span>
        </div>
      </div>
    </section>
  )
}

function StandingsBlock({
  block,
  season,
  meIndex,
  leagueId,
}: {
  block: Block<LeagueHistory>
  season: SeasonHistory | null
  meIndex: number
  leagueId: string
}) {
  return (
    <section className="section lh-standings" aria-labelledby="lh-standings-h">
      <h2 className="section-title" id="lh-standings-h">
        Standings
      </h2>
      {block.status === 'loading' && <p className="muted small">Loading…</p>}
      {block.status === 'error' && <p className="muted small">Couldn't load the standings.</p>}
      {block.status === 'ok' &&
        (!season || season.standings.length === 0 ? (
          <p className="muted small">Standings appear once the season is under way.</p>
        ) : (
          <>
            <ol className="row-list lh-rows">
              {season.standings.slice(0, 5).map((r, i) => (
                <StandingLine key={r.rosterId} row={r} position={i + 1} />
              ))}
              {/* Outside the top five, you still see yourself, after a gap. */}
              {meIndex >= 5 && (
                <StandingLine key={`me-${season.standings[meIndex].rosterId}`} row={season.standings[meIndex]} position={meIndex + 1} />
              )}
            </ol>
            <Link className="lh-link" to={`/leagues/${leagueId}/history`}>
              Full standings
            </Link>
          </>
        ))}
    </section>
  )
}

function StandingLine({ row, position }: { row: StandingRow; position: number }) {
  return (
    <li className={`lh-row${row.isMe ? ' mine' : ''}`} value={position}>
      <span className="lh-pos">{position}</span>
      <Avatar avatarId={row.avatarId} seed={String(row.managerId ?? row.rosterId)} label={teamOf(row)} isMe={row.isMe} />
      <span className="lh-name" title={teamOf(row)}>
        {teamOf(row)}
      </span>
      <span className="lh-rec">{record(row)}</span>
    </li>
  )
}

function MatchupBlock({
  block,
  isMember,
  leagueId,
  seasonYear,
}: {
  block: Block<WeeklyReport>
  isMember: boolean
  leagueId: string
  seasonYear: number | null
}) {
  if (block.status === 'idle') return null
  let body
  if (block.status === 'loading') body = <p className="muted small">Loading…</p>
  else if (block.status === 'error') body = <p className="muted small">Couldn't load the latest matchup.</p>
  else if (!block.data.available || block.data.week === 0) {
    body = <p className="muted small">No week has been scored yet.</p>
  } else if (seasonYear != null && (block.data.season !== seasonYear || block.data.requestedSeason != null)) {
    // The resolver falls back to the newest PLAYED season; that week is not this season's.
    body = <p className="muted small">No week of {seasonYear} has been scored yet.</p>
  } else {
    const m = block.data.matchups.find((x) => x.home.isMe || x.away.isMe)
    if (!m) {
      // Not in the league: no "you" block at all. In it but idle that week: say so.
      if (!isMember) return null
      body = <p className="muted small">You had no game in week {block.data.week}.</p>
    } else {
      const mine = m.home.isMe ? m.home : m.away
      const theirs = m.home.isMe ? m.away : m.home
      const final = block.data.weekFinal
      // A live game is not a result: Leading/Trailing/Level until the week is final.
      const result = matchupWord(mine.points, theirs.points, final)
      body = (
        <>
          <div className="lh-matchup">
            <div className="lh-side mine">
              <Avatar avatarId={mine.avatarId} seed={String(mine.rosterId)} label={mine.teamName} isMe />
              <span className="lh-name" title={mine.teamName}>
                {mine.teamName}
              </span>
              <span className="lh-pts">{mine.points.toFixed(2)}</span>
            </div>
            <div className="lh-side">
              <Avatar avatarId={theirs.avatarId} seed={String(theirs.rosterId)} label={theirs.teamName} />
              <span className="lh-name" title={theirs.teamName}>
                {theirs.teamName}
              </span>
              <span className="lh-pts">{theirs.points.toFixed(2)}</span>
            </div>
          </div>
          <p className="small">
            <span className={`lh-result ${result.toLowerCase()}`}>{result}</span> in week {block.data.week}
            {final ? '' : ' · in progress'}
          </p>
        </>
      )
    }
  }
  return (
    <section className="section lh-latest" aria-labelledby="lh-latest-h">
      <h2 className="section-title" id="lh-latest-h">
        Latest matchup
      </h2>
      {body}
      <Link className="lh-link" to={`/leagues/${leagueId}/weekly-report`}>
        Matchups &amp; awards
      </Link>
    </section>
  )
}

function NextOpponentBlock({ block, leagueId }: { block: Block<LeagueAnalysis>; leagueId: string }) {
  if (block.status === 'idle') return null
  let body
  if (block.status === 'loading') body = <p className="muted small">Loading…</p>
  else if (block.status === 'error') body = <p className="muted small">Couldn't load the next matchup.</p>
  else {
    const analysisData = block.data
    const mu: AnalysisMatchups = analysisData.matchups
    const game = mu.available ? mu.matchups.find((g) => g.sides.some((s) => s.isMe)) : undefined
    if (!game) {
      // Non-member or bye week: nothing to say about "you", and no error either.
      if (!mu.available && mu.reason) body = <p className="muted small">{mu.reason}</p>
      else return null
    } else {
      const mine = game.sides.find((s) => s.isMe)!
      const theirs = game.sides.find((s) => !s.isMe)
      const nameFor = (rosterId: number, fallback: string | null) =>
        analysisData.teams.find((t) => t.rosterId === rosterId)?.teamName || fallback || `roster ${rosterId}`
      body = theirs ? (
        <>
          <p>
            Week {mu.week} against{' '}
            <strong>
              <PersonName teamName={nameFor(theirs.rosterId, theirs.manager)} username={theirs.manager} />
            </strong>
          </p>
          <p className="muted small">
            Projected {mine.projected.toFixed(1)} to {theirs.projected.toFixed(1)}. A projection, not a result.
          </p>
        </>
      ) : (
        <p className="muted small">You have a bye in week {mu.week}.</p>
      )
    }
  }
  return (
    <section className="section lh-next" aria-labelledby="lh-next-h">
      <h2 className="section-title" id="lh-next-h">
        Next opponent
      </h2>
      {body}
      <Link className="lh-link" to={`/leagues/${leagueId}/analysis`}>
        Team strength
      </Link>
    </section>
  )
}

/**
 * Basketball's next opponent (specs/017 US3): the pairing from getNextMatchup.
 * No projected line, since basketball has no projection source wired up here.
 * Names go through the same fallback as the football block: team name, then
 * username, then "roster N". Nothing renders when `me` is null (not a member),
 * the same contract as the football block.
 */
function NextMatchupBlock({
  block,
  draft,
  note,
}: {
  block: Block<NextMatchup>
  draft: DraftSummary | null
  /** Before week 1 (spec 030): which of your projected starters have no scheduled game. */
  note?: ReactNode
}) {
  if (block.status === 'idle') return null
  let body
  if (block.status === 'loading') body = <p className="muted small">Loading…</p>
  else if (block.status === 'error') body = <p className="muted small">Couldn't load the next matchup.</p>
  else {
    const m = block.data
    if (!m.available) {
      if (!m.reason) return null
      body = <p className="muted small">{m.reason}</p>
    } else if (!m.me) {
      return null
    } else if (!m.opponent) {
      body = <p className="muted small">You have a bye in week {m.week}.</p>
    } else {
      body = (
        <p>
          Week {m.week} against{' '}
          <strong>
            <PersonName
              teamName={m.opponent.teamName}
              username={m.opponent.username}
              fallback={`roster ${m.opponent.rosterId}`}
            />
          </strong>
        </p>
      )
    }
    // Only beside a real pairing or bye: a non-member (returned above) gets no "you" text.
    if (note) body = <>{body}{note}</>
  }
  // This block is basketball's, and its link stays the schedule grid even though basketball
  // now has an Analysis page (spec 030 S1): the page it summarises is the pairing, and the
  // strength block has its own link to Analysis.
  const target = draft ? scheduleLink(draft) : null
  return (
    <section className="section lh-next" aria-labelledby="lh-next-h">
      <h2 className="section-title" id="lh-next-h">
        Next opponent
      </h2>
      {body}
      {target && (
        <Link className="lh-link" to={target.to}>
          {target.label}
        </Link>
      )}
    </section>
  )
}

/**
 * "Which of my projected starters have no game in week 1" (spec 030 FR-010). Scheduled games
 * only: it makes no claim about points, since Sleeper credits one game a week however many
 * a player has.
 */
function idleStartersNote(first: FirstWeek | null, strengthAvailable: boolean | undefined): ReactNode {
  const swg = first?.startersWithoutGame
  if (strengthAvailable === false) return null // no lineup, so no starters to speak of
  if (!swg) {
    return (
      <p className="muted small lh-next-note">
        {first?.week != null ? `The week ${first.week} schedule isn't loaded yet.` : "The first week's schedule isn't loaded yet."}
      </p>
    )
  }
  if (swg.mine == null) return null // no roster or no projected starters: nothing to say
  if (swg.mine.length === 0) {
    return <p className="small lh-next-note">All your projected starters play in week {swg.week}.</p>
  }
  const n = swg.mine.length
  return (
    <div className="lh-next-note">
      <p className="small">
        {n} of your projected starters {n === 1 ? 'has' : 'have'} no game in week {swg.week}
      </p>
      <p className="muted small">
        {swg.mine.map((p) => (p.team ? `${p.name} (${p.team})` : p.name)).join(', ')}. Scheduled games only.
      </p>
    </div>
  )
}

function scheduleLink(draft: DraftSummary): { to: string; label: string } | null {
  const d = destinationsFor({ lineage: { current: draft, seasons: [draft] }, season: draft }).find(
    (x) => x.key === 'schedule',
  )
  return d ? { to: d.href({ lineage: { current: draft, seasons: [draft] }, season: draft }), label: labelOf(d, draft) } : null
}

function PowerBlock({ block, leagueId, seasonYear }: { block: Block<PowerRankings>; leagueId: string; seasonYear: number | null }) {
  // The hero is the newest season that has a vote; if that is not the season shown, this season has none.
  const heroSeason = block.status === 'ok' ? leagueVoteHero(block.data.entries)?.season : undefined
  const otherSeason = heroSeason != null && seasonYear != null && heroSeason !== seasonYear
  const headline = block.status === 'ok' && !otherSeason ? powerHeadline(block.data) : null
  return (
    <section className="section lh-power" aria-labelledby="lh-power-h">
      <h2 className="section-title" id="lh-power-h">
        Power rankings
      </h2>
      {block.status === 'loading' && <p className="muted small">Loading…</p>}
      {block.status === 'error' && <p className="muted small">Couldn't load the power rankings.</p>}
      {block.status === 'ok' &&
        (headline ? <p className="lh-headline">{headline}</p> : <p className="muted small">{otherSeason ? `The ${seasonYear} league vote hasn't started yet.` : "The league vote hasn't started yet."}</p>)}
      <Link className="lh-link" to={`/leagues/${leagueId}/power`}>
        All power rankings
      </Link>
    </section>
  )
}

function AwardBlock({
  block,
  leagueId,
  seasonShown,
}: {
  block: Block<SuperlativesResponse>
  leagueId: string
  seasonShown: number | null
}) {
  // The awards endpoint can fall back to an earlier season when this one has no
  // games yet; say which season the award is from rather than pass it off as this one.
  const fromOtherSeason = block.status === 'ok' && seasonShown != null && block.data.season !== seasonShown
  const top =
    block.status === 'ok' && block.data.available
      ? block.data.superlatives.find((s) => s.available && s.holders.length > 0)
      : undefined
  return (
    <section className="section lh-award" aria-labelledby="lh-award-h">
      <h2 className="section-title" id="lh-award-h">
        Awards
      </h2>
      {block.status === 'loading' && <p className="muted small">Loading…</p>}
      {block.status === 'error' && <p className="muted small">Couldn't load the awards.</p>}
      {block.status === 'ok' &&
        (top ? (
          <>
            <p className="lh-award-title">{TITLES[top.kind]?.title ?? top.kind}</p>
            {fromOtherSeason && block.status === 'ok' && (
              <p className="muted small">From the {block.data.season} season; {seasonShown} has no games yet.</p>
            )}
            <p>
              {top.holders.map((h) => h.teamName).join(', ')}
              {top.value != null && (
                <span className="muted">
                  {' '}
                  · {Math.round(top.value * 100) / 100} {unitLabel(top.unit)}
                </span>
              )}
            </p>
            {top.early && <p className="sl-early small">early — this is mostly noise</p>}
          </>
        ) : (
          <p className="muted small">No awards yet.</p>
        ))}
      <Link className="lh-link" to={`/leagues/${leagueId}/superlatives`}>
        All awards
      </Link>
    </section>
  )
}
