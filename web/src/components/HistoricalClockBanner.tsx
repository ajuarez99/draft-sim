import { REVEAL_SPEEDS, type RevealSpeed } from '../revealSpeed'
import { roundPickLabel } from '../roundPickLabel'
import RoomControls from './RoomControls'

const SPEED_LABEL: Record<RevealSpeed, string> = { SLOW: 'Slow', NORMAL: 'Normal', FAST: 'Fast', INSTANT: 'Instant' }

type Props = {
  currentPickNo: number
  teams: number
  /** "Team 5", or "You". Null once there is no pick left to make. */
  clockTeam: string | null
  isUsersTurn: boolean
  /** Picks that happen before yours (the current one included). Null when you have no pick left. */
  picksUntilYou: number | null
  speed: RevealSpeed
  onSpeed: (s: RevealSpeed) => void
  revealing: boolean
  onSkip: () => void
  busy: 'PICK' | 'FINISH' | 'DRAFT' | null
  /** The top queued player who is still available, or null for an empty queue. */
  autoPickName: string | null
  onAutoPick: () => void
  onAutoFinish: () => void
}

/**
 * Spec 063 C2: the room's sticky banner. Who is on the clock and when you are up, the pace controls, and the two
 * automatic buttons. Everything it says comes from the reveal VIEW (the parent passes it), so it never announces a
 * computer pick before that pick is shown.
 *
 * Control roles follow the repo's hierarchy: the pace is the one exclusive choice (segmented); Skip and Auto-pick
 * are quiet actions; Auto-finish is the single primary.
 */
export default function HistoricalClockBanner({
  currentPickNo,
  teams,
  clockTeam,
  isUsersTurn,
  picksUntilYou,
  speed,
  onSpeed,
  revealing,
  onSkip,
  busy,
  autoPickName,
  onAutoPick,
  onAutoFinish,
}: Props) {
  const over = clockTeam == null
  const headline = over ? 'The draft is done' : isUsersTurn ? 'Your turn' : `${clockTeam} is on the clock`
  const detail = over
    ? 'No picks left'
    : isUsersTurn
      ? `Pick ${roundPickLabel(currentPickNo, teams)}`
      : picksUntilYou == null
        ? 'No picks left for you'
        : `Your next pick in ${picksUntilYou} ${picksUntilYou === 1 ? 'pick' : 'picks'}`
  return (
    <div className={`hist-banner${isUsersTurn ? ' your-turn' : ''}`} role="region" aria-label="Draft clock">
      <div className="hist-banner-clock">
        <span className="hist-banner-headline">{headline}</span>
        <span className="hist-banner-detail muted small">{detail}</span>
      </div>
      <RoomControls
        primary={{
          label: busy === 'FINISH' ? 'Finishing…' : 'Auto-finish the draft',
          onClick: onAutoFinish,
          disabled: busy != null,
          title: 'Makes every remaining pick, yours included. Ignores your queue.',
        }}
      >
        <div className="segmented sm" role="group" aria-label="Pick speed">
          {REVEAL_SPEEDS.map((sp) => (
            <button
              key={sp}
              type="button"
              className={`segment${sp === speed ? ' on' : ''}`}
              aria-pressed={sp === speed}
              onClick={() => onSpeed(sp)}
            >
              {SPEED_LABEL[sp]}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="chip"
          disabled={!revealing}
          onClick={onSkip}
          title={revealing ? 'Show every pick the computer teams already made' : 'Nothing to skip'}
        >
          Skip
        </button>
        {isUsersTurn && !revealing && (
          <button
            type="button"
            className="chip"
            disabled={busy != null}
            onClick={onAutoPick}
            title={autoPickName ? `Takes ${autoPickName}, the top of your queue` : 'Makes your pick for you'}
          >
            {busy === 'PICK' ? 'Picking…' : autoPickName ? `Auto-pick: ${autoPickName}` : 'Auto-pick'}
          </button>
        )}
      </RoomControls>
    </div>
  )
}
