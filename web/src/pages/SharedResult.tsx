import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import type { PublicGame } from '../api'
import HistoricalResults from '../components/HistoricalResults'
import RedraftFoot from '../components/RedraftFoot'
import { RedraftWhereTo } from '../components/RedraftNotFound'
import { useStartDaily } from '../components/DailyCards'
import { sportOf } from '../historicalCopy'
import { dayLabel } from '../historicalDaily'
import { usePageTitle } from '../pageTitle'
import * as copy from '../redraftCopy'
import { tmSeason } from '../site'
import { currentSite } from '../siteEnv'

// Spec 074 T043 / design.md §5: a finished game somebody else sent a link to. It is written about "this team"
// (voice="shared"), shows no owner, no Share button, and never "you". While the game is today's game of the day the
// server sends a reduced view (no picks, lineups or hindsight roster: those are the day's answer) and the page says
// when the rest shows. Not meant for a search index: a game id is not a page worth ranking, and the static app shell
// already says noindex; this adds the same to the mounted page and takes it off again on the way out.

let noindexUsers = 0

/**
 * Exactly one `<meta name="robots" content="noindex">` in the head while anything that uses this hook is mounted, none after
 * the last one unmounts (V-08g). The delivered app shell already carries one; it is replaced, not doubled, and nested users
 * (the room around a shared result around its "no game" page) share the one meta.
 */
export function useNoindex(): void {
  useEffect(() => {
    if (noindexUsers++ === 0) {
      document.head.querySelectorAll('meta[name="robots"]').forEach((m) => m.remove())
      const meta = document.createElement('meta')
      meta.name = 'robots'
      meta.content = 'noindex'
      document.head.appendChild(meta)
    }
    return () => {
      if (--noindexUsers === 0) document.head.querySelectorAll('meta[name="robots"][content="noindex"]').forEach((m) => m.remove())
    }
  }, [])
}

export function NoFinishedGame() {
  usePageTitle(copy.brand)
  useNoindex()
  return (
    <div className="content tm tm-page">
      <header className="tm-hero">
        <h1 className="tm-title tm-title-sm">{copy.sharedNoneHeading}</h1>
        <p className="tm-tagline">{copy.sharedNoneBody}</p>
        <RedraftWhereTo />
      </header>
    </div>
  )
}

/** Calls `onElapsed` once when `iso` (an ISO-8601 instant) has passed, so a result that was reduced until then can be asked for again (CR-10). */
export function useAt(iso: string | null, onElapsed: () => void): void {
  useEffect(() => {
    if (!iso) return
    const at = new Date(iso).getTime()
    if (Number.isNaN(at)) return
    // setTimeout runs at once for a delay past about 24.8 days (a 32-bit overflow); a "reduced until" is at most a day away,
    // so anything further than that is not a rollover this page can be open for.
    const wait = Math.max(0, at - Date.now()) + 1000
    if (wait > 2_000_000_000) return
    const t = window.setTimeout(onElapsed, wait)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [iso])
}

export default function SharedResult({ pub, onReducedEnd }: { pub: PublicGame; onReducedEnd?: () => void }) {
  usePageTitle(copy.brand)
  useNoindex()
  useAt(pub.reducedUntil ?? null, () => onReducedEnd?.())
  const { starting, error, start } = useStartDaily()
  const game = pub.state
  const sport = sportOf(game)
  const reduced = pub.reducedUntil != null
  if (!game.result) return <NoFinishedGame />
  const seasonLink = reduced ? null : tmSeason(currentSite(), sport, game.season.label)
  const tail = game.dailyDate ? copy.sharedDailyTail(dayLabel(game.dailyDate)) : game.chosenSeason ? copy.sharedChosenTail : null
  return (
    <div className="content tm tm-page tm-results">
      <h1 className="tm-kicker">{copy.sharedKicker(game.season.label, sport, tail)}</h1>
      <HistoricalResults
        game={game}
        result={game.result}
        voice="shared"
        reduced={reduced}
        finishAction={
          <>
            {/* aria-disabled, not disabled: a button that disables itself while focused drops focus (C3). */}
            <button type="button" className="tm-play" aria-disabled={starting ? true : undefined} onClick={() => !starting && void start(sport)}>
              {starting ? copy.draftStarting : copy.sharedPlayToday(sport)}
            </button>
            {/* Today's game: its season page would be the day's answer, so no link to it until tomorrow. */}
            {seasonLink && (
              <Link className="tm-btn quiet" to={seasonLink}>
                {copy.sharedHowPlayed(game.season.label)}
              </Link>
            )}
          </>
        }
      />
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      <span className="hist-sr-only" role="status">
        {starting ? copy.draftStarting : ''}
      </span>
      <RedraftFoot />
    </div>
  )
}
