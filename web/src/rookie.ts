import type { PlayerRef } from './api'

/**
 * Rookie mark and filter for the draft rooms. One definition, and it is the backend's
 * (Player.isRookie(): Sleeper's years_exp is exactly 0); nothing here re-derives it.
 */

// The Time Machine's card can also say null: "not known" (see HistoricalPlayerCard.rookie in api.ts).
type RookieLike = { rookie?: PlayerRef['rookie'] | null }

export function isRookie(p: RookieLike): boolean {
  return p.rookie === true
}

/**
 * Whether this list came from a server that sends the flag at all. An older backend omits it
 * (split deploy), and a Time Machine board can carry null for everyone (a game started before the
 * flag existed, or a season with nothing earlier to compare against); the rooms then hide the
 * Rookies chip instead of offering a filter that would empty the list and read as "there are no
 * rookies".
 */
export function rookieFlagKnown(players: readonly RookieLike[]): boolean {
  return players.some((p) => p.rookie != null)
}
