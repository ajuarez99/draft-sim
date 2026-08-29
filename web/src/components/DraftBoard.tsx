import type { PredictedPick } from '../api'

type Props = {
  board: PredictedPick[]
  teams: number
  rounds: number
  myPicks: number[]
  revealedThrough?: number
}

/**
 * Rounds x slots grid, snake-aware. Each cell shows the modal pick and how
 * often it actually happened across the runs -- a cell at 22% is the model
 * telling you it has very little idea, and it should read that way.
 */
export default function DraftBoard({ board, teams, rounds, myPicks, revealedThrough }: Props) {
  const byPick = new Map(board.map((p) => [p.pickNo, p]))
  const mine = new Set(myPicks)

  return (
    <div className="board-scroll">
      <table className="board">
        <thead>
          <tr>
            <th className="rnd">R</th>
            {Array.from({ length: teams }, (_, i) => (
              <th key={i}>{i + 1}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: rounds }, (_, r) => {
            const round = r + 1
            return (
              <tr key={round}>
                <th className="rnd">{round}</th>
                {Array.from({ length: teams }, (_, s) => {
                  const slot = s + 1
                  // snake: even rounds run right to left
                  const indexInRound = round % 2 === 1 ? slot : teams - slot + 1
                  const pickNo = (round - 1) * teams + indexInRound
                  const pick = byPick.get(pickNo)
                  const hidden = revealedThrough !== undefined && pickNo > revealedThrough
                  const visible = hidden ? undefined : pick
                  const cls =
                    'cell' +
                    (mine.has(pickNo) ? ' mine' : '') +
                    (visible && !visible.isModal ? ' uncertain' : '')
                  return (
                    <td
                      key={slot}
                      className={cls}
                      title={
                        visible
                          ? `${visible.manager} — ${Math.round(visible.probability * 100)}% of runs\n` +
                            (visible.isModal
                              ? ''
                              : 'Not the most likely player here; the most likely one went earlier.\n') +
                            visible.alternatives
                              .map((a) => `${a.player.name} ${Math.round(a.probability * 100)}%`)
                              .join('\n')
                          : ''
                      }
                    >
                      {visible ? (
                        <>
                          <span className={`pos ${visible.player.position}`}>{visible.player.position}</span>
                          <span className="name">{visible.player.name}</span>
                          <span className="prob">{Math.round(visible.probability * 100)}%</span>
                        </>
                      ) : (
                        <span className="empty">—</span>
                      )}
                    </td>
                  )
                })}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
