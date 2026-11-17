import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { getYesterdayDaily, type YesterdayDaily } from '../api'
import DailyCards, { useDailyGames, useStartDaily } from '../components/DailyCards'
import RedraftFoot from '../components/RedraftFoot'
import { dayLabel } from '../historicalDaily'
import { usePageTitle } from '../pageTitle'
import * as copy from '../redraftCopy'
import { SPORTS } from '../redraftPages'
import { tmSeason } from '../site'
import { currentSite } from '../siteEnv'
import { useUser } from '../user'

// Spec 074 T037 / design.md §3. Mirrors dailyBody() in seasonPageHtml.ts. The cards are the home page's component;
// yesterday's seasons come from the public endpoint (read-only: it never deals a puzzle), and each row links to that
// season's page, where its spoilers are folded.

function Yesterday({ day }: { day: string | null }) {
  const [rows, setRows] = useState<YesterdayDaily[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let current = true
    getYesterdayDaily()
      .then((r) => current && (setRows(Array.isArray(r) ? r : null), setFailed(!Array.isArray(r))))
      .catch(() => current && setFailed(true))
    return () => {
      current = false
    }
  }, [attempt])
  // `day` is today's puzzle date. When midnight Eastern passes it changes from one date to another, and "Yesterday" is a
  // different day now, so it is asked for again (CR-10). The first value (null to a date) is the page loading, not a rollover.
  const lastDay = useRef<string | null>(day)
  useEffect(() => {
    if (lastDay.current && day && day !== lastDay.current) setAttempt((n) => n + 1)
    if (day) lastDay.current = day
  }, [day])
  if (failed) {
    return (
      <div data-dynamic="yesterday">
        <p className="error" role="alert">
          {copy.yesterdayError}{' '}
          <button type="button" className="link-button" onClick={() => (setFailed(false), setAttempt((n) => n + 1))}>
            {copy.tryAgain}
          </button>
        </p>
      </div>
    )
  }
  if (!rows)
    return (
      <p className="muted small" data-dynamic="yesterday" role="status">
        {copy.yesterdayStatic}
      </p>
    )
  return (
    <div data-dynamic="yesterday">
      <ul className="tm-games">
        {SPORTS.map((s) => {
          const r = rows.find((x) => x.sport === s)
          const to = r && r.slug ? tmSeason(currentSite(), s, r.slug) : null
          return r && r.slug && r.seasonLabel && to ? (
            <li key={s} className="tm-game">
              <div className="tm-game-text">
                <strong>{copy.yesterdayRow(s, dayLabel(r.date))}</strong>
                <span>{r.seasonLabel}</span>
              </div>
              <div className="tm-game-actions">
                <Link className="tm-btn quiet" to={to}>
                  {copy.yesterdayLink(r.seasonLabel)}
                </Link>
              </div>
            </li>
          ) : (
            <li key={s} className="tm-game">
              <p className="muted small">{copy.yesterdayNone(s)}</p>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

export default function DailyPage() {
  usePageTitle(copy.dailyTitle)
  const user = useUser()
  const games = useDailyGames()
  const { starting, error, start } = useStartDaily()
  return (
    <div className="content tm tm-page">
      <header className="tm-hero">
        <p className="tm-over">{copy.dailyOver}</p>
        <h1 className="tm-title tm-title-sm">{copy.dailyH1}</h1>
        <p className="tm-tagline">{copy.dailyTagline}</p>
        <DailyCards
          daily={games.daily}
          status={games.status}
          rollover={games.rollover}
          starting={starting}
          onPlay={(s) => void start(s)}
          onRetry={games.retry}
          signedIn={user != null}
          left={games.left}
        />
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
      </header>
      <section className="tm-section" aria-label={copy.yesterdayHeading}>
        <h2>{copy.yesterdayHeading}</h2>
        <Yesterday day={games.daily && games.daily.length > 0 ? games.daily[0].date : null} />
      </section>
      <section className="tm-section" aria-label={copy.howHeading}>
        <h2>{copy.howHeading}</h2>
        <ol className="tm-steps">
          {copy.howSteps.map((s) => (
            <li key={s.title}>
              <strong>{s.title}</strong>
              <span>{s.body}</span>
            </li>
          ))}
        </ol>
      </section>
      <RedraftFoot />
    </div>
  )
}
