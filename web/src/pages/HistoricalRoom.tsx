import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  getHistoricalGame,
  getPublicGame,
  historicalAuto,
  historicalPick,
  type HistoricalGameState,
  type HistoricalPlayerCard,
  type PublicGame,
  type PredictedPick,
  type Seat,
  type MockSeat,
} from '../api'
import { useFailure } from '../useFailure'
import { ApiError } from '../apiError'
import SharedResult, { NoFinishedGame, useNoindex } from './SharedResult'
import ClaimLine from '../components/ClaimLine'
import { appShellDescription, brand, keepResultBody, keepResultHeading, notCountedNote } from '../redraftCopy'
import SignInForm from '../components/SignInForm'
import { peekGuestId } from '../guest'
import { useUser } from '../user'
import { usePageHead } from '../pageTitle'
import NotFound from '../components/NotFound'
import { tmHome } from '../site'
import { currentSite } from '../siteEnv'
import DraftBoard from '../components/DraftBoard'
import HistoricalPlayerList from '../components/HistoricalPlayerList'
import HistoricalQueue from '../components/HistoricalQueue'
import HistoricalPickRail from '../components/HistoricalPickRail'
import HistoricalRosterPanel from '../components/HistoricalRosterPanel'
import HistoricalClockBanner from '../components/HistoricalClockBanner'
import HistoricalRoomLayout from '../components/HistoricalRoomLayout'
import HistoricalResults from '../components/HistoricalResults'
import HistoricalAwardPanel from '../components/HistoricalAwardPanel'
import HistoricalSeasonReveal from '../components/HistoricalSeasonReveal'
import { dailyName } from '../historicalDaily'
import { LoadingScreen } from '../components/Skeleton'
import { cardToPlayerRef, coverageText, historicalSeats, notCountedReason, sportOf, statLong, teamName } from '../historicalCopy'
import { usePickReveal } from '../usePickReveal'
import { loadRevealSpeed, saveRevealSpeed, type RevealSpeed } from '../revealSpeed'
import { useDraftQueue } from '../useDraftQueue'

/** Same stub the mock room uses: no fitted-history provenance exists for a computer team here. */
function toBoardSeat(s: MockSeat): Seat {
  return {
    slot: s.slot,
    managerId: -s.slot,
    manager: s.manager,
    avatarId: null,
    provenance: 'NEUTRAL',
    reachBias: 0,
    relativeReachBias: null,
    relativeReachStdErr: null,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 0,
    picksScored: 0,
  }
}

/**
 * Spec 071: whether a timed reveal may run. With reduced motion on there is none, and where the browser cannot say
 * (no matchMedia) there is none either: the results simply appear, as they did before the reveal existed.
 */
function motionAllowed(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** What counts, what does not, and why: the era disclosure (FR-007/FR-008), per sport (spec 056). */
function SeasonDisclosure({ game }: { game: HistoricalGameState }) {
  const s = game.season
  const nfl = sportOf(game) === 'NFL'
  const excluded = Object.entries(s.excludedStats)
  return (
    <section className="panel hist-disclosure" aria-label="What counts this season">
      <div className="hist-disclosure-cols">
        <div>
          <h2>{nfl ? `Scoring in ${s.label}` : `Stats that count in ${s.label}`}</h2>
          <ul className="hist-counted">
            {s.countedStats.map((k) => (
              <li key={k} className="hist-pill" title={nfl ? statLong(k) : coverageText(k, s.statCoverage[k])}>
                {nfl ? statLong(k) : coverageText(k, s.statCoverage[k])}
              </li>
            ))}
          </ul>
          {!nfl && <p className="muted small">A stat missing from a box score scores 0. Bonuses are not shown on player cards.</p>}
        </div>
        {excluded.length > 0 && (
          <div>
            <h2>Not counted</h2>
            <ul className="hist-excluded">
              {excluded.map(([k, why]) => (
                <li key={k}>
                  <strong>{statLong(k)}</strong> <span className="muted">{notCountedReason(why, k)}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      {(s.dataNotes ?? []).length > 0 && (
        <ul className="hist-notes muted small" aria-label="About the data">
          {(s.dataNotes ?? []).map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
      <p className="muted small">
        Board order: {s.rankingLabel}. {s.settingsSource}
      </p>
      {(s.attribution ?? null) && <p className="muted small hist-attribution">{s.attribution}</p>}
    </section>
  )
}

/**
 * The closed disclosure's one line. The counts are in the summary so a caveat is never invisible while it is
 * collapsed (plan review PR-16); a part whose count is 0 is left out.
 */
export function disclosureSummary(game: Pick<HistoricalGameState, 'season'>): string {
  const s = game.season
  const excluded = Object.keys(s.excludedStats).length
  const notes = (s.dataNotes ?? []).length
  const parts = [`What counts in ${s.label}`]
  if (excluded > 0) parts.push(`${excluded} ${excluded === 1 ? 'stat' : 'stats'} not counted`)
  if (notes > 0) parts.push(`${notes} data ${notes === 1 ? 'note' : 'notes'}`)
  return parts.join(' · ')
}

export default function HistoricalRoom() {
  const { gameId = '' } = useParams<{ gameId: string }>()
  const user = useUser()
  const id = Number(gameId)
  const [speed, setSpeed] = useState<RevealSpeed>(loadRevealSpeed)
  const reveal = usePickReveal(speed)
  // Everything below reads `state`, which is the reveal's view: the server state cut at the last revealed pick.
  // Reading the raw response anywhere would show a computer pick before its turn (spec 063 FR-002).
  const state = reveal.view
  const { error, notFound, setError, fail } = useFailure()
  const [busy, setBusy] = useState<'PICK' | 'FINISH' | 'DRAFT' | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const announced = useRef<number | null>(null)
  // Spec 071: the season plays out before the results, but only for a game seen DRAFTING in this visit. A game
  // opened already finished goes straight to its results, where "Replay the season" can start it by hand.
  const sawDrafting = useRef(false)
  const [seasonDone, setSeasonDone] = useState(false)
  const [replaying, setReplaying] = useState(false)
  // Spec 074 T043: a game that is not the viewer's own (the owner endpoint answers 404, or 403) may be somebody's shared
  // result. 'pending' holds the loading screen until the public answer settles, so the owner never sees the shared
  // page flash and a stranger never sees a not-found flash. 'none' is the "No finished game here" page.
  const [shared, setShared] = useState<PublicGame | 'none' | null>(null)
  usePageHead({ title: brand, description: appShellDescription, noindex: true, jsonLd: [] }) // app-shell.html's head
  // A game page is never worth indexing, whoever is looking (owner room, shared result or "no game"): V-08g.
  useNoindex()

  const { load } = reveal
  // Somebody else's finished game is a Redraft-site page (CR-04). The combined build keeps what it always did: a game
  // that is not yours is not found, and nothing is requested from the public route.
  const publicPages = currentSite().site === 'timemachine'
  // A reduced result (today's game of the day) is asked for again once its "reduced until" instant has passed (CR-10).
  const refetchShared = useCallback(() => {
    getPublicGame(id)
      .then((p) => setShared(p))
      .catch(() => {})
  }, [id])
  useEffect(() => {
    announced.current = null
    sawDrafting.current = false
    setSeasonDone(false)
    setReplaying(false)
    setShared(null)
    let current = true
    const asStranger = async () => {
      try {
        const p = await getPublicGame(id)
        if (current) setShared(p)
      } catch (e2) {
        if (!current) return
        if (e2 instanceof ApiError && e2.status === 404) setShared('none')
        else fail(e2)
      }
    }
    // V-08j: a viewer with no identity at all (not signed in, no stored guest id) owns no game, so the private request could
    // only 404 and show as a console error. With any identity the owner route still goes first.
    if (publicPages && !user && !peekGuestId()) {
      void asStranger()
      return () => {
        current = false
      }
    }
    getHistoricalGame(id)
      .then(load)
      .catch(async (e) => {
        if (publicPages && e instanceof ApiError && (e.status === 404 || e.status === 403)) {
          await asStranger()
          return
        }
        if (current) fail(e)
      })
    return () => {
      current = false
    }
    // `load` changes with the speed; a speed change must not refetch the game.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  function chooseSpeed(next: RevealSpeed) {
    setSpeed(next)
    saveRevealSpeed(next)
  }

  // One polite live region. SLOW and NORMAL announce each pick, coalescing when several are pending; FAST and
  // INSTANT announce only an end summary so a screen reader is not flooded.
  const { revealedThrough, revealing } = reveal
  useEffect(() => {
    if (!state) return
    if (announced.current == null) {
      announced.current = revealedThrough
      return
    }
    const from = announced.current
    if (revealedThrough <= from) return
    const pending = revealedThrough - from
    const sayPick = (n: number) => {
      const p = state.picks.find((x) => x.pickNo === n)
      return p?.player ? `Pick ${n}: ${teamName(p.draftSlot, state.userSlot)} took ${p.player.name}.` : ''
    }
    const turn = revealing ? '' : state.isUsersTurn ? ' You are on the clock.' : state.status === 'COMPLETE' ? ' The draft is complete.' : ''
    if (speed === 'SLOW' || speed === 'NORMAL') {
      setAnnouncement(pending === 1 ? sayPick(revealedThrough) + turn : `${pending} picks made. ${sayPick(revealedThrough)}${turn}`)
      announced.current = revealedThrough
    } else if (!revealing) {
      setAnnouncement(`${pending} ${pending === 1 ? 'pick' : 'picks'} made.${turn}`)
      announced.current = revealedThrough
    }
  }, [state, revealedThrough, revealing, speed])

  const seats = useMemo(() => (state ? historicalSeats(state) : []), [state])

  // The queue reads the reveal view, so a queued player leaves at the moment his pick is shown (FR-032).
  const queue = useDraftQueue(id, state?.status ?? null, state?.available ?? null)
  const queuedCards = useMemo(() => {
    if (!state) return []
    const byId = new Map(state.available.map((c) => [c.id, c]))
    return queue.ids.map((qid) => byId.get(qid)).filter((c): c is HistoricalPlayerCard => c != null)
  }, [state, queue.ids])
  const queuedSet = useMemo(() => new Set(queuedCards.map((c) => c.id)), [queuedCards])

  // The in-flight guard is a ref: `busy` is state captured in the click closure, so two clicks landing before the
  // re-render would both pass it (review finding 9).
  const inFlight = useRef(false)

  async function run(kind: 'PICK' | 'FINISH' | 'DRAFT', call: () => Promise<HistoricalGameState>) {
    if (!state || busy || inFlight.current) return
    inFlight.current = true
    setBusy(kind)
    setError(null)
    try {
      reveal.receive(await call())
      // Auto-finish: results appear at once, as before the reveal existed.
      if (kind === 'FINISH') reveal.skip()
    } catch (e) {
      // A failed request dispatches nothing, so a running reveal carries on.
      fail(e)
    } finally {
      inFlight.current = false
      setBusy(null)
    }
  }

  if (shared === 'none') return <NoFinishedGame />
  if (shared) return <SharedResult pub={shared} onReducedEnd={refetchShared} />
  if (notFound && !state) return <NotFound what="page" />
  if (error && !state) {
    return (
      <div className="content">
        <div className="error">{error}</div>
      </div>
    )
  }
  if (!state) return <LoadingScreen label="Loading this draft…" />

  const sport = sportOf(state)
  const complete = state.status === 'COMPLETE'
  const drafting = state.status === 'DRAFTING'
  if (drafting) sawDrafting.current = true
  const board: PredictedPick[] = state.picks
    .filter((p): p is typeof p & { player: HistoricalPlayerCard } => p.player != null)
    .map((p) => ({
      pickNo: p.pickNo,
      round: p.round,
      slot: p.draftSlot,
      manager: seats.find((s) => s.slot === p.draftSlot)?.manager ?? String(p.draftSlot),
      avatarId: null,
      player: cardToPlayerRef(p.player, sport),
      probability: 1,
      isModal: true,
      alternatives: [],
    }))
  const userPicks: Record<number, ReturnType<typeof cardToPlayerRef>> = {}
  for (const p of state.picks) {
    if (p.seatType === 'USER' && p.player) userPicks[p.pickNo] = cardToPlayerRef(p.player, sport)
  }
  const nextOwnPick = state.myPicks.find((p) => p > state.currentPickNo) ?? null
  const picksUntilYou = nextOwnPick != null ? nextOwnPick - state.currentPickNo : null
  const clockTeam = drafting && state.onTheClockSlot != null ? teamName(state.onTheClockSlot, state.userSlot) : null

  if (state.status === 'ABANDONED') {
    return (
      <div className="content tm tm-page">
        <header className="tm-hero">
          <h1 className="tm-title">{state.season.label} (abandoned)</h1>
          <p className="tm-tagline">This game was abandoned.</p>
          <Link className="tm-btn" to={tmHome(currentSite())}>
            Back to Redraft
          </Link>
        </header>
      </div>
    )
  }

  const boardNode = (density: 'full' | 'compact') => (
    <DraftBoard
      density={density}
      room="real"
      onTheClockPickNo={drafting ? state.currentPickNo : undefined}
      board={board}
      teams={state.teams}
      rounds={state.rounds}
      myPicks={state.myPicks}
      userPicks={userPicks}
      seats={seats.map(toBoardSeat)}
      mySlot={state.userSlot}
      sport={sport === 'NFL' ? 'nfl' : 'nba'}
      reversalRound={state.reversalRound ?? 0}
      hideProvenanceDots
      hidePhotos
    />
  )

  // The live region is the first child of both the results and the room, so it stays mounted through the render in
  // which the game completes and the end-of-draft sentence is actually spoken (CR-08).
  const liveRegion = (
    <div className="hist-sr-only" role="status" aria-live="polite">
      {announcement}
    </div>
  )

  if (complete && state.result) {
    const kicker = `You drafted ${state.season.label}${state.dailyDate ? ` · ${dailyName('game of the day', state.dailyDate, state.dailyNumber, ', ')}` : ''}`
    // The award panel is not mounted during the reveal, so an XP or badge toast cannot give the ending away.
    if (replaying || (sawDrafting.current && !seasonDone && motionAllowed())) {
      return (
        <>
          {liveRegion}
          <div className="content tm tm-page tm-results">
            <h1 className="tm-kicker">{kicker}</h1>
            <HistoricalSeasonReveal
              game={state}
              result={state.result}
              onDone={() => {
                setSeasonDone(true)
                setReplaying(false)
              }}
            />
          </div>
        </>
      )
    }
    return (
      <>
        {liveRegion}
      <div className="content tm tm-page tm-results">
        <h1 className="tm-kicker">{kicker}</h1>
        <HistoricalResults
          game={state}
          result={state.result}
          voice="you"
          afterFinish={
            // Spec 074 T039: achievements are for a signed-in player's own free-play and daily games. A guest has none to
            // earn (the server answers 403), and a season the player picked earns nothing; neither mounts the panel.
            <>
              <ClaimLine />
              {!user ? (
              <section className="panel tm-signin tm-keep" aria-label={keepResultHeading}>
                <h2>{keepResultHeading}</h2>
                <p>{keepResultBody}</p>
                <SignInForm autoFocus={false} notes={[]} />
              </section>
            ) : state.chosenSeason ? (
              <p className="muted small tm-not-counted">{notCountedNote}</p>
            ) : (
              <HistoricalAwardPanel gameId={state.id} />
            )}
            </>
          }
          finishAction={
            <>
              <Link className="tm-play" to={tmHome(currentSite())}>
                Draft another year
              </Link>
              <button type="button" className="tm-btn quiet" onClick={() => setReplaying(true)}>
                Replay the season
              </button>
            </>
          }
        />
        <details className="how tm-more">
          <summary>Your draft board</summary>
          {boardNode('full')}
        </details>
        {/* Closed, with the counts in its summary so a caveat is never invisible while collapsed (as in the room). */}
        <details className="how tm-more">
          <summary>{disclosureSummary(state)}</summary>
          <SeasonDisclosure game={state} />
        </details>
        <p>
          <Link to={tmHome(currentSite())}>Back to Redraft</Link>
        </p>
      </div>
      </>
    )
  }

  const canPick = state.isUsersTurn && !revealing
  const topQueued = queuedCards[0] ?? null
  const draftFrom = (c: HistoricalPlayerCard) => void run('DRAFT', () => historicalPick(state.id, c.id))
  // Auto-pick takes the top queued player who is still there; with an empty queue it is the server's own auto-pick.
  const autoPick = () =>
    void run('PICK', () => (topQueued ? historicalPick(state.id, topQueued.id) : historicalAuto(state.id, 'PICK')))

  // The view lags the server during a reveal, so the legality of this request comes from the server's status: once
  // the server has finished the draft there is nothing to ask it, and "finish" just means end the reveal (CR-01).
  const autoFinish = () => {
    if (!reveal.serverDrafting) {
      reveal.skip()
      return
    }
    void run('FINISH', () => historicalAuto(state.id, 'FINISH'))
  }

  return (
    <>
      {liveRegion}
    <div className="content hist-content tm">
      <header className="hist-head">
        <h1 className="page-title">{`You're drafting ${state.season.label}`}</h1>
        <p className="muted small">You only know what each player did the season before. The real season plays out after the draft.</p>
      </header>
      <HistoricalRoomLayout
        queueCount={queuedCards.length}
        banner={
          <HistoricalClockBanner
            currentPickNo={state.currentPickNo}
            teams={state.teams}
            clockTeam={clockTeam}
            isUsersTurn={state.isUsersTurn}
            picksUntilYou={picksUntilYou}
            speed={speed}
            onSpeed={chooseSpeed}
            revealing={revealing}
            onSkip={reveal.skip}
            busy={busy}
            autoPickName={topQueued?.name ?? null}
            onAutoPick={autoPick}
            onAutoFinish={autoFinish}
          />
        }
        notices={error ? <div className="error">{error}</div> : undefined}
        players={
          <HistoricalPlayerList
            players={state.available}
            rankingLabel={state.season.rankingLabel}
            sport={sport}
            canPick={canPick}
            busy={busy != null}
            onPick={draftFrom}
            queued={queuedSet}
            onToggleQueue={(c) => queue.toggle(c.id)}
          />
        }
        queue={
          <HistoricalQueue
            ids={queuedCards.map((c) => c.id)}
            players={state.available}
            sport={sport}
            canPick={canPick}
            busy={busy != null}
            onPick={draftFrom}
            onMoveUp={queue.moveUp}
            onMoveDown={queue.moveDown}
            onRemove={queue.remove}
          />
        }
        board={boardNode('full')}
        rail={
          <HistoricalPickRail
            voice="you"
            picks={state.picks}
            teams={state.teams}
            rounds={state.rounds}
            userSlot={state.userSlot}
            currentPickNo={state.currentPickNo}
            reversalRound={state.reversalRound ?? 0}
            sport={sport}
            drafting={drafting}
          />
        }
        roster={
          <HistoricalRosterPanel
            picks={state.picks}
            userSlot={state.userSlot}
            rosterPositions={state.rosterPositions}
            sport={sport}
            rounds={state.rounds}
          />
        }
      />
      <details className="how hist-counts">
        <summary>{disclosureSummary(state)}</summary>
        <SeasonDisclosure game={state} />
      </details>
    </div>
    </>
  )
}
