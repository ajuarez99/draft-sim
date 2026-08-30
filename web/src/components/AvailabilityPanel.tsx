import { useMemo, useState } from 'react'
import type { AvailabilityRow } from '../api'

type Props = {
  availability: AvailabilityRow[]
  myPicks: number[]
  teams: number
}

const POSITIONS = ['ALL', 'QB', 'RB', 'WR', 'TE'] as const

/**
 * The headline output. For each player, the probability he is still on the
 * board when each of your picks comes up.
 *
 * Only your first few picks are shown by default -- past about four picks out
 * the numbers are compounding a lot of model uncertainty and are worth much
 * less than they look.
 */
export default function AvailabilityPanel({ availability, myPicks, teams }: Props) {
  const [filter, setFilter] = useState<(typeof POSITIONS)[number]>('ALL')
  const [depth, setDepth] = useState(4)

  // myPicks belongs in the dependency list: it feeds `picks`, which the filter
  // below reads. It only changes alongside `availability` today, so the stale
  // value was never observable -- but that is a coincidence of the call site,
  // not a property of this component.
  const picks = useMemo(() => myPicks.slice(0, depth), [myPicks, depth])
  const rows = useMemo(
    () =>
      availability
        .filter((r) => filter === 'ALL' || r.player.position === filter)
        .filter((r) => picks.some((p) => (r.survivalByPick[String(p)] ?? 0) > 0.01))
        .slice(0, 60),
    [availability, filter, picks],
  )

  const label = (pickNo: number) => {
    const round = Math.ceil(pickNo / teams)
    const inRound = pickNo - (round - 1) * teams
    return `${round}.${String(inRound).padStart(2, '0')}`
  }

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>Availability at your picks</h2>
        <div className="controls-inline">
          {POSITIONS.map((p) => (
            <button key={p} className={filter === p ? 'chip on' : 'chip'} onClick={() => setFilter(p)}>
              {p}
            </button>
          ))}
          <label className="depth">
            picks shown
            <input
              type="range"
              min={1}
              max={Math.min(8, myPicks.length)}
              value={depth}
              onChange={(e) => setDepth(Number(e.target.value))}
            />
            {depth}
          </label>
        </div>
      </header>

      <div className="avail-scroll panel-body">
        <table className="avail">
          <thead>
            <tr>
              <th className="player-col">Player</th>
              <th>Board</th>
              {picks.map((p) => (
                <th key={p}>{label(p)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.player.id}>
                <td className="player-col">
                  <span className={`pos ${r.player.position}`}>{r.player.position}</span>
                  {r.player.name}
                  <span className="team">{r.player.team}</span>
                </td>
                <td className="num">{Math.round(r.player.adp)}</td>
                {picks.map((p) => {
                  const v = r.survivalByPick[String(p)] ?? 0
                  return (
                    <td key={p} className="bar-cell">
                      <div className="bar" style={{ width: `${Math.round(v * 100)}%` }} />
                      <span className="bar-label">{Math.round(v * 100)}%</span>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="muted">No players survive to these picks in any run.</p>}
      </div>
    </section>
  )
}
