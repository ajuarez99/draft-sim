import { useMemo, useState } from 'react'
import { type AnalysisProjections, type AnalysisLineupPlayer } from '../../api'
import { InjuryTag, ManagerLink, NotYet, pts, useTeamName } from './shared'

export type SlotRow = { slot: string; left: AnalysisLineupPlayer | null; right: AnalysisLineupPlayer | null }

/**
 * Both lineups arrive in the league's own slot order, so the rows are that
 * order walked once, taking each side's nth player at each slot. Pairing by
 * array index alone would slide every row out of alignment the moment one
 * roster has nobody at a position -- which happens, because a roster that
 * dropped its kicker really does field fewer starters.
 */
export function slotRows(left: AnalysisLineupPlayer[], right: AnalysisLineupPlayer[]): SlotRow[] {
  const order: string[] = []
  for (const p of [...left, ...right]) if (!order.includes(p.slot)) order.push(p.slot)

  const rows: SlotRow[] = []
  for (const slot of order) {
    const ls = left.filter((p) => p.slot === slot)
    const rs = right.filter((p) => p.slot === slot)
    for (let i = 0; i < Math.max(ls.length, rs.length); i++) {
      rows.push({ slot, left: ls[i] ?? null, right: rs[i] ?? null })
    }
  }
  return rows
}

export function CompareCell({ player, won }: { player: AnalysisLineupPlayer | null; won: boolean }) {
  if (!player) return <span className="muted small analysis-vs-empty">nobody</span>
  return (
    <span className={`analysis-vs-player${won ? ' won' : ''}`}>
      <span className="analysis-vs-name">
        {player.name}
        {player.injuryStatus && <InjuryTag status={player.injuryStatus} />}
      </span>
      <span className="mono analysis-vs-pts">{pts(player.points)}</span>
    </span>
  )
}

/**
 * Two rosters, slot against slot, over the same rest-of-season window. Every
 * number here is already on the page -- this is the same lineups read a second
 * way, which is the whole reason it costs no request.
 */
export function HeadToHead({ block }: { block: AnalysisProjections }) {
  const teamName = useTeamName()
  const rosters = block.rosters

  // Your roster against the league leader, and rank 1 against rank 2 for a
  // reader who is signed out. The rosters arrive ranked, so "the leader" is
  // just the first one that is not already picked.
  const initial = useMemo(() => {
    const mine = rosters.find((r) => r.isMe) ?? rosters[0]
    const other = rosters.find((r) => r.rosterId !== mine.rosterId) ?? mine
    return { left: mine.rosterId, right: other.rosterId }
  }, [rosters])

  const [leftId, setLeftId] = useState(initial.left)
  const [rightId, setRightId] = useState(initial.right)

  const left = rosters.find((r) => r.rosterId === leftId) ?? rosters[0]
  const right = rosters.find((r) => r.rosterId === rightId) ?? rosters[0]
  const rows = useMemo(() => slotRows(left.starters, right.starters), [left, right])

  if (rosters.length < 2) {
    return <NotYet reason="One roster is not a comparison." />
  }

  const margin = left.total - right.total
  const ahead = margin > 0 ? left : right

  const picker = (value: number, onChange: (id: number) => void, label: string) => (
    <label className="analysis-vs-pick">
      <span className="muted small">{label}</span>
      <select value={value} onChange={(e) => onChange(Number(e.target.value))}>
        {rosters.map((r) => (
          <option key={r.rosterId} value={r.rosterId}>
            {r.rank}. {teamName(r.manager, r.rosterId)}
            {r.isMe ? ' (you)' : ''}
          </option>
        ))}
      </select>
    </label>
  )

  return (
    <div className="analysis-vs">
      <div className="analysis-vs-picks">
        {picker(leftId, setLeftId, 'Left')}
        <span className="analysis-vs-versus cond">vs</span>
        {picker(rightId, setRightId, 'Right')}
      </div>

      <div className="analysis-vs-head">
        <div className="analysis-vs-side">
          <ManagerLink
            managerId={left.managerId}
            manager={left.manager}
            rosterId={left.rosterId}
            avatarId={left.avatarId}
            isMe={left.isMe}
          />
          <span className="mono analysis-vs-total">{pts(left.total)}</span>
        </div>
        <div className="analysis-vs-margin">
          {leftId === rightId ? (
            <span className="muted small">Same roster on both sides.</span>
          ) : margin === 0 ? (
            <span className="muted small">level</span>
          ) : (
            // The magnitude, with the name of whoever it belongs to. Signing it
            // against the left side and then naming the winner produced
            // "-144.6 for kieriskash", a number arguing with its own label.
            <>
              <span className="mono analysis-vs-margin-n">{pts(Math.abs(margin))}</span>
              <span className="muted small">
                {' '}
                ahead for {teamName(ahead.manager, ahead.rosterId)}
              </span>
            </>
          )}
        </div>
        <div className="analysis-vs-side right">
          <span className="mono analysis-vs-total">{pts(right.total)}</span>
          <ManagerLink
            managerId={right.managerId}
            manager={right.manager}
            rosterId={right.rosterId}
            avatarId={right.avatarId}
            isMe={right.isMe}
          />
        </div>
      </div>

      <ul className="analysis-vs-rows">
        {rows.map((row, i) => {
          const l = row.left?.points ?? 0
          const r = row.right?.points ?? 0
          return (
            <li key={`${row.slot}-${i}`} className="analysis-vs-row">
              <CompareCell player={row.left} won={l > r} />
              <span className={`pos ${row.left?.position ?? row.right?.position ?? ''} analysis-vs-slot`}>
                {row.slot}
              </span>
              <CompareCell player={row.right} won={r > l} />
            </li>
          )
        })}
      </ul>

      {/* The column is signed against the left roster, so the page says so
          rather than leaving the reader to infer it from one row. */}
      <p className="muted small analysis-vs-groups-note">
        Difference is {teamName(left.manager, left.rosterId)} minus{' '}
        {teamName(right.manager, right.rosterId)}.
      </p>
      <div className="analysis-vs-groups">
        {block.positionGroups.map((g) => {
          const l = left.byPosition[g] ?? 0
          const r = right.byPosition[g] ?? 0
          const diff = l - r
          return (
            <div key={g} className="analysis-vs-group">
              <span className="mono">{l.toFixed(0)}</span>
              <span className={`pos ${g}`}>{g}</span>
              <span className="mono">{r.toFixed(0)}</span>
              <span className={`analysis-vs-group-diff${diff === 0 ? '' : diff > 0 ? ' left' : ' right'}`}>
                {diff === 0 ? 'level' : `${diff > 0 ? '+' : ''}${diff.toFixed(0)}`}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
