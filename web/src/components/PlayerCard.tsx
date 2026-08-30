import { useEffect } from 'react'
import type { PlayerRef, PredictedPick } from '../api'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  pick: PredictedPick
  teams: number
  yourPick?: PlayerRef
  onClose: () => void
}

export default function PlayerCard({ pick, teams, yourPick, onClose }: Props) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      {/* stopPropagation so a click inside the card doesn't bubble to the backdrop and close it */}
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>

        {/* When present, your actual pick is shown first and distinctly from
            the model's own projection below -- neither one silently
            overwrites the other. */}
        {yourPick && (
          <div className="modal-your-pick">
            <h3 className="cond tiny muted">You picked</h3>
            <div className="modal-head">
              <span className={`pos ${yourPick.position}`}>{yourPick.position}</span>
              <h2 className="modal-name">{yourPick.name}</h2>
            </div>
            <p className="muted small">{yourPick.team ?? '—'}</p>
          </div>
        )}

        <div className={yourPick ? 'modal-model-pick' : undefined}>
          {yourPick && <h3 className="cond tiny muted">Model's own pick here</h3>}
          <div className="modal-head">
            <span className={`pos ${pick.player.position}`}>{pick.player.position}</span>
            <h2 className="modal-name">{pick.player.name}</h2>
          </div>
          <p className="muted small">{pick.player.team ?? '—'}</p>
          <p className="muted small">
            Round {roundPickLabel(pick.pickNo, teams)} — pick #{pick.pickNo} — {Math.round(pick.probability * 100)}%
            of runs
            {pick.isModal ? '' : ' (not the most likely player here)'}
          </p>
        </div>

        {pick.alternatives.length > 0 && (
          <div className="modal-alts">
            <h3 className="cond">Alternatives</h3>
            {pick.alternatives.map((a) => (
              <div key={a.player.id} className="modal-alt-row">
                <span className={`pos ${a.player.position}`}>{a.player.position}</span>
                <span className="name">{a.player.name}</span>
                <span className="mono muted">{Math.round(a.probability * 100)}%</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
