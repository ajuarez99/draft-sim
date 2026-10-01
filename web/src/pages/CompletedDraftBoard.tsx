import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  getRealDraftBoard,
  getSeats,
  type PredictedPick,
  type RealDraftBoard,
  type SeatsResponse,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import DraftBoard from '../components/DraftBoard'
import PlayerCard from '../components/PlayerCard'
import { LoadingScreen } from '../components/Skeleton'
import { hasAnyAdpAtDraft } from '../stealsReaches'

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
  const { error, notFound, setError, fail } = useFailure()
  const [openPick, setOpenPick] = useState<PredictedPick | null>(null)
  const [valueView, setValueView] = useState(false)

  useEffect(() => {
    setSeats(null)
    setBoard(null)
    setError(null)
    Promise.all([getSeats(draftId), getRealDraftBoard(draftId)])
      .then(([s, b]) => {
        setSeats(s)
        setBoard(b)
      })
      .catch((e) => fail(e))
  }, [draftId])

  if (notFound) return <NotFound what="draft" />
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
    avatarId: p.avatarId,
    player: p.player,
    probability: 1,
    isModal: true,
    alternatives: [],
  }))

  // pickNo -> ADP when the pick was made. Only this, never player.adp (today's).
  const adpAtDraft: Record<number, number | null | undefined> = {}
  for (const p of board.picks) adpAtDraft[p.pickNo] = p.adpAtDraft
  const canShowValue = hasAnyAdpAtDraft(board.picks.map((p) => p.adpAtDraft))

  const myPicks = seats.mySlot != null ? picks.filter((p) => p.slot === seats.mySlot).map((p) => p.pickNo) : []

  return (
    <div className="content">
      {/* No `PageHeader` here, deliberately -- claude/site-wide-shell-
          propagation.md Phase 4 applies to content-shaped pages, and this is
          board-first: `.board-panel` is `flex: 1 1 0` and the grid takes the
          whole pane. An eyebrow, a 28px title and a sub would cost the board
          about 70px of height, which is the same trade §B already refused
          when it gave draft rooms a 56px rail instead of a 200px one. The
          panel head names the page, and the rail names the league. Same
          reasoning in DraftView, LiveDraftView and MockDraftView. */}
      <div className="board-panel">
        <section className="panel">
          <div className="panel-head">
            <h2>Draft board</h2>
            <span className="muted small">What actually happened</span>
            {/* Hidden outright when no pick carries a draft-time ADP: a toggle
                that can only ever say "no ADP" on every cell is not a feature. */}
            {canShowValue && (
              <button
                type="button"
                className={`chip${valueView ? ' on' : ''}`}
                aria-pressed={valueView}
                onClick={() => setValueView((v) => !v)}
              >
                Steals &amp; reaches
              </button>
            )}
          </div>
          {canShowValue && valueView && (
            <p className="muted small value-legend">
              Each pick against his ADP when it was made.{' '}
              <span className="value-delta steal">+</span> means taken after his ADP (a steal),{' '}
              <span className="value-delta reach">&minus;</span> means taken before it (a reach), counted in picks.
            </p>
          )}
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
              valueView={canShowValue && valueView}
              adpAtDraft={adpAtDraft}
            />
          </div>
        </section>
      </div>

      {openPick && <PlayerCard pick={openPick} teams={board.teams} onClose={() => setOpenPick(null)} />}
    </div>
  )
}
