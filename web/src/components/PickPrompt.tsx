import type { PlayerRef, PredictedPick } from '../api'

type Props = {
  modelPick: PredictedPick | undefined
  // SimulationResult.bestAvailable[pausedAt][0].player -- already computed
  // per-your-pick and simulation-weighted (which player was most often the
  // actual best-available option across the simulated runs, not a static ADP
  // number), so this reuses that rather than a fresh client-side ADP sort
  // that could drift from it. See claude/plan-review-B.md's
  // "existing, unused backend field" gap.
  bestAvailable: PlayerRef | undefined
  onPick: (player: PlayerRef) => void
  onOpenPicker: () => void
}

// The pause banner, which used to live in the reveal scrubber that OnTheClock
// replaced -- this is the one place
// that makes it obvious a pause at your pick is an actual decision, not just
// where the animation happened to stop. All actions below funnel through the
// same onPick (see DraftView's choosePick): "take X" is a pick, not a
// different code path, whichever button triggered it.
export default function PickPrompt({ modelPick, bestAvailable, onPick, onOpenPicker }: Props) {
  // Two buttons only when they'd actually offer different players -- the
  // model's own suggestion (reach bias, roster need, that manager's fitted
  // tendencies) and "best available" often agree, and a second identical
  // button would just be noise.
  const showBestAvailable = bestAvailable != null && bestAvailable.id !== modelPick?.player.id

  return (
    <div className="pause-banner pick-prompt">
      {/* No "Your pick — Round 2.14" line any more: OnTheClock sits directly
          above this, in the same crimson, already naming the round and pick.
          Two banners saying whose turn it is was the duplication the shared
          strip introduced; this one keeps the half the strip doesn't have --
          what happens when you press one of these buttons. */}
      <div className="pick-prompt-info">
        <span className="muted tiny">
          Recalculates every pick after this one based on what you took — may take a few seconds.
        </span>
      </div>
      <div className="pick-prompt-actions">
        {modelPick && (
          <button className="chip on" onClick={() => onPick(modelPick.player)}>
            Take {modelPick.player.name}
          </button>
        )}
        {showBestAvailable && bestAvailable && (
          <button className="chip" onClick={() => onPick(bestAvailable)}>
            Take {bestAvailable.name} (best available)
          </button>
        )}
        <button className="chip" onClick={onOpenPicker}>
          Choose a player
        </button>
      </div>
    </div>
  )
}
