import type { PlayerRef } from '../api'
import { shortName } from '../playerName'
import { posRank } from '../posRank'
import { positionRun } from '../pickRun'
import { roundPickLabel } from '../roundPickLabel'

export type FeedPick = {
  pickNo: number
  player: PlayerRef
  manager: string
}

type Props = {
  /** Picks that have actually landed, oldest first. Nothing undecided. */
  picks: FeedPick[]
  teams: number
  limit?: number
}

/**
 * The last few picks off the board, newest first, with a position-run callout.
 *
 * This is the piece the room was missing most and the cheapest one to add:
 * every row is a pick the board already holds, and the run is a read over the
 * last six of them (pickRun.ts). Between your turns it is the thing worth
 * watching -- the old header showed a progress counter instead, which told you
 * nothing about whether the position you were waiting on was disappearing.
 *
 * Callers must pass only *decided* picks. In DraftView the reveal pauses with
 * `revealedThrough === pausedAt`, so the board's predicted player at your own
 * still-open pick would otherwise show up here as though it had happened.
 */
export default function PickFeed({ picks, teams, limit = 3 }: Props) {
  if (picks.length === 0) return null

  const run = positionRun(picks.map((p) => p.player))
  const recent = picks.slice(-limit).reverse()

  return (
    <ol className="pick-feed" aria-label="Recent picks">
      {recent.map((p, i) => {
        const { lead, rest } = shortName(p.player)
        // The run belongs on the newest row only -- it describes the state the
        // last pick just created, and repeating it down the list would read as
        // three separate runs.
        const showRun = i === 0 && run != null
        return (
          <li key={p.pickNo} className={`pick-feed-row${showRun ? ` run pos-run-${run.position}` : ''}`}>
            <span className="pick-feed-no mono">{roundPickLabel(p.pickNo, teams)}</span>
            <span className={`pos ${p.player.position}`}>{posRank(p.player)}</span>
            <span className="pick-feed-name">
              <span className="pick-feed-lead">{lead}</span>
              {rest}
            </span>
            {showRun && run ? (
              <span className="pick-feed-run cond">
                {run.count} of the last {run.window} were {run.position}
              </span>
            ) : (
              <span className="pick-feed-by mono">{p.manager}</span>
            )}
          </li>
        )
      })}
    </ol>
  )
}
