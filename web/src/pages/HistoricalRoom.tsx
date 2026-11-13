import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  getHistoricalGame,
  historicalAuto,
  historicalPick,
  type HistoricalGameState,
  type HistoricalPlayerCard,
  type PredictedPick,
  type Seat,
  type MockSeat,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import DraftBoard from '../components/DraftBoard'
import HistoricalPlayerList from '../components/HistoricalPlayerList'
import HistoricalQueue from '../components/HistoricalQueue'
import HistoricalPickRail from '../components/HistoricalPickRail'
import HistoricalRosterPanel from '../components/HistoricalRosterPanel'
import HistoricalClockBanner from '../components/HistoricalClockBanner'
import HistoricalRoomLayout from '../components/HistoricalRoomLayout'
import HistoricalResults from '../components/HistoricalResults'
import HistoricalAwardPanel from '../components/HistoricalAwardPanel'
import PageHeader from '../components/PageHeader'
import { LoadingScreen } from '../components/Skeleton'
import { cardToPlayerRef, coverageText, historicalSeats, sportOf, statLong, teamName } from '../historicalCopy'
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
                  <strong>{statLong(k)}</strong> <span className="muted">{why}</span>
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

  const { load } = reveal
  useEffect(() => {
    announced.current = null
    getHistoricalGame(id)
      .then(load)
      .catch((e) => fail(e))
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

  const header = (
    <PageHeader
      eyebrow="Time Machine"
      title={
        complete
          ? `You drafted ${state.season.label}`
          : state.status === 'ABANDONED'
            ? `${state.season.label} (abandoned)`
            : `You're drafting ${state.season.label}`
      }
      sub={
        complete
          ? 'The season is in the books. Here is how your team did.'
          : "You see each player's line from the season before. The real season plays out once the draft is over."
      }
    />
  )

  if (state.status === 'ABANDONED') {
    return (
      <div className="content">
        {header}
        <p className="muted">This game was abandoned.</p>
        <Link to="/history">Back to Time Machine</Link>
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
    return (
      <>
        {liveRegion}
      <div className="content">
        {header}
        <SeasonDisclosure game={state} />
        <HistoricalResults game={state} result={state.result} afterFinish={<HistoricalAwardPanel gameId={state.id} />} />
        <details className="how">
          <summary>Your draft board</summary>
          {boardNode('full')}
        </details>
        <p>
          <Link to="/history">Back to Time Machine</Link>
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
    <div className="content hist-content">
      <header className="hist-head">
        <h1 className="page-title">{`You're drafting ${state.season.label}`}</h1>
        <p className="muted small">
          Time Machine. You see each player's line from the season before; the real season plays out after the draft.
        </p>
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
