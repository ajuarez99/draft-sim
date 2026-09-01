import { useEffect, useMemo, useState } from 'react'
import type { AvailabilityRow, PlayerRef } from '../api'
import { roundPickLabel } from '../roundPickLabel'
import { computeTeamNeeds, needLabel, openPositions } from '../teamNeeds'

type Props = {
  pausedAt: number
  teams: number
  availability: AvailabilityRow[]
  alreadyPicked: Set<number>
  rosterPositions: string[]
  draftedPlayers: PlayerRef[]
  onPick: (player: PlayerRef) => void
  onClose: () => void
}

const POSITIONS = ['ALL', 'QB', 'RB', 'WR', 'TE'] as const

// 999 is Sleeper's own "no rank" sentinel (BoardService's default, never
// null) -- fall back to a plain ADP number rather than rendering "RB999".
function rankLabel(p: PlayerRef): string {
  return p.positionalRank === 999 ? `ADP ${Math.round(p.adp)}` : `${p.position}${p.positionalRank}`
}

// Same "realistic options" data AvailabilityPanel already shows below the
// board (SimulationResult.availability's survivalByPick), scoped to exactly
// this pick. Picking here triggers a real resimulation of every pick after
// this one -- see the honesty note below, and claude/reactive-resimulation.md.
//
// Sorted by ADP ascending ("best player available" first) rather than
// survival descending, and the survival bar/percentage column is gone --
// survival stays as the filter (still the right guard against listing
// someone realistically already gone) but stopped being the sort key or a
// displayed number. See claude/ui-polish-roadmap.md section B: the percentage
// answers "how often did this player survive in simulation," not "who should
// I take," and reads as noise for the latter question.
export default function PlayerPicker({
  pausedAt,
  teams,
  availability,
  alreadyPicked,
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
    const key = String(pausedAt)
    return availability
      .filter((r) => filter === 'ALL' || r.player.position === filter)
      .filter((r) => (r.survivalByPick[key] ?? 0) > 0.01 && !alreadyPicked.has(r.player.id))
      .sort((a, b) => a.player.adp - b.player.adp)
  }, [availability, filter, pausedAt, alreadyPicked])

  // Empty rosterPositions is a real (if degraded) state -- roster settings
  // that haven't synced, or a malformed ingest -- not just "no data yet".
  // Hiding the strip entirely reads honestly; a strip with zero badges would
  // look broken instead. See claude/plan-review-B.md's empty-rosterPositions gap.
  const needs = useMemo(() => computeTeamNeeds(rosterPositions, draftedPlayers), [rosterPositions, draftedPlayers])
  const open = useMemo(() => openPositions(needs), [needs])

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
          Round {roundPickLabel(pausedAt, teams)} — pick #{pausedAt}. Recalculates every pick after this one based
          on what you take — may take a few seconds.
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
              {rows.map((r) => {
                const survival = r.survivalByPick[String(pausedAt)] ?? 0
                const need = needLabel(r.player.position, open)
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
                      {survival < 0.2 && <span className="tag likely-gone">likely gone</span>}
                    </td>
                    <td className="num">{rankLabel(r.player)}</td>
                    <td>{need && <span className="tag need">{need}</span>}</td>
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
