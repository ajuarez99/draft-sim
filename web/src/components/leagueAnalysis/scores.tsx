import { useMemo, useState } from 'react'
import BumpChart, { type Series } from '../../components/BumpChart'
import { NO_PINS, managerHues, toggleSelection, type PinSlots } from '../../managerColor'
import { type AnalysisScores } from '../../api'
import { BumpLegend } from './projections'
import { ManagerLink, NotYet, pts, useTeamName } from './shared'

/**
 * Every scored week, read across. Points are the value; the week's best is
 * marked, because "who won the week" is the one thing a grid of numbers this
 * dense will not give up on its own.
 */
export function ScoresBlock({ block }: { block: AnalysisScores }) {
  const teamName = useTeamName()
  const [selection, setSelection] = useState<PinSlots>(NO_PINS)
  const toggle = (rosterId: number) => setSelection((s) => toggleSelection(s, rosterId))

  const hues = useMemo(() => managerHues(block.rosters), [block.rosters])
  const meRosterId = block.rosters.find((r) => r.isMe)?.rosterId ?? null

  const series: Series[] = useMemo(
    () =>
      block.rosters.map((r) => ({
        rosterId: r.rosterId,
        managerId: r.managerId,
        manager: teamName(r.manager, r.rosterId),
        hue: hues.get(r.rosterId)?.hue ?? 0,
        // `thin` and `note` belong to power rankings' ballot coverage; a scored
        // week has no such notion, so every point here is solid.
        points: r.weeks.map((w) => ({
          week: w.week,
          rank: w.rank,
          score: w.points,
          note: null,
          ballotCount: null,
          thin: false,
        })),
      })),
    [block.rosters, hues, teamName],
  )

  if (!block.available) return <NotYet reason={block.reason} />

  // With one scored week, total == avg == high == low == that week, and the
  // row reads as five copies of one number pretending to be five facts. Same
  // discipline as the ranking score's own gate: the honest answer early is no
  // answer, said out loud.
  const summarisable = block.weeks.length > 1

  return (
    <>
      <div className="table-wrap">
        <table className="standings analysis-scores-grid">
          <thead>
            <tr>
              <th>Manager</th>
              {block.weeks.map((w) => (
                <th key={w} className="mono analysis-scores-week">
                  {w}
                </th>
              ))}
              {summarisable && (
                <>
                  <th className="mono">Total</th>
                  <th className="mono">Avg</th>
                  <th className="mono">High</th>
                  <th className="mono">Low</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {block.rosters.map((r) => {
              const byWeek = new Map(r.weeks.map((w) => [w.week, w]))
              return (
                <tr
                  key={r.rosterId}
                  className={`${r.isMe ? 'mine ' : ''}${selection.includes(r.rosterId) ? 'on' : ''}`}
                >
                  <td>
                    <ManagerLink
                      managerId={r.managerId}
                      manager={r.manager}
                      rosterId={r.rosterId}
                      avatarId={r.avatarId}
                      isMe={r.isMe}
                    />
                  </td>
                  {block.weeks.map((w) => {
                    const cell = byWeek.get(w)
                    if (!cell) {
                      return (
                        <td key={w} className="mono muted analysis-scores-cell" title="no game scored">
                          —
                        </td>
                      )
                    }
                    return (
                      <td
                        key={w}
                        className={`mono analysis-scores-cell${cell.rank === 1 ? ' best' : ''}`}
                        title={`${teamName(r.manager, r.rosterId)} — week ${w}: ${pts(cell.points)}, ${cell.rank} of ${block.rosters.length}`}
                      >
                        {pts(cell.points)}
                      </td>
                    )
                  })}
                  {summarisable && (
                    <>
                      <td className="mono">{pts(r.total)}</td>
                      <td className="mono">{pts(r.avg)}</td>
                      <td className="mono">{pts(r.high)}</td>
                      <td className="mono">{pts(r.low)}</td>
                    </>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {summarisable ? (
        <>
          <p className="muted small analysis-bump-note">
            The same weeks as movement: where each roster ranked on points in each one. Your line is
            crimson; pin up to three to compare them.
          </p>
          <BumpLegend
            rosters={block.rosters}
            selection={selection}
            meRosterId={meRosterId}
            onToggle={toggle}
          />
          <BumpChart
            series={series}
            weeks={block.weeks}
            teamCount={block.rosters.length}
            colorBy="focus"
            selection={selection}
            meRosterId={meRosterId}
            onToggle={toggle}
          />
        </>
      ) : (
        <p className="muted small analysis-bump-note">
          One scored week is a column, not a line. The movement chart, and the total, average, high
          and low — which are all that same number until there are two weeks — appear from week two.
        </p>
      )}
    </>
  )
}
