import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  getMockSession,
  submitMockPick,
  type MockSeat,
  type MockSessionState,
  type PlayerRef,
  type PredictedPick,
  type Seat,
} from '../api'
import DraftBoard from '../components/DraftBoard'
import TurnIndicator from '../components/TurnIndicator'
import OnTheClockPickInput from '../components/OnTheClockPickInput'

/**
 * Always reports NEUTRAL/zeroed behaviour, even for a MANAGER-type seat
 * (managerSeats lets /mock/new assign a real manager -- see MockSetup.tsx).
 * Harmless today only because DraftBoard is rendered here with
 * hideProvenanceDots and this view never opens a seat popover, so nothing
 * reads these fields -- MockSessionState.SeatView doesn't carry the real
 * reachBias/unpredictability/positionalTilt to begin with. If a click-to-
 * inspect popover is ever added to the mock board, this needs real profile
 * data threaded through, not this stub.
 */
function toBoardSeat(s: MockSeat): Seat {
  return {
    slot: s.slot,
    managerId: s.managerId ?? -s.slot, // synthetic per-slot id so bot headers still get distinct colors
    manager: s.manager,
    provenance: 'NEUTRAL',
    reachBias: 0,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 0,
    picksScored: 0,
  }
}

export default function MockDraftView() {
  const { sessionId = '' } = useParams<{ sessionId: string }>()
  const [state, setState] = useState<MockSessionState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)

  useEffect(() => {
    getMockSession(Number(sessionId))
      .then(setState)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [sessionId])

  async function pick(player: PlayerRef) {
    if (!state) return
    setSubmitting(true)
    setError(null)
    try {
      const next = await submitMockPick(state.id, player.sleeperId)
      setState(next)
      setPickerOpen(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  if (error && !state) {
    return (
      <div className="content">
        <div className="error">{error}</div>
      </div>
    )
  }
  if (!state) {
    return <div className="content" />
  }

  // Every committed pick is real, not a probability -- probability:1/isModal:true
  // is what makes DraftBoard render it exactly like a pick you actually made
  // (its "chosen"/predicted precedence collapses to the same thing either way).
  const board: PredictedPick[] = state.picks
    .filter((p): p is typeof p & { player: PlayerRef } => p.player != null)
    .map((p) => ({
      pickNo: p.pickNo,
      round: p.round,
      slot: p.draftSlot,
      manager: state.seats.find((s) => s.slot === p.draftSlot)?.manager ?? String(p.draftSlot),
      player: p.player,
      probability: 1,
      isModal: true,
      alternatives: [],
    }))

  const userPicks: Record<number, PlayerRef> = {}
  for (const p of state.picks) {
    if (p.source === 'USER' && p.player) userPicks[p.pickNo] = p.player
  }

  const complete = state.status === 'COMPLETE'
  const round = state.onTheClockSlot != null ? Math.ceil(state.currentPickNo / state.teams) : state.rounds
  const draftedByUser = Object.values(userPicks)

  return (
    <div className="content">
      {error && <div className="error">{error}</div>}
      <div className="board-panel">
        <section className="panel">
          {state.sourceDraftId != null && (
            <p className="muted small">
              Forked from a live draft, continuing from pick {state.forkedAtPickNo}.
            </p>
          )}
          <TurnIndicator
            currentPickNo={state.currentPickNo}
            round={round}
            onTheClockSlot={state.onTheClockSlot}
            isUsersTurn={state.isUsersTurn}
            seats={state.seats}
            complete={complete}
          />
          {state.isUsersTurn && !complete && (
            <div className="controls-inline">
              <button className="start-button" onClick={() => setPickerOpen(true)} disabled={submitting}>
                {submitting ? 'submitting…' : 'make your pick'}
              </button>
            </div>
          )}

          <div className="board-stage">
            <DraftBoard
              board={board}
              teams={state.teams}
              rounds={state.rounds}
              myPicks={state.myPicks}
              userPicks={userPicks}
              seats={state.seats.map(toBoardSeat)}
              mySlot={state.userSlot}
              hideProvenanceDots
            />
          </div>
        </section>
      </div>

      {pickerOpen && (
        <OnTheClockPickInput
          pickNo={state.currentPickNo}
          round={round}
          available={state.available}
          rosterPositions={state.rosterPositions}
          draftedPlayers={draftedByUser}
          onPick={pick}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </div>
  )
}
