import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import {
  abandonHistoricalGame,
  claimGuestGames,
  getHistoricalProfile,
  listHistoricalGames,
  startHistoricalGame,
  type HistoricalGameSummary,
  type HistoricalProfile,
  type HistoricalSport,
} from '../api'
import { useFailure } from '../useFailure'
import DailyCards, { useDailyGames, useStartDaily } from '../components/DailyCards'
import HistoricalProfileCard from '../components/HistoricalProfileCard'
import ClaimLine from '../components/ClaimLine'
import RedraftFoot from '../components/RedraftFoot'
import { noteClaim, peekGuestId } from '../guest'
import { sportOf, summaryStatus } from '../historicalCopy'
import { usePageTitle } from '../pageTitle'
import * as copy from '../redraftCopy'
import { useHomeLists } from '../redraftData'
import { SPORTS } from '../redraftPages'
import { tmDaily, tmGame, tmSportIndex } from '../site'
import { currentSite } from '../siteEnv'
import { clearDraftQueue } from '../useDraftQueue'
import { dayLabel, landingStats } from '../historicalDaily'
import { useUser } from '../user'
import TimeMachineSignIn from './TimeMachineSignIn'

// Spec 074 T038 / design.md §4: ONE home for a guest and a signed-in visitor. A guest sees the game of the day, the
// season tiles, the games kept in this browser (only if there are any) and a "Keep your games" sign-in panel; a
// signed-in visitor also gets the random-year control, the games list and the profile. The element tree for the
// guest's first paint mirrors homeBody() in seasonPageHtml.ts (what index.html delivers).

function when(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

const SPORT_LABEL: Record<HistoricalSport, string> = { NBA: 'Basketball', NFL: 'Football' }

export default function HistoricalDraft() {
  usePageTitle(copy.homeTitle)
  const user = useUser()
  const navigate = useNavigate()
  const location = useLocation()
  const { error, fail, setError } = useFailure()
  const [games, setGames] = useState<HistoricalGameSummary[] | null>(null)
  const [profile, setProfile] = useState<HistoricalProfile | null>(null)
  const [startingRandom, setStartingRandom] = useState(false)
  // Basketball is only the preselected tab; every request still carries the sport explicitly.
  const [chosen, setSport] = useState<HistoricalSport>('NBA')
  const { lists, status: listsStatus, retry: retryLists } = useHomeLists()
  const daily = useDailyGames()
  const startDaily = useStartDaily()

  // Games: a signed-in visitor always; a guest only when this browser has made a guest id (no id, no games).
  // A claim left over from an earlier sign-in (it failed then) is tried again first, so the list is the merged one.
  useEffect(() => {
    if (!user && !peekGuestId()) {
      setGames([])
      return
    }
    let current = true
    ;(async () => {
      if (user && peekGuestId()) {
        try {
          const c = await claimGuestGames(user.sleeperUserId)
          if (c) noteClaim(c)
        } catch {
          // Kept for the next visit: the id is only dropped after a 200.
        }
      }
      const g = await listHistoricalGames()
      if (current) setGames(g)
    })().catch((e) => current && fail(e))
    return () => {
      current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.sleeperUserId])

  useEffect(() => {
    if (!user) {
      setProfile(null)
      return
    }
    let current = true
    getHistoricalProfile()
      .then((p) => current && setProfile(p))
      .catch(() => {})
    return () => {
      current = false
    }
  }, [user?.sleeperUserId])

  // `/#sign-in` (the bar's "Sign in" link from any page) scrolls the panel into view once the page has it, and moves
  // focus to the panel's heading (not the input: a phone would open its keyboard). `location.key` is in the list so a
  // second tap on "Sign in", which changes no hash, does it again (C4).
  useEffect(() => {
    if (location.hash !== '#sign-in') return
    const el = document.getElementById('sign-in')
    if (!el) return
    if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'start' })
    const h = el.querySelector('h2')
    if (h) {
      h.setAttribute('tabindex', '-1')
      h.focus({ preventScroll: true })
    }
  }, [location.hash, location.key, user])

  // A sport is offered for the random year only once the server lists at least one of its seasons.
  const offered = SPORTS.filter((sp) => (lists[sp]?.seasons.length ?? 0) > 0)
  const sport: HistoricalSport = offered.includes(chosen) ? chosen : (offered[0] ?? chosen)
  // The random year needs no list to work (the server picks the season); it is only hidden when the lists loaded and
  // offered nothing. While they are still loading it waits; if they failed it stays available (CR-09).
  const cfg = currentSite()
  const dailyPath = tmDaily(cfg)

  async function startRandom() {
    if (startingRandom) return
    setStartingRandom(true)
    setError(null)
    try {
      const g = await startHistoricalGame(sport)
      navigate(tmGame(currentSite(), g.id))
    } catch (e) {
      fail(e)
      setStartingRandom(false)
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

  const visible = (games ?? []).filter((g) => g.status !== 'ABANDONED')
  const stats = landingStats(visible)
  const busy = startingRandom || startDaily.starting

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
        <p className="tm-over">{copy.dailyOver}</p>
        <h1 className="tm-title tm-title-sm">{copy.homeH1}</h1>
        <p className="tm-tagline">{copy.homeTagline}</p>
      </header>

      <section className="tm-daily" aria-label={copy.dailyHeading}>
        <h2>{copy.dailyHeading}</h2>
        <DailyCards
          daily={daily.daily}
          status={daily.status}
          rollover={daily.rollover}
          starting={busy}
          onPlay={(s) => void startDaily.start(s)}
          onRetry={daily.retry}
          signedIn={user != null}
          left={daily.left}
        />
        {dailyPath ? (
          <Link className="tm-fold-link" to={dailyPath}>
            {copy.dailyAbout}
          </Link>
        ) : null}
        {startDaily.error && (
          <div className="error" role="alert">
            {startDaily.error}
          </div>
        )}
      </section>

      {/* "Draft a random year" is for a signed-in visitor only: the server refuses it for a guest, and a button that always fails is a trap. */}
      {user && (offered.length > 0 || listsStatus === 'error') && (
        <div className="tm-random">
          {offered.length > 1 && (
            <div className="tm-sports" role="group" aria-label="Sport">
              {offered.map((sp) => (
                <button
                  key={sp}
                  type="button"
                  className={`tm-sport${sp === sport ? ' on' : ''}`}
                  aria-pressed={sp === sport}
                  disabled={busy}
                  onClick={() => setSport(sp)}
                >
                  {SPORT_LABEL[sp]}
                </button>
              ))}
            </div>
          )}
          <button type="button" className="tm-play" onClick={() => void startRandom()} disabled={busy || listsStatus === 'loading'}>
            {startingRandom ? copy.draftStarting : 'Draft a random year'}
          </button>
        </div>
      )}
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      {/* V-08h: the tiles link to the Redraft site's sport indexes; where no such page exists (the combined build) the section would be a bare heading. */}
      {!SPORTS.some((s) => tmSportIndex(cfg, s)) && listsStatus === 'error' && (
        <div className="error" role="alert">
          {copy.seasonsError}{' '}
          <button type="button" className="link-button" onClick={retryLists}>
            {copy.tryAgain}
          </button>
        </div>
      )}
      {SPORTS.some((s) => tmSportIndex(cfg, s)) && (
      <section className="tm-section" aria-label={copy.pickASeason}>
        <h2>{copy.pickASeason}</h2>
        {listsStatus === 'loading' && (
          <p className="muted small" role="status">
            {copy.seasonsLoading}
          </p>
        )}
        {listsStatus === 'error' && (
          <div className="error" role="alert">
            {copy.seasonsError}{' '}
            <button type="button" className="link-button" onClick={retryLists}>
              {copy.tryAgain}
            </button>
          </div>
        )}
        <div className="tm-sport-tiles">
          {SPORTS.map((s) => {
            const seasons = lists[s]?.seasons ?? []
            const to = tmSportIndex(cfg, s)
            if (seasons.length === 0 || !to) return null
            return (
              <Link key={s} className={`tm-sport-tile ${s.toLowerCase()}`} to={to}>
                <strong>{copy.sportTile(s)}</strong>
                <span>{copy.sportTileRange(seasons[0].label, seasons[seasons.length - 1].label)}</span>
                <span className="muted small">{copy.sportTileCount(seasons.length)}</span>
              </Link>
            )
          })}
        </div>
      </section>
      )}

      <ClaimLine />

      {(user || visible.length > 0) && (
        <section className="tm-section" aria-label="Your games">
          <h2>Your games</h2>
          {!user && <p className="muted small">{copy.savedHere}</p>}
          {games == null && !error ? (
            <p className="muted" role="status">
              Loading…
            </p>
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
                      {g.chosenSeason && <span className="tm-game-chosen">{copy.chosenPill}</span>}
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
                          <Link className="tm-btn" to={tmGame(currentSite(), g.id)}>
                            Resume
                          </Link>
                        </>
                      )}
                      {g.status === 'COMPLETE' && (
                        <Link className="tm-btn quiet" to={tmGame(currentSite(), g.id)}>
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
      )}

      {user && profile && <HistoricalProfileCard profile={profile} />}

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

      {!user && <TimeMachineSignIn />}

      <RedraftFoot />
    </div>
  )
}
