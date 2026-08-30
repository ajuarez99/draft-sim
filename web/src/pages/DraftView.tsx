import { useEffect, useState } from 'react'
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

// Every league, of any size, has a slot 1 -- unlike the single-league app's old
// hardcoded 11, this stays a valid seat no matter which draft the picker opens.
const DEFAULT_SLOT = 1

export default function DraftView() {
  const { draftId = '' } = useParams<{ draftId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const slotParam = searchParams.get('slot')
  const mySlot = slotParam ? Number(slotParam) : DEFAULT_SLOT

  const [iterations, setIterations] = useState(2000)
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

  const reveal = useRevealedBoard(result?.board, result ? result.teams * result.rounds : 0, result?.myPicks)

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
  // (PickPrompt's "take model's pick" or a PlayerPicker row) triggered it --
  // see the design doc's §3. The dedup guard has to live here, not in
  // PlayerPicker's own filtering, because "take the model's pick" has no list
  // to filter against.
  function choosePick(player: PlayerRef) {
    if (reveal.pausedAt == null) return
    if (Object.values(userPicks).some((p) => p.id === player.id)) return // already claimed at an earlier pick
    setUserPicks((prev) => ({ ...prev, [reveal.pausedAt!]: player }))
    setPickerOpen(false)
    reveal.resume()
  }

  async function run() {
    setRunning(true)
    setError(null)
    setProgress(0)
    setOpenPick(null) // stale card from the previous board would otherwise linger through the re-run
    setUserPicks({}) // same staleness class of bug -- don't let a prior run's picks survive into this one
    setPickerOpen(false)
    try {
      const r = await streamSimulation(
        { draftSleeperId: draftId, mySlot, iterations, temperature },
        setProgress,
      )
      setResult(r)
      setSeatsDirty(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setRunning(false)
    }
  }

  const pickedPlayerIds = new Set(Object.values(userPicks).map((p) => p.id))

  return (
    <>
      <div className="controls">
        <label>
          slot
          <input
            type="number"
            min={1}
            max={seats?.teams ?? 14}
            value={mySlot}
            onChange={(e) => setMySlot(Number(e.target.value))}
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
        <button onClick={run} disabled={running}>
          {running ? `simulating ${Math.round(progress * 100)}%` : 'run'}
        </button>
      </div>

      {error && <div className="error">{error}</div>}
      {running && (
        <div className="progress">
          <div className="progress-bar" style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      )}

      {seatsDirty && result && (
        <div className="error">Seats changed since this simulation ran — re-run to refresh the board.</div>
      )}

      <div className="content">
        <div className="board-panel">
          {result && (
            <section className="panel">
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
              />
              {reveal.pausedAt != null && (
                <PickPrompt
                  pausedAt={reveal.pausedAt}
                  teams={result.teams}
                  modelPick={result.board.find((p) => p.pickNo === reveal.pausedAt)}
                  onPick={choosePick}
                  onOpenPicker={() => setPickerOpen(true)}
                />
              )}
              <DraftBoard
                board={result.board}
                teams={result.teams}
                rounds={result.rounds}
                myPicks={result.myPicks}
                userPicks={userPicks}
                revealedThrough={reveal.revealedThrough}
                seats={seats?.seats}
                onCellClick={setOpenPick}
              />
            </section>
          )}

          {!result && !running && (
            <section className="panel">
              <p className="muted">
                Run ingest first if you have not:{' '}
                <code>POST /api/ingest/all/1391509063170293760</code>, then hit run.
              </p>
            </section>
          )}
        </div>

        {result && (
          <div className="lower-grid">
            <AvailabilityPanel
              availability={result.availability}
              myPicks={result.myPicks}
              teams={result.teams}
              pickedPlayerIds={pickedPlayerIds}
            />
            <ConfidenceNote c={result.confidence} />
          </div>
        )}

        <div className="seats-panel">
          {seats && <SeatList seats={seats.seats} mySlot={mySlot} onChanged={handleSeatsChanged} />}
        </div>
      </div>

      {openPick && result && (
        <PlayerCard
          pick={openPick}
          teams={result.teams}
          yourPick={userPicks[openPick.pickNo]}
          onClose={() => setOpenPick(null)}
        />
      )}

      {pickerOpen && result && reveal.pausedAt != null && (
        <PlayerPicker
          pausedAt={reveal.pausedAt}
          teams={result.teams}
          availability={result.availability}
          alreadyPicked={pickedPlayerIds}
          onPick={choosePick}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </>
  )
}
