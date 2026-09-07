import type { MockSeat } from '../api'
import OnTheClock from './OnTheClock'

type Props = {
  currentPickNo: number
  onTheClockSlot: number | null
  isUsersTurn: boolean
  seats: MockSeat[]
  complete: boolean
  teams: number
  rounds: number
  /** Your next pick, for the "picks until you" readout. Null once you're up. */
  nextOwnPick?: number | null
}

/**
 * The mock room's adapter over OnTheClock.
 *
 * It used to render its own strip -- `Pick 121 · Round 15 · Bot 2 is on the
 * clock` -- while DraftView rendered an unrelated reveal scrubber for the same
 * job. Two components saying "whose turn is it" in two visual languages was
 * the duplication; the shared component is OnTheClock, and this maps the mock
 * session's turn state into it. The batch room's own mapping lives inline in
 * DraftView, because it derives the same facts from reveal state instead.
 */
export default function TurnIndicator({
  currentPickNo,
  onTheClockSlot,
  isUsersTurn,
  seats,
  complete,
  teams,
  rounds,
  nextOwnPick,
}: Props) {
  const seat = seats.find((s) => s.slot === onTheClockSlot)
  return (
    <OnTheClock
      manager={seat?.manager ?? null}
      isMine={isUsersTurn}
      // Bot seats carry no manager id, so the slot is the only stable tint
      // seed for them -- same fallback DraftBoard's column headers use.
      hueSeed={String(seat?.managerId ?? onTheClockSlot ?? 0)}
      pickNo={currentPickNo}
      maxPickNo={teams * rounds}
      teams={teams}
      rounds={rounds}
      nextOwnPick={nextOwnPick}
      idle={complete}
      idleLabel="Mock draft complete"
    />
  )
}
