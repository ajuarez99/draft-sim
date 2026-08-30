import { Fragment } from 'react'
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
      <div className="board" style={{ gridTemplateColumns: `44px repeat(${teams}, minmax(84px, 1fr))` }}>
        <div />
        {Array.from({ length: teams }, (_, i) => (
          <div key={i} className="col-head">{i + 1}</div>
        ))}
        {Array.from({ length: rounds }, (_, r) => {
          const round = r + 1
          return (
            <Fragment key={round}>
              <div className="rnd cond">R{round}</div>
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
                  <div
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
                        <div className="meta">
                          <span className="team-code mono">{visible.player.team ?? '—'}</span>
                          <span className="prob mono">{Math.round(visible.probability * 100)}%</span>
                        </div>
                        <div className="bar-track">
                          <div className="bar-fill" style={{ width: `${Math.round(visible.probability * 100)}%` }} />
                        </div>
                      </>
                    ) : (
                      <span className="empty">—</span>
                    )}
                  </div>
                )
              })}
            </Fragment>
          )
        })}
      </div>
    </div>
  )
}
