import { useEffect, useMemo, useState } from 'react'
import type { PlayerRef } from '../api'
import { posRankOrAdp } from '../posRank'
import { computeTeamNeeds, needLabel, openPositions } from '../teamNeeds'

type Props = {
  pickNo: number
  round: number
  available: PlayerRef[]
  rosterPositions: string[]
  draftedPlayers: PlayerRef[]
  onPick: (player: PlayerRef) => void
  onClose: () => void
}

const POSITIONS = ['ALL', 'QB', 'RB', 'WR', 'TE'] as const

/**
 * A new component rather than a PlayerPicker reuse (claude/next-features-roadmap.md
 * §4, Phase 3): PlayerPicker's columns are survival-curve data (`availability`)
 * that doesn't exist in a mock room -- every committed pick here is real, not a
 * probability -- so the two would only tangle two different data models
 * together. `available` is the full undrafted board, not a filtered slice.
 */
export default function OnTheClockPickInput({
  pickNo,
  round,
  available,
  rosterPositions,
  draftedPlayers,
  onPick,
  onClose,
}: Props) {
  const [filter, setFilter] = useState<(typeof POSITIONS)[number]>('ALL')

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const rows = useMemo(() => {
    return available
      .filter((p) => filter === 'ALL' || p.position === filter)
      .sort((a, b) => a.adp - b.adp)
  }, [available, filter])

  const needs = useMemo(() => computeTeamNeeds(rosterPositions, draftedPlayers), [rosterPositions, draftedPlayers])
  const open = useMemo(() => openPositions(needs), [needs])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card wide" onClick={(e) => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>
        <div className="modal-head">
          <h2 className="modal-name">Your pick</h2>
        </div>
        <p className="muted small">
          Round {round} — pick #{pickNo}.
        </p>

        {rosterPositions.length > 0 && (
          <div className="team-strip">
            {needs.map((n, i) => (
              <div key={i} className={n.player ? 'team-slot filled' : 'team-slot open'}>
                {n.player ? (
                  <>
                    <span className={`pos ${n.player.position}`}>{n.player.position}</span>
                    <span className="team-slot-name">{n.player.name}</span>
                  </>
                ) : (
                  <span className="team-slot-empty">{n.slot}</span>
                )}
              </div>
            ))}
          </div>
        )}

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
                <th>Rank</th>
                <th>Team need</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => {
                const need = needLabel(p.position, open)
                return (
                  <tr
                    key={p.id}
                    className="picker-row"
                    tabIndex={0}
                    onClick={() => onPick(p)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        onPick(p)
                      }
                    }}
                  >
                    <td className="player-col">
                      <span className={`pos ${p.position}`}>{p.position}</span>
                      {p.name}
                      <span className="team">{p.team}</span>
                    </td>
                    <td className="num">{posRankOrAdp(p)}</td>
                    <td>{need && <span className="tag need">{need}</span>}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {rows.length === 0 && <p className="muted">Nothing left at this position.</p>}
        </div>
      </div>
    </div>
  )
}
