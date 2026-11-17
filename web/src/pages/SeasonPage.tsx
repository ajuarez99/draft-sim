import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  listHistoricalGames,
  startChosenSeasonGame,
  type Finisher,
  type HistoricalGameSummary,
  type HistoricalSport,
  type Mover,
  type SeasonPage as SeasonPageData,
} from '../api'
import { ApiError } from '../apiError'
import ClaimLine from '../components/ClaimLine'
import RedraftFoot from '../components/RedraftFoot'
import RedraftNotFound from '../components/RedraftNotFound'
import SignInForm from '../components/SignInForm'
import { notCountedReason, pts1, sportOf, statLong } from '../historicalCopy'
import { peekGuestId } from '../guest'
import { usePageTitle } from '../pageTitle'
import * as copy from '../redraftCopy'
import { useSeasonPage } from '../redraftData'
import { tmDaily, tmGame, tmSeason, tmSportIndex } from '../site'
import { currentSite } from '../siteEnv'
import { useUser } from '../user'

// Spec 074 T035 / design.md §1. The element tree and class names mirror seasonBody() in seasonPageHtml.ts, which is
// what a visitor without scripts (and a crawler) gets; seasonPageParity tests compare the two, leaving out only the
// regions marked `data-dynamic`. Every word is from redraftCopy.ts or the server's own sentences (pointsNote,
// scoringNote, rankingLabel, dataNotes), never a second copy.

const STARTER_SKIP = new Set(['BN', 'IR'])

function PosBadges({ positions }: { positions: string[] }) {
  return (
    <>
      {positions.map((p) => (
        <span key={p} className={`pos ${p}`}>
          {p}
        </span>
      ))}
    </>
  )
}

/** No team: the card's team is the PRIOR season's (HistoricalPlayerCard), so on a page about the season itself it would mislead (V-08c). */
function Who({ p, sub }: { p: { name: string; positions: string[] }; sub: string | null }) {
  return (
    <div className="tm-rank-who">
      <strong>{p.name}</strong> <PosBadges positions={p.positions} />
      {sub ? <span className="tm-rank-sub muted small">{sub}</span> : null}
    </div>
  )
}

function Pts({ n }: { n: number }) {
  return (
    <>
      {pts1(n)}
      <span className="muted small">{copy.ptsUnit}</span>
    </>
  )
}

function FinisherRows({ rows, numbered, ranked }: { rows: Finisher[]; numbered: 'rank' | 'index'; ranked: number }) {
  const counts = new Map<number, number>()
  for (const r of rows) counts.set(r.finishRank, (counts.get(r.finishRank) ?? 0) + 1)
  return (
    <>
      {rows.map((r, i) => {
        let num: ReactNode
        if (numbered === 'index') num = String(i + 1)
        else if ((counts.get(r.finishRank) ?? 0) > 1)
          num = (
            <>
              {copy.tieText(r.finishRank)}
              <span className="hist-sr-only">{copy.tieAria(r.finishRank)}</span>
            </>
          )
        else num = String(r.finishRank)
        return (
          <li key={`${r.playerId}-${i}`} className="tm-rank-row">
            <span className="tm-rank-num">{num}</span>
            <Who p={r} sub={numbered === 'rank' ? copy.rankedGoingIn(r.goingInRank, ranked) : null} />
            <span className="tm-rank-pts">
              <Pts n={r.points} />
            </span>
          </li>
        )
      })}
    </>
  )
}

function MoverRows({ rows, board, ranked }: { rows: Mover[]; board: number; ranked: number }) {
  return (
    <>
      {rows.map((m) => (
        <li key={m.playerId} className="tm-mover">
          <div className="tm-mover-head">
            <Who p={m} sub={copy.weeksPlayed(m.weeksPlayed)} />
            <span className="tm-rank-pts">
              <Pts n={m.points} />
            </span>
          </div>
          <dl className="tm-move-nums">
            <div>
              <dt>{copy.moverGoingIn}</dt>
              <dd>{copy.moverGoingInOf(m.goingInRank, ranked)}</dd>
            </div>
            <div>
              <dt>{copy.moverFinished}</dt>
              <dd>{copy.moverFinishedOf(m.finishRank, board)}</dd>
            </div>
          </dl>
          <p className="tm-move-delta">{copy.moverDelta(m.goingInRank, m.finishRank)}</p>
        </li>
      ))}
    </>
  )
}

function MoverSection({
  heading, sub, kind, rows, board, ranked, shortText, emptyText, unranked,
}: {
  heading: string; sub: string; kind: 'bust' | 'steal'; rows: Mover[]; board: number; ranked: number; shortText: string; emptyText: string; unranked: string | null
}) {
  return (
    <>
      <h3>{heading}</h3>
      <p className="muted small">{sub}</p>
      {rows.length === 0 ? (
        <p className="muted small">{emptyText}</p>
      ) : (
        <>
          <ol className={`tm-movers ${kind}`}>
            <MoverRows rows={rows} board={board} ranked={ranked} />
          </ol>
          {rows.length < 5 ? <p className="muted small">{shortText}</p> : null}
        </>
      )}
      {unranked ? <p className="muted small">{unranked}</p> : null}
    </>
  )
}

/** The page's content for one season: pure of identity, so the static-versus-React test can render it with no providers but a router. */
function SeasonContent({ p, hero }: { p: SeasonPageData; hero: ReactNode }) {
  const s = p.sport
  const starters = p.rosterPositions.filter((x) => !STARTER_SKIP.has(x))
  const bench = p.rounds - starters.length
  const excluded = Object.entries(p.excludedStats)
  const countedKeys = Object.keys(p.statCoverage)
  const unrankedText = p.unrankedLeftOut > 0 ? copy.unrankedLine(p.unrankedLeftOut, p.teams) : null
  return (
    <>
      {hero}
      <section className="tm-section" aria-label={copy.theDraft}>
        <h2>{copy.theDraft}</h2>
        <ul className="hist-counted">
          {starters.map((x, i) => (
            <li key={`${x}-${i}`} className="hist-pill">
              {x}
            </li>
          ))}
          {bench > 0 ? <li className="hist-pill">{copy.benchLabel(bench)}</li> : null}
        </ul>
        <p className="muted small">{p.scoringNote}</p>
        <p className="tm-points-note muted small">{p.pointsNote}</p>
        <p className="tm-going-in muted small">{copy.goingInNote(p.rankingLabel)}</p>
      </section>
      <details className="tm-fold tm-spoilers">
        <summary>
          <h2>{copy.spoilerH2(p.label)}</h2>
          <span className="tm-fold-hint muted small">{copy.spoilerHint}</span>
        </summary>
        <h3>{copy.topHeading(p.topOverall.length)}</h3>
        <p className="muted small">{copy.topSub}</p>
        <ol className="tm-rank-list tm-top">
          <FinisherRows rows={p.topOverall} numbered="rank" ranked={p.rankedPlayers} />
        </ol>
        <MoverSection
          heading={copy.bustsHeading} sub={copy.bustsSub(p.teams)} kind="bust" rows={p.busts} board={p.boardPlayers} ranked={p.rankedPlayers}
          shortText={copy.bustsShort(p.busts.length, p.teams)} emptyText={copy.bustsEmpty(p.teams)} unranked={null}
        />
        <MoverSection
          heading={copy.stealsHeading} sub={copy.stealsSub(p.teams)} kind="steal" rows={p.steals} board={p.boardPlayers} ranked={p.rankedPlayers}
          shortText={copy.stealsShort(p.steals.length, p.teams)} emptyText={copy.stealsEmpty(p.teams)} unranked={unrankedText}
        />
        <h3>{copy.dreamHeading}</h3>
        <p className="muted small">{copy.dreamSub}</p>
        <ul className="hist-slots">
          {p.dreamRoster.map((d) => (
            <li key={d.playerId}>
              <strong>{d.name}</strong> <PosBadges positions={d.positions} />
              <span className="tm-slot-pts">
                <Pts n={d.points} />
              </span>
            </li>
          ))}
        </ul>
        <p className="tm-dream-total muted small">{copy.dreamTotal(pts1(p.dreamRosterPoints))}</p>
        <details className="tm-fold">
          <summary>
            <h3>{copy.positionFold}</h3>
          </summary>
          <div className="tm-pos-grid">
            {Object.entries(p.topByPosition).map(([pos, rows]) => (
              <section key={pos}>
                <h4>
                  <span className={`pos ${pos}`}>{pos}</span> {copy.positionFullWord[pos] ?? pos}
                </h4>
                <ol className="tm-rank-list">
                  <FinisherRows rows={rows} numbered="index" ranked={p.rankedPlayers} />
                </ol>
              </section>
            ))}
          </div>
        </details>
      </details>
      <section className="panel hist-disclosure" aria-label={copy.aboutHeading}>
        <h2>{copy.aboutHeading}</h2>
        {s === 'NFL' && countedKeys.length > 0 ? (
          <>
            <h3>{copy.countedHeading(p.label)}</h3>
            <ul className="hist-counted">
              {countedKeys.map((k) => (
                <li key={k} className="hist-pill">
                  {statLong(k)}
                </li>
              ))}
            </ul>
          </>
        ) : null}
        {excluded.length > 0 ? (
          <>
            <h3>{copy.excludedHeading(p.label)}</h3>
            <ul className="hist-excluded">
              {excluded.map(([k, why]) => (
                <li key={k}>
                  <strong>{statLong(k)}</strong> <span className="muted">{notCountedReason(why, k)}</span>
                </li>
              ))}
            </ul>
          </>
        ) : null}
        {p.dataNotes.length > 0 ? (
          <ul className="hist-notes muted small">
            {p.dataNotes.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
        ) : null}
        <p className="muted small">
          {copy.howRanksWork(p.boardPlayers)}
          {s === 'NFL' ? ` ${copy.footballPositionsOnly}` : ''}
        </p>
        <p className="muted small">
          {copy.rookieNote(s)} {copy.missedSeasonNote(p.label)}
        </p>
        {p.attribution ? <p className="muted small hist-attribution">{p.attribution}</p> : null}
      </section>
    </>
  )
}

/**
 * What the visitor may do about starting this season. A guest gets one chosen-season game ever (an abandoned one still
 * counts): the server enforces it (403 GUEST_LIMIT), and the page also shows the panel up front when this browser's
 * games already include one, so the button is not a trap.
 */
function useStartState(sport: HistoricalSport, label: string) {
  const user = useUser()
  const [games, setGames] = useState<HistoricalGameSummary[] | null>(null)
  useEffect(() => {
    // A browser that never made a guest id and is not signed in has no games to ask about.
    if (!user && !peekGuestId()) return
    let current = true
    listHistoricalGames()
      .then((g) => current && setGames(g))
      .catch(() => {})
    return () => {
      current = false
    }
  }, [user])
  const resume = (games ?? []).find((g) => g.status === 'DRAFTING' && g.chosenSeason && g.seasonLabel === label && sportOf(g) === sport) ?? null
  const usedAllowance = !user && (games ?? []).some((g) => !g.dailyDate)
  return { user, resume, usedAllowance }
}

/** The page's own h1 label, links back to the sport index and the footer: what the loading and error states keep (D5). */
function SeasonShell({ sport, slug, children }: { sport: HistoricalSport; slug: string; children: ReactNode }) {
  const index = tmSportIndex(currentSite(), sport)
  return (
    <div className="content tm tm-page">
      <header className="tm-hero">
        <h1 className="tm-title tm-title-sm">
          {copy.seasonH1(slug, sport)}
        </h1>
      </header>
      {children}
      {index ? (
        <nav className="tm-neighbours" aria-label={copy.otherSeasons(sport)}>
          <Link className="tm-pill-link" to={index}>
            {copy.allSeasons(sport)}
          </Link>
        </nav>
      ) : null}
      <RedraftFoot />
    </div>
  )
}

export default function SeasonPage({ sport, slug }: { sport: HistoricalSport; slug: string }) {
  const { page, status, retry } = useSeasonPage(sport, slug)
  const label = page?.label ?? slug
  usePageTitle(copy.seasonTitle(label, sport)) // V-08i: from the slug at once, so a season-to-season click never flashes the bare brand
  if (status === 'missing') return <RedraftNotFound />
  if (!page) {
    return (
      <SeasonShell sport={sport} slug={slug}>
        {status === 'error' ? (
          <div className="error" role="alert">
            {copy.seasonError}{' '}
            <button type="button" className="link-button" onClick={retry}>
              {copy.tryAgain}
            </button>
          </div>
        ) : (
          <p className="muted" role="status">
            {copy.seasonLoading(label)}
          </p>
        )}
      </SeasonShell>
    )
  }
  return <SeasonLoaded p={page} />
}

function SeasonLoaded({ p }: { p: SeasonPageData }) {
  const navigate = useNavigate()
  const { user, resume, usedAllowance } = useStartState(p.sport, p.label)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [limited, setLimited] = useState(false)
  const limitHeading = useRef<HTMLHeadingElement>(null)
  // A limit is a guest's. Signing in from the panel lifts it at once (A1); a game already under way here is not a limit
  // at all: the guest is mid-draft on this season and has one control, Resume (D1).
  const atLimit = !user && (limited || usedAllowance) && !resume
  const s = p.sport
  const cfg = currentSite()
  const daily = tmDaily(cfg)
  const index = tmSportIndex(cfg, s)

  useEffect(() => {
    // The 403 belongs to a guest. Once somebody is signed in the panel's reason is gone.
    if (user) setLimited(false)
  }, [user])

  useEffect(() => {
    // After a click that ended in the panel, focus moves to its heading (it appeared under the pointer, not the focus).
    if (limited && atLimit) limitHeading.current?.focus()
  }, [limited, atLimit])

  async function start() {
    // aria-disabled, not disabled: a button that disables itself while it holds focus drops that focus (C3).
    if (starting || atLimit) return
    setStarting(true)
    setError(null)
    try {
      const g = await startChosenSeasonGame(s, p.seasonStart)
      navigate(tmGame(cfg, g.id))
    } catch (e) {
      if (e instanceof ApiError && e.status === 403 && e.code === 'GUEST_LIMIT') setLimited(true)
      else setError(e instanceof Error ? e.message : String(e))
      setStarting(false)
    }
  }

  const drafting = (cls: string) =>
    resume ? (
      cls === 'tm-play' ? (
        <Link className={cls} to={tmGame(cfg, resume.id)}>
          {copy.draftResume(p.label)}
        </Link>
      ) : null
    ) : (
      <button type="button" className={cls} onClick={() => void start()} aria-disabled={starting || atLimit ? true : undefined}>
        {starting ? copy.draftStarting : cls === 'tm-play' ? copy.draftButton : copy.draftAgain(p.label)}
      </button>
    )

  const hero = (
    <header className="tm-hero">
      <p className="tm-over">{copy.seasonOver(s)}</p>
      <h1 className="tm-title tm-title-sm">
        {copy.seasonH1(p.label, s)}
      </h1>
      <p className="tm-tagline">{copy.seasonTagline(p.label, s)}</p>
      {drafting('tm-play')}
      <span className="hist-sr-only" role="status" data-dynamic="starting">
        {starting ? copy.draftStarting : ''}
      </span>
      <p className="tm-facts">{copy.seasonFacts(p.teams, p.rounds)}</p>
      {!atLimit && (
        <p className="muted small" data-dynamic="state-line">
          {user ? copy.stateLineSignedIn : copy.stateLineGuest}
        </p>
      )}
      <ClaimLine />
      {atLimit && (
        <section className="tm-limit" aria-labelledby="tm-limit-h">
          <h2 id="tm-limit-h" ref={limitHeading} tabIndex={-1}>
            {copy.limitHeading}
          </h2>
          <p>{copy.limitBody}</p>
          <SignInForm notes={[]} autoFocus={false} />
          <p className="muted small">{copy.limitNote}</p>
          {daily ? (
            <div className="tm-limit-actions">
              <Link className="tm-btn quiet" to={daily}>
                {copy.playTodays}
              </Link>
            </div>
          ) : null}
        </section>
      )}
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
    </header>
  )

  return (
    <div className="content tm tm-page">
      <SeasonContent p={p} hero={hero} />
      <nav className="tm-neighbours" aria-label={copy.otherSeasons(s)}>
        {p.prevSlug && tmSeason(cfg, s, p.prevSlug) ? (
          <Link className="tm-pill-link" to={tmSeason(cfg, s, p.prevSlug)!}>
            {copy.prevSeason(p.prevSlug)}
          </Link>
        ) : null}
        {p.nextSlug && tmSeason(cfg, s, p.nextSlug) ? (
          <Link className="tm-pill-link" to={tmSeason(cfg, s, p.nextSlug)!}>
            {copy.nextSeason(p.nextSlug)}
          </Link>
        ) : null}
        {index ? (
          <Link className="tm-pill-link" to={index}>
            {copy.allSeasons(s)}
          </Link>
        ) : null}
      </nav>
      {drafting('tm-btn quiet')}
      <RedraftFoot />
    </div>
  )
}
