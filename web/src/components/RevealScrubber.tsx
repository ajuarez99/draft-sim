import { roundPickLabel } from '../roundPickLabel'

type Props = {
  value: number
  max: number
  teams: number
  onSkip: () => void
  // Re-run lives here (not a top strip -- see
  // claude/board-first-layout-and-pick-latency.md §A) because this is the
  // board panel's own head, the same place `skip` already sits. Always
  // rendered once a board exists, unlike `skip` which only makes sense
  // mid-reveal -- re-running from scratch is always a valid action.
  onRerun: () => void
  disabled?: boolean
  rerunDisabled?: boolean
}

// No manual scrub control -- the reveal runs at its own pace (the 450ms tick
// in useRevealedBoard.ts) and `skip` is the only way to move faster than
// that, jumping straight to the end rather than to an arbitrary pick.
export default function RevealScrubber({ value, max, teams, onSkip, onRerun, disabled, rerunDisabled }: Props) {
  if (max <= 0) return null
  const shown = Math.max(1, value)
  return (
    <div className="reveal-controls">
      <div className="reveal-status">
        <span className="muted small">
          Pick {value} of {max} <span className="mono">{roundPickLabel(shown, teams)}</span>
        </span>
        <div className="controls-inline">
          {value < max && (
            <button className="chip" onClick={onSkip} disabled={disabled}>
              skip
            </button>
          )}
          <button className="chip" onClick={onRerun} disabled={rerunDisabled}>
            re-run
          </button>
        </div>
      </div>
    </div>
  )
}
