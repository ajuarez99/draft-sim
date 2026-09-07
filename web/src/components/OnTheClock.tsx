import type { ReactNode } from 'react'
import { hueFor } from '../hue'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  /** Whose turn it is. Null when nobody's is -- see `idle`. */
  manager: string | null
  isMine: boolean
  /** Avatar tint seed: the manager id where there is one, the slot otherwise. */
  hueSeed: string
  pickNo: number
  maxPickNo: number
  teams: number
  rounds: number
  /**
   * Your next pick, when it hasn't come up yet. Drives the right-hand readout:
   * how many picks until you're up is the number you actually want while
   * someone else is on the clock, and it is the question the old
   * "Pick 28 of 210" header never answered.
   */
  nextOwnPick?: number | null
  /**
   * Nobody is on the clock: the draft is over, hasn't started, or we don't yet
   * know. `idleLabel` says which -- the three are not interchangeable and the
   * live room can be in any of them.
   */
  idle?: boolean
  idleLabel?: string
  /**
   * Makes the identity block a real button. The live room opens SeatPopover
   * from it (the same popover a board column header opens), which is worth
   * more there than anywhere else -- the seat on the clock is exactly the
   * manager whose tendencies you want to check before they pick.
   */
  onManagerClick?: () => void
  /** Trailing controls: the reveal's `skip`, the live room's telemetry. */
  children?: ReactNode
}

/**
 * The draft room's header strip, shared by all three rooms.
 *
 * Replaces the reveal scrubber's `Pick 28 of 210 · skip · re-run`, which was a
 * loop counter and two operator buttons in the room's most valuable strip of
 * pixels -- it said where the cursor was and never who was picking, what had
 * just gone, or how long until your turn.
 *
 * Every room maps its own idea of "whose turn" into this rather than drawing
 * its own: DraftView from reveal state, TurnIndicator from the mock session,
 * LiveStatusBar from the SSE frame (and it wraps this in its own telemetry
 * rather than replacing it). Crimson-when-it's-you is the same identity rule
 * the board cells and column headers follow -- see styles.css's house-style
 * header.
 */
export default function OnTheClock({
  manager,
  isMine,
  hueSeed,
  pickNo,
  maxPickNo,
  teams,
  rounds,
  nextOwnPick,
  idle,
  idleLabel,
  onManagerClick,
  children,
}: Props) {
  if (idle) {
    return (
      <div className="on-clock idle">
        <span className="cond on-clock-idle-label">{idleLabel ?? 'Nobody on the clock'}</span>
        <span className="on-clock-spacer" />
        {/* Suppressed rather than rendered as "0 picks · 0 rounds" when the
            live room has no state frame yet -- an unknown size is not zero. */}
        {maxPickNo > 0 && (
          <span className="muted small mono">
            {maxPickNo} picks · {rounds} rounds
          </span>
        )}
        {children}
      </div>
    )
  }

  const hue = hueFor(hueSeed)
  const round = Math.ceil(pickNo / teams)
  // Only meaningful while someone else is picking: during your own turn the
  // answer is "now", which the kicker already says.
  const until = !isMine && nextOwnPick != null ? nextOwnPick - pickNo : null

  const identity = (
    <>
      <span
        className="avatar on-clock-avatar"
        style={
          isMine
            ? { background: 'var(--crimson)', color: 'var(--bg)' }
            : { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
        }
      >
        {(manager ?? '?').trim().charAt(0).toUpperCase()}
      </span>
      <span className="on-clock-text">
        <span className="on-clock-kicker cond">{isMine ? 'Your pick' : 'On the clock'}</span>
        <span className="on-clock-name">
          {isMine ? 'You' : (manager ?? 'Unknown seat')}
          <span className="muted"> — {roundPickLabel(pickNo, teams)}</span>
        </span>
      </span>
    </>
  )

  return (
    <div className={`on-clock${isMine ? ' mine' : ''}`}>
      {onManagerClick ? (
        <button
          type="button"
          className="on-clock-identity"
          onClick={onManagerClick}
          title={manager ? `${manager} — click for details` : undefined}
        >
          {identity}
        </button>
      ) : (
        <span className="on-clock-identity">{identity}</span>
      )}

      <span className="on-clock-spacer" />

      {/* One readout, not two: while you're waiting the useful number is how
          many picks until you're up; on your own turn it's where the draft is
          overall. Showing both put the number that mattered next to a number
          that didn't. */}
      {until != null && until > 0 ? (
        <span className="on-clock-eta">
          <b className="cond">{until}</b>
          {until === 1 ? 'pick until you' : 'picks until you'}
        </span>
      ) : (
        <span className="on-clock-eta">
          <b className="cond mono">
            {pickNo}/{maxPickNo}
          </b>
          round {round} of {rounds}
        </span>
      )}

      {children}
    </div>
  )
}
