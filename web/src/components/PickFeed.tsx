import type { CSSProperties } from 'react'
import type { PlayerRef, Sport } from '../api'
import { shortName } from '../playerName'
import { posRank } from '../posRank'
import { positionRun } from '../pickRun'
import { roundPickLabel } from '../roundPickLabel'

export type FeedPick = {
  pickNo: number
  player: PlayerRef
  manager: string
  /**
   * How this player fits the roster that just took him, e.g. "Fills RB2" or
   * "Depth" -- rendered on the newest row only, and only when the caller can
   * work it out. Optional because two of the three rooms have no reason to:
   * the mock room's picks are the engine's guesses, and the fit that matters
   * there is yours, which PlayerPicker already shows against your own strip.
   * See LiveDraftView, which computes it from teamNeeds.fitSlot.
   */
  fit?: string | null
}

type Props = {
  /** Picks that have actually landed, oldest first. Nothing undecided. */
  picks: FeedPick[]
  teams: number
  limit?: number
  // Defaults to 'nfl' -- pre-multi-sport callers (and MockDraftView, whose
  // room is football-only regardless) don't need to pass anything. Drives
  // both the run detector's runnable-position list and the DEF name guard.
  sport?: Sport
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
 * The newest row is the announcement: full name rather than the abbreviated
 * one, the manager who took him, and one trailing clause. A pick that is part
 * of a run says so; otherwise it says how the player fits the roster that took
 * him, when the caller supplied that. Deliberately this row and not a banner
 * or a toast of its own -- the newest pick already lives here, and a second
 * place claiming to be where new picks appear would just split the reader's
 * attention between two things saying the same thing.
 *
 * Callers must pass only *decided* picks. In DraftView the reveal pauses with
 * `revealedThrough === pausedAt`, so the board's predicted player at your own
 * still-open pick would otherwise show up here as though it had happened.
 */
export default function PickFeed({ picks, teams, limit = 3, sport = 'nfl' }: Props) {
  if (picks.length === 0) return null

  const run = positionRun(picks.map((p) => p.player), 6, 4, sport)
  const recent = picks.slice(-limit).reverse()

  return (
    <ol className="pick-feed" aria-label="Recent picks">
      {recent.map((p, i) => {
        const { lead, rest } = shortName(p.player, sport)
        // The run belongs on the newest row only -- it describes the state the
        // last pick just created, and repeating it down the list would read as
        // three separate runs.
        const isLead = i === 0
        const showRun = isLead && run != null
        // The run wins the trailing slot when there is one: it is the rarer
        // signal and the one you can still act on, where the fit is a fact
        // about someone else's roster that will keep until you read it.
        const clause = showRun && run ? `${run.count} of the last ${run.window} were ${run.position}` : null
        const runClass = showRun && run ? ` run pos-run-${run.position}` : ''
        const fit = isLead && !clause ? p.fit : null
        return (
          <li
            key={p.pickNo}
            className={`pick-feed-row${isLead ? ' lead' : ''}${runClass}`}
            // The position's own color as a left edge on the announcement row.
            // Set from the position code rather than through eleven CSS rules
            // because --qb/--rb/--pg/... are already named after it in
            // styles.css, so this covers both sports for free.
            style={isLead ? ({ '--lead-hue': `var(--${p.player.position.toLowerCase()})` } as CSSProperties) : undefined}
          >
            <span className="pick-feed-no mono">{roundPickLabel(p.pickNo, teams)}</span>
            <span className={`pos ${p.player.position}`}>{posRank(p.player)}</span>
            {/* The announcement says the whole name. Everywhere else the
                abbreviation is right -- three rows of "Ja'Marr Chase" crowd
                out the numbers around them -- but the pick that just landed is
                the one being announced, and announcing an initial is odd. */}
            <span className="pick-feed-name">
              {isLead ? (
                p.player.name
              ) : (
                <>
                  <span className="pick-feed-lead">{lead}</span>
                  {rest}
                </>
              )}
            </span>
            {/* The lead row keeps the manager even when a clause follows it:
                "who took him" is half of what an announcement is for, and the
                old row dropped it entirely whenever a run was showing. */}
            {(isLead || !clause) && <span className="pick-feed-by mono">{p.manager}</span>}
            {clause && <span className="pick-feed-run cond">{clause}</span>}
            {fit && <span className="pick-feed-fit cond">{fit}</span>}
          </li>
        )
      })}
    </ol>
  )
}
