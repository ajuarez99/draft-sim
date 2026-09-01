import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import {
  getSeats,
  streamSimulation,
  type PlayerRef,
  type PredictedPick,
  type SeatsResponse,
  type SimulationResult,
} from '../api'
import DraftBoard from '../components/DraftBoard'
import AvailabilityPanel from '../components/AvailabilityPanel'
import SeatList from '../components/SeatList'
import ConfidenceNote from '../components/ConfidenceNote'
import RevealScrubber from '../components/RevealScrubber'
import PlayerCard from '../components/PlayerCard'
import PickPrompt from '../components/PickPrompt'
import PlayerPicker from '../components/PlayerPicker'
import { useRevealedBoard } from '../useRevealedBoard'
import { draftedSoFar } from '../teamNeeds'

// Every league, of any size, has a slot 1 -- unlike the single-league app's old
// hardcoded 11, this stays a valid seat no matter which draft the picker opens.
const DEFAULT_SLOT = 1

// Measured live against the real fantasy(heart) board (see
// claude/reactive-resimulation.md's acceptance criterion #7): 2000 iterations
// took ~18.5-18.8s wall clock for a resim at pick 11 of 210, which does not
// match the UI's own "may take a few seconds" copy. 500 iterations took
// ~4.9s -- cost scales roughly linearly with iteration count (not dominated
// by the fixed ProfileService.fit() cost every simulate() call pays, as
// originally worried), so a lower cap directly buys a faster resim rather
// than hitting a floor.
//
// 500 is now the default for the FIRST run too, not just the resim cap.
// Why 500 rather than "as many as we can afford": the standard error of a
// displayed proportion at p = 0.10 is +/-1.3 points at n = 500 against
// +/-0.67 at n = 2000, and every probability in this UI is rounded to a whole
// percent next to copy that already says low percentages mean the model does
// not know. 2000 was buying precision the display cannot show, at 4x the wall
// clock. See claude/board-first-layout-and-pick-latency.md §B6.
const DEFAULT_ITERATIONS = 500

// Kept as its own constant even though it now equals DEFAULT_ITERATIONS: the
// runs dropdown still offers 1000/2000, and a resim of a 2000-run board must
// still come back fast enough to not strand the reveal.
const RESIM_ITERATION_CAP = 500

export default function DraftView() {
  const { draftId = '' } = useParams<{ draftId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const slotParam = searchParams.get('slot')
  const mySlot = slotParam ? Number(slotParam) : DEFAULT_SLOT

  const [iterations, setIterations] = useState(DEFAULT_ITERATIONS)
  const [temperature, setTemperature] = useState(1.0)

  const [seats, setSeats] = useState<SeatsResponse | null>(null)
  const [result, setResult] = useState<SimulationResult | null>(null)
  const [progress, setProgress] = useState(0)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [seatsDirty, setSeatsDirty] = useState(false)
  const [openPick, setOpenPick] = useState<PredictedPick | null>(null)
  const [userPicks, setUserPicks] = useState<Record<number, PlayerRef>>({})
  const [pickerOpen, setPickerOpen] = useState(false)
  const [resimming, setResimming] = useState(false)
  const [resimProgress, setResimProgress] = useState(0)

  // Bumped only by run() -- passed to useRevealedBoard as its resetKey, so a
  // resim's setResult() (which changes `board`'s identity but not this) does
  // not restart the reveal from pick 1. See useRevealedBoard.ts and
  // claude/reactive-resimulation.md §3, "Why run()'s existing 'fresh board'
  // reset must NOT fire on a resim".
  const [runSeq, setRunSeq] = useState(0)

  // Bumped at the start of *every* streamSimulation call (a fresh run() or a
  // resim), never read for its value beyond identity -- exists purely so a
  // response can tell whether it's still the most recent request before
  // applying itself. See the stale-response guard in choosePick/run() below.
  const requestSeqRef = useRef(0)

  // Synchronous reentrancy lock for choosePick -- see its own comment.
  const choosingRef = useRef(false)

  // Tracks whether the *current* URL slot value came from auto-detection
  // (vs. an explicit ?slot= the user typed or loaded with) -- purely for the
  // start-overlay CTA copy below. Can't be derived from slotParam alone:
  // auto-detection adopts its value via the same setMySlot()/setSearchParams
  // mechanism the manual input uses, so slotParam is non-null in *both* cases
  // once it fires. A live-verification bug (claude/live-verification-A.md)
  // found the CTA saying "we found your seat -- you're slot 11" even after an
  // explicit ?slot=5 override, because the original check only looked at
  // slotParam == null, which auto-detection itself falsifies the instant it
  // runs. This ref is set only inside the auto-detect effect and cleared on
  // any explicit slot edit (including an explicit ?slot= present when seats
  // load), so it reflects "did auto-detection choose this," not "is a slot
  // param present."
  const autoAdoptedSlotRef = useRef(false)

  const reveal = useRevealedBoard(
    result?.board,
    result ? result.teams * result.rounds : 0,
    result?.myPicks,
    runSeq,
  )

  function refetchSeats() {
    getSeats(draftId).then(setSeats).catch((e) => setError(e.message))
  }

  function handleSeatsChanged() {
    refetchSeats()
    if (result) setSeatsDirty(true)
  }

  useEffect(() => {
    refetchSeats()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId])

  // Auto-detect: once seats arrive, if the URL has no explicit ?slot= (an
  // explicit param is a deliberate user choice and must never be overridden)
  // and the backend matched the configured owner to a seat in this league,
  // adopt that slot via the same setMySlot(..., {replace:true}) mechanism the
  // manual input already uses. useLayoutEffect (not useEffect) so this commits
  // before the browser paints: since `mySlot` is derived from the URL on every
  // render, the plain-useEffect version would paint one real frame of
  // DEFAULT_SLOT (1) on the exact full-screen start-overlay CTA this feature
  // is meant to streamline, between the seats commit and the slot-URL commit.
  // useLayoutEffect closes that gap; it can't do anything about the fetch
  // window itself (nothing can, seats is inherently async), which is why the
  // slot input below is separately gated on `seats` having loaded rather than
  // rendering DEFAULT_SLOT while nothing is known yet. See
  // claude/plan-review-A.md's "Auto-detect timing" finding.
  useLayoutEffect(() => {
    if (!seats) return
    if (slotParam != null) {
      autoAdoptedSlotRef.current = false
      return
    }
    if (seats.mySlot != null) {
      autoAdoptedSlotRef.current = true
      setMySlot(seats.mySlot)
    } else {
      autoAdoptedSlotRef.current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seats])

  function setMySlot(slot: number) {
    // replace: true -- otherwise every keystroke in the slot input pushes its own
    // history entry, and Back steps through transient values instead of leaving.
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set('slot', String(slot))
        return next
      },
      { replace: true },
    )
  }

  // The one place a pick gets recorded, whichever of the two entry points
  // (PickPrompt's "take model's pick" or a PlayerPicker row) triggered it.
  // Builds a startState covering every pick from 1 through the pick just
  // made -- your own choice wherever you have one, the model's own
  // already-revealed prediction everywhere else -- and re-runs the
  // simulation with it before resuming the reveal. See the design doc's §3.
  async function choosePick(player: PlayerRef) {
    // Also refuses while a fresh run() is in flight (running): PickPrompt/
    // PlayerPicker used to stay clickable through that window (only resimming
    // hid them), letting a stale pick fire a redundant resim against a result
    // about to be replaced anyway. Guarding here is enough -- no render change
    // needed, since a click during that window now simply no-ops.
    //
    // choosingRef is a synchronous reentrancy lock, checked and set before
    // anything else: `resimming`/`userPicks` above are still last-render
    // state, so two invocations racing before React commits the first one's
    // setResimming(true) could otherwise both pass every check above and
    // both proceed -- a plain ref mutation is synchronous and closes that
    // window regardless of render timing, which relying on a setState
    // updater's side effect would not actually guarantee.
    if (reveal.pausedAt == null || resimming || running || !result || choosingRef.current) return
    if (Object.values(userPicks).some((p) => p.id === player.id)) return // already claimed at an earlier pick
    choosingRef.current = true
    setError(null) // clear any stale error from a previous failed resim -- see run()'s identical reset
    const pausedAt = reveal.pausedAt
    const nextUserPicks = { ...userPicks, [pausedAt]: player }

    const seq = ++requestSeqRef.current
    setResimming(true)
    setResimProgress(0)
    try {
      setUserPicks(nextUserPicks)
      setPickerOpen(false)

      // Everything through this pick is now "decided": your own picks win, the
      // model's own already-shown prediction fills every other slot. A pick
      // number with no board entry at all (BoardAssembler.assemble skips a pick
      // only when literally every run ran out of distinct candidates there -- a
      // late-round edge case) is left out of startState rather than guessed
      // at; the engine just resimulates that one slot fresh, which is the same
      // thing it would do if this pick had never been reached yet.
      const startState: Record<number, string> = {}
      for (let n = 1; n <= pausedAt; n++) {
        const chosen = nextUserPicks[n]
        if (chosen) {
          startState[n] = chosen.sleeperId
          continue
        }
        const predicted = result.board.find((p) => p.pickNo === n)
        if (predicted) startState[n] = predicted.player.sleeperId
      }

      const r2 = await streamSimulation(
        {
          draftSleeperId: draftId,
          // Both frozen to what produced the prefix being locked in, not live
          // control state -- see the design doc's §3 for why (temperature) and
          // the code-review finding for why mySlot needed the same treatment:
          // the whole "useRevealedBoard doesn't need to reset on a resim"
          // argument depends on mySlot staying invariant across one.
          mySlot: result.mySlot,
          iterations: Math.min(iterations, RESIM_ITERATION_CAP),
          temperature: result.temperature,
          startState,
        },
        setResimProgress,
      )
      if (seq !== requestSeqRef.current) return // superseded by a newer run()/pick

      // Defensive: startState resolution can silently drop an unmapped
      // sleeperId (SimulationService.resolveStartState just skips it rather
      // than erroring). Checking only the pick just made isn't enough -- an
      // earlier locked pick could just as easily be the one silently
      // dropped, quietly re-deciding a pick the user already watched happen.
      // Check the WHOLE locked prefix.
      const byPickNo = new Map(r2.board.map((p) => [p.pickNo, p]))
      for (const [pickNoStr, sleeperId] of Object.entries(startState)) {
        const landed = byPickNo.get(Number(pickNoStr))
        if (!landed || landed.player.sleeperId !== sleeperId) {
          throw new Error(`Resimulation didn't preserve pick ${pickNoStr} as decided — showing the prior board.`)
        }
      }
      setResult(r2)
      reveal.resume()
    } catch (e) {
      if (seq !== requestSeqRef.current) return // a newer request already owns the screen
      setError(e instanceof Error ? e.message : String(e))
      reveal.resume() // don't strand the user paused forever; continue against the stale board
    } finally {
      if (seq === requestSeqRef.current) setResimming(false)
      choosingRef.current = false
    }
  }

  async function run() {
    const seq = ++requestSeqRef.current
    setRunning(true)
    setError(null)
    setProgress(0)
    setOpenPick(null) // stale card from the previous board would otherwise linger through the re-run
    setUserPicks({}) // same staleness class of bug -- don't let a prior run's picks survive into this one
    setPickerOpen(false)
    setResimming(false)
    setResimProgress(0)
    try {
      const r = await streamSimulation(
        { draftSleeperId: draftId, mySlot, iterations, temperature },
        setProgress,
      )
      if (seq !== requestSeqRef.current) return // superseded by a newer run()/pick
      setResult(r)
      setSeatsDirty(false)
      setRunSeq((n) => n + 1)
    } catch (e) {
      if (seq !== requestSeqRef.current) return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (seq === requestSeqRef.current) setRunning(false)
    }
  }

  const pickedPlayerIds = new Set(Object.values(userPicks).map((p) => p.id))
  // Only the picks that haven't happened yet -- once resimulated, an already
  // decided pick reads ~100% everywhere (every iteration replays it
  // identically), which is dead information that would otherwise hide the
  // genuinely new future-pick numbers this whole feature exists to produce.
  // DraftBoard/RevealScrubber still get the full, unfiltered result.myPicks
  // below -- they need every one of your slots marked, decided or not.
  const undecidedMyPicks = result ? result.myPicks.filter((p) => !(p in userPicks)) : []

  // The user's own roster so far -- feeds PlayerPicker's "your team" strip
  // and its "fills a need" row tags. See teamNeeds.ts's draftedSoFar().
  const myDraftedPlayers =
    result && reveal.pausedAt != null ? draftedSoFar(result.myPicks, reveal.pausedAt, result.board, userPicks) : []

  // Nothing has been started for this draft yet. The pre-start screen is a
  // real full-screen empty board (Sleeper's own draft room, before a pick has
  // landed, looks like this), not a small placeholder squeezed next to
  // controls and empty side panels -- see claude/ui-polish-roadmap.md §C.
  // `started` gates the lower-grid/seats-panel bands entirely: they have
  // nothing to show yet (no availability curves, and seat-editing belongs to
  // a moment before or after watching a draft, not squeezed alongside an
  // empty grid) and hiding them is what lets the board fill the screen.
  const started = result != null

  // Whether the slot input has anything real to show yet. An explicit
  // ?slot= is known immediately (nothing to wait for). Otherwise, until
  // `seats` loads there is no way to tell "will auto-detect" from "will fall
  // back to DEFAULT_SLOT" -- rendering DEFAULT_SLOT (1) here would be exactly
  // the flash this feature is meant to avoid, so the input stays blank and
  // disabled for that one short window instead of guessing.
  const slotKnown = slotParam != null || seats != null

  return (
    <>
      <div className="controls">
        <label>
          slot
          <input
            type="number"
            min={1}
            max={seats?.teams ?? 14}
            value={slotKnown ? mySlot : ''}
            placeholder={slotKnown ? undefined : '...'}
            disabled={!slotKnown}
            onChange={(e) => {
              autoAdoptedSlotRef.current = false
              setMySlot(Number(e.target.value))
            }}
            size={3}
          />
        </label>
        <label>
          runs
          <select value={iterations} onChange={(e) => setIterations(Number(e.target.value))}>
            {[500, 1000, 2000, 5000, 10000].map((n) => (
              <option key={n} value={n}>
                {n.toLocaleString()}
              </option>
            ))}
          </select>
        </label>
        <label className="temp">
          chaos
          <input
            type="range"
            min={0}
            max={3}
            step={0.1}
            value={temperature}
            onChange={(e) => setTemperature(Number(e.target.value))}
          />
          <span className="temp-value">
            {temperature === 0
              ? 'most likely board'
              : temperature <= 1.2
                ? `realistic (${temperature.toFixed(1)})`
                : `chaos (${temperature.toFixed(1)})`}
          </span>
        </label>
        <button onClick={run} disabled={running || resimming}>
          {running ? `simulating ${Math.round(progress * 100)}%` : 'start'}
        </button>
      </div>

      {error && <div className="error">{error}</div>}
      {/* Only for a re-run once a board already exists -- the first run's
          progress lives inside the empty board's own overlay below, so the
          two never show at once. */}
      {started && running && (
        <div className="progress">
          <div className="progress-bar" style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      )}

      {seatsDirty && result && (
        <div className="error">Seats changed since this simulation ran — re-run to refresh the board.</div>
      )}

      <div className="content">
        <div className="board-panel">
          <section className="panel">
            {started && (
              <>
                <h2>Predicted board</h2>
                <p className="muted small">
                  Each cell is the most likely player still unassigned at that pick, with the share of runs
                  he actually went there. Low percentages mean the model does not know, which is most of the
                  board after round three. Faded names are cells where the most likely player had already
                  gone earlier.
                </p>
                <RevealScrubber
                  value={reveal.revealedThrough}
                  max={result.teams * result.rounds}
                  teams={result.teams}
                  myPicks={result.myPicks}
                  onChange={reveal.scrubTo}
                  onSkip={reveal.skip}
                  disabled={resimming}
                />
                {reveal.pausedAt != null &&
                  (resimming ? (
                    <div className="pause-banner">
                      <span className="muted small">
                        Recalculating the board past pick {reveal.pausedAt}... {Math.round(resimProgress * 100)}%
                      </span>
                    </div>
                  ) : (
                    <PickPrompt
                      pausedAt={reveal.pausedAt}
                      teams={result.teams}
                      modelPick={result.board.find((p) => p.pickNo === reveal.pausedAt)}
                      bestAvailable={result.bestAvailable[String(reveal.pausedAt)]?.[0]?.player}
                      onPick={choosePick}
                      onOpenPicker={() => setPickerOpen(true)}
                    />
                  ))}
              </>
            )}

            {seats && (
              // teams/rounds come from `seats` (fetched independently of a
              // run) so the grid -- and its column headers -- exists before a
              // simulation ever has. Everything else defaults to "nothing
              // revealed yet": an empty board, not a placeholder screen.
              <div className="board-stage">
                <DraftBoard
                  board={result?.board ?? []}
                  teams={result?.teams ?? seats.teams}
                  rounds={result?.rounds ?? seats.rounds}
                  myPicks={result?.myPicks ?? []}
                  userPicks={userPicks}
                  revealedThrough={started ? reveal.revealedThrough : 0}
                  seats={seats.seats}
                  onCellClick={started ? setOpenPick : undefined}
                />
                {!started && (
                  <div className="start-overlay">
                    {running ? (
                      <div className="start-overlay-status">
                        <span className="cond">Simulating your draft...</span>
                        <span className="muted small">{Math.round(progress * 100)}%</span>
                      </div>
                    ) : (
                      <div className="start-overlay-cta">
                        <h2 className="cond">Ready when you are</h2>
                        <p className="muted small">
                          {autoAdoptedSlotRef.current && seats?.mySlot != null
                            ? `We found your seat — you're slot ${seats.mySlot}. Start the mock draft.`
                            : 'Set your slot above if you know it, then start the mock draft.'}
                        </p>
                        <button className="start-button" onClick={run}>
                          Start the mock draft
                        </button>
                        <p className="muted tiny">
                          First time with this league? Ingest it first:{' '}
                          <code>POST /api/ingest/all/1391509063170293760</code>
                        </p>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </section>
        </div>

        {started && (
          <div className="lower-grid">
            <AvailabilityPanel
              availability={result.availability}
              myPicks={undecidedMyPicks}
              teams={result.teams}
              pickedPlayerIds={pickedPlayerIds}
            />
            <ConfidenceNote c={result.confidence} />
          </div>
        )}

        {started && (
          <div className="seats-panel">
            {seats && <SeatList seats={seats.seats} mySlot={mySlot} onChanged={handleSeatsChanged} />}
          </div>
        )}
      </div>

      {openPick && result && (
        <PlayerCard
          pick={openPick}
          teams={result.teams}
          yourPick={userPicks[openPick.pickNo]}
          onClose={() => setOpenPick(null)}
        />
      )}

      {pickerOpen && result && reveal.pausedAt != null && !resimming && (
        <PlayerPicker
          pausedAt={reveal.pausedAt}
          teams={result.teams}
          availability={result.availability}
          alreadyPicked={pickedPlayerIds}
          rosterPositions={seats?.rosterPositions ?? []}
          draftedPlayers={myDraftedPlayers}
          onPick={choosePick}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </>
  )
}
