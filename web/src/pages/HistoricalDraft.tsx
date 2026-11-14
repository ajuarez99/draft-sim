import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  abandonHistoricalGame,
  getHistoricalDaily,
  getHistoricalProfile,
  historicalSeasons,
  listHistoricalGames,
  startHistoricalDaily,
  startHistoricalGame,
  type HistoricalDaily,
  type HistoricalGameSummary,
  type HistoricalProfile,
  type HistoricalSport,
  type HistoricalSeasonSummary,
} from '../api'
import { useFailure } from '../useFailure'
import HowThisWorks from '../components/HowThisWorks'
import HistoricalProfileCard from '../components/HistoricalProfileCard'
import { sportOf, summaryStatus } from '../historicalCopy'
import { clearDraftQueue } from '../useDraftQueue'
import { countdown, dayLabel, landingStats } from '../historicalDaily'

function when(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

const SPORT_LABEL: Record<HistoricalSport, string> = { NBA: 'Basketball', NFL: 'Football' }

/** The offered football range, from the seasons the server actually offers (never a literal): "2000 to 2025". */
function nflRange(rows: HistoricalSeasonSummary[]): string {
  if (rows.length === 0) return ''
  const starts = rows.map((r) => r.seasonStart)
  return `${Math.min(...starts)} to ${Math.max(...starts)}`
}

/** Per-sport wording. Football never mentions the NBA, 1970-71 or a draft-position feed. */
const COPY: Record<HistoricalSport, (range: string) => { sub: string; scoring: string; how: string }> = {
  NBA: () => ({
    sub: 'Draft a team from a real NBA season, then watch that season play out.',
    scoring: "It runs as lock-in best ball: each starter's best game of the week counts, and your best legal lineup is set for you.",
    how: "Seasons run from 1970-71 to the latest complete one. Only stats the league tracked for essentially the whole season count, and each season says which. Opponents draft from last season's points, not from a real draft-position feed.",
  }),
  NFL: (range) => ({
    sub: `Draft a team from a real NFL season${range ? `, ${range},` : ''} then watch that season play out.`,
    scoring:
      'It runs as weekly best ball on real full-PPR scores: your best legal lineup is set for you each week. Quarterbacks, running backs, wide receivers and tight ends only; no kickers or defenses.',
    how: `Seasons run${range ? ` from ${range}` : ' over the years on offer'}. Scores come from the real weekly stat lines. Opponents draft from last season's points over replacement, not from a real draft-position feed.`,
  }),
}

export default function HistoricalDraft() {
  const navigate = useNavigate()
  const { error, fail, setError } = useFailure()
  const [games, setGames] = useState<HistoricalGameSummary[] | null>(null)
  const [nbaRows, setNbaRows] = useState<HistoricalSeasonSummary[] | null>(null)
  const [nflRows, setNflRows] = useState<HistoricalSeasonSummary[] | null>(null)
  // Spec 064: a failed profile request shows nothing, so the page looks as it did before achievements.
  const [profile, setProfile] = useState<HistoricalProfile | null>(null)
  const [seasonsFailed, setSeasonsFailed] = useState(false)
  const [starting, setStarting] = useState(false)
  // Spec 071. A failed request (an old backend has no such route) shows no game of the day, and nothing else changes.
  const [daily, setDaily] = useState<HistoricalDaily[] | null>(null)
  const [now, setNow] = useState(() => Date.now())
  // Basketball is only the preselected tab; every request still carries the sport explicitly.
  const [chosen, setSport] = useState<HistoricalSport>('NBA')

  useEffect(() => {
    listHistoricalGames()
      .then(setGames)
      .catch((e) => fail(e))
  }, [])

  useEffect(() => {
    let current = true
    getHistoricalProfile()
      .then((p) => current && setProfile(p))
      .catch(() => {})
    return () => {
      current = false
    }
  }, [])

  function loadDaily() {
    getHistoricalDaily()
      .then((d) => setDaily(Array.isArray(d) ? d : null))
      .catch(() => setDaily(null))
  }
  useEffect(loadDaily, [])

  // The countdown ticks twice a minute; when the day rolls over, ask for the new day's game.
  const nextAt = daily && daily.length > 0 ? daily[0].nextAt : null
  useEffect(() => {
    if (!nextAt) return
    const t = window.setInterval(() => {
      const at = Date.now()
      setNow(at)
      if (new Date(nextAt).getTime() <= at) loadDaily()
    }, 30_000)
    return () => window.clearInterval(t)
  }, [nextAt])

  // The settings line is the same for every season of a sport; a failure here only loses that line.
  // A 053 backend ignores ?sport= and returns basketball rows with no `sport` field, so football rows are kept only
  // when they say NFL themselves: that is what decides whether the Football choice is offered at all (it also stays
  // hidden until the post-deploy NFLVERSE import has put at least one season in).
  useEffect(() => {
    let current = true
    historicalSeasons('NBA')
      .then((x) => current && setNbaRows(x.filter((r) => sportOf(r) === 'NBA')))
      .catch(() => {
        if (!current) return
        setSeasonsFailed(true)
        setNbaRows([])
      })
    historicalSeasons('NFL')
      .then((x) => current && setNflRows(x.filter((r) => r.sport === 'NFL')))
      .catch(() => {
        if (!current) return
        setSeasonsFailed(true)
        setNflRows([])
      })
    return () => {
      current = false
    }
  }, [])

  // A sport is offered only once the server lists at least one of its seasons: prod ran with football imported
  // and basketball not (2026-11-08), and the Basketball tab then failed every start. If a seasons request failed we
  // can't tell, so the old behaviour stands (basketball preselected, start allowed) rather than locking the page.
  const loaded = nbaRows !== null && nflRows !== null
  const offered = (['NBA', 'NFL'] as const).filter((sp) => ((sp === 'NFL' ? nflRows : nbaRows) ?? []).length > 0)
  const sport: HistoricalSport = offered.includes(chosen) || seasonsFailed ? chosen : (offered[0] ?? chosen)
  const nothingOffered = loaded && !seasonsFailed && offered.length === 0
  const seasons = sport === 'NFL' ? nflRows : nbaRows
  const copy = COPY[sport](nflRange(nflRows ?? []))

  async function start() {
    if (starting) return
    setStarting(true)
    setError(null)
    try {
      const g = await startHistoricalGame(sport)
      navigate(`/history/${g.id}`)
    } catch (e) {
      fail(e)
      setStarting(false)
    }
  }

  async function startDaily(sp: HistoricalSport) {
    if (starting) return
    setStarting(true)
    setError(null)
    try {
      const g = await startHistoricalDaily(sp)
      navigate(`/history/${g.id}`)
    } catch (e) {
      fail(e)
      setStarting(false)
    }
  }

  async function abandon(id: number) {
    setError(null)
    try {
      await abandonHistoricalGame(id)
      clearDraftQueue(id)
      setGames((gs) => (gs ?? []).map((g) => (g.id === id ? { ...g, status: 'ABANDONED' } : g)))
    } catch (e) {
      fail(e)
    }
  }

  const settingsSource = seasons && seasons.length > 0 ? seasons[0].settingsSource : null
  const visible = (games ?? []).filter((g) => g.status !== 'ABANDONED')
  const stats = landingStats(visible)
  const left = nextAt ? countdown(nextAt, now) : null

  // A first-timer reads the rules right under the button; once there are games, the games come first.
  const howToPlay = (
    <section className="tm-section" aria-label="How to play">
      <h2>How to play</h2>
      <ol className="tm-steps">
        <li>
          <strong>Draft your team</strong>
          <span>You only see what each player did the year before, like a real drafter would have.</span>
        </li>
        <li>
          <strong>The year plays out</strong>
          <span>{copy.scoring}</span>
        </li>
        <li>
          <strong>See how you did</strong>
          <span>Your finish, the picks you nailed, the ones you missed, and how you stack up against hindsight.</span>
        </li>
      </ol>
      <HowThisWorks>
        <p>{copy.how}</p>
        {settingsSource && <p>{settingsSource}</p>}
      </HowThisWorks>
    </section>
  )

  return (
    <div className="content tm tm-page">
      <header className="tm-hero">
        <span className="tm-mark" aria-hidden="true">
          <svg viewBox="0 0 48 48" width="34" height="34" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round">
            <path d="M9 24a15 15 0 1 0 5-11.2" />
            <path d="M8 7v7h7" />
            <path d="M24 16v9l6 4" />
          </svg>
        </span>
        <h1 className="tm-title">Time Machine</h1>
        <p className="tm-tagline">{copy.sub}</p>
        {daily && daily.length > 0 && (
          <section className="tm-daily" aria-label="Game of the day">
            <h2>Game of the day</h2>
            <ul className="tm-daily-cards">
              {daily.map((d) => (
                <li key={d.sport} className={`tm-daily-card${d.gameStatus === 'COMPLETE' ? ' done' : ''}`}>
                  <div className="tm-daily-text">
                    <span className="tm-daily-sport">{SPORT_LABEL[d.sport]}</span>
                    <strong className="tm-daily-season">{d.seasonLabel}</strong>
                    {d.gameStatus === 'COMPLETE' ? (
                      <span className="tm-daily-state">
                        {d.draftScore != null ? `Done. Draft score ${d.draftScore}` : 'Done'}
                      </span>
                    ) : d.gameStatus === 'DRAFTING' ? (
                      <span className="tm-daily-state">You are mid-draft</span>
                    ) : (
                      <span className="tm-daily-state">Not played yet</span>
                    )}
                    {d.streak > 0 && (
                      <span className="tm-daily-streak">
                        {d.streak}-day streak
                      </span>
                    )}
                  </div>
                  {d.gameStatus === 'COMPLETE' && d.gameId != null ? (
                    <Link className="tm-btn quiet" to={`/history/${d.gameId}`} aria-label={`View today's ${SPORT_LABEL[d.sport].toLowerCase()} game`}>
                      View
                    </Link>
                  ) : d.gameStatus === 'DRAFTING' && d.gameId != null ? (
                    <Link className="tm-btn" to={`/history/${d.gameId}`} aria-label={`Resume today's ${SPORT_LABEL[d.sport].toLowerCase()} game`}>
                      Resume
                    </Link>
                  ) : (
                    <button
                      type="button"
                      className="tm-btn"
                      disabled={starting}
                      onClick={() => void startDaily(d.sport)}
                      aria-label={`Play today's ${SPORT_LABEL[d.sport].toLowerCase()} game`}
                    >
                      Play
                    </button>
                  )}
                </li>
              ))}
            </ul>
            <p className="muted small tm-daily-note">
              {dayLabel(daily[0].date)}: the same season for everyone, from a random draft slot.
              {left ? ` Next one in ${left}.` : ''}
            </p>
          </section>
        )}
        {offered.length > 1 && (
          <div className="tm-sports" role="group" aria-label="Sport">
            {offered.map((sp) => (
              <button
                key={sp}
                type="button"
                className={`tm-sport${sp === sport ? ' on' : ''}`}
                aria-pressed={sp === sport}
                disabled={starting}
                onClick={() => setSport(sp)}
              >
                {SPORT_LABEL[sp]}
              </button>
            ))}
          </div>
        )}
        <button type="button" className="tm-play" onClick={() => void start()} disabled={starting || !loaded || nothingOffered}>
          {starting ? 'Starting…' : 'Draft a random year'}
        </button>
        {error && <div className="error">{error}</div>}
        {nothingOffered && <p className="muted">No seasons are loaded yet, so there is nothing to draft.</p>}
      </header>

      {visible.length === 0 && howToPlay}

      <section className="tm-section" aria-label="Your games">
        <h2>Your games</h2>
        {games == null && !error ? (
          <p className="muted">Loading…</p>
        ) : visible.length === 0 ? (
          <p className="muted">No games yet. Your first one will show up here.</p>
        ) : (
          <>
          {stats.finished > 0 && (
            <dl className="tm-stats" aria-label="Your numbers">
              <div>
                <dt>Finished</dt>
                <dd>{stats.finished}</dd>
              </div>
              {stats.best != null && (
                <div>
                  <dt>Best draft score</dt>
                  <dd>{stats.best}</dd>
                </div>
              )}
              {stats.average != null && (
                <div>
                  <dt>Average draft score</dt>
                  <dd>{stats.average}</dd>
                </div>
              )}
            </dl>
          )}
          {stats.finished > 0 && stats.scored > 0 && stats.scored < stats.finished && (
            <p className="muted small tm-stats-note">
              The scores cover {stats.scored} of your {stats.finished} finished games; older ones were saved without one.
            </p>
          )}
          <ul className="tm-games">
            {visible.map((g) => (
              <li key={g.id} className="tm-game">
                <div className="tm-game-text">
                  <strong>{g.seasonLabel}</strong>
                  <span className="tm-game-sport">{SPORT_LABEL[sportOf(g)]}</span>
                  <span className={`tm-game-status status-${g.status.toLowerCase()}`}>{summaryStatus(g)}</span>
                  {g.status === 'COMPLETE' && g.draftScore != null && <span className="tm-game-score">Score {g.draftScore}</span>}
                  {g.dailyDate ? (
                    <span className="tm-game-daily">Daily · {dayLabel(g.dailyDate)}</span>
                  ) : (
                    <span className="muted small">{when(g.createdAt)}</span>
                  )}
                </div>
                <div className="tm-game-actions">
                  {g.status === 'DRAFTING' && (
                    <>
                      {/* The game of the day cannot be abandoned (the server refuses it): it is the day's one go. */}
                      {!g.dailyDate && (
                        <button type="button" className="tm-btn quiet" onClick={() => void abandon(g.id)}>
                          Abandon
                        </button>
                      )}
                      <Link className="tm-btn" to={`/history/${g.id}`}>
                        Resume
                      </Link>
                    </>
                  )}
                  {g.status === 'COMPLETE' && (
                    <Link className="tm-btn quiet" to={`/history/${g.id}`}>
                      View
                    </Link>
                  )}
                </div>
              </li>
            ))}
          </ul>
          </>
        )}
      </section>

      {profile && <HistoricalProfileCard profile={profile} />}

      {visible.length > 0 && howToPlay}
    </div>
  )
}
