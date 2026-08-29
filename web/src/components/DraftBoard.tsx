import type { PredictedPick } from '../api'

type Props = {
  board: PredictedPick[]
  teams: number
  rounds: number
  myPicks: number[]
}

/**
 * Rounds x slots grid, snake-aware. Each cell shows the modal pick and how
 * often it actually happened across the runs -- a cell at 22% is the model
 * telling you it has very little idea, and it should read that way.
 */
export default function DraftBoard({ board, teams, rounds, myPicks }: Props) {
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
                  const cls =
                    'cell' +
                    (mine.has(pickNo) ? ' mine' : '') +
                    (pick && !pick.isModal ? ' uncertain' : '')
                  return (
                    <td
                      key={slot}
                      className={cls}
                      title={
                        pick
                          ? `${pick.manager} — ${Math.round(pick.probability * 100)}% of runs\n` +
                            (pick.isModal
                              ? ''
                              : 'Not the most likely player here; the most likely one went earlier.\n') +
                            pick.alternatives
                              .map((a) => `${a.player.name} ${Math.round(a.probability * 100)}%`)
                              .join('\n')
                          : ''
                      }
                    >
                      {pick ? (
                        <>
                          <span className={`pos ${pick.player.position}`}>{pick.player.position}</span>
                          <span className="name">{pick.player.name}</span>
                          <span className="prob">{Math.round(pick.probability * 100)}%</span>
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
