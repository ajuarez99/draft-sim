import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getHistoricalDaily, startHistoricalDaily, type HistoricalDaily, type HistoricalSport } from '../api'
import { countdown, dailyName, dayLabel } from '../historicalDaily'
import * as copy from '../redraftCopy'
import { SPORTS } from '../redraftPages'
import { tmGame } from '../site'
import { currentSite } from '../siteEnv'

// Spec 074 T038 (extracted from HistoricalDraft, which the home and /daily now share). Markup for the loaded state is
// what the home page rendered before; the placeholder and error states are new. The <ul> carries `data-dynamic`,
// the marker the static-versus-React parity test excludes: today's season and the visitor's progress are not in
// the static file, so the two cannot match there and the test says so by name rather than loosening.

export type DailyStatus = 'loading' | 'ready' | 'error'

/**
 * The visitor's games of the day. A failed request (an old backend has no such route) is `error`, and the page says
 * so. When the day rolls over under an open page the new day's games are fetched, and `rollover` is true meanwhile.
 */
export function useDailyGames() {
  const [daily, setDaily] = useState<HistoricalDaily[] | null>(null)
  const [status, setStatus] = useState<DailyStatus>('loading')
  const [rollover, setRollover] = useState(false)
  const [now, setNow] = useState(() => Date.now())

  const load = useCallback(() => {
    getHistoricalDaily()
      .then((d) => {
        setDaily(Array.isArray(d) ? d : null)
        setStatus(Array.isArray(d) ? 'ready' : 'error')
      })
      .catch(() => {
        setDaily(null)
        setStatus('error')
      })
      .finally(() => setRollover(false))
  }, [])
  useEffect(() => {
    load()
  }, [load])

  const nextAt = daily && daily.length > 0 ? daily[0].nextAt : null
  useEffect(() => {
    if (!nextAt) return
    const t = window.setInterval(() => {
      const at = Date.now()
      setNow(at)
      if (new Date(nextAt).getTime() <= at) {
        setRollover(true)
        load()
      }
    }, 30_000)
    return () => window.clearInterval(t)
  }, [nextAt, load])

  const retry = useCallback(() => {
    setStatus('loading')
    load()
  }, [load])

  return { daily, status, rollover, nextAt, left: nextAt ? countdown(nextAt, now) : null, retry }
}

/** Starts (or returns) today's game for a sport and goes to it. One request at a time. */
export function useStartDaily() {
  const navigate = useNavigate()
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const start = useCallback(
    async (sport: HistoricalSport) => {
      if (starting) return
      setStarting(true)
      setError(null)
      try {
        const g = await startHistoricalDaily(sport)
        navigate(tmGame(currentSite(), g.id))
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e))
        setStarting(false)
      }
    },
    [starting, navigate],
  )
  return { starting, error, start }
}

type Props = {
  daily: HistoricalDaily[] | null
  status: DailyStatus
  rollover: boolean
  /** Starting a game is in flight (one at a time). */
  starting: boolean
  onPlay: (sport: HistoricalSport) => void
  onRetry: () => void
  signedIn: boolean
  left: string | null
}

const SPORT_LABEL: Record<HistoricalSport, string> = { NBA: 'Basketball', NFL: 'Football' }

/** The game-of-the-day cards, their note, and the loading / rollover / error states. Never inside a <section> of its own: the page names it. */
export default function DailyCards({ daily, status, rollover, starting, onPlay, onRetry, signedIn, left }: Props) {
  if (status === 'error') {
    return (
      <div className="tm-daily-block" data-dynamic="daily-cards">
        <p className="error" role="alert">
          {copy.dailyError}{' '}
          <button type="button" className="link-button" onClick={onRetry}>
            {copy.tryAgain}
          </button>
        </p>
      </div>
    )
  }
  if (status === 'loading' || !daily) {
    // The same cards the static HTML carries (so nothing jumps when today's seasons arrive), with a loading word, not the
    // static "shows when the page loads", which would be untrue once the page has loaded (D6).
    return (
      <div className="tm-daily-block" data-dynamic="daily-cards">
        <ul className="tm-daily-cards">
          {SPORTS.map((s) => (
            <li key={s} className={`tm-daily-card ${s.toLowerCase()}`}>
              <strong>{copy.sportTile(s)}</strong>
              <span className="muted small" role="status">
                {copy.dailyCardLoading}
              </span>
              <button type="button" className="tm-btn" disabled>
                {copy.dailyPlay}
              </button>
            </li>
          ))}
        </ul>
        {!signedIn && <p className="tm-daily-note muted small">{copy.dailyGuestStreak}</p>}
      </div>
    )
  }
  if (daily.length === 0) {
    return (
      <div className="tm-daily-block" data-dynamic="daily-cards">
        <p className="muted small">{copy.dailyNone}</p>
      </div>
    )
  }
  return (
    <div className="tm-daily-block" data-dynamic="daily-cards">
      <ul className="tm-daily-cards">
        {daily.map((d) => (
          <li key={d.sport} className={`tm-daily-card ${d.sport.toLowerCase()}${d.gameStatus === 'COMPLETE' ? ' done' : ''}`}>
            <div className="tm-daily-text">
              <span className="tm-daily-sport">{SPORT_LABEL[d.sport]}</span>
              <strong className="tm-daily-season">{d.seasonLabel}</strong>
              {d.gameStatus === 'COMPLETE' ? (
                <span className="tm-daily-state">{d.draftScore != null ? `Done. Draft score ${d.draftScore}` : 'Done'}</span>
              ) : d.gameStatus === 'DRAFTING' ? (
                <span className="tm-daily-state">You are mid-draft</span>
              ) : (
                <span className="tm-daily-state">Not played yet</span>
              )}
              {signedIn && d.streak > 0 && <span className="tm-daily-streak">{d.streak}-day streak</span>}
            </div>
            {d.gameStatus === 'COMPLETE' && d.gameId != null ? (
              <Link className="tm-btn quiet" to={tmGame(currentSite(), d.gameId)} aria-label={`View today's ${SPORT_LABEL[d.sport].toLowerCase()} game`}>
                View
              </Link>
            ) : d.gameStatus === 'DRAFTING' && d.gameId != null ? (
              <Link className="tm-btn" to={tmGame(currentSite(), d.gameId)} aria-label={`Resume today's ${SPORT_LABEL[d.sport].toLowerCase()} game`}>
                Resume
              </Link>
            ) : (
              <button
                type="button"
                className="tm-btn"
                aria-disabled={starting ? true : undefined}
                onClick={() => !starting && onPlay(d.sport)}
                aria-label={`Play today's ${SPORT_LABEL[d.sport].toLowerCase()} game`}
              >
                Play
              </button>
            )}
          </li>
        ))}
      </ul>
      <p className="muted small tm-daily-note">
        {/* The rollover sentence is announced; the countdown beside it changes every half minute and stays silent. */}
        <span role="status">{rollover ? copy.dailyRollover : ''}</span>
        {rollover ? null : (
          <>
            {daily[0].dailyNumber != null ? dailyName('Daily', daily[0].date, daily[0].dailyNumber, ', ') : dayLabel(daily[0].date)}: the same season for everyone, from a random draft slot.
            {left ? ` Next one in ${left}.` : ''}
          </>
        )}
        {!signedIn && ` ${copy.dailyGuestStreak}`}
      </p>
    </div>
  )
}
