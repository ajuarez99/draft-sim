/**
 * Position-run detection for the pick feed.
 *
 * A run is the one thing happening between your turns that changes what you
 * should do at yours: four receivers in six picks means the receiver you were
 * waiting on is about to be gone. The engine already produces every input for
 * this -- it is a read over picks that have already landed, not new model
 * output -- so the feed can say it without asking the backend anything.
 */

// K and DEF are excluded deliberately. Every draft ends with a block of them,
// so "5 of the last 6 were K" is always true at pick 200 and never
// interesting; a run is only news at a position people are competing for.
const RUNNABLE = new Set(['QB', 'RB', 'WR', 'TE'])

export type PositionRun = {
  position: string
  count: number
  /** How many recent picks `count` is out of -- the feed says "4 of the last 6". */
  window: number
}

type Positioned = { position: string }

/**
 * The strongest run among the most recent `window` picks, or null.
 *
 * `picks` is expected newest-last (board order). Threshold and window are
 * arguments rather than constants because the useful values differ by league
 * size -- 4-of-6 is a real signal in a 14-team room and closer to noise in an
 * 8-team one -- but the caller currently uses the defaults everywhere.
 */
export function positionRun(picks: Positioned[], window = 6, threshold = 4): PositionRun | null {
  if (threshold > window) return null
  const recent = picks.slice(-window)
  // Only claim "of the last 6" once six picks have actually happened --
  // "4 of the last 6" off a four-pick draft is a lie about the sample.
  if (recent.length < window) return null

  const counts = new Map<string, number>()
  for (const p of recent) {
    if (!RUNNABLE.has(p.position)) continue
    counts.set(p.position, (counts.get(p.position) ?? 0) + 1)
  }

  let best: PositionRun | null = null
  for (const [position, count] of counts) {
    if (count < threshold) continue
    if (best == null || count > best.count) best = { position, count, window }
  }
  return best
}
