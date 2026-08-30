import type { PlayerRef, PredictedPick } from '../api'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  pausedAt: number
  teams: number
  modelPick: PredictedPick | undefined
  onPick: (player: PlayerRef) => void
  onOpenPicker: () => void
}

// The pause banner, pulled out of RevealScrubber -- this is the one place
// that makes it obvious a pause at your pick is an actual decision, not just
// where the animation happened to stop. Both actions below funnel through
// the same onPick (see DraftView's choosePick): "take the model's pick" is a
// pick, not a different code path.
export default function PickPrompt({ pausedAt, teams, modelPick, onPick, onOpenPicker }: Props) {
  return (
    <div className="pause-banner pick-prompt">
      <div className="pick-prompt-info">
        <span>
          Your pick — Round {roundPickLabel(pausedAt, teams)} (pick {pausedAt})
        </span>
        <span className="muted tiny">
          Doesn't change the rest of the board — later picks are still the model's own projection.
        </span>
      </div>
      <div className="pick-prompt-actions">
        {modelPick && (
          <button className="chip on" onClick={() => onPick(modelPick.player)}>
            Take {modelPick.player.name}
          </button>
        )}
        <button className="chip" onClick={onOpenPicker}>
          Choose a player
        </button>
      </div>
    </div>
  )
}
