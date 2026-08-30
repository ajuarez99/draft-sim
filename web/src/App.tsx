import { useEffect, useState } from 'react'
import { getSeats, streamSimulation, type SeatsResponse, type SimulationResult } from './api'
import DraftBoard from './components/DraftBoard'
import AvailabilityPanel from './components/AvailabilityPanel'
import SeatList from './components/SeatList'
import ConfidenceNote from './components/ConfidenceNote'
import RevealScrubber from './components/RevealScrubber'
import { useRevealedBoard } from './useRevealedBoard'

// fantasy(heart) 2026 -- 14 teams, PPR, you are slot 11.
const DEFAULT_DRAFT = '1391509064357273600'
const DEFAULT_SLOT = 11

export default function App() {
  const [draftId, setDraftId] = useState(DEFAULT_DRAFT)
  const [mySlot, setMySlot] = useState(DEFAULT_SLOT)
  const [iterations, setIterations] = useState(2000)
  const [temperature, setTemperature] = useState(1.0)

  const [seats, setSeats] = useState<SeatsResponse | null>(null)
  const [result, setResult] = useState<SimulationResult | null>(null)
  const [progress, setProgress] = useState(0)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [seatsDirty, setSeatsDirty] = useState(false)

  const reveal = useRevealedBoard(result?.board, result ? result.teams * result.rounds : 0)

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

  async function run() {
    setRunning(true)
    setError(null)
    setProgress(0)
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

  return (
    <div className="app">
      <header className="top">
        <h1>draft-sim</h1>
        <div className="controls">
          <label>
            draft
            <input value={draftId} onChange={(e) => setDraftId(e.target.value)} size={22} />
          </label>
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
      </header>

      {error && <div className="error">{error}</div>}
      {running && (
        <div className="progress">
          <div className="progress-bar" style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      )}

      {seatsDirty && result && (
        <div className="error">Seats changed since this simulation ran — re-run to refresh the board.</div>
      )}

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
            myPicks={result.myPicks}
            isRevealing={reveal.isRevealing}
            onChange={reveal.scrubTo}
            onSkip={reveal.skip}
          />
          <DraftBoard
            board={result.board}
            teams={result.teams}
            rounds={result.rounds}
            myPicks={result.myPicks}
            revealedThrough={reveal.revealedThrough}
            seats={seats?.seats}
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

      <div className="workspace">
        <div className="workspace-main">
          {result && (
            <AvailabilityPanel
              availability={result.availability}
              myPicks={result.myPicks}
              teams={result.teams}
            />
          )}
        </div>
        <aside className="workspace-side">
          {result && <ConfidenceNote c={result.confidence} />}
          {seats && <SeatList seats={seats.seats} mySlot={mySlot} onChanged={handleSeatsChanged} />}
        </aside>
      </div>
    </div>
  )
}
