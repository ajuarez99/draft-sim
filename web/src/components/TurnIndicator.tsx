import type { MockSeat } from '../api'

type Props = {
  currentPickNo: number
  round: number
  onTheClockSlot: number | null
  isUsersTurn: boolean
  seats: MockSeat[]
  complete: boolean
}

/**
 * The one genuinely new "what's happening right now" readout the mock room
 * needs (claude/next-features-roadmap.md §4, Phase 3) -- everything else
 * (the grid itself) is DraftBoard, reused as-is.
 */
export default function TurnIndicator({ currentPickNo, round, onTheClockSlot, isUsersTurn, seats, complete }: Props) {
  if (complete) {
    return (
      <div className="turn-indicator done">
        <span className="cond">Mock draft complete</span>
      </div>
    )
  }

  const seat = seats.find((s) => s.slot === onTheClockSlot)
  return (
    <div className={`turn-indicator${isUsersTurn ? ' mine' : ''}`}>
      <span className="pickno mono">Pick {currentPickNo}</span>
      <span className="muted small">Round {round}</span>
      <span className="cond">{isUsersTurn ? 'Your pick' : `${seat?.manager ?? 'Someone'} is on the clock`}</span>
    </div>
  )
}
