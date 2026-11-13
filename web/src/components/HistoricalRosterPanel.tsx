import { useMemo } from 'react'
import type { HistoricalPickView, HistoricalSport } from '../api'
import { cardToPlayerRef } from '../historicalCopy'
import { positionLabel, posPill } from '../positions'
import { computeTeamNeeds } from '../teamNeeds'

type Props = {
  /** The reveal view's picks, so a pick the computer made but the fan has not yet seen never counts. */
  picks: HistoricalPickView[]
  userSlot: number
  rosterPositions: string[]
  sport: HistoricalSport
  rounds: number
}

/**
 * Spec 063 C6: the fan's roster, slot by slot. The seating rule is computeTeamNeeds (the one place it lives,
 * pinned to the backend by its own parity fixture); this only draws it. Bench is whoever the rule did not seat.
 * A display of the roster so far, not a prediction of the lineup the replay will pick.
 */
export default function HistoricalRosterPanel({ picks, userSlot, rosterPositions, sport, rounds }: Props) {
  const sportKey = sport === 'NFL' ? 'nfl' : 'nba'
  const mine = useMemo(
    () => picks.filter((p) => p.seatType === 'USER' && p.draftSlot === userSlot && p.player != null).map((p) => cardToPlayerRef(p.player!, sport)),
    [picks, userSlot, sport],
  )
  const needs = useMemo(() => computeTeamNeeds(sportKey, rosterPositions, mine), [sportKey, rosterPositions, mine])
  const seated = new Set(needs.filter((n) => n.player).map((n) => n.player!.id))
  const bench = mine.filter((p) => !seated.has(p.id))

  return (
    <section className="panel hist-roster" aria-label="Your roster">
      <div className="panel-head">
        <h2>Your roster so far</h2>
        <span className="muted small">
          {mine.length} of {rounds}
        </span>
      </div>
      {needs.length === 0 ? (
        <p className="muted small">The lineup slots are not known for this game.</p>
      ) : (
        <ul className="hist-slots">
          {needs.map((n, i) => (
            <li key={`${n.slot}-${i}`} className={`hist-slot${n.player ? ' filled' : ' open'}`}>
              <span className="hist-slot-label mono">{n.slot}</span>
              {n.player ? (
                <span className="hist-slot-player">
                  <span {...posPill(n.player, sportKey)}>{positionLabel(n.player, sportKey)}</span>
                  <span className="hist-slot-name" title={n.player.name}>
                    {n.player.name}
                  </span>
                </span>
              ) : (
                <span className="hist-slot-empty muted">empty</span>
              )}
            </li>
          ))}
        </ul>
      )}
      <h3 className="hist-bench-title">Bench</h3>
      {bench.length === 0 ? (
        <p className="muted small">Nobody on the bench yet.</p>
      ) : (
        <ul className="hist-slots hist-bench">
          {bench.map((p) => (
            <li key={p.id} className="hist-slot filled">
              <span className="hist-slot-label mono">BN</span>
              <span className="hist-slot-player">
                <span {...posPill(p, sportKey)}>{positionLabel(p, sportKey)}</span>
                <span className="hist-slot-name" title={p.name}>
                  {p.name}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
