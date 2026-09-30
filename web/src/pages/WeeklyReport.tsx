import { useEffect, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import {
  getWeeklyReport,
  type WeeklyReport as Data,
  type WeeklyMatchup,
  type WeeklySide,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import PersonName from '../components/PersonName'
import { useLeagueDataVersion } from '../leagueDataVersion'

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

  // A link (or a stale bookmark) past the last scored week is pulled back to it rather
  // than left showing "week 99 has not been scored".
  useEffect(() => {
    if (requestedWeek != null && latest > 0 && requestedWeek > latest) {
      const next = new URLSearchParams(searchParams)
      next.set('week', String(latest))
      setSearchParams(next, { replace: true })
    }
  }, [requestedWeek, latest, searchParams, setSearchParams])

  function pickWeek(raw: string) {
    const n = Math.min(latest, Math.max(1, Math.trunc(Number(raw)) || 1))
    const next = new URLSearchParams(searchParams)
    next.set('week', String(n))
    setSearchParams(next)
  }

  if (notFound) return <NotFound what="league" />

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Weekly report"
        sub="Every matchup, the week's best performances, and the awards nobody wants — read back from what actually happened."
        actions={
          // Nothing scored means no week to pick, so no input rather than a week 0.
          latest > 0 ? (
            <div className="wr-weekpick">
              <label htmlFor="wr-week">Week</label>
              <input
                id="wr-week"
                type="number"
                min={1}
                max={latest}
                value={requestedWeek ?? data?.week ?? latest}
                onChange={(e) => pickWeek(e.target.value)}
              />
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
        <section className="panel">
          <h3 className="cond">
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
          <section className="panel">
            <h3 className="cond">Week {data.week} matchups</h3>
            <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />
            <div className="wr-games">
              {data.matchups.map((m, i) => (
                <Game key={i} game={m} />
              ))}
            </div>
          </section>

          <section className="panel">
            <h3 className="cond">Weekly awards</h3>
            {data.awards.length === 0 ? (
              <p className="muted small">Nobody qualified for an award this week.</p>
            ) : (
              <div className="wr-awards">
                {data.awards.map((a) => (
                  <article key={a.kind} className="wr-award">
                    <h4>{titleOf(a.kind)}</h4>
                    <p className="wr-award-team">{a.teamName}</p>
                    <p className="muted small">{a.detail}</p>
                  </article>
                ))}
              </div>
            )}

            {data.awardsOmitted.length > 0 && (
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
            )}
          </section>

          {data.playersPlayMultiplePerPeriod ? (
            <Rankings data={data} />
          ) : (
            <section className="panel">
              <h3 className="cond">Top performers</h3>
              <ol className="wr-performers">
                {(data.topPerformers ?? []).map((p) => (
                  <li key={p.playerId}>
                    <span className="wr-pname">{p.playerName}</span>
                    <span className="wr-ppos">{p.position}</span>
                    <span className="wr-pteam muted">{p.teamName}</span>
                    <span className="wr-ppts">{p.points.toFixed(2)}</span>
                  </li>
                ))}
              </ol>
            </section>
          )}
        </>
      )}
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
function Rankings({ data }: { data: Data }) {
  const missing = (section: 'BEST_NIGHTS' | 'BEST_WEEK') =>
    (data.sectionsUnavailable ?? []).find((s) => s.section === section)
  const nights = data.bestNights ?? []
  const weeks = data.bestWeek ?? []

  return (
    <div className="wr-rankings">
      <section className="panel">
        <h3 className="cond">Best nights</h3>
        <p className="muted small">The biggest single games anyone rostered this week.</p>
        {missing('BEST_NIGHTS') ? (
          <Unavailable reason={missing('BEST_NIGHTS')!.reason} />
        ) : (
          <ol className="wr-performers">
            {nights.map((p) => (
              <li key={`${p.playerId}-${p.date}`}>
                <span className="wr-pname">{p.playerName}</span>
                <span className="wr-ppos">{p.position}</span>
                <span className="wr-pteam muted">{p.teamName}</span>
                <span className="wr-when muted">{whenLabel(p.date, p.opponent, p.isAway)}</span>
                <span className="wr-ppts">{p.points.toFixed(2)}</span>
              </li>
            ))}
          </ol>
        )}
      </section>

      <section className="panel">
        <h3 className="cond">Best week</h3>
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
                <span className="wr-pname">{p.playerName}</span>
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
