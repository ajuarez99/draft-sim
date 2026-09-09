import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  createMockSessionFromDraft,
  getDrafts,
  getSeats,
  streamSimulation,
  type PredictedPick,
  type RealPick,
  type SeatsResponse,
  type SimulationResult,
} from '../api'
import AvailabilityPanel from '../components/AvailabilityPanel'
import DraftBoard from '../components/DraftBoard'
import LiveStatusBar from '../components/LiveStatusBar'
import PickFeed from '../components/PickFeed'
import PlayerCard from '../components/PlayerCard'
import SeatPopover from '../components/SeatPopover'
import TeamStrip from '../components/TeamStrip'
import { roundPickLabel } from '../roundPickLabel'
import { computeTeamNeeds, fitSlot, openPositions } from '../teamNeeds'
import { prime, readSoundPref, speechSupported, writeSoundPref } from '../sound'
import { useAnnouncer } from '../useAnnouncer'
import { useLiveDraft } from '../useLiveDraft'
import type { FeedPick } from '../components/PickFeed'

// Same cap the mock view's resim uses. The iteration-count comments in
// DraftView.tsx predate the be423eb hot-path refactor and their wall-clock
// figures are stale; this run's real cost on the live stack is UNMEASURED as
// of 2026-09-02. 500 is carried over because it is what the mock view has been
// running at all along, not because a number was checked.
const RESIM_ITERATIONS = 500

// Sleeper's poller can deliver several picks inside one tick -- an autopick
// run empties four seats at once -- and each of those would otherwise start
// its own full simulation. A trailing debounce collapses a burst into one run
// that already knows about every pick in it.
const RESIM_DEBOUNCE_MS = 1500

// Frozen at the engine default rather than exposed: there is no "chaos" slider
// on a live draft, the room is doing whatever it is doing.
const TEMPERATURE = 1.0

const DEFAULT_SLOT = 1

/**
 * The live draft room: the same board component the mock view uses, with the
 * revealed boundary driven by reality (the backend's picksMade) instead of an
 * animation timer, and a fresh simulation of the remaining picks every time
 * reality moves.
 *
 * Deliberately NOT a fork of DraftView. That page's central mechanism is
 * useRevealedBoard's 450ms animated reveal of a *predicted* board, and every
 * piece of machinery around it (pausing at your picks, PickPrompt, userPicks,
 * startState) exists to let you play against a prediction. None of that
 * applies here: the picks that have landed are facts, and the only thing to
 * decide is what to do about the ones that haven't.
 *
 * The engine needs no help replaying those facts either. SimulationService
 * .resolveStartState already falls back to the DB picks the poller writes, and
 * DraftSimulator replays them identically in every iteration -- so they come
 * back at ~100% and render as landed cells with no client-supplied startState
 * and no backend change.
 */
export default function LiveDraftView() {
  const { draftId = '' } = useParams<{ draftId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const { live, connected, secondsSinceContact, error: liveError } = useLiveDraft(draftId)

  const [seats, setSeats] = useState<SeatsResponse | null>(null)
  const [startTime, setStartTime] = useState<string | null>(null)
  const [result, setResult] = useState<SimulationResult | null>(null)
  const [resimming, setResimming] = useState(false)
  const [resimProgress, setResimProgress] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [openPick, setOpenPick] = useState<PredictedPick | null>(null)
  const [openSeatSlot, setOpenSeatSlot] = useState<number | null>(null)
  const [forking, setForking] = useState(false)
  const [forkError, setForkError] = useState<string | null>(null)
  // Read once, from the same page load's localStorage -- the *preference*
  // persists. The browser's permission to make noise does not: see sound.ts.
  const [sound, setSound] = useState(() => readSoundPref())

  // An explicit ?slot= always wins; otherwise the backend's own owner match.
  // No auto-adopt effect is needed here (unlike DraftView) because this reads
  // straight through to `seats` rather than round-tripping through the URL --
  // so there is no frame where DEFAULT_SLOT is painted as "you" while seats
  // are still loading, and nothing to un-adopt later.
  const slotParam = searchParams.get('slot')
  const mySlot = slotParam ? Number(slotParam) : (seats?.mySlot ?? DEFAULT_SLOT)
  // Stricter than DraftView's version on purpose: there, DEFAULT_SLOT is a
  // starting point you are invited to correct before pressing start. Here the
  // draft is happening, and painting a crimson "you" on slot 1 because nobody
  // has said otherwise is a claim about reality that might be wrong. The
  // simulation still needs *a* slot, so DEFAULT_SLOT stays the request's
  // fallback -- it just doesn't get drawn as fact.
  const slotKnown = slotParam != null || (seats != null && seats.mySlot != null)

  // SimulationResult has no `sport` of its own (see DraftView's identical
  // comment); `seats` does (LeagueController.seats(), Phase 6) and is fetched
  // independently of a projection.
  const sport = seats?.sport ?? 'nfl'

  // Bumped at the start of every simulation, read only for identity -- a
  // response applies itself only if it is still the newest request.
  const requestSeqRef = useRef(0)
  // Synchronous reentrancy lock, the same pattern (and for the same reason) as
  // DraftView.choosePick's choosingRef: `resimming` is last-render state, so
  // two calls racing before React commits the first setResimming(true) would
  // both sail past a state check. A ref mutation is synchronous.
  const resimmingRef = useRef(false)
  // Coalesce, don't cancel. If picks land while a run is in flight, we do not
  // abort it (the work is most of the way done and the next run has to redo
  // all of it) -- we remember that one more run is owed and fire exactly one
  // from the finally block, however many picks arrived meanwhile.
  const pendingRef = useRef(false)
  const abortRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)
  const mySlotRef = useRef(mySlot)
  mySlotRef.current = mySlot

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      // Live mode holds an SSE stream open alongside this one; leaving a
      // simulation reading after the page is gone means the backend keeps
      // burning cores on a board nobody will see.
      abortRef.current?.abort()
    }
  }, [])

  useEffect(() => {
    getSeats(draftId).then(setSeats).catch((e) => setError(e.message))
    // Only for the pre-draft "starts at ..." line -- the live stream carries no
    // start time, and hardcoding one would be a lie in the source. A failure
    // here is not worth surfacing: the waiting copy just drops the clause.
    getDrafts()
      .then((ds) => setStartTime(ds.find((d) => d.sleeperDraftId === draftId)?.startTime ?? null))
      .catch(() => {})
  }, [draftId])

  async function resimulate() {
    if (resimmingRef.current) {
      pendingRef.current = true
      return
    }
    resimmingRef.current = true
    const seq = ++requestSeqRef.current
    const ac = new AbortController()
    abortRef.current = ac
    setResimming(true)
    setResimProgress(0)
    try {
      const r = await streamSimulation(
        {
          draftSleeperId: draftId,
          mySlot: mySlotRef.current,
          iterations: RESIM_ITERATIONS,
          temperature: TEMPERATURE,
          // No startState on purpose: the completed picks are already in the
          // DB (the poller writes them) and the engine replays them itself.
          // A client-supplied prefix here would be the frontend telling the
          // backend what the backend already knows for a fact.
        },
        setResimProgress,
        ac.signal,
      )
      if (!mountedRef.current || seq !== requestSeqRef.current) return
      setResult(r)
      setError(null)
    } catch (e) {
      if (ac.signal.aborted || !mountedRef.current || seq !== requestSeqRef.current) return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      resimmingRef.current = false
      if (mountedRef.current && seq === requestSeqRef.current) setResimming(false)
      if (pendingRef.current) {
        pendingRef.current = false
        if (mountedRef.current) void resimulate()
      }
    }
  }

  // Reality moved (or you changed which seat is yours) -> the board past it is
  // out of date. Gated on `seats` rather than on `live` so the page is still
  // worth something when the stream is down: the engine reads the completed
  // picks out of the DB either way, so a projection is available even with no
  // live state at all -- it just can't say where the draft has got to.
  useEffect(() => {
    if (!seats) return
    const id = window.setTimeout(() => void resimulate(), RESIM_DEBOUNCE_MS)
    return () => window.clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live?.picksMade, seats, mySlot])

  async function forkToMock() {
    setForking(true)
    setForkError(null)
    try {
      const session = await createMockSessionFromDraft(draftId, slotKnown ? mySlot : undefined)
      navigate(`/mock/${session.id}`)
    } catch (e) {
      setForkError(e instanceof Error ? e.message : String(e))
      setForking(false)
    }
  }

  const picksMade = live?.picksMade ?? 0

  // Your picks that are still ahead of the draft. A pick that has already
  // landed reads ~100% everywhere (every iteration replays it), which is dead
  // information crowding out the numbers this panel exists for.
  const upcomingMyPicks = useMemo(
    () => (result ? result.myPicks.filter((p) => p > picksMade) : []),
    [result, picksMade],
  )

  // Everyone actually off the board, straight off the landed prefix -- no
  // reveal-boundary subtlety here, because the boundary is reality.
  const takenPlayerIds = useMemo(
    () => new Set((result?.board ?? []).filter((p) => p.pickNo <= picksMade).map((p) => p.player.id)),
    [result, picksMade],
  )

  // What has actually happened, oldest first -- two sources, and the order of
  // preference matters.
  //
  // `result.board` below `picksMade` is *mostly* the record of what happened:
  // the engine replays every completed pick out of the DB identically in every
  // iteration (see this file's header). But `picksMade` moves the instant the
  // poller sees a pick, and `result` is whatever the last simulation returned
  // -- so for the handful of picks that landed since, that filter was quietly
  // promoting the engine's *guess* at those cells to a fact and printing it in
  // the feed as one.
  //
  // `live.recentPicks` is the fix and the reason the backend now sends it: the
  // last dozen picks straight out of draft_pick, with no simulation between
  // them and the reader. They win every overlap, and they are also what makes
  // the feed work at all before the first projection ever returns.
  const landedPicks = useMemo<RealPick[]>(() => {
    const byPickNo = new Map<number, RealPick>()
    for (const p of result?.board ?? []) {
      if (p.pickNo > picksMade) continue
      byPickNo.set(p.pickNo, {
        pickNo: p.pickNo,
        round: p.round,
        slot: p.slot,
        manager: p.manager,
        player: p.player,
      })
    }
    for (const p of live?.recentPicks ?? []) byPickNo.set(p.pickNo, p)
    return [...byPickNo.values()].sort((a, b) => a.pickNo - b.pickNo)
  }, [result, picksMade, live])

  const rosterPositions = seats?.rosterPositions ?? []

  // Fit is attached to the newest pick only -- it is the only row PickFeed
  // renders it on, and working it out costs a full needs computation per pick.
  //
  // Gated on having the WHOLE landed list, not just the last dozen: the fit
  // clause is a claim about the roster that took the player ("Fills RB2"), and
  // a roster assembled from a partial history would state that confidently
  // while being wrong about it. Before the first projection returns we have
  // only `live.recentPicks`, so the feed shows the names -- which are facts --
  // and says nothing about fit until it can say something true.
  const feedPicks = useMemo<FeedPick[]>(() => {
    if (landedPicks.length === 0) return landedPicks
    const complete = landedPicks.length === picksMade && rosterPositions.length > 0
    if (!complete) return landedPicks
    const newest = landedPicks[landedPicks.length - 1]
    const priorRoster = landedPicks
      .filter((p) => p.slot === newest.slot && p.pickNo < newest.pickNo)
      .map((p) => p.player)
    const slot = fitSlot(sport, newest.player.position, computeTeamNeeds(sport, rosterPositions, priorRoster))
    // "Depth" rather than silence when nothing is open: a fifth receiver in
    // round 11 is a real thing to have noticed, and an absent clause reads as
    // "we didn't work it out" rather than "this filled nothing".
    return landedPicks.map((p, i) =>
      i === landedPicks.length - 1 ? { ...p, fit: slot ? `Fills ${slot}` : 'Depth' } : p,
    )
  }, [landedPicks, picksMade, rosterPositions, sport])

  // Your own roster, off the same landed list rather than through
  // teamNeeds.draftedSoFar: that helper exists for the mock room, where the
  // roster is your *confirmed* picks against a reveal boundary that can sit
  // mid-board. Here there is no boundary and no confirming -- your team is
  // whatever landed in your seat.
  const myNeeds = useMemo(
    () =>
      computeTeamNeeds(
        sport,
        rosterPositions,
        landedPicks.filter((p) => p.slot === mySlot).map((p) => p.player),
      ),
    [sport, rosterPositions, landedPicks, mySlot],
  )
  const myOpenSlots = useMemo(() => openPositions(sport, myNeeds), [sport, myNeeds])
  const startersSet = myNeeds.filter((n) => n.player != null).length

  // Fed from `landedPicks` rather than `feedPicks` on purpose: what is spoken
  // is the manager and the name, both of which are facts on the state frame,
  // and none of it waits on the fit clause's "do we have the whole history"
  // gate. The chime is gated on slotKnown for the same reason the crimson
  // cells are -- chiming for slot 1's turn because nobody has said whose seat
  // is whose would send someone to the board for a pick that isn't theirs.
  useAnnouncer(
    sound,
    landedPicks.length > 0 ? landedPicks[landedPicks.length - 1] : null,
    slotKnown && live?.onTheClockSlot === mySlot,
  )

  function toggleSound() {
    const next = !sound
    setSound(next)
    writeSoundPref(next)
    // Inside the click, which is the browser's condition for allowing any of
    // this to make a sound at all.
    if (next) prime()
  }

  // Memoized so the once-a-second freshness tick (which re-renders this page by
  // design -- it is the one reading that has to stay current) doesn't re-render
  // 210 board cells with it.
  const board = useMemo(
    () =>
      seats ? (
        <DraftBoard
          board={result?.board ?? []}
          teams={result?.teams ?? seats.teams}
          rounds={result?.rounds ?? seats.rounds}
          myPicks={result?.myPicks ?? []}
          userPicks={{}}
          // Reality, not an animation timer -- and `undefined` (show the whole
          // projection) rather than 0 when there is no live state to read it
          // from, since "nothing has been drafted" and "we don't know how much
          // has been drafted" are different claims.
          revealedThrough={live ? live.picksMade : undefined}
          seats={seats.seats}
          mySlot={slotKnown ? mySlot : undefined}
          sport={sport}
          reversalRound={seats.reversalRound}
          onCellClick={setOpenPick}
          onSeatClick={setOpenSeatSlot}
        />
      ) : null,
    [seats, result, live, mySlot, slotKnown, sport],
  )

  const waiting = live == null || live.status === 'pre_draft'
  const waitingTitle =
    live == null
      ? connected
        ? 'Connecting…'
        : 'Not connected'
      : 'Waiting for the draft to start'
  const startsAt = startTime
    ? new Date(startTime).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
    : null
  const waitingDetail =
    live == null
      ? connected
        ? 'Opened the live stream, waiting for the first state frame.'
        : "The live stream isn't answering — the backend may not have this endpoint yet. Retrying every few seconds; seats and the board below are still real."
      : live.seatsMapped === 0
        ? 'Waiting for the commissioner to set the draft order.'
        : `${live.seatsMapped} seats mapped${startsAt ? ` · starts ${startsAt}` : ''}`

  return (
    <>
      {error && <div className="error">{error}</div>}
      {liveError && <div className="error">{liveError}</div>}

      <div className="content">
        <LiveStatusBar
          draftId={draftId}
          live={live}
          connected={connected}
          secondsSinceContact={secondsSinceContact}
          seats={seats?.seats ?? []}
          mySlot={slotKnown ? mySlot : undefined}
          nextOwnPick={upcomingMyPicks[0] ?? null}
          onSeatClick={setOpenSeatSlot}
          onTracked={() => getSeats(draftId).then(setSeats).catch(() => {})}
        />

        {/* The room where a position run matters most: these picks are real
            and there is no rewinding them. Same component the other two rooms
            use, fed from the landed prefix. */}
        <PickFeed picks={feedPicks} teams={result?.teams ?? seats?.teams ?? 0} sport={sport} />

        {/* Your team, on draft night. Gated on slotKnown for the same reason
            the crimson board cells are: painting slot 1's roster as yours
            because nobody has said otherwise is a claim about reality that
            might be wrong. */}
        {slotKnown && myNeeds.length > 0 && (
          <div className="live-team">
            <strong className="cond">Your team</strong>
            <span className="muted tiny">
              {startersSet} of {myNeeds.length} starters
            </span>
            <TeamStrip needs={myNeeds} />
          </div>
        )}

        <div className="board-panel">
          <section className="panel">
            <div className="live-panel-head">
              <span className="muted tiny">
                {resimming
                  ? `Simulating the rest of the draft… ${Math.round(resimProgress * 100)}%`
                  : result
                    ? live
                      ? // "projected past pick 210" is also nonsense on a
                        // finished draft -- there is nothing past the last pick
                        // to project, and every cell on the board is a fact.
                        picksMade >= live.totalPicks
                        ? 'Every pick is in — nothing left to project'
                        : `Picks after ${roundPickLabel(picksMade + 1, live.teams)} are projected`
                      : 'Projected from scratch — no live position to project from'
                    : 'No projection yet'}
                {/* The crimson cells and the availability columns are both
                    "slot N", so say which N, and say when N is only a
                    fallback rather than something anyone confirmed. */}
                {result && ` · slot ${mySlot}${slotKnown ? '' : ' (assumed — click your seat)'}`}
              </span>
              {speechSupported() && (
                <button
                  className={sound ? 'chip on' : 'chip'}
                  onClick={toggleSound}
                  aria-pressed={sound}
                  title={
                    sound
                      ? 'Stop reading picks out loud'
                      : 'Read each pick out loud, and chime when your turn comes up'
                  }
                >
                  {sound ? '🔊 Announcing' : '🔈 Announce picks'}
                </button>
              )}
              <button
                className="chip"
                onClick={() => void resimulate()}
                disabled={resimming || !seats}
                title="Re-simulate the rest of the draft from where it stands now"
              >
                Project again
              </button>
              <button
                className="chip on"
                onClick={() => void forkToMock()}
                disabled={forking || live?.status !== 'drafting'}
                title="Start an interactive mock draft picking up from where this live draft is right now"
              >
                {forking ? 'Forking…' : 'Continue as a mock →'}
              </button>
            </div>
            {forkError && <div className="error tiny">{forkError}</div>}
            {resimming && (
              <div className="progress live-resim-progress">
                <div className="progress-bar" style={{ width: `${Math.round(resimProgress * 100)}%` }} />
              </div>
            )}

            {seats && (
              <div className="board-stage">
                {board}
                {/* Same floating sheet as the mock page (§E) -- the board owns
                    the whole content area on both. `started` here is "there is
                    a projection to read options out of", which is what the old
                    `result &&` gate below the board was saying too. */}
                <AvailabilityPanel
                  availability={result?.availability ?? []}
                  myPicks={upcomingMyPicks}
                  teams={result?.teams ?? seats.teams}
                  pickedPlayerIds={takenPlayerIds}
                  started={result != null}
                  sport={sport}
                  // Only when the seat is known -- "fills a need" against
                  // somebody else's roster is worse than no tag at all.
                  openSlots={slotKnown ? myOpenSlots : undefined}
                />
                {waiting && !result && (
                  <div className="start-overlay">
                    <div className="start-overlay-cta">
                      <h2 className="cond">{waitingTitle}</h2>
                      <p className="muted small">{waitingDetail}</p>
                      {resimming && (
                        <p className="muted tiny">
                          Projecting the board meanwhile… {Math.round(resimProgress * 100)}%
                        </p>
                      )}
                    </div>
                  </div>
                )}
              </div>
            )}
            {/* Once a projection exists there is something worth looking at
                underneath, so the same waiting copy shrinks to a line rather
                than covering the board with it. */}
            {waiting && result && (
              <div className={`live-waiting${live == null ? ' offline' : ''}`}>
                <strong className="cond">{waitingTitle}</strong>
                <span className="muted small">{waitingDetail}</span>
              </div>
            )}
          </section>
        </div>

      </div>

      {openPick && result && (
        <PlayerCard pick={openPick} teams={result.teams} onClose={() => setOpenPick(null)} />
      )}

      {openSeatSlot != null &&
        seats &&
        (() => {
          const openSeat = seats.seats.find((s) => s.slot === openSeatSlot)
          return openSeat ? (
            <SeatPopover
              seat={openSeat}
              sport={sport}
              isMe={openSeat.slot === mySlot}
              onChanged={() => getSeats(draftId).then(setSeats).catch(() => {})}
              onClose={() => setOpenSeatSlot(null)}
              onMakeMine={() => {
                setSearchParams(
                  (prev) => {
                    const next = new URLSearchParams(prev)
                    next.set('slot', String(openSeat.slot))
                    return next
                  },
                  { replace: true },
                )
                setOpenSeatSlot(null)
              }}
            />
          ) : null
        })()}
    </>
  )
}
