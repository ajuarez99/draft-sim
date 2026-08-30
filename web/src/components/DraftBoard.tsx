import { Fragment } from 'react'
import type { PredictedPick, Seat } from '../api'
import { hueFor } from '../hue'

type Props = {
  board: PredictedPick[]
  teams: number
  rounds: number
  myPicks: number[]
  revealedThrough?: number
  seats?: Seat[]
}

/**
 * Rounds x slots grid, snake-aware. Each cell shows the modal pick and how
 * often it actually happened across the runs -- a cell at 22% is the model
 * telling you it has very little idea, and it should read that way.
 */
export default function DraftBoard({ board, teams, rounds, myPicks, revealedThrough, seats }: Props) {
  const byPick = new Map(board.map((p) => [p.pickNo, p]))
  const mine = new Set(myPicks)
  const seatBySlot = new Map((seats ?? []).map((s) => [s.slot, s]))

  return (
    <div className="board-scroll">
      <div className="board" style={{ gridTemplateColumns: `44px repeat(${teams}, minmax(84px, 1fr))` }}>
        <div className="corner" />
        {Array.from({ length: teams }, (_, i) => {
          const slot = i + 1
          const seat = seatBySlot.get(slot)
          const hue = seat ? hueFor(String(seat.managerId)) : hueFor(String(slot))
          return (
            <div key={i} className="col-head">
              <span className="avatar" style={{ background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }}>
                {seat ? seat.manager.trim().charAt(0).toUpperCase() : slot}
              </span>
              <span className="col-head-name mono">{seat ? seat.manager : slot}</span>
            </div>
          )
        })}
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
                    <span className="pickno mono">{pickNo}</span>
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
