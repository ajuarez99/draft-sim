import { useEffect, useMemo, useState } from 'react'
import type { AvailabilityRow, PlayerRef } from '../api'
import { roundPickLabel } from '../roundPickLabel'

type Props = {
  pausedAt: number
  teams: number
  availability: AvailabilityRow[]
  alreadyPicked: Set<number>
  onPick: (player: PlayerRef) => void
  onClose: () => void
}

const POSITIONS = ['ALL', 'QB', 'RB', 'WR', 'TE'] as const

// Same "realistic options" data AvailabilityPanel already shows below the
// board (SimulationResult.availability's survivalByPick), scoped to exactly
// this pick. Picking here does not touch the rest of the board -- see the
// honesty note below, and Scope decision in the design doc.
export default function PlayerPicker({ pausedAt, teams, availability, alreadyPicked, onPick, onClose }: Props) {
  const [filter, setFilter] = useState<(typeof POSITIONS)[number]>('ALL')

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const rows = useMemo(() => {
    const key = String(pausedAt)
    return availability
      .filter((r) => filter === 'ALL' || r.player.position === filter)
      .filter((r) => (r.survivalByPick[key] ?? 0) > 0.01 && !alreadyPicked.has(r.player.id))
      .sort((a, b) => (b.survivalByPick[key] ?? 0) - (a.survivalByPick[key] ?? 0))
  }, [availability, filter, pausedAt, alreadyPicked])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      {/* stopPropagation so a click inside the card doesn't bubble to the backdrop and close it */}
      <div className="modal-card wide" onClick={(e) => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>
        <div className="modal-head">
          <h2 className="modal-name">Choose your pick</h2>
        </div>
        <p className="muted small">
          Round {roundPickLabel(pausedAt, teams)} — pick #{pausedAt}. Doesn't change the rest of the board — later
          picks are still the model's own projection.
        </p>

        <div className="controls-inline picker-filters">
          {POSITIONS.map((p) => (
            <button key={p} className={filter === p ? 'chip on' : 'chip'} onClick={() => setFilter(p)}>
              {p}
            </button>
          ))}
        </div>

        <div className="picker-scroll">
          <table className="avail">
            <thead>
              <tr>
                <th className="player-col">Player</th>
                <th>Board</th>
                <th>Survives to this pick</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const pct = Math.round((r.survivalByPick[String(pausedAt)] ?? 0) * 100)
                return (
                  <tr
                    key={r.player.id}
                    className="picker-row"
                    tabIndex={0}
                    onClick={() => onPick(r.player)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        onPick(r.player)
                      }
                    }}
                  >
                    <td className="player-col">
                      <span className={`pos ${r.player.position}`}>{r.player.position}</span>
                      {r.player.name}
                      <span className="team">{r.player.team}</span>
                    </td>
                    <td className="num">{Math.round(r.player.adp)}</td>
                    <td className="bar-cell">
                      <div className="bar" style={{ width: `${pct}%` }} />
                      <span className="bar-label">{pct}%</span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {rows.length === 0 && (
            <p className="muted">
              Nothing survives above 1% here. Close this and take the model's suggested pick from the prompt behind
              it instead.
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
