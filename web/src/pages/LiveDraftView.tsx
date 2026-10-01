import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  createMockSessionFromDraft,
  getDraftPool,
  getDrafts,
  getRealDraftBoard,
  getSeats,
  streamSimulationQuietly,
  type PlayerRef,
  type PredictedPick,
  type RealPick,
  type SeatsResponse,
  type SimulationResult,
} from '../api'
import { ApiError } from '../apiError'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import AvailabilityPanel from '../components/AvailabilityPanel'
import DraftBoard from '../components/DraftBoard'
import LiveStatusBar from '../components/LiveStatusBar'
import OnBrandPanel, { OnBrandLine } from '../components/OnBrandPanel'
import PickFeed from '../components/PickFeed'
import ScarcityMeter from '../components/ScarcityMeter'
import PlayerCard from '../components/PlayerCard'
import SeatPopover from '../components/SeatPopover'
import TeamStrip from '../components/TeamStrip'
import { roundPickLabel } from '../roundPickLabel'
import { computeTeamNeeds, openPositions } from '../teamNeeds'
import { INSIGHT } from '../insightConstants'
import {
  buildFactInsight,
  buildStartState,
  fillsFor,
  isSurprise,
  likelyNext,
  missingPickNos,
  modelShare,
  nextPickFor,
  seatComplete,
  type PickInsight,
} from '../pickInsight'
import { onBrandReads } from '../onBrand'
import { positionScarcity } from '../scarcity'
import { readPickCardsPref, writePickCardsPref } from '../pickCardsPref'
import PickInsightCard from '../components/PickInsightCard'
import { prime, readSoundPref, speechSupported, writeSoundPref } from '../sound'
import { useAnnouncer } from '../useAnnouncer'
import { useLiveDraft } from '../useLiveDraft'
import type { FeedPick } from '../components/PickFeed'

// Same cap the mock view's resim uses; 500 was carried over from there, not
// derived. Cost on the live stack was measured 2026-09-30 (specs/012-draft-pick-insight/
// verification.md T023): ~150-220 ms per 500-iteration run on a 12-core dev
// machine, and 12 concurrent callers were all served within ~2.2 s with 0 x 429
// even at 2 permits. Railway is unmeasured. The DraftView.tsx comments are stale.
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
 * One projection run's outcome (spec 012 data-model DM-1). `asOfPick` is the
 * highest pick the run was conditioned on via an explicit startState, or null
 * when none was sent. `busy` means the server answered 429 after every retry
 * in streamSimulationQuietly: no result, and the next landed pick tries again.
 */
export interface StampedProjection {
  result?: SimulationResult
  asOfPick: number | null
  busy: boolean
}
const STAMP_RING = 2

/** `over` wins every overlap; result is oldest first. */
function mergeByPickNo(base: RealPick[], over: RealPick[]): RealPick[] {
  const byPickNo = new Map<number, RealPick>()
  for (const p of base) byPickNo.set(p.pickNo, p)
  for (const p of over) byPickNo.set(p.pickNo, p)
  return [...byPickNo.values()].sort((a, b) => a.pickNo - b.pickNo)
}

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
  // Every pick that has landed, from GET /drafts/{id}/board -- null until that
  // fetch succeeds (or forever, against a backend that cannot answer it), in
  // which case landedPicks falls back to the projection's prefix. See
  // landedPicks below.
  const [realPicks, setRealPicks] = useState<RealPick[] | null>(null)
  const realPicksRef = useRef<RealPick[] | null>(null)
  realPicksRef.current = realPicks
  // The fetch has answered, success or not. The pick card needs to know when
  // "what has already landed" is settled, so it can tell history from news.
  const [realPicksSettled, setRealPicksSettled] = useState(false)
  const [startTime, setStartTime] = useState<string | null>(null)
  const [result, setResult] = useState<SimulationResult | null>(null)
  const [resimming, setResimming] = useState(false)
  const [resimProgress, setResimProgress] = useState(0)
  const { error, notFound, setError, fail } = useFailure()
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
  // resimulate() runs from timers, so what it reads comes through refs, not
  // closed-over render state. Assigned below, once landedPicks/missingNos exist.
  const landedRef = useRef<RealPick[]>([])
  const missingRef = useRef<number[]>([])
  // The last STAMP_RING runs, newest last. A ref so the timer-driven run can
  // append without a stale closure; `stampsVersion` is bumped on every push so
  // components that read `stamps` re-render. (Chosen over ring-in-state to keep
  // the ring readable from timers, which Phase 6's freezing needs.)
  const stampRingRef = useRef<StampedProjection[]>([])
  const [stampsVersion, setStampsVersion] = useState(0)
  // asOfPick of the run currently in flight (null = none, or one sent without a
  // startState), so the card can say "updating".
  const [inFlightAsOf, setInFlightAsOf] = useState<number | null>(null)
  function pushStamp(s: StampedProjection) {
    stampRingRef.current = [...stampRingRef.current, s].slice(-STAMP_RING)
    setStampsVersion((v) => v + 1)
  }

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
    getSeats(draftId).then(setSeats).catch(fail)
    // Only for the pre-draft "starts at ..." line -- the live stream carries no
    // start time, and hardcoding one would be a lie in the source. A failure
    // here is not worth surfacing: the waiting copy just drops the clause.
    getDrafts()
      .then((ds) => setStartTime(ds.find((d) => d.sleeperDraftId === draftId)?.startTime ?? null))
      .catch(() => {})
  }, [draftId])

  function loadRealPicks() {
    getRealDraftBoard(draftId)
      .then((b) => {
        if (!mountedRef.current) return
        // The server's list is authoritative, but a state frame may have
        // delivered a pick newer than this response was read at -- keep it.
        setRealPicks((prev) => mergeByPickNo(prev ?? [], b.picks))
        setRealPicksSettled(true)
      })
      // Old backend, 404, network: realPicks stays null and landedPicks falls
      // back to the projection's prefix, which is what the page did before.
      .catch(() => {
        if (mountedRef.current) setRealPicksSettled(true)
      })
  }

  useEffect(() => {
    loadRealPicks()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId])

  // Keep realPicks whole as the draft moves. recentPicks is only the last 12,
  // so a pick that lands after the mount fetch would otherwise fall out of
  // every list once 12 more arrive. A gap -- the oldest pick in the frame is
  // further on than anything we know -- means we missed some (a reconnect, or
  // an autopick burst of more than 12), so ask the server for the lot.
  useEffect(() => {
    const known = realPicksRef.current
    if (!live || known == null || live.recentPicks.length === 0) return
    const highest = known.reduce((m, p) => Math.max(m, p.pickNo), 0)
    if (live.recentPicks[0].pickNo > highest + 1) loadRealPicks()
    if (live.recentPicks.some((p) => known.find((k) => k.pickNo === p.pickNo)?.player.id !== p.player.id)) {
      setRealPicks((prev) => (prev ? mergeByPickNo(prev, live.recentPicks) : prev))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live])

  // Refetch when the stream comes back after dropping: whatever landed while it
  // was down is not in any frame we will ever receive.
  const everConnectedRef = useRef(false)
  const droppedRef = useRef(false)
  useEffect(() => {
    if (connected) {
      if (droppedRef.current) loadRealPicks()
      droppedRef.current = false
      everConnectedRef.current = true
    } else if (everConnectedRef.current) {
      droppedRef.current = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected])

  async function resimulate() {
    if (resimmingRef.current) {
      pendingRef.current = true
      return
    }
    resimmingRef.current = true
    // All-or-nothing prefix from the landed list as of NOW (not as of the
    // render that scheduled this run): a coalesced re-run must see new picks.
    const { startState, asOfPick } = buildStartState(landedRef.current, missingRef.current)
    const seq = ++requestSeqRef.current
    const ac = new AbortController()
    abortRef.current = ac
    setResimming(true)
    setResimProgress(0)
    setInFlightAsOf(asOfPick)
    try {
      const r = await streamSimulationQuietly(
        {
          draftSleeperId: draftId,
          mySlot: mySlotRef.current,
          iterations: RESIM_ITERATIONS,
          temperature: TEMPERATURE,
          // Explicit only when the landed list is provably whole (R2): then the
          // result is conditioned on exactly what the card reasons over, and
          // asOfPick says so. With a hole or a missing sleeperId there is no
          // startState at all and the engine replays its own DB picks.
          ...(startState ? { startState } : {}),
        },
        setResimProgress,
        ac.signal,
      )
      if (!mountedRef.current || seq !== requestSeqRef.current) return
      setResult(r)
      pushStamp({ result: r, asOfPick, busy: false })
      setError(null)
    } catch (e) {
      if (ac.signal.aborted || !mountedRef.current || seq !== requestSeqRef.current) return
      // Survived streamSimulationQuietly's own retries: the server is saturated,
      // which is weather on draft night, not a page failure. No banner, no
      // second retry policy -- the next landed pick is the next attempt.
      if (e instanceof ApiError && e.status === 429) pushStamp({ asOfPick, busy: true })
      else fail(e)
    } finally {
      resimmingRef.current = false
      if (mountedRef.current && seq === requestSeqRef.current) {
        setResimming(false)
        setInFlightAsOf(null)
      }
      if (pendingRef.current) {
        pendingRef.current = false
        if (mountedRef.current) void resimulate()
      }
    }
  }

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

  // What has actually happened, oldest first. Facts, not a simulation: nothing
  // here waits on a projection (the 41b9426 invariant).
  //
  // `realPicks` is the whole draft so far, read from draft_pick through
  // GET /drafts/{id}/board on mount and kept current (see loadRealPicks).
  // `live.recentPicks` -- the last dozen, straight off the state frame -- wins
  // every overlap, because it is newer than any fetch.
  //
  // When the board fetch has failed there is no complete record, and the page
  // does what it did before: `result.board` below `picksMade` is *mostly* the
  // record (the engine replays every completed pick out of the DB in every
  // iteration, see this file's header), merged under recentPicks. "Mostly"
  // because `picksMade` moves the instant the poller sees a pick while `result`
  // is whatever the last simulation returned, so for the picks that landed
  // since, that prefix promotes the engine's *guess* to a fact -- recentPicks
  // winning the overlap is what keeps that out of the feed.
  const landedPicks = useMemo<RealPick[]>(() => {
    if (realPicks != null) return mergeByPickNo(realPicks, live?.recentPicks ?? [])
    const fromResult: RealPick[] = (result?.board ?? [])
      .filter((p) => p.pickNo <= picksMade)
      .map((p) => ({
        pickNo: p.pickNo,
        round: p.round,
        slot: p.slot,
        manager: p.manager,
        avatarId: p.avatarId,
        player: p.player,
      }))
    return mergeByPickNo(fromResult, live?.recentPicks ?? [])
  }, [realPicks, result, picksMade, live])

  // Everyone actually off the board -- no reveal-boundary subtlety here,
  // because the boundary is reality. Read off the landed list so a pick that
  // landed since the last resim started is not still "available" until the next
  // one finishes.
  const takenPlayerIds = useMemo(() => new Set(landedPicks.map((p) => p.player.id)), [landedPicks])

  // Same overlay `landedPicks` does, but kept as PredictedPick[] for the grid:
  // a landed real pick shown before the next resim lands is a fact, not a
  // guess, so it gets `isModal: true` (keeps the cell out of the "uncertain"
  // fade) and no alternatives rather than carrying over a stale projection's.
  // Overlays the whole landed list, not only the last dozen on the state frame:
  // before the first projection returns, a room opened at pick 31 would
  // otherwise paint rounds 1-2 blank despite knowing every one of those picks.
  const boardWithLive = useMemo<PredictedPick[]>(() => {
    if (!landedPicks.length) return result?.board ?? []
    const byPickNo = new Map((result?.board ?? []).map((p) => [p.pickNo, p]))
    for (const p of landedPicks) {
      byPickNo.set(p.pickNo, { ...p, probability: 1, isModal: true, alternatives: [] })
    }
    return [...byPickNo.values()].sort((a, b) => a.pickNo - b.pickNo)
  }, [result, landedPicks])

  const rosterPositions = seats?.rosterPositions ?? []

  // Fit is attached to the newest pick only -- it is the only row PickFeed
  // renders it on, and working it out costs a full needs computation per pick.
  //
  // Gated on having every pick the NEWEST PICK'S SEAT made, not on the whole
  // list: the fit clause is a claim about the roster that took the player
  // ("Fills RB2"), and a roster assembled from a partial history would state
  // that confidently while being wrong about it. A hole in some other seat's
  // picks does not touch this roster, so it must not silence the clause (review
  // F2: one unresolvable pick would otherwise mute the feed for the rest of the
  // draft). When the seat is not whole the feed shows the names -- which are
  // facts -- and says nothing about fit until it can say something true.
  const teamsCount = result?.teams ?? seats?.teams ?? live?.teams ?? 0
  // max() with the highest landed number: with no live state `picksMade` is 0
  // and would call every list complete.
  const missingNos = useMemo(
    () =>
      missingPickNos(
        landedPicks,
        Math.max(picksMade, landedPicks.length ? landedPicks[landedPicks.length - 1].pickNo : 0),
      ),
    [landedPicks, picksMade],
  )
  landedRef.current = landedPicks
  missingRef.current = missingNos
  const stamps = useMemo(() => stampRingRef.current, [stampsVersion])
  const highestLanded = landedPicks.length ? landedPicks[landedPicks.length - 1].pickNo : 0
  // A projection per landed pick, off the critical path. Commit 41b9426 took
  // the resim off every pick because a ~1.5s debounce + ~5s run sat between a
  // real pick and the board reflecting it. This PARTIALLY reverses that, and
  // the invariant it protected still holds: facts never wait on a projection.
  // `boardWithLive`/`landedPicks`/`takenPlayerIds` overlay landed picks onto
  // `result` the instant SSE delivers them, with no resim involved, and that
  // overlay stays exactly as it was. What the per-pick run buys is freshness
  // for the numbers that genuinely depend on a projection -- the pick card's
  // and meters' "what they will probably do next" figures -- which would
  // otherwise be conditioned on a draft that has since moved on. It runs
  // behind the same trailing debounce, coalescing and stale-response guard, so
  // a burst of picks is one run, and it never blocks a render or a fact.
  // Re-runs on [seats, mySlot] as before, plus whenever the highest landed
  // pickNo rises. Gated on `seats` rather than on `live` so the page is still
  // worth something when the stream is down: the engine reads the completed
  // picks out of the DB either way.
  useEffect(() => {
    if (!seats) return
    const id = window.setTimeout(() => void resimulate(), RESIM_DEBOUNCE_MS)
    return () => window.clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seats, mySlot, highestLanded])

  const feedPicks = useMemo<FeedPick[]>(() => {
    if (landedPicks.length === 0) return landedPicks
    const newest = landedPicks[landedPicks.length - 1]
    const complete =
      rosterPositions.length > 0 &&
      seatComplete(newest.slot, missingNos, teamsCount, seats?.reversalRound ?? 0)
    if (!complete) return landedPicks
    const priorRoster = landedPicks
      .filter((p) => p.slot === newest.slot && p.pickNo < newest.pickNo)
      .map((p) => p.player)
    // The same function the pick card calls, so the two cannot disagree.
    const slot = fillsFor(sport, rosterPositions, priorRoster, newest.player)
    // "Depth" rather than silence when nothing is open: a fifth receiver in
    // round 11 is a real thing to have noticed, and an absent clause reads as
    // "we didn't work it out" rather than "this filled nothing".
    return landedPicks.map((p, i) =>
      i === landedPicks.length - 1 ? { ...p, fit: slot ? `Fills ${slot}` : 'Depth' } : p,
    )
  }, [landedPicks, missingNos, rosterPositions, sport, teamsCount, seats?.reversalRound])

  // ---- Pick card lifecycle (spec 012, R9) ---------------------------------
  //
  // A card is for a pick that ARRIVES while you are looking: after the landed
  // list has first loaded (everything in it is history), with the tab visible,
  // the preference on, and you not on the clock. Anything else is history and
  // never pops up -- a room you join at pick 90 must not replay 90 cards.
  const [cardsOn, setCardsOn] = useState(() => readPickCardsPref())
  const [card, setCard] = useState<{ pickNo: number; autoFocus: boolean } | null>(null)
  const [cardPaused, setCardPaused] = useState(false)
  const seenThroughRef = useRef<number | null>(null)
  // Picks whose projection-dependent figures are final (DM-6). Nothing writes
  // it until the projection rows exist; reopening a pick reads it first.
  const frozenInsightsRef = useRef(new Map<number, PickInsight>())
  const onTheClock = slotKnown && live?.onTheClockSlot === mySlot

  function closeCard() {
    setCard(null)
    setCardPaused(false)
  }

  useEffect(() => {
    // Not ready until we know what "already happened" means: the state stream
    // is up and the board fetch has answered one way or the other.
    if (live == null || !realPicksSettled) return
    const newest = landedPicks.length ? landedPicks[landedPicks.length - 1].pickNo : 0
    if (seenThroughRef.current == null) {
      seenThroughRef.current = newest
      return
    }
    if (newest <= seenThroughRef.current) return
    // Advance even when we decline to show it, so a pick delivered while the
    // tab was hidden is not opened later by the next unrelated re-render.
    seenThroughRef.current = newest
    if (document.visibilityState !== 'visible' || !cardsOn || onTheClock) return
    setCard({ pickNo: newest, autoFocus: false })
  }, [live, realPicksSettled, landedPicks, cardsOn, onTheClock])

  // You are up: the card gets out of the way, and stays out (see above). The
  // newest feed row still updates, and a click on it opens the card on demand.
  useEffect(() => {
    if (onTheClock) closeCard()
  }, [onTheClock])

  // Hover or focus inside the card pauses this (the page, not the card, owns
  // the timer so a replacement card restarts the full delay).
  useEffect(() => {
    if (card == null || cardPaused) return
    const id = window.setTimeout(closeCard, INSIGHT.CARD_DISMISS_MS)
    return () => window.clearTimeout(id)
  }, [card, cardPaused])

  function openCardFor(pickNo: number) {
    // Asked for, so it takes focus (an auto-open never does).
    setCard({ pickNo, autoFocus: true })
  }

  function toggleCards() {
    const next = !cardsOn
    setCardsOn(next)
    writePickCardsPref(next)
    if (!next) closeCard()
  }

  const cardInsight = useMemo<PickInsight | null>(() => {
    if (card == null) return null
    const frozen = frozenInsightsRef.current.get(card.pickNo)
    if (frozen) return frozen
    const pick = landedPicks.find((p) => p.pickNo === card.pickNo)
    if (!pick || !seats) return null
    const fact = buildFactInsight(pick, landedPicks, seats.seats, sport, rosterPositions, {
      seatComplete: seatComplete(pick.slot, missingNos, teamsCount, seats.reversalRound),
    })
    // The projection-dependent half. Recomputed whenever a run lands or starts
    // (stamps / inFlightAsOf) until it is final, then frozen.
    const nextPickNo = nextPickFor(pick.slot, pick.pickNo, teamsCount, seats.rounds, seats.reversalRound)
    const landedIds = new Set(landedPicks.map((p) => p.player.id))
    const next = likelyNext(stamps, pick, nextPickNo, inFlightAsOf, landedIds)
    const ms = modelShare(stamps, pick)
    const insight: PickInsight = { ...fact, nextPickNo, likelyNext: next, modelShare: ms, surprise: isSurprise(ms) }
    // DM-6: freeze once final, so reopening an old card shows what was known
    // then. A 'none' that is only "no projection yet" is NOT final -- the run for
    // a just-landed pick has not started during its debounce, and freezing that
    // would pin the card to "not back yet" forever. Only 'no picks left' is.
    // A pick whose fit facts are untrusted is not frozen either (a later
    // backfill can correct them).
    const final = next.state === 'ready' || next.state === 'busy' || (next.state === 'none' && nextPickNo == null)
    if (final && fact.fitKnown) frozenInsightsRef.current.set(pick.pickNo, insight)
    return insight
  }, [card, landedPicks, seats, sport, rosterPositions, missingNos, teamsCount, stamps, inFlightAsOf])

  // ---- Scarcity meter + room read (spec 012 US4/US5) ------------------------
  //
  // The starter pool is the board's top S players, S = teams x starters. The
  // pool comes from GET /drafts/{id}/pool, fetched once per S. A failure (an
  // old backend without the endpoint) sets poolFailed and degrades ONLY the
  // meter row; nothing else on the page waits on it.
  const startersPerTeam = useMemo(
    () => computeTeamNeeds(sport, rosterPositions, []).length,
    [sport, rosterPositions],
  )
  const poolSize = (seats?.teams ?? 0) * startersPerTeam
  const [pool, setPool] = useState<PlayerRef[] | null>(null)
  const [poolFailed, setPoolFailed] = useState(false)
  const seatsLoaded = seats != null
  useEffect(() => {
    if (!seatsLoaded || poolSize <= 0) return
    let cancelled = false
    // Promise.resolve().then so a synchronous throw degrades the same way a
    // rejected fetch does.
    Promise.resolve()
      .then(() => getDraftPool(draftId, Math.min(poolSize, INSIGHT.POOL_LIMIT_MAX)))
      .then((p) => {
        if (cancelled) return
        setPool(p)
        setPoolFailed(false)
      })
      .catch(() => {
        if (!cancelled) setPoolFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [draftId, poolSize, seatsLoaded])

  // Only a projection pinned to EXACTLY the current landed state: the newest
  // non-busy stamp, and only when it was conditioned on the highest landed pick.
  // Anything older gives null, so expectedAtNext is absent rather than stale.
  const postPickResult = useMemo(() => {
    const done = stamps.filter((st) => !st.busy && st.result)
    const newest = done.length ? done[done.length - 1] : null
    return newest && highestLanded > 0 && newest.asOfPick === highestLanded ? (newest.result ?? null) : null
  }, [stamps, highestLanded])

  const myNextPick = useMemo(() => {
    if (!seats || !slotKnown) return null
    if (highestLanded > 0) return nextPickFor(mySlot, highestLanded, teamsCount, seats.rounds, seats.reversalRound)
    return result?.myPicks.find((p) => p > 0) ?? null
  }, [seats, slotKnown, highestLanded, mySlot, teamsCount, result])

  const scarcity = useMemo(
    () =>
      pool && poolSize > 0
        ? positionScarcity({
            sport,
            pool,
            teams: seats?.teams ?? 0,
            startersPerTeam,
            landed: landedPicks,
            postPickResult,
            myNextPick,
            slotKnown,
          })
        : null,
    [pool, poolSize, sport, seats?.teams, startersPerTeam, landedPicks, postPickResult, myNextPick, slotKnown],
  )

  const reads = useMemo(() => (seats ? onBrandReads(seats.seats, landedPicks) : []), [seats, landedPicks])

  // The two card extras are FACTS about landed picks, not projections, so they
  // are computed at render time for whichever card is open -- they are not part
  // of the frozen insight. Reopening an old card therefore shows the room as it
  // stands now, not as it stood then.
  const cardExtras = useMemo(() => {
    if (!cardInsight) return undefined
    const pick = cardInsight.pick
    const read = reads.find((r) => r.slot === pick.slot)
    const row = scarcity?.rows.find((r) => r.position === pick.player.position)
    let scarcityLine: string | undefined
    if (row && (row.running || row.leftNow <= INSIGHT.SCARCE_LEFT)) {
      const run =
        row.running && scarcity?.run
          ? ` · ${scarcity.run.count} of the last ${scarcity.run.window} were ${row.position}`
          : ''
      scarcityLine = `${row.position}: ${row.leftNow} of ${row.poolSize} starter-pool players left${run}`
    }
    return {
      ...(read ? { onBrand: <OnBrandLine read={read} /> } : {}),
      ...(scarcityLine ? { scarcityLine } : {}),
    }
  }, [cardInsight, reads, scarcity])

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
          board={boardWithLive}
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

  if (notFound) return <NotFound what="draft" />

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
        <PickFeed
          picks={feedPicks}
          teams={result?.teams ?? seats?.teams ?? 0}
          sport={sport}
          // With cards off the rows stay plain rows: "Pick cards off" means
          // no card, including the one a click would have opened.
          onPickClick={cardsOn ? openCardFor : undefined}
        />

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
            <TeamStrip needs={myNeeds} sport={sport} />
          </div>
        )}

        {/* Under the team block, and in the same place when the seat is unknown:
            the team block is hidden then, the meters are not. They always show,
            whatever the pick-cards preference says. */}
        {seats && (pool != null || poolFailed) && (
          <div className="live-meters">
            <ScarcityMeter
              scarcity={scarcity}
              failed={poolFailed}
              myNextPickLabel={myNextPick != null && teamsCount > 0 ? roundPickLabel(myNextPick, teamsCount) : undefined}
            />
            <OnBrandPanel
              reads={reads}
              myManager={slotKnown ? seats.seats.find((x) => x.slot === mySlot)?.manager : null}
            />
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
                className={cardsOn ? 'chip on' : 'chip'}
                onClick={toggleCards}
                aria-pressed={cardsOn}
                title={
                  cardsOn
                    ? 'Stop showing a card for each pick as it lands'
                    : 'Show a card for each pick as it lands: how it fits that roster'
                }
              >
                {cardsOn ? 'Pick cards on' : 'Pick cards off'}
              </button>
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
                {/* Over the top of the stage only, never above it: the status
                    bar, feed, team strip and meters all sit outside. */}
                {cardInsight && (
                  <PickInsightCard
                    insight={cardInsight}
                    teams={teamsCount}
                    autoFocus={card?.autoFocus ?? false}
                    onClose={closeCard}
                    onPauseChange={setCardPaused}
                    extras={cardExtras}
                  />
                )}
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
                  // The projection assumes a seat until the reader's is known,
                  // so its survival numbers would answer for somebody else's
                  // picks. Hold them back; the tiered list still shows.
                  noAvailabilityReason={slotKnown ? undefined : 'Availability appears once your seat is known.'}
                  recentPicks={landedPicks.map((p) => p.player)}
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
              onBrand={reads.find((r) => r.slot === openSeat.slot)}
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
