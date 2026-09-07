import type { ReactNode } from 'react'
import { hueFor } from '../hue'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  /** Whose turn it is. Null when nobody is -- see `done`. */
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
  done?: boolean
  doneLabel?: string
  /** Trailing controls (the reveal's `skip`). Kept out of the identity block. */
  children?: ReactNode
}

/**
 * The draft room's header strip.
 *
 * Replaces the reveal scrubber's `Pick 28 of 210 · skip · re-run`, which was a
 * loop counter and two operator buttons in the room's most valuable strip of
 * pixels -- it said where the cursor was and never who was picking, what had
 * just gone, or how long until your turn. Every value here already exists in
 * `SimulationResult` and the revealed-board state; nothing new is computed
 * server-side.
 *
 * Shared by both rooms: DraftView maps the reveal state into it, TurnIndicator
 * maps the mock session's own turn state into it. Crimson-when-it's-you is the
 * same identity rule the board cells and column headers already follow -- see
 * styles.css's house-style header.
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
  done,
  doneLabel,
  children,
}: Props) {
  if (done) {
    return (
      <div className="on-clock done">
        <span className="cond on-clock-done-label">{doneLabel ?? 'Draft complete'}</span>
        <span className="on-clock-spacer" />
        <span className="muted small mono">
          {maxPickNo} picks · {rounds} rounds
        </span>
        {children}
      </div>
    )
  }

  const hue = hueFor(hueSeed)
  const round = Math.ceil(pickNo / teams)
  // Only meaningful while someone else is picking: during your own turn the
  // answer is "now", which the kicker already says.
  const until = !isMine && nextOwnPick != null ? nextOwnPick - pickNo : null

  return (
    <div className={`on-clock${isMine ? ' mine' : ''}`}>
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
