/**
 * Position-run detection for the pick feed.
 *
 * A run is the one thing happening between your turns that changes what you
 * should do at yours: four receivers in six picks means the receiver you were
 * waiting on is about to be gone. The engine already produces every input for
 * this -- it is a read over picks that have already landed, not new model
 * output -- so the feed can say it without asking the backend anything.
 */

import type { PlayerRef, Sport } from './api'
import { eligiblePositions } from './positions'

/**
 * Positions worth calling out a run at, per sport. Football excludes K and
 * DEF deliberately: every draft ends with a block of them, so "5 of the last
 * 6 were K" is always true at pick 200 and never interesting -- a run is only
 * news at a position people are competing for. Basketball has no such
 * late-round dump (all five positions are drafted throughout), so nothing is
 * excluded there. Mirrors positions.ts's POSITIONS_BY_SPORT minus that
 * football carve-out, not a separate list that could drift from it.
 */
export const RUNNABLE_BY_SPORT: Record<Sport, ReadonlySet<string>> = {
  nfl: new Set(['QB', 'RB', 'WR', 'TE']),
  nba: new Set(['PG', 'SG', 'SF', 'PF', 'C']),
}

export type PositionRun = {
  /** Football: the position. NBA: the family code 'G' | 'F' | 'C' (spec 025 A2). */
  position: string
  count: number
  /** How many recent picks `count` is out of -- the feed says "4 of the last 6". */
  window: number
}

type Positioned = { position: string; positions?: string[] | null }

/**
 * NBA run families (spec 025 A2, the user's decision). Counting a player at every
 * eligible position fired a "run" in 108 of the 2025 draft's 163 six-pick windows
 * (17 ties); counting by family fires in 26, with no ties. A pick counts toward a
 * family only when ALL his eligible positions sit in it, so an SG/SF counts toward
 * none. Each pick counts at most once, so two families can't tie on the same
 * picks (FR-006). Football is unchanged: per position.
 */
const NBA_FAMILY_OF: Record<string, string> = { PG: 'G', SG: 'G', SF: 'F', PF: 'F', C: 'C' }
const NBA_FAMILY_NOUN: Record<string, string> = { G: 'guards', F: 'forwards', C: 'centers' }

/** The run's copy noun: "guards"/"forwards"/"centers" for NBA, the position for football. */
export function runLabel(run: PositionRun, sport: Sport): string {
  return sport === 'nba' ? (NBA_FAMILY_NOUN[run.position] ?? run.position) : run.position
}

/** Whether a scarcity row / position is part of the run (an NBA run covers its whole family). */
export function runCovers(run: PositionRun, position: string, sport: Sport): boolean {
  return sport === 'nba' ? NBA_FAMILY_OF[position] === run.position : run.position === position
}

/** The single family all of a pick's eligible positions share, or null (spans families / none). */
function nbaFamily(p: Positioned): string | null {
  const ps = eligiblePositions(p as Pick<PlayerRef, 'position' | 'positions'>, 'nba')
  let fam: string | null = null
  for (const x of ps) {
    const f = NBA_FAMILY_OF[x]
    if (f == null || (fam != null && fam !== f)) return null
    fam = f
  }
  return fam
}

/**
 * The strongest run among the most recent `window` picks, or null.
 *
 * `picks` is expected newest-last (board order). Threshold and window are
 * arguments rather than constants because the useful values differ by league
 * size -- 4-of-6 is a real signal in a 14-team room and closer to noise in an
 * 8-team one -- but the caller currently uses the defaults everywhere.
 *
 * `sport` picks which positions are runnable at all (RUNNABLE_BY_SPORT) --
 * the caller's own sport, never a union of both (a basketball feed calling
 * out a "K run" would be nonsense, and vice versa). Required, not defaulted:
 * a default would assert a rule ("football") instead of carrying a value
 * (spec 013 T062).
 */
export function positionRun(
  picks: Positioned[],
  window = 6,
  threshold = 4,
  sport: Sport,
): PositionRun | null {
  if (threshold > window) return null
  const recent = picks.slice(-window)
  // Only claim "of the last 6" once six picks have actually happened --
  // "4 of the last 6" off a four-pick draft is a lie about the sample.
  if (recent.length < window) return null

  const runnable = RUNNABLE_BY_SPORT[sport]
  const counts = new Map<string, number>()
  for (const p of recent) {
    const key = sport === 'nba' ? nbaFamily(p) : runnable.has(p.position) ? p.position : null
    if (key == null) continue
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }

  let best: PositionRun | null = null
  for (const [position, count] of counts) {
    if (count < threshold) continue
    if (best == null || count > best.count) best = { position, count, window }
  }
  return best
}
