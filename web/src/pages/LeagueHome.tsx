import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import NotFound from '../components/NotFound'
import PersonName from '../components/PersonName'
import {
  fetchSuperlatives,
  getLeagueAnalysis,
  getLeagueHistory,
  getPowerRankings,
  getWeeklyReport,
  type AnalysisMatchups,
  type DraftSummary,
  type LeagueAnalysis,
  type LeagueHistory,
  type PowerRankings,
  type SeasonHistory,
  type Sport,
  type StandingRow,
  type SuperlativesResponse,
  type WeeklyReport,
} from '../api'
import { isNotFound } from '../apiError'
import { LEAGUE_DESTINATIONS } from '../destinations'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { cachedDrafts } from '../railLeague'
import { ordinal } from '../rankOrder'
import { buildHeadline, computeWeeklyStory, leagueVoteHero } from './PowerRankings'
import { TITLES } from './Superlatives'

/**
 * specs/013 US4: League home, the page a league's name leads to.
 *
 * Composed client-side from endpoints that already exist, one block per
 * endpoint, so a slow or failing one costs its own block and nothing else:
 *
 *   standings + your record + position  <- getLeagueHistory  (both sports)
 *   latest matchup                      <- getWeeklyReport(id, 0) via WeeklySide.isMe  (both sports)
 *   next opponent                       <- getLeagueAnalysis matchups isMe  (football only)
 *   power headline                      <- getPowerRankings, via PowerRankings' own
 *                                          computeWeeklyStory + buildHeadline
 *   top award                           <- fetchSuperlatives
 *
 * "You" is only ever a server-side fact (`isMe` on a standings row, a weekly
 * side, an analysis side), never a client-side name match. A reader who is not
 * in the league gets no "you" blocks and no error.
 */

type Block<T> = { status: 'idle' } | { status: 'loading' } | { status: 'error'; notFound: boolean } | { status: 'ok'; data: T }

/** One endpoint, one block. `load` null means "this block does not apply"
 *  (idle): rendered as nothing, never as an empty state. */
function useBlock<T>(load: (() => Promise<T>) | null, deps: readonly unknown[]): Block<T> {
  const [state, setState] = useState<Block<T>>(load ? { status: 'loading' } : { status: 'idle' })
  useEffect(() => {
    if (!load) {
      setState({ status: 'idle' })
      return
    }
    let cancelled = false
    setState({ status: 'loading' })
    load()
      .then((data) => {
        if (!cancelled) setState({ status: 'ok', data })
      })
      .catch((e) => {
        if (!cancelled) setState({ status: 'error', notFound: isNotFound(e) })
      })
    return () => {
      cancelled = true
    }
    // `load` is a fresh closure every render; `deps` is the real identity of the request.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return state
}

/** Football-only on the destination table's own rule, not a second copy of it. */
function analysisOffered(sport: Sport): boolean {
  return LEAGUE_DESTINATIONS.find((d) => d.key === 'analysis')?.sports.includes(sport) ?? false
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
  const history = useBlock(id ? () => getLeagueHistory(id) : null, [id, version])
  const weekly = useBlock(id ? () => getWeeklyReport(id, 0) : null, [id, version])
  const power = useBlock(id ? () => getPowerRankings(id) : null, [id, version])
  const awards = useBlock(id ? () => fetchSuperlatives(id) : null, [id, version])

  const draft: DraftSummary | null =
    drafts.status === 'ok' ? (drafts.data.find((d) => d.sleeperLeagueId === id) ?? null) : null
  const sport: Sport | null = draft?.sport ?? (weekly.status === 'ok' ? weekly.data.sport : null)

  // Football only, and only once the sport is known: guessing it would fetch a
  // projection for a league that has none.
  const analysis = useBlock(sport && analysisOffered(sport) ? () => getLeagueAnalysis(id) : null, [id, sport, version])

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
  const preSeason = draft != null && (draft.status ?? 'unknown') !== 'complete'

  const leagueName = draft?.leagueName ?? season?.name ?? 'League home'
  const seasonYear = draft?.season ?? season?.season ?? null

  return (
    <div className="content lh-page">
      <PageHeader
        eyebrow={seasonYear ? `League home · ${seasonYear}` : 'League home'}
        title={leagueName}
        sub={
          me && anyGames
            ? `You're ${ordinal(meIndex + 1)} of ${standings.length} at ${record(me)}.`
            : me
              ? "Your season hasn't started yet."
              : undefined
        }
      />

      {preSeason && draft && <DraftBlock draft={draft} />}

      <div className="lh-grid">
        {/* "You" first: record, then your latest matchup, then what's next, so all
            three fit the first screen on a phone (SC-005); the league after. */}
        {me && anyGames && <YourSeason me={me} position={meIndex + 1} of={standings.length} />}

        {!preSeason && <MatchupBlock block={weekly} isMember={me != null} leagueId={id} seasonYear={seasonYear} />}

        {analysis.status !== 'idle' && <NextOpponentBlock block={analysis} leagueId={id} />}

        <StandingsBlock block={history} season={anyGames ? season : null} meIndex={meIndex} leagueId={id} />

        <PowerBlock block={power} leagueId={id} seasonYear={seasonYear} />

        <AwardBlock block={awards} leagueId={id} seasonShown={seasonYear} />
      </div>
    </div>
  )
}

// --- blocks ------------------------------------------------------------------

function DraftBlock({ draft }: { draft: DraftSummary }) {
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
        <Link className="home-hero-cta" to={`/drafts/${draft.sleeperDraftId}`}>
          Open the draft room
        </Link>
        {(status === 'pre_draft' || status === 'drafting') && (
          <Link className="lh-link" to={`/drafts/${draft.sleeperDraftId}/live`}>
            Follow live
          </Link>
        )}
      </div>
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
      const result = final
        ? mine.points > theirs.points
          ? 'Won'
          : mine.points < theirs.points
            ? 'Lost'
            : 'Tied'
        : mine.points > theirs.points
          ? 'Leading'
          : mine.points < theirs.points
            ? 'Trailing'
            : 'Level'
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
