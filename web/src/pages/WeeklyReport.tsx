import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import Avatar from '../components/Avatar'
import PlayerFace from '../components/PlayerFace'
import PlayerLink from '../components/PlayerLink'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import RecapCard from '../components/RecapCard'
import {
  getWeeklyReport,
  type WeeklyReport as Data,
  type WeeklyMatchup,
  type WeeklySide,
  type WeeklyPerformer,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import PersonName from '../components/PersonName'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useSeasonLeagueIds } from '../railLeague'

/**
 * specs/004-ffwrapped-feature-parity US5: one week, read back.
 *
 * Matchups are cards rather than table rows on purpose — a matchup is a pair
 * facing each other, not a row of columns, and the layout should look like the
 * thing it describes. The scoreboard and the awards are different shapes of
 * content and get different shapes of container.
 *
 * Awards that could not be computed are RENDERED, not dropped. An award that
 * simply fails to appear is indistinguishable from one where nobody qualified,
 * and only one of those is worth knowing about.
 */
export default function WeeklyReport() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  // The chosen week lives in the URL so a link reproduces the view. Absent (or not a
  // whole number >= 1) means "the latest scored week", which the server resolves.
  const [searchParams, setSearchParams] = useSearchParams()
  const parsed = Number(searchParams.get('week'))
  const requestedWeek = Number.isInteger(parsed) && parsed >= 1 ? parsed : null
  const [data, setData] = useState<Data | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [loading, setLoading] = useState(false)
  // Bumped by the rail when this league's background refresh finishes (specs/009-auto-data-refresh).
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)
  const leagueIdForSeason = useSeasonLeagueIds(sleeperLeagueId)

  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    getWeeklyReport(sleeperLeagueId, requestedWeek ?? 0)
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch((e) => {
        if (!cancelled) fail(e)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId, requestedWeek, dataVersion])

  const latest = data?.latestScoredWeek ?? 0
  // Links out of a row name the league of the season the data came from: the page can show an earlier
  // season than the route's (a requestedSeason fallback), and the player page opens that season's games.
  const dataLeagueId = data ? leagueIdForSeason(data.season) : (sleeperLeagueId ?? '')

  // A link (or a stale bookmark) past the last scored week is pulled back to it rather
  // than left showing "week 99 has not been scored".
  useEffect(() => {
    if (requestedWeek != null && latest > 0 && requestedWeek > latest) {
      const next = new URLSearchParams(searchParams)
      next.set('week', String(latest))
      setSearchParams(next, { replace: true })
    }
  }, [requestedWeek, latest, searchParams, setSearchParams])

  // The week on screen: what the server resolved, else the URL's, else the latest.
  const shown = data?.week || requestedWeek || latest

  function goToWeek(n: number) {
    const clamped = Math.min(latest, Math.max(1, n))
    const next = new URLSearchParams(searchParams)
    next.set('week', String(clamped))
    setSearchParams(next)
  }

  // The first matchup with a side that is the signed-in reader's own. Absent (signed out,
  // not in the league, or a bye week) means the page keeps its original order.
  const mineIdx = data?.available ? data.matchups.findIndex((m) => m.home.isMe || m.away.isMe) : -1
  const mine = mineIdx >= 0 && data ? data.matchups[mineIdx] : null
  const others = data ? data.matchups.filter((_, i) => i !== mineIdx) : []

  if (notFound) return <NotFound what="league" />

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Matchups & awards"
        sub="Who won each matchup this week, and the awards nobody wants — read back from what actually happened."
        actions={
          // Nothing scored means no week to pick, so no stepper rather than a week 0.
          latest > 0 ? (
            <div className="wr-stepper" role="group" aria-label="Choose week">
              <button type="button" aria-label="Previous week" disabled={shown <= 1} onClick={() => goToWeek(shown - 1)}>
                ‹
              </button>
              <span className="wr-stepper-label" aria-live="polite">
                Week {shown}
              </span>
              <button type="button" aria-label="Next week" disabled={shown >= latest} onClick={() => goToWeek(shown + 1)}>
                ›
              </button>
            </div>
          ) : undefined
        }
      />

      {error && (
        <div className="error">
          <span>{error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="section">
          <h3 className="section-title">
            {data.week === 0 ? 'No week has been scored yet' : `Week ${data.week} has not been scored`}
          </h3>
          <p className="muted small">
            {data.reason ?? 'No results stored for this week yet.'}
          </p>
        </section>
      )}

      {data && data.available && (
        <>
          {!data.weekFinal && (
            <p className="wr-inprogress small" role="status">
              <strong>In progress</strong> — scores can still change until the week closes.
            </p>
          )}
          {/* Which week this is when the reader did not choose one: the server picked the newest
              FINAL week, and a later one may be under way. */}
          {requestedWeek == null && data.weekFinal && data.week === data.latestFinalWeek && (
            <p className="wr-weeknote small">
              <span>Week {data.week} · latest final week</span>
              {data.latestScoredWeek > data.week && (
                <Link className="lh-link" to={`?week=${data.latestScoredWeek}`}>
                  Week {data.latestScoredWeek} in progress →
                </Link>
              )}
            </p>
          )}
          {sleeperLeagueId && <RecapCard sleeperLeagueId={sleeperLeagueId} week={data.week} />}
          <section className="section">
            <h3 className="section-title">Week {data.week} matchups</h3>
            <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />
            {mine ? (
              <>
                <Scoreboard game={mine} />
                {others.length > 0 && (
                  <div className="wr-games wr-strip">
                    {others.map((m, i) => (
                      <Game key={i} game={m} />
                    ))}
                  </div>
                )}
              </>
            ) : (
              <div className="wr-games">
                {data.matchups.map((m, i) => (
                  <Game key={i} game={m} />
                ))}
              </div>
            )}
          </section>

          {mine ? (
            <WeekInReview data={data} leagueId={dataLeagueId} />
          ) : (
            <>
              <section className="section">
                <h3 className="section-title">Weekly awards</h3>
                {data.awards.length === 0 ? (
                  <p className="muted small">Nobody qualified for an award this week.</p>
                ) : (
                  <div className="wr-awards row-list">
                    {data.awards.map((a) => (
                      <article key={a.kind} className="wr-award">
                        <h4>{titleOf(a.kind)}</h4>
                        <p className="wr-award-team">{a.teamName}</p>
                        <p className="muted small">{a.detail}</p>
                      </article>
                    ))}
                  </div>
                )}
                <Omitted data={data} />
              </section>

              {!data.playersPlayMultiplePerPeriod && (
                <section className="section">
                  <h3 className="section-title">Top performers</h3>
                  <ol className="wr-performers">
                    {(data.topPerformers ?? []).map((p) => (
                      <li key={p.playerId}>
                        <PerformerLine p={p} sport={data.sport} leagueId={dataLeagueId} />
                      </li>
                    ))}
                  </ol>
                </section>
              )}
            </>
          )}

          {data.playersPlayMultiplePerPeriod && <Rankings data={data} leagueId={dataLeagueId} />}
        </>
      )}
    </div>
  )
}

/** Awards that could not be computed are rendered, with the reason, not dropped. */
function Omitted({ data }: { data: Data }) {
  if (data.awardsOmitted.length === 0) return null
  return (
    <ul className="muted small wr-omitted">
      {data.awardsOmitted.map((o) => (
        <li key={o.kind}>
          <strong>{titleOf(o.kind)}</strong> could not be worked out for this week:{' '}
          {o.reason === 'STARTERS_NOT_STORED'
            ? 'this week was stored before the app recorded which players were actually started, so naming the swap would be a guess.'
            : o.reason}
        </li>
      ))}
    </ul>
  )
}

/**
 * Awards and (football) top performers as one list of one-line records, used when the
 * reader's own matchup leads the page. The same facts as the two sections it replaces.
 */
function WeekInReview({ data, leagueId }: { data: Data; leagueId: string }) {
  const performers = data.playersPlayMultiplePerPeriod ? [] : (data.topPerformers ?? [])
  return (
    <section className="section">
      <h3 className="section-title">Week in review</h3>
      {data.awards.length === 0 && performers.length === 0 ? (
        <p className="muted small">Nobody qualified for an award this week.</p>
      ) : (
        <ul className="wr-review row-list">
          {data.awards.map((a) => (
            <li key={a.kind} className="wr-award">
              <span className="wr-review-kind">{titleOf(a.kind)}</span>
              <span className="wr-award-team">{a.teamName}</span>
              <span className="muted small">{a.detail}</span>
            </li>
          ))}
          {performers.map((p) => (
            <li key={`p-${p.playerId}`} className="wr-perf-row">
              <span className="wr-review-kind">Top performer</span>
              <PerformerLine p={p} sport={data.sport} leagueId={leagueId} />
            </li>
          ))}
        </ul>
      )}
      <Omitted data={data} />
    </section>
  )
}

/**
 * playerId here is a Sleeper player id (WeeklyReportService keys every performer through
 * playersBySleeperId), so the face can use it. The row carries the FANTASY team name, not
 * the player's pro team, so there is no logo step: photo, then initials.
 */
function PerformerLine({ p, sport, leagueId }: { p: WeeklyPerformer; sport: Data['sport']; leagueId: string }) {
  return (
    <>
      <PlayerFace sport={sport} sleeperId={p.playerId} team={null} position={p.position} name={p.playerName} size={24} />
      <span className="wr-pname">
        <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={p.playerId} sport={sport}>
          {p.playerName}
        </PlayerLink>
      </span>
      <span className="wr-ppos">{p.position}</span>
      <span className="wr-pteam muted">{p.teamName}</span>
      <span className="wr-ppts">{p.points.toFixed(2)}</span>
    </>
  )
}

/** The reader's own matchup, full width: both sides, big scores, and the result in words. */
function Scoreboard({ game }: { game: WeeklyMatchup }) {
  const me = game.home.isMe ? game.home : game.away
  const them = game.home.isMe ? game.away : game.home
  const result = me.points > them.points ? 'Won' : me.points < them.points ? 'Lost' : 'Tied'
  const margin = Math.abs(me.points - them.points)
  return (
    <article className="wr-score" aria-label="Your matchup">
      <ScoreSide side={me} isMe />
      <ScoreSide side={them} right />
      <p className="wr-score-result small">
        <span className={`wr-result ${result.toLowerCase()}`}>{result}</span>
        {result !== 'Tied' && <span className="muted"> by {margin.toFixed(2)}</span>}
      </p>
    </article>
  )
}

function ScoreSide({ side, isMe, right }: { side: WeeklySide; isMe?: boolean; right?: boolean }) {
  return (
    <div className={right ? 'wr-score-side right' : 'wr-score-side'}>
      <Avatar
        avatarId={side.avatarId}
        seed={String(side.rosterId)}
        label={side.teamName}
        isMe={isMe}
        className="wr-score-avatar"
      />
      <div className="wr-score-id">
        <span className="wr-score-name" title={side.teamName}>
          <PersonName teamName={side.teamName} username={side.username} />
        </span>
        <span className="wr-record muted">({side.record})</span>
      </div>
      <span className="wr-score-pts">{side.points.toFixed(2)}</span>
    </div>
  )
}

/**
 * The pair, for sports whose players play more than once in a scoring period.
 *
 * Two rankings of the same week that answer different questions: the biggest
 * single night, and the biggest whole week. They disagree often -- a player
 * with four steady games can top the week without owning a single night --
 * and that disagreement is the reason both are here.
 */
function Rankings({ data, leagueId }: { data: Data; leagueId: string }) {
  const missing = (section: 'BEST_NIGHTS' | 'BEST_WEEK') =>
    (data.sectionsUnavailable ?? []).find((s) => s.section === section)
  const nights = data.bestNights ?? []
  const weeks = data.bestWeek ?? []

  return (
    <div className="wr-rankings">
      <section className="section">
        <h3 className="section-title">Best nights</h3>
        {missing('BEST_NIGHTS') ? (
          <Unavailable reason={missing('BEST_NIGHTS')!.reason} />
        ) : (
          <ol className="wr-performers">
            {nights.map((p) => (
              <li key={`${p.playerId}-${p.date}`}>
                <PlayerFace sport={data.sport} sleeperId={p.playerId} team={null} position={p.position} name={p.playerName} size={24} />
                <span className="wr-pname">
        <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={p.playerId} sport={data.sport}>
          {p.playerName}
        </PlayerLink>
      </span>
                <span className="wr-ppos">{p.position}</span>
                <span className="wr-pteam muted">{p.teamName}</span>
                <span className="wr-when muted">{whenLabel(p.date, p.opponent, p.isAway)}</span>
                <span className="wr-ppts">{p.points.toFixed(2)}</span>
              </li>
            ))}
          </ol>
        )}
        <HowThisWorks>
          <p>The biggest single games anyone rostered this week.</p>
        </HowThisWorks>
      </section>

      <section className="section">
        <h3 className="section-title">Best week</h3>
        {/*
          FR-005: this total counts every game the player played, including
          games the league's scoring never counted. Driven by `basis` rather
          than hardcoded, so the page cannot keep claiming it after the
          server stops meaning it.
        */}
        {data.basis === 'ALL_GAMES_PLAYED' && (
          <p className="muted small">
            Every game played, added up — real production, not the points that decided a matchup.
          </p>
        )}
        {missing('BEST_WEEK') ? (
          <Unavailable reason={missing('BEST_WEEK')!.reason} />
        ) : (
          <ol className="wr-performers">
            {weeks.map((p) => (
              <li key={p.playerId}>
                <PlayerFace sport={data.sport} sleeperId={p.playerId} team={null} position={p.position} name={p.playerName} size={24} />
                <span className="wr-pname">
        <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={p.playerId} sport={data.sport}>
          {p.playerName}
        </PlayerLink>
      </span>
                <span className="wr-ppos">{p.position}</span>
                <span className="wr-pteam muted">{p.teamName}</span>
                {/* The denominator, always beside the total: 182.0 means
                    nothing without knowing it covers four games. */}
                <span className="wr-when muted">
                  {p.gamesPlayed} {p.gamesPlayed === 1 ? 'game' : 'games'}
                </span>
                <span className="wr-ppts">{p.totalPoints.toFixed(2)}</span>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  )
}

/** A ranking that could not be filled says why, rather than rendering empty. */
function Unavailable({ reason }: { reason: string }) {
  return (
    <p className="muted small">
      {reason === 'PER_GAME_DETAIL_MISSING'
        ? 'Game-by-game detail has not been stored for this week yet, so this ranking cannot be built. It is not that nobody played.'
        : `This ranking is unavailable (${reason}).`}
    </p>
  )
}

/**
 * "Nov 17 vs CHI". An unknown opponent shows the night alone rather than a
 * guess -- the date is the fact, the opponent is the nicety.
 */
function whenLabel(date: string, opponent?: string | null, isAway?: boolean | null): string {
  const d = new Date(`${date}T00:00:00`)
  const day = Number.isNaN(d.getTime())
    ? date
    : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  if (!opponent) return day
  return `${day} ${isAway ? '@' : 'vs'} ${opponent}`
}

function Game({ game }: { game: WeeklyMatchup }) {
  const homeWon = game.home.points > game.away.points
  const awayWon = game.away.points > game.home.points
  return (
    <article className="wr-game">
      <TeamLine side={game.home} won={homeWon} />
      <TeamLine side={game.away} won={awayWon} />
    </article>
  )
}

function TeamLine({ side, won }: { side: WeeklySide; won: boolean }) {
  return (
    <div className={won ? 'wr-side wr-won' : 'wr-side'}>
      <Avatar
        avatarId={side.avatarId}
        seed={String(side.rosterId)}
        label={side.teamName}
        className="wr-avatar"
      />
      <span className="wr-team"><PersonName teamName={side.teamName} username={side.username} /></span>
      <span className="wr-record muted">({side.record})</span>
      <span className="wr-points">{side.points.toFixed(2)}</span>
    </div>
  )
}

/** SCREAMING_SNAKE on the wire, sentence case on the page. */
function titleOf(kind: string): string {
  const known: Record<string, string> = {
    GOT_AWAY_WITH_IT: 'Got away with it',
    DESERVED_BETTER: 'Deserved better',
    ONE_PLAYER_CARRY: 'One-player carry',
    SELF_INFLICTED_WOUND: 'Self-inflicted wound',
  }
  return known[kind] ?? kind.toLowerCase().replace(/_/g, ' ')
}
