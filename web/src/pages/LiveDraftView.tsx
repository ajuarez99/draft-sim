import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import {
  getDrafts,
  getSeats,
  streamSimulation,
  type PredictedPick,
  type SeatsResponse,
  type SimulationResult,
} from '../api'
import AvailabilityPanel from '../components/AvailabilityPanel'
import DraftBoard from '../components/DraftBoard'
import LiveStatusBar from '../components/LiveStatusBar'
import PlayerCard from '../components/PlayerCard'
import SeatPopover from '../components/SeatPopover'
import { useLiveDraft } from '../useLiveDraft'

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

  const { live, connected, secondsSinceContact, error: liveError } = useLiveDraft(draftId)

  const [seats, setSeats] = useState<SeatsResponse | null>(null)
  const [startTime, setStartTime] = useState<string | null>(null)
  const [result, setResult] = useState<SimulationResult | null>(null)
  const [resimming, setResimming] = useState(false)
  const [resimProgress, setResimProgress] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [openPick, setOpenPick] = useState<PredictedPick | null>(null)
  const [openSeatSlot, setOpenSeatSlot] = useState<number | null>(null)

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
          onCellClick={setOpenPick}
          onSeatClick={setOpenSeatSlot}
        />
      ) : null,
    [seats, result, live, mySlot, slotKnown],
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
          onSeatClick={setOpenSeatSlot}
          onTracked={() => getSeats(draftId).then(setSeats).catch(() => {})}
        />

        <div className="board-panel">
          <section className="panel">
            <div className="live-panel-head">
              <span className="muted tiny">
                {resimming
                  ? `Simulating the rest of the draft… ${Math.round(resimProgress * 100)}%`
                  : result
                    ? live
                      ? `Board projected past pick ${picksMade}`
                      : 'Full projection — no live position to project from'
                    : 'No projection yet'}
                {/* The crimson cells and the availability columns are both
                    "slot N", so say which N, and say when N is only a
                    fallback rather than something anyone confirmed. */}
                {result && ` · slot ${mySlot}${slotKnown ? '' : ' (assumed — click your seat)'}`}
              </span>
              <button
                className="chip"
                onClick={() => void resimulate()}
                disabled={resimming || !seats}
                title="Re-run the projection now"
              >
                re-run
              </button>
            </div>
            {resimming && (
              <div className="progress live-resim-progress">
                <div className="progress-bar" style={{ width: `${Math.round(resimProgress * 100)}%` }} />
              </div>
            )}

            {seats && (
              <div className="board-stage">
                {board}
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

        {result && (
          <div className="lower-grid">
            <AvailabilityPanel
              availability={result.availability}
              myPicks={upcomingMyPicks}
              teams={result.teams}
              pickedPlayerIds={takenPlayerIds}
            />
          </div>
        )}
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
