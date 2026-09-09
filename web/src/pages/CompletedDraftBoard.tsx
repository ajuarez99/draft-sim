import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  getRealDraftBoard,
  getSeats,
  type PredictedPick,
  type RealDraftBoard,
  type SeatsResponse,
} from '../api'
import DraftBoard from '../components/DraftBoard'
import PlayerCard from '../components/PlayerCard'
import { LoadingScreen } from '../components/Skeleton'

/**
 * The picks that actually happened in this draft -- never runs the engine,
 * never lets you take a pick. DraftView (the simulator) is for a draft that
 * hasn't happened yet, or for asking "what would this room do"; this is for
 * "what did this room do." See LeagueController.realBoard's own comment for
 * why the two had to become separate endpoints.
 */
export default function CompletedDraftBoard() {
  const { draftId = '' } = useParams<{ draftId: string }>()
  const [seats, setSeats] = useState<SeatsResponse | null>(null)
  const [board, setBoard] = useState<RealDraftBoard | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [openPick, setOpenPick] = useState<PredictedPick | null>(null)

  useEffect(() => {
    setSeats(null)
    setBoard(null)
    setError(null)
    Promise.all([getSeats(draftId), getRealDraftBoard(draftId)])
      .then(([s, b]) => {
        setSeats(s)
        setBoard(b)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [draftId])

  if (error) {
    return (
      <div className="content">
        <div className="error">{error}</div>
      </div>
    )
  }
  if (!seats || !board) {
    return <LoadingScreen label="Loading this draft's board…" />
  }

  // probability:1/isModal:true is what makes DraftBoard render every cell as a
  // pick that actually happened rather than a guess -- same convention
  // MockDraftView uses for its own committed picks.
  const picks: PredictedPick[] = board.picks.map((p) => ({
    pickNo: p.pickNo,
    round: p.round,
    slot: p.slot,
    manager: p.manager,
    player: p.player,
    probability: 1,
    isModal: true,
    alternatives: [],
  }))

  const myPicks = seats.mySlot != null ? picks.filter((p) => p.slot === seats.mySlot).map((p) => p.pickNo) : []

  return (
    <div className="content">
      <div className="board-panel">
        <section className="panel">
          <div className="panel-head">
            <h2>Draft board</h2>
            <span className="muted small">What actually happened</span>
          </div>
          <div className="board-stage">
            <DraftBoard
              board={picks}
              teams={board.teams}
              rounds={board.rounds}
              myPicks={myPicks}
              userPicks={{}}
              seats={seats.seats}
              mySlot={seats.mySlot ?? undefined}
              sport={seats.sport}
              reversalRound={seats.reversalRound}
              onCellClick={setOpenPick}
            />
          </div>
        </section>
      </div>

      {openPick && <PlayerCard pick={openPick} teams={board.teams} onClose={() => setOpenPick(null)} />}
    </div>
  )
}
