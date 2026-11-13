import type { PlayerRef } from './api'

/**
 * Rookie mark and filter for the draft rooms. One definition, and it is the backend's
 * (Player.isRookie(): Sleeper's years_exp is exactly 0); nothing here re-derives it.
 */

type RookieLike = Pick<PlayerRef, 'rookie'>

export function isRookie(p: RookieLike): boolean {
  return p.rookie === true
}

/**
 * Whether this list came from a server that sends the flag at all. An older backend omits it
 * (split deploy); the rooms then hide the Rookies chip instead of offering a filter that would
 * empty the list and read as "there are no rookies".
 */
export function rookieFlagKnown(players: readonly RookieLike[]): boolean {
  return players.some((p) => p.rookie !== undefined)
}
